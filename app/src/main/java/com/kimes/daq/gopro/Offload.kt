package com.kimes.daq.gopro

import android.annotation.SuppressLint
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.companion.WifiDeviceFilter
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/** One video as the camera lists it. */
data class CameraMedia(val dir: String, val name: String, val createdMs: Long, val size: Long)

/** Pure logic for the camera's media list, kept apart from Android so it is unit tested. */
object MediaList {
    /** Parses the response of GET /gopro/media/list. Creation time "cre" is epoch seconds (a string). */
    fun parse(json: String): List<CameraMedia> {
        val out = ArrayList<CameraMedia>()
        val media = JSONObject(json).optJSONArray("media") ?: return out
        for (i in 0 until media.length()) {
            val d = media.optJSONObject(i) ?: continue
            val dir = d.optString("d")
            val fs = d.optJSONArray("fs") ?: continue
            for (j in 0 until fs.length()) {
                val f = fs.optJSONObject(j) ?: continue
                val n = f.optString("n")
                if (n.isEmpty()) continue
                val cre = f.optString("cre", "0").toLongOrNull() ?: 0L
                out += CameraMedia(dir, n, cre * 1000, f.optString("s", "0").toLongOrNull() ?: 0L)
            }
        }
        return out
    }

    fun isVideo(name: String) = name.endsWith(".mp4", ignoreCase = true)

    /** GX010123.MP4 / GH010123.MP4 -> GL010123.LRV (same chapter and file number). */
    fun lrvName(mp4: String): String {
        val base = mp4.substringBeforeLast('.')
        return if (base.length > 2 && base[0] == 'G') "GL" + base.substring(2) + ".LRV" else "$base.LRV"
    }

    /**
     * Videos recorded since [sinceUtcMs] (and started by [untilUtcMs], when given), with a little slack
     * for clock differences, oldest first, at most [max] (the newest ones). Chapter splits of one
     * recording (GX01xxxx, GX02xxxx) are all kept.
     */
    fun choose(items: List<CameraMedia>, sinceUtcMs: Long, max: Int = 3, slackMs: Long = 30_000, untilUtcMs: Long = Long.MAX_VALUE): List<CameraMedia> =
        items.filter { isVideo(it.name) && it.createdMs >= sinceUtcMs - slackMs && (untilUtcMs == Long.MAX_VALUE || it.createdMs <= untilUtcMs + slackMs) }
            .sortedBy { it.createdMs }
            .takeLast(max)
}

/**
 * Pulls each run's low-resolution preview (.LRV, which carries the same GPMF telemetry as the MP4)
 * off the GoPro over its own Wi-Fi access point, so the video is on the tablet by the time the driver
 * is back in the paddock.
 *
 * The app joins the camera's AP with WifiNetworkSpecifier (only this app uses that network; the
 * system Wi-Fi is untouched) and sends HTTP through that Network object, never binding the process.
 */
@SuppressLint("MissingPermission")
object Offload {
    private val statusFlow = MutableStateFlow("idle")
    val status: StateFlow<String> = statusFlow
    /** Every status change also goes here (the session's event log), so a failed download can be traced. */
    @Volatile var log: ((String) -> Unit)? = null
    private val _status = object {
        var value: String
            get() = statusFlow.value
            set(v) { statusFlow.value = v; try { log?.invoke(v) } catch (_: Exception) {} }
    }

