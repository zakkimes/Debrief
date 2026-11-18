package com.kimes.daq

import com.kimes.daq.bt.ObdLink
import com.kimes.daq.bt.toHex
import com.kimes.daq.data.CanDecoder
import com.kimes.daq.data.CanFinder
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.PI
import kotlin.math.abs

/**
 * The unattended morning auto-check: started parked with the dash's tire pressures typed in, then
 * left running on the drive. It looks for the tire pressures and the steering angle everywhere the
 * car will answer, and applies what it confirms:
 *
 *  1. Finds every module on the diagnostic bus (tester-present to 0x700-0x7F7).
 *  2. Tries the known tire-pressure addresses (2A00-2A3F, 2800-283F, C900-C9FF) on each module,
 *     in the default session and again in the extended diagnostic session.
 *  3. Sweeps the rest of each module's data ids, a 4096-id block at a time, most likely blocks first.
 *     After every block it tries to match the tires to the dash numbers.
 *  4. While the car is moving, polls what it has found and compares each signal with how hard the
 *     car is turning (RaceBox yaw rate / speed). One that follows the turns, both ways, is steering.
 *
 * It pauses normal logging while it runs, stops by itself when a timed run starts, and writes
 * everything it saw to morning_check.txt in the session folder.
 */
object MorningCheck {
    data class State(val running: Boolean, val stage: String, val detail: String, val found: List<String>)
    val state = MutableStateFlow<State?>(null)
    @Volatile private var stop = false

    private const val PCM = 0x7E0
    private const val DSC = 0x760
    // Already covered last night, so skipped in the sweep
    private val done = setOf(0x720 to 0x2000, 0x726 to 0x2000, 0x730 to 0x2000, DSC to 0x2000)
    private val blocks = listOf(0x0000, 0x1000, 0x2000, 0x4000, 0xC000, 0xD000, 0x3000, 0x5000, 0xF000, 0x6000, 0x7000, 0x8000, 0x9000, 0xA000, 0xB000, 0xE000)
    // ND wheelbase and an approximate steering ratio: steering wheel degrees for a given path curvature
    private const val WHEELBASE = 2.31
    private const val RATIO = 15.5

    fun cancel() { stop = true }

    fun start(obd: ObdLink, dash: List<Double>?) {
        if (state.value?.running == true) return
        stop = false
        val found = ArrayList<String>()
        state.value = State(true, "Starting", "", found)
        obd.pendingJob = ObdLink.Job { ask -> run(ask, dash, found) }
    }

