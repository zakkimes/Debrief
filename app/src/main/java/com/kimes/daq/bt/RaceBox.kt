package com.kimes.daq.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Decoded RaceBox Data Message (UBX class 0xFF, id 0x01, 80-byte payload). */
data class RaceBoxPacket(
    val iTow: Long,
    val fix: Int,
    val sats: Int,
    val lat: Double,
    val lon: Double,
    val speed: Double,     // m/s
    val heading: Double,   // degrees
    val hAcc: Double,      // meters
    val gX: Double, val gY: Double, val gZ: Double,
    val rX: Double = 0.0, val rY: Double = 0.0, val rZ: Double = 0.0, // rotation rates, deg/s
    val battery: Int,
    val utcMs: Long = 0,   // GPS UTC time, epoch ms; 0 until the receiver has valid date and time
)

@SuppressLint("MissingPermission")
class RaceBox(
    private val ctx: Context,
    private val onPacket: (RaceBoxPacket) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val nus = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val tx = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var stopped = false
    private val buf = ByteArray(4096)
    private var bufLen = 0
    /** The RaceBox this link is connected (or connecting) to. */
    @Volatile var address: String? = null
        private set

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("devices", Context.MODE_PRIVATE)
        /** The RaceBox chosen on the Devices page (its Bluetooth address), or null for the first one found. */
        fun chosen(ctx: Context): String? = prefs(ctx).getString("racebox", null)
        /** What the user called it, else its own name. */
        fun label(ctx: Context): String? = prefs(ctx).getString("raceboxNick", null)?.takeIf { it.isNotBlank() } ?: prefs(ctx).getString("raceboxName", null)
        fun choose(ctx: Context, address: String?, name: String?) {
            prefs(ctx).edit().apply { if (address == null) { remove("racebox"); remove("raceboxName"); remove("raceboxNick") } else { putString("racebox", address); putString("raceboxName", name) } }.apply()
        }
        fun nickname(ctx: Context, nick: String) = prefs(ctx).edit().putString("raceboxNick", nick).apply()
    }

    fun start() {
        stopped = false
        scan()
    }

    fun stop() {
        stopped = true
        stopScan()
        gatt?.close()
        gatt = null
        onStatus("off")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            // Only the chosen RaceBox, when one is chosen: with two cars at the grid, never the other one
            val want = chosen(ctx)
            if (name.startsWith("RaceBox") && (want == null || result.device.address.equals(want, ignoreCase = true))) {
                stopScan()
                connect(result.device, name)
            }
        }
    }

    private fun scan() {
        if (stopped || scanning) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onStatus("Bluetooth off")
            main.postDelayed({ scan() }, 3000)
            return
        }
        onStatus(label(ctx)?.let { "searching for $it" } ?: "searching")
        scanning = true
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({ if (scanning) { stopScan(); scan() } }, 15000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    private fun connect(device: BluetoothDevice, name: String) {
        address = device.address
        onStatus("connecting ${label(ctx) ?: name}")
        gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun reconnectLater() {
        gatt?.close()
        gatt = null
        if (!stopped) main.postDelayed({ scan() }, 2000)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatus("disconnected")
                main.post { reconnectLater() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(nus)?.getCharacteristic(tx)
            if (ch == null) {
                onStatus("no data service")
                g.disconnect()
                return
            }
            g.setCharacteristicNotification(ch, true)
            val d = ch.getDescriptor(cccd)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
            onStatus("connected")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            feed(value)
        }

        @Deprecated("pre-33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) feed(c.value)
        }
    }

    @Synchronized
    private fun feed(bytes: ByteArray) {
        if (bufLen + bytes.size > buf.size) bufLen = 0
        System.arraycopy(bytes, 0, buf, bufLen, bytes.size)
        bufLen += bytes.size
        var i = 0
        while (bufLen - i >= 8) {
            if (buf[i] != 0xB5.toByte() || buf[i + 1] != 0x62.toByte()) { i++; continue }
            val len = (buf[i + 4].toInt() and 0xFF) or ((buf[i + 5].toInt() and 0xFF) shl 8)
            val total = 6 + len + 2
            if (len > 512) { i++; continue }
            if (bufLen - i < total) break
            var a = 0
            var b = 0
            for (k in i + 2 until i + 6 + len) {
                a = (a + (buf[k].toInt() and 0xFF)) and 0xFF
                b = (b + a) and 0xFF
            }
            if (a == (buf[i + 6 + len].toInt() and 0xFF) && b == (buf[i + 7 + len].toInt() and 0xFF)) {
                if (buf[i + 2] == 0xFF.toByte() && buf[i + 3] == 0x01.toByte() && len == 80) {
                    onPacket(parse(ByteBuffer.wrap(buf, i + 6, 80).slice().order(ByteOrder.LITTLE_ENDIAN)))
                }
                i += total
            } else {
                i++
            }
        }
        System.arraycopy(buf, i, buf, 0, bufLen - i)
        bufLen -= i
    }

    private fun parse(p: ByteBuffer) = RaceBoxPacket(
        iTow = p.getInt(0).toLong() and 0xFFFFFFFFL,
        fix = p.get(20).toInt() and 0xFF,
        sats = p.get(23).toInt() and 0xFF,
        lon = p.getInt(24) / 1e7,
        lat = p.getInt(28) / 1e7,
        hAcc = (p.getInt(40).toLong() and 0xFFFFFFFFL) / 1000.0,
        speed = p.getInt(48) / 1000.0,
        heading = p.getInt(52) / 1e5,
        battery = p.get(67).toInt() and 0x7F,
        gX = p.getShort(68) / 1000.0,
        gY = p.getShort(70) / 1000.0,
        gZ = p.getShort(72) / 1000.0,
        rX = p.getShort(74) / 100.0,
        rY = p.getShort(76) / 100.0,
        rZ = p.getShort(78) / 100.0,
        utcMs = utc(p),
    )

    /** UTC from the packet's date/time fields (bytes 4-19), valid only when the date and time flags are set. */
    private fun utc(p: ByteBuffer): Long {
        val flags = p.get(11).toInt() and 0xFF
        if (flags and 0x03 != 0x03) return 0
        return try {
            java.time.LocalDateTime.of(
                p.getShort(4).toInt() and 0xFFFF, p.get(6).toInt() and 0xFF, p.get(7).toInt() and 0xFF,
                p.get(8).toInt() and 0xFF, p.get(9).toInt() and 0xFF, (p.get(10).toInt() and 0xFF).coerceAtMost(59),
            ).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() + p.getInt(16) / 1_000_000
        } catch (e: Exception) { 0 }
    }
}
