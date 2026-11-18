package com.kimes.daq.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * OBDLink MX+ over Bluetooth Classic SPP.
 *
 * CAN mode: listen-only bus monitoring (STMA/ATMA). Works on cars whose OBD port carries
 * broadcast traffic (ND1/ND2). The 2024+ ND3 port is diagnostic-only, so it shows nothing.
 * DIAG mode: request/response polling. Only read services are ever sent: 0x01 (OBD-II current
 * data) and 0x22 (ReadDataByIdentifier). Nothing is written to any module.
 *
 * Every command and reply (rate-limited) goes to [onLog] for the session's obd_log.txt.
 */
@SuppressLint("MissingPermission")
class ObdLink(
    private val ctx: Context,
    private val onFrame: (tNs: Long, id: Int, data: ByteArray) -> Unit,
    private val onDiag: (tNs: Long, header: Int, resp: ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    enum class Mode { AUTO, CAN, DIAG }

    /** One diagnostic request: CAN header to send to, and request bytes as hex ("222B0D"). */
    data class Req(val header: Int, val data: String)

    /** probe: first check the module is there at all (tester present), and skip it if not. */
    class Scan(val header: Int, val dids: List<Int>, val probe: Boolean = false, val onProgress: (done: Int, hits: Map<Int, ByteArray>) -> Unit)

    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: BluetoothSocket? = null
    @Volatile var mode = Mode.DIAG

    /** Requests cycled in DIAG mode; replaced by the app as needs change. */
    @Volatile var plan: List<Req> = emptyList()
    /** Slow channels (tire pressures, temperatures): one of these is asked after each pass of [plan].
     *  A request the car never answers is dropped after a few tries so it can't slow the plan. */
    @Volatile var slow: List<Req> = emptyList()
    private var slowIdx = 0
    private val slowMiss = HashMap<Req, Int>()
    private val slowOk = HashSet<Req>()
    @Volatile var pendingScan: Scan? = null

    /** A long diagnostic job (the morning auto-check) run on the adapter thread; [ask] sends one request and returns the reply (empty = none). */
    fun interface Job { fun run(ask: (Req, Int) -> ByteArray) }
    @Volatile var pendingJob: Job? = null

    private var stpx = true
    private var curHeader = -1

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "obdlink").apply { start() }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        thread?.interrupt()
        onStatus("off")
    }

    private fun loop() {
        while (running) {
            try {
                session()
            } catch (e: Exception) {
                onLog("! ${e.javaClass.simpleName}: ${e.message}")
                onStatus("error: ${e.message ?: e.javaClass.simpleName}")
            }
            try { socket?.close() } catch (_: Exception) {}
            if (running) SystemClock.sleep(2000)
        }
    }

    private fun session() {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val dev = adapter.bondedDevices.firstOrNull { (it.name ?: "").contains("OBDLink", true) }
            ?: run { onStatus("OBDLink not paired"); return }
        onStatus("connecting ${dev.name}")
        onLog("connecting ${dev.name}, mode $mode")
        adapter.cancelDiscovery()
        val s = dev.createRfcommSocketToServiceRecord(spp)
        socket = s
        s.connect()
        val inp = s.inputStream
        val out = s.outputStream

        onStatus("initializing")
        // Wake it, drop anything left over from a previous monitor session, then reset
        out.write("\r".toByteArray()); out.flush()
        readUntilPrompt(inp, 1000)
        cmd(inp, out, "ATZ", 4000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "STDI", "ATRV", "ATSP6", "ATCAF1")) cmd(inp, out, c)

        val probe = cmd(inp, out, "0100", 5000).replace(" ", "").replace("\r", " ").trim()
        val carOk = probe.contains("4100")
        onLog("car OBD-II reply: ${if (carOk) "YES" else "NO"} ($probe)")

        if (mode != Mode.DIAG) {
            cmd(inp, out, "ATH1")
            cmd(inp, out, "ATCSM1") // silent monitoring: never ACK on the bus
            if (monitor(inp, out, carOk)) return
            if (mode == Mode.CAN) {
                onStatus("no CAN broadcast at port (car OBD reply: ${if (carOk) "yes" else "no"})")
                SystemClock.sleep(3000)
                return
            }
            cmd(inp, out, "ATH0")
        }
        diag(inp, out)
    }

    // ---- diagnostic polling -------------------------------------------------------------

    private fun diag(inp: InputStream, out: OutputStream) {
        // Accept replies from any 0x7xx diagnostic address (DSC 0x768, PCM 0x7E8, ...)
        cmd(inp, out, "ATCF 700")
        cmd(inp, out, "ATCM 700")
        cmd(inp, out, "ATST 19") // ~76 ms reply timeout for the ATSH fallback path
        val test = cmd(inp, out, "STPX H:7E0, D:0100, R:1, T:200")
        stpx = test.replace(" ", "").contains("4100")
        onLog("STPX ${if (stpx) "supported" else "not supported, using ATSH"}")
        curHeader = -1

        var lastStatus = SystemClock.elapsedRealtime()
        val counts = HashMap<String, Int>()
        var fails = 0
        var logged = 0
        while (running) {
            pendingScan?.let { sc ->
                pendingScan = null
                runScan(inp, out, sc)
            }
            pendingJob?.let { j ->
                pendingJob = null
                try {
                    j.run { r, to -> if (running) (ask(inp, out, r, to, log = false) ?: ByteArray(0)) else throw java.io.IOException("adapter stopped") }
                } catch (e: java.io.IOException) {
                    throw e
                } catch (e: Exception) {
                    onLog("job failed: $e")
                }
                curHeader = -1
            }
            val p = plan
            if (p.isEmpty()) { SystemClock.sleep(50); continue }
            for (r in p) {
                if (!running || pendingScan != null || pendingJob != null) break
                val resp = ask(inp, out, r, 100, log = logged++ < 60)
                if (resp != null && resp.isNotEmpty() && resp[0] != 0x7F.toByte()) {
                    onDiag(SystemClock.elapsedRealtimeNanos(), r.header, resp)
                    counts[r.data] = (counts[r.data] ?: 0) + 1
                } else {
                    fails++
                }
            }
            val sl = slow.filter { (slowMiss[it] ?: 0) < 4 || it in slowOk }
            if (sl.isNotEmpty() && running && pendingScan == null && pendingJob == null) {
                val r = sl[slowIdx++ % sl.size]
                val resp = ask(inp, out, r, 100, log = false)
                if (resp != null && resp.isNotEmpty() && resp[0] != 0x7F.toByte()) {
                    slowOk += r
                    onDiag(SystemClock.elapsedRealtimeNanos(), r.header, resp)
                } else if (r !in slowOk) {
                    slowMiss[r] = (slowMiss[r] ?: 0) + 1
                    if (slowMiss[r] == 4) onLog("no reply from %03X:%s, dropped".format(r.header, r.data))
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatus >= 1000) {
                val secs = (now - lastStatus) / 1000.0
                val rates = counts.entries.sortedByDescending { it.value }.take(3)
                    .joinToString("  ") { "${label(it.key)} %.0f Hz".format(it.value / secs) }
                onStatus("DIAG: $rates" + if (fails > 0) "  ($fails no reply)" else "")
                counts.clear(); fails = 0
                lastStatus = now
            }
        }
    }

    private fun label(req: String) = when {
        req == "222B0D" -> "brake"
        req.startsWith("0149") -> "pedal/rpm"
        else -> req
    }

    private fun runScan(inp: InputStream, out: OutputStream, sc: Scan) {
        onLog("scan header %03X, ${sc.dids.size} ids".format(sc.header))
        val hits = LinkedHashMap<Int, ByteArray>()
        if (sc.probe) {
            // Any reply (even "not supported") means a module lives at this address
            val here = (0 until 3).any { val r = ask(inp, out, Req(sc.header, "3E00"), 200, log = true); r != null && r.isNotEmpty() } ||
                ask(inp, out, Req(sc.header, "22F190"), 200, log = true).let { it != null && it.isNotEmpty() }
            if (!here) {
                onLog("no module at %03X, skipped".format(sc.header))
                sc.onProgress(sc.dids.size, hits)
                return
            }
        }
        for ((k, did) in sc.dids.withIndex()) {
            if (!running) return
            val resp = ask(inp, out, Req(sc.header, "22%04X".format(did)), 80, log = false)
            if (resp != null && resp.size >= 3 && resp[0] == 0x62.toByte()) {
                hits[did] = resp.copyOfRange(3, resp.size)
                onLog("scan hit %03X 22%04X -> %s".format(sc.header, did, resp.toHex()))
            }
            if (k % 8 == 7 || k == sc.dids.size - 1) sc.onProgress(k + 1, HashMap(hits))
            onStatus("scanning %03X: %d/%d, %d found".format(sc.header, k + 1, sc.dids.size, hits.size))
        }
        onLog("scan done: ${hits.size} responders")
    }

    /** Sends one request; returns the reply bytes, an empty array for no reply, or null on garbage. */
    private fun ask(inp: InputStream, out: OutputStream, r: Req, timeoutMs: Int, log: Boolean): ByteArray? {
        // "1003|222A05": send the first part(s) first (e.g. enter the extended session), keep the last reply
        if ('|' in r.data) {
            val parts = r.data.split('|')
            parts.dropLast(1).forEach { ask(inp, out, Req(r.header, it), timeoutMs, log) }
            return ask(inp, out, Req(r.header, parts.last()), timeoutMs, log)
        }
        val raw = if (stpx) {
            cmd(inp, out, "STPX H:%03X, D:%s, R:1, T:%d".format(r.header, r.data, timeoutMs), timeoutMs + 500L, log)
        } else {
            if (curHeader != r.header) { cmd(inp, out, "ATSH %03X".format(r.header), log = log); curHeader = r.header }
            cmd(inp, out, r.data, timeoutMs + 500L, log)
        }
        val line = raw.split('\r', '\n').map { it.replace(" ", "").trim() }
            .firstOrNull { it.length >= 2 && it.length % 2 == 0 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
            ?: return ByteArray(0)
        return ByteArray(line.length / 2) { line.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // ---- broadcast monitoring -----------------------------------------------------------

    /** Streams monitor output. Returns false if no frames arrive in the first few seconds. */
    private fun monitor(inp: InputStream, out: OutputStream, carOk: Boolean): Boolean {
        var monCmd = "STMA"
        out.write("$monCmd\r".toByteArray()); out.flush()
        onLog("> $monCmd")
        onStatus("CAN monitor")
        val line = StringBuilder()
        val buf = ByteArray(4096)
        val t0 = SystemClock.elapsedRealtime()
        var frames = 0L
        var other = 0L
        var logged = 0
        var lastOther = ""
        var lastStatus = t0
        var restarts = 0
        while (running) {
            val n = if (inp.available() > 0) inp.read(buf) else { SystemClock.sleep(3); 0 }
            if (n < 0) throw java.io.IOException("stream closed")
            val tNs = SystemClock.elapsedRealtimeNanos()
            for (k in 0 until n) {
                val ch = buf[k].toInt().toChar()
                if (ch == '\r' || ch == '\n' || ch == '>') {
                    if (line.isNotEmpty()) {
                        val ok = parseFrame(tNs, line)
                        if (ok) frames++ else { other++; lastOther = line.toString().trim() }
                        if (logged < 300) { onLog("< $line"); logged++ }
                        line.setLength(0)
                    }
                    if (ch == '>') {
                        // Monitor ended: unsupported command ("?") or BUFFER FULL. Restart it.
                        restarts++
                        if (frames == 0L && lastOther == "?" && monCmd == "STMA") monCmd = "ATMA"
                        if (restarts < 50 || restarts % 50 == 0) onLog("monitor ended (last '$lastOther'), restart #$restarts with $monCmd")
                        out.write("$monCmd\r".toByteArray()); out.flush()
                    }
                } else if (line.length < 80) {
                    line.append(ch)
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (frames == 0L && now - t0 > 6000 && mode == Mode.AUTO) {
                onLog("no frames after 6 s ($other other lines, last '$lastOther'); switching to diagnostic polling")
                out.write("\r".toByteArray()); out.flush()
                readUntilPrompt(inp, 1000)
                return false
            }
            if (now - lastStatus > 1000) {
                val extra = if (frames == 0L) " · other lines $other" + (if (lastOther.isNotEmpty()) " (last \"$lastOther\")" else "") +
                    " · car OBD reply ${if (carOk) "yes" else "no"}" else ""
                onStatus("CAN monitor: $frames frames$extra")
                lastStatus = now
            }
        }
        return true
    }

    private fun parseFrame(tNs: Long, raw: CharSequence): Boolean {
        // 11-bit with headers: "201" + data hex, e.g. "2010B5400000FFFF00". Spaces tolerated.
        val s = raw.filter { it != ' ' }
        if (s.length < 5 || (s.length - 3) % 2 != 0 || s.length > 3 + 16) return false
        for (c in s) if (!(c in '0'..'9' || c in 'A'..'F')) return false
        val id = s.substring(0, 3).toInt(16)
        val n = (s.length - 3) / 2
        val data = ByteArray(n) { s.substring(3 + it * 2, 5 + it * 2).toInt(16).toByte() }
        onFrame(tNs, id, data)
        return true
    }

    // ---- plumbing -----------------------------------------------------------------------

    private fun cmd(inp: InputStream, out: OutputStream, c: String, timeoutMs: Long = 1500, log: Boolean = true): String {
        out.write((c + "\r").toByteArray()); out.flush()
        val r = readUntilPrompt(inp, timeoutMs)
        if (log) onLog("> $c  < ${r.replace('\r', '|').trim('|', ' ')}")
        return r
    }

    private fun readUntilPrompt(inp: InputStream, timeoutMs: Long): String {
        val sb = StringBuilder()
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (inp.available() > 0) {
                val ch = inp.read()
                if (ch < 0) throw java.io.IOException("stream closed")
                if (ch == '>'.code) return sb.toString()
                sb.append(ch.toChar())
            } else {
                SystemClock.sleep(1)
            }
        }
        return sb.toString()
    }
}

fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
