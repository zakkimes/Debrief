package com.kimes.daq.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

/** A RaceBox seen nearby: its Bluetooth address, advertised name and signal strength. */
data class SeenRaceBox(val address: String, val name: String, val rssi: Int, val at: Long)

/**
 * Lists the RaceBoxes advertising nearby, for picking one on the Devices page. A RaceBox already
 * connected to this (or another) tablet stops advertising, so it won't show up here.
 */
@SuppressLint("MissingPermission")
class RaceBoxScan(ctx: Context, private val onSeen: (SeenRaceBox) -> Unit) {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var on = false
    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (name.startsWith("RaceBox")) onSeen(SeenRaceBox(result.device.address, name, result.rssi, System.currentTimeMillis()))
        }
    }

    /** False when Bluetooth is off. */
    fun start(): Boolean {
        val s = adapter?.bluetoothLeScanner ?: return false
        if (on) return true
        on = true
        s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        return true
    }

    fun stop() {
        if (!on) return
        on = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}

/** Lists GoPros nearby (HERO9 and later advertise GoPro's service 0xFEA6), for picking one on the Devices page. */
@SuppressLint("MissingPermission")
class GoProScan(ctx: Context, private val onSeen: (SeenRaceBox) -> Unit) {
    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var on = false
    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: "GoPro"
            onSeen(SeenRaceBox(result.device.address, name, result.rssi, System.currentTimeMillis()))
        }
    }

    fun start(): Boolean {
        val s = adapter?.bluetoothLeScanner ?: return false
        if (on) return true
        on = true
        s.startScan(
            listOf(android.bluetooth.le.ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(java.util.UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb"))).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb,
        )
        return true
    }

    fun stop() {
        if (!on) return
        on = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }
}