    private fun run(ask: (ObdLink.Req, Int) -> ByteArray, dash: List<Double>?, found: ArrayList<String>) {
        val logFile = Hub.sessionName.value?.let { File(File(Hub.sessionsRoot(), it), "morning_check.txt") }
        val w = logFile?.printWriter()
        fun log(s: String) { w?.println("%.1f %s".format(Hub.now(), s)); w?.flush() }
        fun say(stage: String, detail: String) { state.value = State(true, stage, detail, ArrayList(found)) }
        fun halted() = stop || Hub.timingActive()
        val nrc = HashMap<Int, HashMap<Int, Int>>()
        val hits = HashMap<Int, HashMap<Int, ByteArray>>() // header -> did -> payload
        val extHits = HashSet<Int>()                        // keys found only in the extended session
        var tiresOk = false
        var steerOk = false
        val tried = HashSet<Int>()                         // steering candidates already watched while turning

        fun read(h: Int, did: Int, to: Int = 80): ByteArray? {
            val r = ask(ObdLink.Req(h, "22%04X".format(did)), to)
            if (r.size >= 3 && r[0] == 0x62.toByte() && ((r[1].toInt() and 0xFF) shl 8 or (r[2].toInt() and 0xFF)) == did) return r.copyOfRange(3, r.size)
            if (r.size >= 3 && r[0] == 0x7F.toByte()) nrc.getOrPut(h) { HashMap() }.merge(r[2].toInt() and 0xFF, 1, Int::plus)
            return null
        }
        fun scan(h: Int, dids: List<Int>, ext: Boolean, label: String): Int {
            var n = 0
            for ((k, did) in dids.withIndex()) {
                if (halted()) return n
                if (ext && k % 40 == 0) ask(ObdLink.Req(h, "3E00"), 80) // keep the extended session alive
                val p = read(h, did) ?: continue
                val m = hits.getOrPut(h) { HashMap() }
                if (did !in m) {
                    m[did] = p; n++
                    if (ext) extHits += (h shl 16) or did
                    log("hit %03X:%04X %s%s".format(h, did, p.toHex(), if (ext) " (extended)" else ""))
                }
                if (k % 16 == 0) say(label, "%03X: %d/%d · %d found here".format(h, k, dids.size, n))
            }
            return n
        }

        fun tryTires() {
            if (tiresOk || dash == null) return
            val pool = hits.filterKeys { it != PCM }
            val m = CanFinder.matchTires(pool, dash) ?: return
            log("tire candidate: ${m.note} corners ${m.corners}")
            // Confirm: read them again; they must answer and still look like tire pressures
            val ok = (0 until 2).all {
                Thread.sleep(500)
                m.decoders.all { d ->
                    if (((d.header shl 16) or d.id) in extHits) ask(ObdLink.Req(d.header, "1003"), 100)
                    val p = read(d.header, d.id, 120) ?: return@all false
                    val v = d.decode(p) ?: return@all false
                    v in 12.0..60.0
                }
            }
            if (!ok) { log("tire candidate failed the re-read"); return }
            val ext = m.decoders.any { ((it.header shl 16) or it.id) in extHits }
            Hub.setSlow(m.decoders, extended = ext)
            m.corners.forEachIndexed { i, c -> Hub.setTireCorner(i + 1, c) }
            tiresOk = true
            found += "Tire pressures: " + m.corners.mapIndexed { i, c -> "%04X=%s".format(m.decoders[i].id, c) }.joinToString(" ") + " (${m.note})"
            log("TIRES APPLIED ${m.decoders}")
        }

        fun moving() = (Hub.sampleAt(Hub.now() - 0.5)?.speed ?: 0.0) > 3.0

        /** Polls steering candidates while driving, up to 90 s, and applies one that follows the turns. False if there was nothing to watch. */
        fun watchSteering(): Boolean {
            if (steerOk || !moving()) return false
            val known = Hub.decoders.filter { it.header != 0 }.map { (it.header shl 16) or it.id }.toSet()
            val cands = hits.filterKeys { it != PCM }.flatMap { (h, m) -> m.map { (d, p) -> Triple(h, d, p) } }
                .filter { (h, d, p) -> val key = (h shl 16) or d
                    key !in tried && key !in known && p.size >= 1 && !(h == DSC && d in 0x2000..0x2FFF) }
                .sortedByDescending { it.third.size >= 2 }.take(48)
            if (cands.isEmpty()) return false
            say("Steering", "watching ${cands.size} signals while you drive: turn both ways")
            log("steering watch: ${cands.joinToString { "%03X:%04X".format(it.first, it.second) }}")
            val frames = ArrayList<CanFinder.Frame>()
            val t0 = Hub.now()
            while (Hub.now() - t0 < 90 && !halted()) {
                for ((h, d, _) in cands) {
                    if (halted()) break
                    val p = read(h, d, 80) ?: continue
                    frames += CanFinder.Frame(Hub.now(), (h shl 16) or d, p)
                }
            }
            val t1 = Hub.now()
            fun ref(t: Double): Double? {
                val s = Hub.sampleAt(t) ?: return null
                if (s.speed < 3 || s.yaw.isNaN()) return null
                return RATIO * WHEELBASE * (s.yaw * PI / 180 / s.speed) * 180 / PI
            }
            val refs = generateSequence(t0) { it + 0.25 }.takeWhile { it < t1 }.mapNotNull { ref(it) }.toList()
            // Needs real turns both ways, or the comparison means nothing; try these again later
            if (refs.isEmpty() || refs.max() < 25 || refs.min() > -25) { log("steering watch: not enough turning (%s)".format(if (refs.isEmpty()) "no data" else "%.0f..%.0f deg".format(refs.min(), refs.max()))); return true }
            cands.forEach { tried += (it.first shl 16) or it.second }
            val res = CanFinder.follows(frames, listOf(CanFinder.Phase("DRIVE", t0, t1)), ::ref, "deg", minR = 0.9, unsigned16 = true)
            log("steering watch results: ${res.joinToString(" | ")}")
            val best = res.firstOrNull() ?: return true
            // Snap to the usual encodings when the fit is close (the steering ratio here is approximate)
            val per = listOf(0.1, 1.0, 0.0625, 0.5).firstOrNull { abs(abs(best.scale) / it - 1) < 0.3 }?.let { if (best.scale < 0) -it else it } ?: best.scale
            val center = best.c.rest // mean raw value over the drive is close to straight ahead
            val dec = best.c.decoder("steer").copy(scale = per, offset = -center * per)
            Hub.setDecoders(Hub.decoders.filter { it.name != "steer" } + dec)
            steerOk = true
            found += "Steering: ${best.c.name} (${best.note})"
            log("STEERING APPLIED $dec")
            return true
        }

        try {
            // 1. Which modules are there
            val present = ArrayList<Int>()
            for (h in 0x700..0x7F7) {
                if (halted()) break
                // Requests go to 0x7n0-0x7n7; 0x7n8-0x7nF are where modules reply, and 0x7DF is broadcast
                if (h == 0x7DF || (h and 0xF) >= 8) continue
                if (h % 16 == 0) say("Finding modules", "%03X · %d found".format(h, present.size))
                val r = ask(ObdLink.Req(h, "3E00"), 60)
                if (r.isNotEmpty()) { present += h; log("module %03X answers %s".format(h, r.toHex())) }
            }
            for (h in listOf(0x720, 0x726, 0x730, DSC)) if (h !in present) present += h
            found += "Modules: " + present.sorted().joinToString(" ") { "%03X".format(it) }
            val order = (listOf(0x720, DSC, 0x730, 0x726) + present.sorted()).distinct().filter { it in present && it != PCM }

            // 2. Known tire-pressure addresses, default then extended session
            val targets = (0x2A00..0x2A3F) + (0x2800..0x283F) + (0xC900..0xC9FF)
            for (h in order) { if (halted()) break; scan(h, targets, ext = false, label = "Tire addresses") }
            tryTires()
            // Extended session only on comfort modules (never brakes, steering, airbag or engine) and only parked
            if (!tiresOk) for (h in order) {
                if (halted()) break
                if (h in setOf(DSC, 0x730, 0x737, PCM) || moving()) continue
                val r = ask(ObdLink.Req(h, "1003"), 150)
                log("extended session %03X: %s".format(h, r.toHex()))
                if (r.isEmpty() || r[0] != 0x50.toByte()) continue
                scan(h, targets, ext = true, label = "Tire addresses (extended)")
                ask(ObdLink.Req(h, "1001"), 150)
            }
            tryTires()
            watchSteering()

            // 3. Everything else, most likely blocks first
            outer@ for (b in blocks) for (h in order) {
                if (halted()) break@outer
                if ((h to b) in done) continue
                if (tiresOk && steerOk) break@outer
                val n = scan(h, (b until b + 0x1000).toList(), ext = false, label = "Sweeping %04X-%04X".format(b, b + 0xFFF))
                log("block %03X %04X: %d new".format(h, b, n))
                tryTires()
                watchSteering()
            }
            // 4. Keep trying steering while driving (up to 20 min) until it's found or a run starts
            val until = Hub.now() + 20 * 60
            while (!halted() && !steerOk && Hub.now() < until) {
                if (!watchSteering()) { if (moving()) break else Thread.sleep(2000) }
            }
        } finally {
            nrc.forEach { (h, m) -> log("refusals %03X: %s".format(h, m.entries.joinToString { "%02X x%d".format(it.key, it.value) })) }
            log("done: ${found.joinToString(" | ")}")
            w?.close()
            val why = when { Hub.timingActive() -> "Stopped: a timed run started"; stop -> "Stopped"; else -> "Finished" }
            state.value = State(false, why, "${hits.values.sumOf { it.size }} signals seen · details in morning_check.txt", ArrayList(found).apply {
                if (!tiresOk) add("Tire pressures: not found")
                if (!steerOk) add("Steering: not found")
            })
        }
    }
}
