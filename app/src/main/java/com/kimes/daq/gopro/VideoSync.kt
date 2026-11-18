package com.kimes.daq.gopro

import com.kimes.daq.data.Sample
import org.json.JSONObject

/** A video linked to a session: UTC at the video's first frame, after syncing. */
data class VideoLink(
    val uri: String,
    val name: String,
    val startUtc: Long,       // epoch ms at video time 0
    val durationMs: Long,
    val fineMs: Long,         // correction the speed match applied on top of the GoPro's own clock
    val note: String,         // how the sync went, shown to the user
) {
    val endUtc get() = startUtc + durationMs
    /** Seconds into the video for a moment in the world, or null if the video doesn't cover it. */
    fun videoTime(utc: Long): Double? = if (utc in startUtc..endUtc) (utc - startUtc) / 1000.0 else null

    fun json(): JSONObject = JSONObject().put("uri", uri).put("name", name).put("startUtc", startUtc)
        .put("durationMs", durationMs).put("fineMs", fineMs).put("note", note)

    companion object {
        fun from(o: JSONObject) = VideoLink(
            o.getString("uri"), o.optString("name"), o.getLong("startUtc"), o.getLong("durationMs"),
            o.optLong("fineMs"), o.optString("note"),
        )
    }
}

object VideoSync {
    sealed class Outcome {
        data class Ok(val startUtc: Long, val fineMs: Long, val rmsMps: Double, val overlapS: Double) : Outcome()
        data class Fail(val reason: String) : Outcome()
    }

    /**
     * 1. Coarse: the GoPro's GPS fixes carry UTC, so video time 0 = median(utc - videoT).
     * 2. Fine: slide the GoPro's speed trace against the RaceBox's (both GPS) by up to ±2 s and keep
     *    the shift with the smallest squared error. This absorbs the camera's timestamp lag.
     */
    fun sync(t: GoProTelemetry, session: List<Sample>): Outcome {
        val fixes = t.fixes.filter { it.utcMs > 0 && it.fix >= 2 }
        if (fixes.size < 20) return Outcome.Fail("The video has no GPS track. Turn on GPS in the GoPro settings.")
        val withUtc = session.filter { it.utc > 0 }
        if (withUtc.size < 50) return Outcome.Fail("This session has no GPS time. Sessions recorded before Nov 11 can't be synced automatically.")
        val coarse = fixes.map { it.utcMs - (it.videoT * 1000).toLong() }.sorted()[fixes.size / 2]

        val su = LongArray(withUtc.size) { withUtc[it].utc }
        val sv = DoubleArray(withUtc.size) { withUtc[it].speed }
        fun speedAt(u: Long): Double? {
            var lo = 0; var hi = su.size - 1
            if (u < su[0] || u > su[hi]) return null
            while (hi - lo > 1) { val m = (lo + hi) / 2; if (su[m] <= u) lo = m else hi = m }
            val span = (su[hi] - su[lo]).toDouble()
            if (span > 500) return null // gap in logging
            val f = if (span <= 0) 0.0 else (u - su[lo]) / span
            return sv[lo] + (sv[hi] - sv[lo]) * f
        }
        val moving = fixes.filter { it.speed > 1.5 }
        if (moving.size < 20) return Outcome.Fail("The video and the session don't overlap while the car is moving.")

        var bestShift = 0L; var bestErr = Double.MAX_VALUE; var bestN = 0
        for (shift in -2000L..2000L step 20) {
            var e = 0.0; var n = 0
            for (f in moving) {
                val u = coarse + (f.videoT * 1000).toLong() + shift
                val v = speedAt(u) ?: continue
                e += (v - f.speed) * (v - f.speed); n++
            }
            if (n >= 20 && e / n < bestErr) { bestErr = e / n; bestShift = shift; bestN = n }
        }
        if (bestN == 0) return Outcome.Fail("The video was recorded outside this session's time.")
        return Outcome.Ok(coarse + bestShift, bestShift, kotlin.math.sqrt(bestErr), bestN / 10.0)
    }
}
