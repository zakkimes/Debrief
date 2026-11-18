package com.kimes.daq.geo

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator in "world pixels" at zoom 0 (256 px wide world). */
object Merc {
    fun x(lon: Double) = (lon + 180) / 360 * 256
    fun y(lat: Double): Double {
        val r = lat * PI / 180
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * 256
    }
    fun lon(x: Double) = x / 256 * 360 - 180
    fun lat(y: Double) = atan(sinh(PI * (1 - 2 * y / 256))) * 180 / PI
    /** Meters per world pixel (zoom 0) at a latitude. */
    fun mPerPx0(lat: Double) = 156543.034 * cos(lat * PI / 180)
}

/**
 * Esri World Imagery tiles, cached on disk so the map works at events with no internet.
 * Tiles are downloaded ahead of time ("Save imagery") or whenever a map is viewed online.
 */
object Tiles {
    const val MAX_Z = 19
    const val ATTRIBUTION = "Imagery: Esri, Maxar, Earthstar Geographics"
    private lateinit var dir: File
    private val mem = LruCache<String, ImageBitmap>(96)
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)
    /** Bumps whenever a tile arrives, so maps redraw. */
    val version = MutableStateFlow(0L)

    fun init(root: File) { dir = File(root, "tiles").apply { mkdirs() } }

    private fun file(z: Int, x: Int, y: Int) = File(dir, "$z/$x/$y.jpg")

    /** A tile if it's in memory or on disk; otherwise null and (if allowed) fetch it in the background. */
    fun get(z: Int, x: Int, y: Int, fetch: Boolean = true): ImageBitmap? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        val f = file(z, x, y)
        if (f.exists()) {
            if (inFlight.add(key)) scope.launch {
                try { BitmapFactory.decodeFile(f.path)?.let { mem.put(key, it.asImageBitmap()); version.value++ } } finally { inFlight.remove(key) }
            }
            return null
        }
        if (fetch) {
            val failed = failedAt[key]
            if ((failed == null || System.currentTimeMillis() - failed > 30_000) && inFlight.add(key)) scope.launch {
                try {
                    if (download(z, x, y)) BitmapFactory.decodeFile(f.path)?.let { mem.put(key, it.asImageBitmap()); version.value++ }
                } finally { inFlight.remove(key) }
            }
        }
        return null
    }

    private suspend fun download(z: Int, x: Int, y: Int): Boolean = gate.withPermit {
        val f = file(z, x, y)
        if (f.exists()) return true
        try {
            val c = URL("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Debrief/0.2 (personal telemetry app)")
            if (c.responseCode != 200) { failedAt["$z/$x/$y"] = System.currentTimeMillis(); return false }
            val bytes = c.inputStream.use { it.readBytes() }
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            true
        } catch (e: Exception) {
            failedAt["$z/$x/$y"] = System.currentTimeMillis()
            false
        }
    }

    data class Progress(val done: Int, val total: Int, val failed: Int)
    val saving = MutableStateFlow<Progress?>(null)

    /** Save every tile around a point (radius in meters) for zooms 14..MAX_Z. */
    fun saveArea(lat: Double, lon: Double, radiusM: Double) {
        if (saving.value?.let { it.done < it.total } == true) return
        val jobs = ArrayList<Triple<Int, Int, Int>>()
        for (z in 14..MAX_Z) {
            val scale = (1 shl z).toDouble()
            val rPx = radiusM / Merc.mPerPx0(lat) // world px at z0
            val x0 = floor((Merc.x(lon) - rPx) * scale / 256).toInt(); val x1 = floor((Merc.x(lon) + rPx) * scale / 256).toInt()
            val y0 = floor((Merc.y(lat) - rPx) * scale / 256).toInt(); val y1 = floor((Merc.y(lat) + rPx) * scale / 256).toInt()
            for (x in x0..x1) for (y in y0..y1) jobs += Triple(z, x, y)
        }
        saving.value = Progress(0, jobs.size, 0)
        scope.launch {
            var done = 0; var failed = 0
            val js = jobs.map { (z, x, y) ->
                launch {
                    val ok = download(z, x, y)
                    synchronized(this@Tiles) { done++; if (!ok) failed++ }
                    saving.value = Progress(done, jobs.size, failed)
                }
            }
            js.forEach { it.join() }
            version.value++
        }
    }

    private val pixCache = LruCache<String, android.graphics.Bitmap>(24)

    /** Satellite color at a spot from saved imagery (zoom 18, else 17), or null when not saved. Off the main thread. */
    fun satPixel(lat: Double, lon: Double): Int? {
        for (z in intArrayOf(18, 17)) {
            val n = (1 shl z).toDouble()
            val wx = Merc.x(lon) * n / 256; val wy = Merc.y(lat) * n / 256
            val tx = floor(wx).toInt(); val ty = floor(wy).toInt()
            val key = "$z/$tx/$ty"
            val bmp = pixCache.get(key) ?: file(z, tx, ty).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.also { pixCache.put(key, it) }
                ?: continue
            val px = ((wx - tx) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val py = ((wy - ty) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            return bmp.getPixel(px, py)
        }
        return null
    }

    /** Download (blocking; call off the main thread) any missing zoom-18 tiles within radiusM. Returns how many are available. */
    fun ensure(lat: Double, lon: Double, radiusM: Double): Int {
        val z = 18; val scale = (1 shl z).toDouble()
        val rPx = radiusM / Merc.mPerPx0(lat)
        val x0 = floor((Merc.x(lon) - rPx) * scale / 256).toInt(); val x1 = floor((Merc.x(lon) + rPx) * scale / 256).toInt()
        val y0 = floor((Merc.y(lat) - rPx) * scale / 256).toInt(); val y1 = floor((Merc.y(lat) + rPx) * scale / 256).toInt()
        var have = 0
        kotlinx.coroutines.runBlocking {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<Boolean>>()
            for (x in x0..x1) for (y in y0..y1) jobs += async(Dispatchers.IO) { download(z, x, y) }
            have = jobs.count { it.await() }
        }
        return have
    }

    fun savedCount(): Int = dir.walkTopDown().count { it.isFile && it.name.endsWith(".jpg") }
}
