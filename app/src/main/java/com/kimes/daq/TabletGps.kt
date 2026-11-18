package com.kimes.daq

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The tablet's own GPS and compass, for the course walk (the RaceBox stays in the car).
 * Positions are averaged over the last few seconds to steady the tablet's weaker GPS.
 */
object TabletGps : LocationListener, SensorEventListener {
    /** src: "tablet" (Android's location, which includes an external GPS feeding it) or "RaceBox"; whichever is more accurate right now. */
    data class Fix(val lat: Double, val lon: Double, val accM: Float, val n: Int, val src: String = "tablet")

    /** What the GPS chip hears: satellites in view, used in the fix, and the average signal of the best four. */
    data class Sky(val heard: Int, val used: Int, val cn0: Float)
    val sky = MutableStateFlow<Sky?>(null)
    @Volatile private var lastTabletFix = 0L
    private var ctxRef: Context? = null

    val fix = MutableStateFlow<Fix?>(null)
    val status = MutableStateFlow("off")
    /** Direction the back of the tablet faces, degrees from north (null without a compass). */
    val azimuth = MutableStateFlow<Double?>(null)

    private class Pt(val t: Long, val lat: Double, val lon: Double, val acc: Float)
    private val recent = ArrayDeque<Pt>()
    /** Every source's last few seconds, so the most accurate one can be used. */
    private val bySrc = HashMap<String, ArrayDeque<Pt>>()
    /** The other source's accuracy, for the screen ("RaceBox ±1 ft, tablet ±30 ft"). */
    @Volatile var others: Map<String, Float> = emptyMap()
        private set
    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private var users = 0

