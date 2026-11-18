package com.kimes.daq.gopro

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Starts and stops a GoPro (HERO9 and later) over Bluetooth LE, using GoPro's published Open GoPro
 * commands. The first time, the camera has to be in pairing mode; after that it reconnects by itself
 * whenever it's on and in range.
 *
 * The app says what it wants (recording or not) and this keeps sending the shutter command until the
 * camera reports it is doing that, so a busy or just-woken camera still catches up.
 */
@SuppressLint("MissingPermission")
object GoPro {
    val status = MutableStateFlow("off")         // plain words for the screen
    val recording = MutableStateFlow(false)      // what the camera reports
    val battery = MutableStateFlow<Int?>(null)
    /** True when the pairing looks lost (bond removed on the tablet or forgotten by the camera). The UI should offer [repair]. */
    val needsRepair = MutableStateFlow(false)
    /** Step-by-step text while [repair] runs ("On the GoPro: ...", "Pairing...", "Connected"); empty when not repairing. */
    val repairStep = MutableStateFlow("")
    @Volatile var want = false                   // what the app wants
        private set

    var onEvent: ((String) -> Unit)? = null      // session event log

    private fun uuid(s: String) = UUID.fromString("b5f9$s-aa8d-11e3-9046-0002a5d5c51b")
    private val service = UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb")
    private val cmd = uuid("0072"); private val cmdRsp = uuid("0073")
    private val set = uuid("0074"); private val setRsp = uuid("0075")
    private val qry = uuid("0076"); private val qryRsp = uuid("0077")
    private val wifiSsid = uuid("0002"); private val wifiPass = uuid("0003")
    private val wifiService = uuid("0001")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val ST_BUSY = 8
    private const val ST_ENCODING = 10
    private const val ST_BATTERY = 70

    private lateinit var ctx: Context
    private val thread = HandlerThread("gopro").apply { start() }
    private val h = Handler(thread.looper)
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var active = false
    private var ready = false
    private var scanning = false
    private var lastShutter = 0L
    private var shutterTries = 0
    private val ops = ArrayDeque<() -> Unit>()
    private var opBusy = false
    private var repairing = false
    private var repairDeadline = 0L
    private var failures = 0
    private var lastAttempt = 0L
    private var apPending: ((Boolean) -> Unit)? = null
    private var readCb: ((ByteArray?) -> Unit)? = null
    private val rx = HashMap<UUID, ByteArray>()  // partial multi-packet responses

    private val prefs by lazy { ctx.getSharedPreferences("gopro", Context.MODE_PRIVATE) }
    val paired get() = ::ctx.isInitialized && prefs.getString("addr", null) != null
    val name get() = if (::ctx.isInitialized) prefs.getString("name", null) else null

    fun init(c: Context) { ctx = c.applicationContext }

    /** True while the BLE link is up and set up (commands can be sent). */
    val isReady get() = ready

    /** Connect (or look for a camera to pair) and keep the link up until [stop]. */
    fun start() = h.post {
        if (active) return@post
        active = true
        val addr = prefs.getString("addr", null)
        if (addr != null) {
            val d = adapter()?.getRemoteDevice(addr) ?: return@post
            if (d.bondState == BluetoothDevice.BOND_NONE) markStale("the tablet no longer has this GoPro paired")
            connect(d, auto = false)
        } else scan()
        h.removeCallbacks(tick); h.postDelayed(tick, 1000)
    }

    /** Stop recording if it is, then drop the link. */
    fun stop() = h.post {
        if (!active) return@post
        want = false
        if (ready && recording.value) {
            write(cmd, byteArrayOf(3, 1, 1, 0))
            h.postDelayed({ disconnect() }, 1500)
        } else disconnect()
    }

    /** Forget the paired camera and look for another one. */
    fun forget() = h.post {
        prefs.edit().clear().apply()
        disconnect()
        start()
    }

    /**
     * Dependable way back after the pairing was forgotten on the tablet or the camera: removes the old
     * bond, scans for a camera in pairing mode, bonds and reconnects. Progress is in [repairStep]
     * (and [status]); [repairStep] ends at "Connected" or a failure message.
     */
    fun repair() = h.post {
        if (!::ctx.isInitialized) return@post
        disconnect()
        repairing = true; failures = 0
        repairDeadline = SystemClock.elapsedRealtime() + 120_000
        repairStep.value = "Step 1: removing the old pairing"
        status.value = repairStep.value
        val old = prefs.getString("addr", null)
        runCatching {
            adapter()?.bondedDevices?.filter { it.address == old || (it.name ?: "").startsWith("GoPro") }?.forEach { removeBond(it) }
        }
        prefs.edit().clear().apply()
        h.postDelayed({
            if (!repairing) return@postDelayed
            repairStep.value = "Step 2: on the GoPro: Connections → Connect Device → GoPro Quik App (then wait)"
            status.value = repairStep.value
            start()
        }, 1500)
    }