    private const val BASE = "http://10.5.5.9:8080"
    private const val MAX_MP4_FALLBACK = 150L * 1024 * 1024

    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "gopro-offload").apply { isDaemon = true } }

    val busy get() = running.get()

    /**
     * Fetch the previews of videos recorded since [sinceUtcMs]. Runs in the background; progress is in [status].
     * [onFile] is called (on the worker thread) with each downloaded file. [onDone] gets success and a
     * plain-words message; on failure the caller can simply call again later (the camera stays untouched).
     * Does nothing if a fetch is already running. Needs the GoPro BLE link up ([GoPro.isReady]).
     */
    fun fetchLatest(
        ctx: Context, sinceUtcMs: Long, onDone: (ok: Boolean, message: String) -> Unit = { _, _ -> },
        untilUtcMs: Long = Long.MAX_VALUE, max: Int = 3, onFile: (File) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) { log?.invoke("fetch already running, skipped"); return }
        val app = ctx.applicationContext
        worker.execute {
            var ok = false
            var msg = ""
            try {
                msg = run(app, sinceUtcMs, untilUtcMs, max, onFile)
                ok = msg.startsWith("ok")
                if (ok) msg = msg.removePrefix("ok").trim()
            } catch (e: Aborted) {
                msg = e.message ?: "stopped"
            } catch (e: Exception) {
                msg = "video fetch failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                running.set(false)
            }
            _status.value = msg
            onDone(ok, msg)
        }
    }

    private class Aborted(m: String) : Exception(m)

    private fun cameraRecording() = GoPro.recording.value || GoPro.want

    private fun check() {
        if (cameraRecording()) throw Aborted("camera is recording again, will fetch video later")
    }

    private fun run(ctx: Context, since: Long, until: Long, max: Int, onFile: (File) -> Unit): String {
        if (!GoPro.paired) return "no GoPro paired"
        if (!GoPro.isReady) return "GoPro not connected over Bluetooth, will retry"
        check()

        // 1. camera AP on, read SSID and password over BLE
        _status.value = "asking the GoPro to turn on its Wi-Fi"
        var ssid: String? = null; var pass: String? = null
        val got = CountDownLatch(1)
        GoPro.wifiApOn { s, p -> ssid = s; pass = p; got.countDown() }
        if (!got.await(20, TimeUnit.SECONDS) || ssid.isNullOrEmpty() || pass.isNullOrEmpty()) {
            GoPro.wifiApOff()
            return "GoPro would not give its Wi-Fi details, will retry"
        }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var cb: ConnectivityManager.NetworkCallback? = null
        try {
            // 2. join it from this app only
            _status.value = "joining the GoPro's Wi-Fi"
            val sb = WifiNetworkSpecifier.Builder().setSsid(ssid!!).setWpa2Passphrase(pass!!)
            savedBssid(ctx, ssid!!)?.let { runCatching { sb.setBssid(MacAddress.fromString(it)) } }
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(sb.build()).build()
            val netLatch = CountDownLatch(1)
            var net: Network? = null
            var unavailable = false
            val t0 = SystemClock.elapsedRealtime()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { net = network; netLatch.countDown() }
                override fun onUnavailable() { unavailable = true; netLatch.countDown() }
                override fun onLost(network: Network) { if (net == network) net = null }
            }
            cb = callback
            cm.requestNetwork(req, callback, Handler(Looper.getMainLooper()), 45_000)
            netLatch.await(50, TimeUnit.SECONDS)
            val network = net
            if (network == null) {
                val quick = SystemClock.elapsedRealtime() - t0 < 8000
                return if (unavailable && quick) "Wi-Fi join was declined; tap Allow on the Android prompt to get video"
                else "could not join the GoPro's Wi-Fi in time, will retry"
            }

            // 3. media list (the camera may still be finishing the file: retry while it says busy)
            check()
            _status.value = "reading the GoPro's video list"
            // Turbo Transfer (faster Wi-Fi mode); best effort, resets when the camera AP goes off
            http(network, "/gopro/media/turbo_transfer?p=1", 5, retries = 0)
            val listBody = http(network, "/gopro/media/list", 15, retries = 6) ?: return "the GoPro did not answer ($lastHttpError), will retry"
            val wanted = MediaList.choose(MediaList.parse(String(listBody)), since, max = max, untilUtcMs = until)
            if (wanted.isEmpty()) return "no new video on the GoPro yet"

            val dir = ctx.getExternalFilesDir("videos") ?: return "no storage for video"
            dir.mkdirs()
            var n = 0
            for (m in wanted) {
                check()
                val f = downloadPreview(network, m, dir)
                if (f != null) { n++; onFile(f) }
            }
            return if (n > 0) "ok video from the GoPro is on the tablet ($n file${if (n > 1) "s" else ""})" else "could not download the video, will retry"
        } finally {
            cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            GoPro.wifiApOff()
        }
    }

    /** LRV first (same name stem), the MP4 only if there is no LRV and it is small enough. */
    private fun downloadPreview(net: Network, m: CameraMedia, dir: File): File? {
        val lrv = MediaList.lrvName(m.name)
        val dest = File(dir, lrv)
        if (dest.exists() && dest.length() > 0) return dest
        if (fetchTo(net, "/videos/DCIM/${m.dir}/$lrv", dest, lrv)) return dest
        if (m.size in 1..MAX_MP4_FALLBACK) {
            val mp4 = File(dir, m.name)
            if (mp4.exists() && mp4.length() == m.size) return mp4
            if (fetchTo(net, "/videos/DCIM/${m.dir}/${m.name}", mp4, m.name)) return mp4
        }
        return null
    }

    private fun fetchTo(net: Network, path: String, dest: File, label: String): Boolean {
        val part = File(dest.parentFile, dest.name + ".part")
        for (attempt in 1..5) {
            check()
            var c: HttpURLConnection? = null
            try {
                c = net.openConnection(URL(BASE + path)) as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                val code = c.responseCode
                if (code == 404) return false
                if (code != 200) { c.disconnect(); Thread.sleep(3000); continue }  // 5xx / busy encoding
                val total = c.contentLengthLong
                var done = 0L; var lastShown = 0L
                c.inputStream.use { ins ->
                    FileOutputStream(part).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = ins.read(buf); if (r < 0) break
                            out.write(buf, 0, r); done += r
                            if (done - lastShown > 1_000_000) {
                                lastShown = done
                                _status.value = "downloading $label ${done / 1_000_000}" + (if (total > 0) " of ${total / 1_000_000}" else "") + " MB"
                                check()
                            }
                        }
                    }
                }
                if (total > 0 && done != total) { Thread.sleep(2000); continue }
                dest.delete()
                return part.renameTo(dest)
            } catch (e: Aborted) {
                part.delete(); throw e
            } catch (e: Exception) {
                Thread.sleep(2000)
            } finally { c?.disconnect() }
        }
        part.delete()
        return false
    }

    /** GET through the camera network; null if it never answered 200 (busy encoding gets retried). */
    /** Why the last [http] call failed, for the "did not answer" message. */
    @Volatile private var lastHttpError = ""

    private fun http(net: Network, path: String, timeoutS: Int, retries: Int): ByteArray? {
        for (i in 0..retries) {
            var c: HttpURLConnection? = null
            try {
                c = net.openConnection(URL(BASE + path)) as HttpURLConnection
                c.connectTimeout = timeoutS * 1000; c.readTimeout = timeoutS * 1000
                val code = c.responseCode
                if (code == 200) return c.inputStream.use { it.readBytes() }
                lastHttpError = "HTTP $code"
            } catch (e: Exception) {
                lastHttpError = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: "")
            } finally { c?.disconnect() }
            check()
            Thread.sleep(2500)
        }
        return null
    }

    // ---- optional: companion-device association -------------------------------------------
    // Associating the camera's AP once lets Android skip the per-request "connect to this device?" prompt
    // (platform behaviour: WifiNetworkFactory auto-approves a CDM-associated BSSID). Unverified on hardware.

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gopro_offload", Context.MODE_PRIVATE)
    private fun savedBssid(ctx: Context, ssid: String): String? =
        prefs(ctx).takeIf { it.getString("ssid", null) == ssid }?.getString("bssid", null)

    fun isAssociated(ctx: Context) = prefs(ctx).getString("bssid", null) != null

    /**
     * One-time setup. Turns the camera AP on, then asks Android to show its device chooser; the UI must
     * launch the [IntentSender] given to [launch] (startIntentSenderForResult) and pass the result Intent to
     * [onAssociationResult]. Calls [onError] with a plain message if it cannot start.
     */
    fun startAssociation(ctx: Context, launch: (IntentSender) -> Unit, onError: (String) -> Unit) {
        if (!GoPro.isReady) { onError("connect the GoPro first"); return }
        _status.value = "turning on the GoPro's Wi-Fi to set it up"
        GoPro.wifiApOn { ssid, _ ->
            Handler(Looper.getMainLooper()).post {
                if (ssid == null) { onError("the GoPro did not turn on its Wi-Fi"); return@post }
                try {
                    val cdm = ctx.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
                    val filter = WifiDeviceFilter.Builder().setNamePattern(Pattern.compile(Pattern.quote(ssid))).build()
                    val req = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
                    @Suppress("DEPRECATION")
                    cdm.associate(req, object : CompanionDeviceManager.Callback() {
                        override fun onDeviceFound(chooserLauncher: IntentSender) { launch(chooserLauncher) }
                        override fun onFailure(error: CharSequence?) { GoPro.wifiApOff(); onError("association failed: $error") }
                    }, null)
                } catch (e: Exception) { GoPro.wifiApOff(); onError("association not available: ${e.message}") }
            }
        }
    }

    /** Pass the Intent from the chooser's activity result. Stores the camera's SSID/BSSID for later joins. */
    fun onAssociationResult(ctx: Context, data: Intent?): Boolean {
        GoPro.wifiApOff()
        val r: ScanResult? = if (Build.VERSION.SDK_INT >= 33) data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)
        else @Suppress("DEPRECATION") data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        if (r == null) return false
        @Suppress("DEPRECATION") val ssid = if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.trim('"') else r.SSID
        prefs(ctx).edit().putString("ssid", ssid).putString("bssid", r.BSSID).apply()
        return true
    }
}