    /** Android's last known position from any source (GPS, Wi-Fi, cell), newest first. A rough "you are here" before a fix. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Pair<Double, Double>? {
        val l = lm ?: return null
        return try {
            l.getProviders(true).mapNotNull { l.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
        } catch (_: SecurityException) { null }
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context) {
        if (users++ > 0) return
        val l = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = l
        ctxRef = ctx.applicationContext
        status.value = "searching"
        try {
            if (!l.isProviderEnabled(LocationManager.GPS_PROVIDER)) status.value = "location is off in Android settings"
            prime(l)
            l.registerGnssStatusCallback(gnss, android.os.Handler(Looper.getMainLooper()))
            l.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            status.value = "no location permission"
        } catch (e: IllegalArgumentException) {
            status.value = "this tablet has no GPS"
        }
        val s = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = s
        s.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { s.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    @Synchronized
    fun stop() {
        if (--users > 0) return
        users = 0
        lm?.unregisterGnssStatusCallback(gnss)
        lm?.removeUpdates(this); lm = null
        sky.value = null
        sm?.unregisterListener(this); sm = null
        recent.clear(); bySrc.clear(); others = emptyMap()
        fix.value = null
        status.value = "off"
    }

    /**
     * This Wi-Fi tablet has no phone network to help its GPS find satellites, so a cold start can take
     * many minutes or never finish. While online, ask Android to download satellite orbit predictions
     * (PSDS/XTRA) and the time, so the chip starts warm at the lot.
     */
    /** Cold start: throw away the chip's almanac, ephemeris, time and position, then fetch fresh assistance. */
    fun coldStart() {
        val l = lm ?: return
        try { l.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null) } catch (_: Exception) {}
        prime(l)
        recent.clear(); bySrc.remove("tablet"); sky.value = null
        status.value = "reset: searching from scratch (outdoors, up to 15 min)"
    }

    private fun prime(l: LocationManager) {
        for (cmd in listOf("force_time_injection", "force_psds_injection", "force_xtra_injection")) {
            try { l.sendExtraCommand(LocationManager.GPS_PROVIDER, cmd, null) } catch (_: Exception) {}
        }
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(st: GnssStatus) {
            var used = 0
            val cn = ArrayList<Float>()
            for (i in 0 until st.satelliteCount) {
                if (st.usedInFix(i)) used++
                if (st.getCn0DbHz(i) > 0) cn += st.getCn0DbHz(i)
            }
            val top = cn.sortedDescending().take(4)
            sky.value = Sky(cn.size, used, if (top.isEmpty()) 0f else top.average().toFloat())
            // Satellite talk only while there's no position from anywhere
            if (fix.value == null) status.value = skyText()
        }
        override fun onStopped() { sky.value = null }
    }

    /** Plain words for what the chip hears, shown while there is no fix. */
    fun skyText(): String {
        val s = sky.value ?: return "searching (no satellite report yet)"
        return when {
            s.heard == 0 -> "hears no satellites"
            s.used == 0 && s.cn0 < 20 -> "hears ${s.heard} satellites, all weak (%.0f dB): keep it face-up outdoors".format(s.cn0)
            s.used == 0 -> "hears ${s.heard} satellites (%.0f dB), working out position…".format(s.cn0)
            else -> "using ${s.used} of ${s.heard} satellites"
        }
    }

    /** Position from the RaceBox (the session's, or the course walk's own link). */
    @Synchronized
    fun external(lat: Double, lon: Double, accM: Float) {
        if (users == 0) return
        add(lat, lon, accM, "RaceBox")
    }

    @Synchronized
    override fun onLocationChanged(loc: Location) {
        lastTabletFix = SystemClock.elapsedRealtime()
        add(loc.latitude, loc.longitude, loc.accuracy, "tablet")
    }

    private fun add(lat0: Double, lon0: Double, acc: Float, src: String) {
        val now = SystemClock.elapsedRealtime()
        val q = bySrc.getOrPut(src) { ArrayDeque() }
        q.addLast(Pt(now, lat0, lon0, acc))
        for (d in bySrc.values) while (d.isNotEmpty() && now - d.first().t > 4000) d.removeFirst()
        // The most accurate source with a fresh reading; switch only when another is clearly better
        // (or the current one has gone quiet), so the position doesn't hop between the two
        fun latestAcc(s: String) = bySrc[s]?.lastOrNull()?.takeIf { now - it.t < 3000 }?.acc
        val cur = fix.value?.src
        val best = bySrc.keys.mapNotNull { s -> latestAcc(s)?.let { s to it } }.minByOrNull { it.second }?.first ?: src
        val use = if (cur != null && cur != best && latestAcc(cur) != null && latestAcc(best)!! > latestAcc(cur)!! * 0.8f) cur else best
        others = bySrc.keys.filter { it != use }.mapNotNull { s -> latestAcc(s)?.let { s to it } }.toMap()
        val pts = bySrc[use] ?: return
        recent.clear(); recent.addAll(pts)
        // Accuracy-weighted average of the last 4 s
        var wSum = 0.0; var lat = 0.0; var lon = 0.0
        for (p in pts) { val w = 1.0 / (p.acc * p.acc).coerceAtLeast(1f); wSum += w; lat += p.lat * w; lon += p.lon * w }
        fix.value = Fix(lat / wSum, lon / wSum, pts.minOf { it.acc }, pts.size, use)
        val accFt = pts.last().acc * 3.28084
        status.value = (if (use == "RaceBox") "RaceBox ±%.0f ft".format(accFt) else "Tablet GPS ±%.0f ft".format(accFt)) +
            others.entries.joinToString("") { (s, a) -> " · " + (if (s == "RaceBox") "RaceBox" else "tablet") + " ±%.0f ft".format(a * 3.28084) }
    }

    override fun onProviderDisabled(provider: String) { status.value = "location is off in Android settings" }
    override fun onProviderEnabled(provider: String) { status.value = "searching" }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    private val rot = FloatArray(9)
    private val remap = FloatArray(9)
    private val ori = FloatArray(3)
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Direction the camera (back of the tablet) points, whatever way it's held
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remap)
        SensorManager.getOrientation(remap, ori)
        azimuth.value = (Math.toDegrees(ori[0].toDouble()) + 360) % 360
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