    private fun removeBond(d: BluetoothDevice) {
        try { d.javaClass.getMethod("removeBond").invoke(d) } catch (_: Exception) {}
    }

    private fun markStale(why: String) {
        if (needsRepair.value) return
        needsRepair.value = true
        onEvent?.invoke("gopro_stale_pairing $why")
        if (!repairing) status.value = "Pairing lost ($why). Tap Re-pair, then on the GoPro: Connections → Connect Device → GoPro Quik App"
    }

    private fun connectFailed(st: Int) {
        failures++
        // 133 = generic GATT error, 5/15 = authentication / encryption failed, 22 = local host ended it on auth
        val authish = st == 5 || st == 15 || st == 22
        if (!repairing && paired && (authish || (failures >= 4 && st != 0 && st != 8))) {
            markStale("connect failed, status $st, $failures tries")
        }
    }

    fun record(on: Boolean, why: String) {
        if (want == on) return
        want = on
        shutterTries = 0
        onEvent?.invoke("gopro_${if (on) "on" else "off"} $why")
        h.post { reconcile() }
    }

    private fun adapter() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun disconnect() {
        active = false; ready = false
        stopScan()
        h.removeCallbacks(tick)
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        gatt = null
        apPending = null; readCb = null
        ops.clear(); opBusy = false
        recording.value = false
        status.value = "off"
    }

    // ---- finding and pairing ----------------------------------------------------------

    /** Pair only with this camera (Bluetooth address), when one was picked on the Devices page. */
    @Volatile private var target: String? = null

    /** Pair with this camera, not just the first GoPro in pairing mode. */
    fun pairWith(address: String) { target = address; repair() }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            h.post {
                if (!scanning) return@post
                target?.let { t -> if (!r.device.address.equals(t, ignoreCase = true)) return@post }
                stopScan()
                val n = r.scanRecord?.deviceName ?: r.device.name ?: "GoPro"
                prefs.edit().putString("name", n).apply()
                connect(r.device, auto = false)
            }
        }
    }

    private fun scan() {
        if (!active || scanning) return
        val sc = adapter()?.bluetoothLeScanner ?: run { status.value = "Bluetooth off"; h.postDelayed({ scan() }, 3000); return }
        status.value = "looking for a GoPro in pairing mode"
        scanning = true
        sc.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb,
        )
        h.postDelayed({ if (scanning) { stopScan(); scan() } }, 20000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter()?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private fun connect(d: BluetoothDevice, auto: Boolean) {
        device = d
        lastAttempt = SystemClock.elapsedRealtime()
        if (repairing) status.value = "Step 3: Pairing..."
        else if (!needsRepair.value) status.value = if (paired) "waiting for ${name ?: "the GoPro"} (is it on?)" else "connecting"
        gatt?.close()
        gatt = d.connectGatt(ctx, auto, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private val bondRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (d?.address != device?.address) return
            when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                BluetoothDevice.BOND_BONDED -> h.post { gatt?.discoverServices() }
                BluetoothDevice.BOND_NONE -> h.post {
                    status.value = "pairing failed: put the GoPro in pairing mode (Connections → Connect Device → GoPro Quik App) and try again"
                    if (repairing) repairStep.value = "Pairing failed: make sure the GoPro is in pairing mode, then tap Re-pair again"
                    else if (ready) markStale("bond removed")
                    else failures++
                }
            }
        }
    }
    private var bondRxOn = false

    // ---- GATT -------------------------------------------------------------------------

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) = h.post {
            if (g != gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                status.value = if (repairing) "Pairing..." else "connected, setting up"
                if (repairing) repairStep.value = "Step 3: Pairing..."
                if (g.device.bondState == BluetoothDevice.BOND_BONDED) g.discoverServices()
                else {
                    status.value = "pairing"
                    if (!bondRxOn) {
                        ctx.registerReceiver(bondRx, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)); bondRxOn = true
                    }
                    if (g.device.bondState == BluetoothDevice.BOND_NONE) g.device.createBond()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || st != BluetoothGatt.GATT_SUCCESS) {
                val wasReady = ready
                ready = false; ops.clear(); opBusy = false
                apPending = null; readCb = null
                recording.value = false
                if (!active) return@post
                if (wasReady) failures = 0 else connectFailed(st)
                if (!repairing && !needsRepair.value) status.value = if (wasReady) "lost the GoPro, reconnecting" else "can't reach the GoPro (error $st), retrying"
                g.close(); gatt = null
                // Reconnect in the background: Android connects as soon as the camera is back in range.
                // Back off after repeated failures so a gone camera doesn't hammer the radio.
                val delay = (1000L * (1 shl failures.coerceAtMost(4))).coerceAtMost(15000)
                h.postDelayed({ if (active && gatt == null) device?.let { connect(it, auto = failures < 2) } }, delay)
            }
        }.let { }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) = h.post {
            val s = g.getService(service)
            if (s == null) { status.value = "that device isn't a GoPro"; connectFailed(st); return@post }
            prefs.edit().putString("addr", g.device.address).apply()
            for (u in listOf(cmdRsp, setRsp, qryRsp)) s.getCharacteristic(u)?.let { c ->
                g.setCharacteristicNotification(c, true)
                c.getDescriptor(cccd)?.let { d -> op { writeDesc(g, d) } }
            }
            op {
                ready = true
                failures = 0
                status.value = "connected"
                if (needsRepair.value) needsRepair.value = false
                if (repairing) { repairing = false; target = null; repairStep.value = "Connected"; onEvent?.invoke("gopro_repaired") }
                opDone()
                setClock()
                // Video mode, then report recording, busy and battery whenever they change
                write(cmd, byteArrayOf(4, 0x3E, 2, 0x03, 0xE8.toByte()))
                write(qry, byteArrayOf(4, 0x53, ST_ENCODING.toByte(), ST_BUSY.toByte(), ST_BATTERY.toByte()))
                reconcile()
            }
        }.let { }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            h.post {
                // Authentication/encryption failures here mean the camera no longer knows our bond
                if (st == 5 || st == 15) { connectFailed(st); markStale("camera rejected the link, status $st") }
                opDone()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) {
            h.post { val r = readCb; readCb = null; r?.invoke(if (st == 0) value else null); opDone() }
        }

        @Deprecated("pre-33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            @Suppress("DEPRECATION") val v = c.value
            if (Build.VERSION.SDK_INT < 33) h.post { val r = readCb; readCb = null; r?.invoke(if (st == 0) v else null); opDone() }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) { h.post { opDone() } }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            h.post { packet(c.uuid, value) }
        }

        @Deprecated("pre-33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = c.value
            if (Build.VERSION.SDK_INT < 33) h.post { packet(c.uuid, v) }
        }
    }

    private fun op(f: () -> Unit) { ops.addLast(f); if (!opBusy) next() }
    private fun next() {
        val f = ops.removeFirstOrNull() ?: return
        opBusy = true
        f()
    }
    private fun opDone() { opBusy = false; next() }

    private fun writeDesc(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
        else @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        if (!ok) opDone()
    }

    private fun write(ch: UUID, bytes: ByteArray) = op {
        val g = gatt; val c = g?.getService(service)?.getCharacteristic(ch)
        if (g == null || c == null) { opDone(); return@op }
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        else @Suppress("DEPRECATION") run { c.value = bytes; c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; g.writeCharacteristic(c) }
        if (!ok) opDone()
    }

    // ---- keeping the camera in step ---------------------------------------------------

    private var lastKeepAlive = 0L
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (ready && now - lastKeepAlive > 3000) {
                lastKeepAlive = now
                write(set, byteArrayOf(3, 0x5B, 1, 0x42)) // keep-alive: stops the camera powering itself off
            }
            reconcile()
            if (repairing && now > repairDeadline) {
                repairing = false
                repairStep.value = "No GoPro found. Put it in pairing mode (Connections → Connect Device → GoPro Quik App) and tap Re-pair"
                status.value = repairStep.value
            }
            // Watchdog: nothing connected and nothing pending for a while -> try again (camera woke up, tablet BT toggled)
            if (!ready && gatt == null && !scanning && now - lastAttempt > 20000) {
                val d = device ?: prefs.getString("addr", null)?.let { adapter()?.getRemoteDevice(it) }
                if (d != null) connect(d, auto = true) else scan()
            }
            h.postDelayed(this, 500)
        }
    }

    // ---- Wi-Fi AP (used by Offload) and clock ---------------------------------------------

    /** Set the camera clock to UTC so media-list creation times compare with the phone's clock. */
    fun setClock() {
        if (!ready) return
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val y = c.get(java.util.Calendar.YEAR)
        write(cmd, byteArrayOf(9, 0x0D, 7, (y shr 8).toByte(), y.toByte(), (c.get(java.util.Calendar.MONTH) + 1).toByte(),
            c.get(java.util.Calendar.DAY_OF_MONTH).toByte(), c.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            c.get(java.util.Calendar.MINUTE).toByte(), c.get(java.util.Calendar.SECOND).toByte()))
    }

    /** Turn the camera's Wi-Fi access point on and read its SSID and password. Callback runs on the GoPro thread; null = failed. */
    fun wifiApOn(result: (ssid: String?, pass: String?) -> Unit) { h.post {
        if (!ready) { result(null, null); return@post }
        val timeout = Runnable { apPending?.let { apPending = null; it(false) } }
        apPending = { ok ->
            h.removeCallbacks(timeout)
            if (!ok) result(null, null)
            else readString(wifiSsid) { s -> if (s == null) result(null, null) else readString(wifiPass) { p -> result(s, p) } }
        }
        write(cmd, byteArrayOf(3, 0x17, 1, 1))
        h.postDelayed(timeout, 8000)
    } }

    fun wifiApOff() { h.post { if (ready) write(cmd, byteArrayOf(3, 0x17, 1, 0)) } }

    private fun readString(ch: UUID, done: (String?) -> Unit) = op {
        val g = gatt; val c = g?.getService(wifiService)?.getCharacteristic(ch)
        if (g == null || c == null) { opDone(); done(null); return@op }
        readCb = { v -> done(v?.toString(Charsets.UTF_8)?.trim('\u0000', ' ')) }
        if (!g.readCharacteristic(c)) { readCb = null; opDone(); done(null) }
    }

    private fun reconcile() {
        if (!ready || want == recording.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShutter < 1500) return
        lastShutter = now
        shutterTries++
        if (shutterTries > 6) status.value = "the GoPro isn't responding to ${if (want) "record" else "stop"}"
        write(cmd, byteArrayOf(3, 1, 1, if (want) 1 else 0))
    }

    /** Joins Open GoPro's packets (a header with the length, then continuation packets) into messages. */
    private fun packet(ch: UUID, b: ByteArray) {
        if (b.isEmpty()) return
        val h0 = b[0].toInt() and 0xFF
        if (h0 and 0x80 != 0) { // continuation
            rx[ch]?.let { rx[ch] = it + b.copyOfRange(1, b.size) }
        } else {
            val (len, body) = when ((h0 shr 5) and 3) {
                0 -> (h0 and 0x1F) to b.copyOfRange(1, b.size)
                1 -> (((h0 and 0x1F) shl 8) or (b.getOrNull(1)?.toInt()?.and(0xFF) ?: 0)) to b.copyOfRange(2, b.size)
                else -> (((b.getOrNull(1)?.toInt() ?: 0) and 0xFF shl 8) or ((b.getOrNull(2)?.toInt() ?: 0) and 0xFF)) to b.copyOfRange(3, b.size)
            }
            rx[ch] = body
            lens[ch] = len
        }
        val m = rx[ch] ?: return
        if (m.size >= (lens[ch] ?: 0)) { rx.remove(ch); message(ch, m) }
    }
    private val lens = HashMap<UUID, Int>()

    private fun message(ch: UUID, m: ByteArray) {
        if (m.size < 2) return
        val id = m[0].toInt() and 0xFF
        val result = m[1].toInt() and 0xFF
        when {
            ch == cmdRsp && id == 0x17 -> apPending?.let { apPending = null; it(result == 0) }
            ch == cmdRsp && id == 0x01 && result != 0 -> status.value = if (want) "GoPro busy, retrying record" else "GoPro busy, retrying stop"
            // Accepted: trust it until the camera's own status says otherwise
            ch == cmdRsp && id == 0x01 -> if (recording.value != want) { recording.value = want; shutterTries = 0; showStatus() }
            ch == qryRsp && (id == 0x53 || id == 0x93 || id == 0x13) -> statuses(m, 2)
        }
    }

    /** Status values: id, length, value... */
    private fun statuses(m: ByteArray, from: Int) {
        var i = from
        while (i + 1 < m.size) {
            val id = m[i].toInt() and 0xFF; val len = m[i + 1].toInt() and 0xFF
            if (i + 2 + len > m.size) break
            var v = 0L
            for (k in 0 until len) v = (v shl 8) or (m[i + 2 + k].toLong() and 0xFF)
            when (id) {
                ST_ENCODING -> {
                    val on = v != 0L
                    if (on != recording.value) { recording.value = on; onEvent?.invoke("gopro_${if (on) "recording" else "stopped"}") }
                    if (on == want) shutterTries = 0
                }
                ST_BATTERY -> battery.value = v.toInt()
            }
            i += 2 + len
        }
        showStatus()
    }

    private fun showStatus() {
        status.value = "connected" + (battery.value?.let { " · battery $it%" } ?: "") + if (recording.value) " · recording" else ""
    }
}
