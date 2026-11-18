package com.kimes.daq.course

import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The organizer's course map laid over the satellite view. The picture is placed by a similarity
 * transform (scale, rotation, shift) defined by two anchors: picture pixel (u, v) -> ground (lat, lon).
 */
data class MapOverlay(
    val file: String,          // PNG saved in the app's files, next to the course
    val u1: Double, val v1: Double, val lat1: Double, val lon1: Double,
    val u2: Double, val v2: Double, val lat2: Double, val lon2: Double,
    val alpha: Double = 0.6,
    val how: String = "",      // how it was placed, shown to the user
) {
    private val lat0 get() = (lat1 + lat2) / 2
    private val mLon get() = 111_320.0 * cos(lat0 * PI / 180)
    private val mLat = 111_320.0

    /** Picture pixel -> local meters (east, north) around anchor 1. */
    private fun params(): DoubleArray {
        // Pixel axes: u right, v down. Ground: x east, y north. Flip v so both are right-handed.
        val px = u2 - u1; val py = -(v2 - v1)
        val gx = (lon2 - lon1) * mLon; val gy = (lat2 - lat1) * mLat
        val s = hypot(gx, gy) / max(hypot(px, py), 1e-9)
        val rot = atan2(gy, gx) - atan2(py, px)
        return doubleArrayOf(s, rot)
    }

    fun toGround(u: Double, v: Double): Pair<Double, Double> {
        val (s, rot) = params().let { it[0] to it[1] }
        val px = u - u1; val py = -(v - v1)
        val x = s * (px * cos(rot) - py * sin(rot)); val y = s * (px * sin(rot) + py * cos(rot))
        return (lat1 + y / mLat) to (lon1 + x / mLon)
    }

    fun toPixel(lat: Double, lon: Double): Pair<Double, Double> {
        val (s, rot) = params().let { it[0] to it[1] }
        val x = (lon - lon1) * mLon / s; val y = (lat - lat1) * mLat / s
        val px = x * cos(-rot) - y * sin(-rot); val py = x * sin(-rot) + y * cos(-rot)
        return (u1 + px) to (v1 - py)
    }

    /** Meters per picture pixel. */
    val metersPerPixel get() = params()[0]
    val rotationDeg get() = params()[1] * 180 / PI

    /** Same placement, nudged: shift in meters, rotate in degrees, scale factor, all about the picture's center. */
    fun nudged(w: Int, h: Int, dEast: Double = 0.0, dNorth: Double = 0.0, dRotDeg: Double = 0.0, scale: Double = 1.0): MapOverlay {
        val (cLat, cLon) = toGround(w / 2.0, h / 2.0)
        fun move(lat: Double, lon: Double): Pair<Double, Double> {
            var x = (lon - cLon) * mLon * scale; var y = (lat - cLat) * mLat * scale
            val r = dRotDeg * PI / 180
            val rx = x * cos(r) - y * sin(r); val ry = x * sin(r) + y * cos(r)
            x = rx + dEast; y = ry + dNorth
            return (cLat + y / mLat) to (cLon + x / mLon)
        }
        val a = move(lat1, lon1); val b = move(lat2, lon2)
        return copy(lat1 = a.first, lon1 = a.second, lat2 = b.first, lon2 = b.second)
    }

    fun json(): JSONObject = JSONObject().put("file", file)
        .put("u1", u1).put("v1", v1).put("lat1", lat1).put("lon1", lon1)
        .put("u2", u2).put("v2", v2).put("lat2", lat2).put("lon2", lon2)
        .put("alpha", alpha).put("how", how)

    companion object {
        fun from(o: JSONObject) = MapOverlay(
            o.getString("file"), o.getDouble("u1"), o.getDouble("v1"), o.getDouble("lat1"), o.getDouble("lon1"),
            o.getDouble("u2"), o.getDouble("v2"), o.getDouble("lat2"), o.getDouble("lon2"),
            o.optDouble("alpha", 0.6), o.optString("how"),
        )
    }
}

/** A course-map picture as plain ARGB pixels, so the analysis runs anywhere (and in tests). */
class MapPicture(val w: Int, val h: Int, val argb: IntArray) {
    fun at(u: Int, v: Int) = argb[v * w + u]

    // ---- color helpers ---------------------------------------------------------------------
    private fun hsv(c: Int): FloatArray {
        val r = (c shr 16 and 0xFF) / 255f; val g = (c shr 8 and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val hue = when {
            d < 1e-6f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(hue, if (mx <= 0) 0f else d / mx, mx)
    }

    /** Center of the largest blob of pixels matching a color test, or null. */
    private fun blob(test: (FloatArray) -> Boolean, minPx: Int = 6): Pair<Double, Double>? {
        val hit = BooleanArray(w * h) { test(hsv(argb[it])) }
        val seen = BooleanArray(w * h)
        var best: Triple<Double, Double, Int>? = null
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (!hit[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var n = 0; var su = 0.0; var sv = 0.0
            while (sp > 0) {
                val p = stack[--sp]; val u = p % w; val v = p / w
                n++; su += u; sv += v
                for ((du, dv) in NB) {
                    val nu = u + du; val nv = v + dv
                    if (nu < 0 || nv < 0 || nu >= w || nv >= h) continue
                    val q = nv * w + nu
                    if (hit[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            if (n >= minPx && (best == null || n > best.third)) best = Triple(su / n, sv / n, n)
        }
        return best?.let { it.first to it.second }
    }

    /** Where the map draws its timing lines, by their usual colors. */
    class Marks(val green: Pair<Double, Double>?, val orange: Pair<Double, Double>?, val red: Pair<Double, Double>?)

    fun marks() = Marks(
        green = blob({ it[0] in 85f..165f && it[1] > 0.4f && it[2] > 0.3f }),
        orange = blob({ it[0] in 22f..48f && it[1] > 0.5f && it[2] > 0.5f }),
        red = blob({ (it[0] < 15f || it[0] > 340f) && it[1] > 0.3f && it[2] > 0.4f }),
    )

    /** The paved-lot gray: the most common light gray that isn't the white page. */
    val lotGray: Float by lazy {
        val hist = IntArray(101)
        for (c in argb) { val q = hsv(c); if (q[1] < 0.08f && q[2] in 0.55f..0.965f) hist[(q[2] * 100).toInt()]++ }
        (hist.indices.maxByOrNull { hist[it] } ?: 88) / 100f
    }

    /** True where the picture shows drivable lot (not grass islands, page background, or cone lines). */
    fun isLot(u: Int, v: Int): Boolean {
        if (u < 0 || v < 0 || u >= w || v >= h) return false
        val q = hsv(argb[v * w + u])
        return q[1] < 0.12f && abs(q[2] - lotGray) <= 0.03f
    }

    companion object {
        private val NB = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    }
}

/**
 * Lays a course map over the satellite view automatically:
 * 1. Anchor on the timing lines: the map's colored start/finish marks match the course's start/finish
 *    lines (from RaceChrono or the walk), which fixes scale, rotation and position.
 * 2. Refine by shape: nudge until the map's lot gray lines up best with pavement in the satellite photo.
 */
object MapAlign {
    /** satGray(lat, lon) returns how pavement-like the satellite pixel there is (0..1), or null if no imagery. */
    fun auto(pic: MapPicture, course: CourseDef, file: String, satPavement: ((Double, Double) -> Double?)?): MapOverlay? {
        val start = course.start ?: return null
        val finish = course.finish ?: return null
        if (start === finish) return null
        val m = pic.marks()
        val red = m.red ?: return null
        val candidates = listOfNotNull(m.green, m.orange).map { s ->
            MapOverlay(file, s.first, s.second, start.lat, start.lon, red.first, red.second, finish.lat, finish.lon, how = "Placed on the start and finish marks")
        }
        if (candidates.isEmpty()) return null
        val scored = candidates.map { it to (satPavement?.let { sp -> score(pic, it, sp) } ?: 0.0) }
        var best = scored.maxByOrNull { it.second }!!
        if (satPavement == null) return best.first
        // Hill-climb on shift, rotation and scale
        var step = doubleArrayOf(4.0, 2.0, 0.03)
        repeat(4) {
            var improved = true
            while (improved) {
                improved = false
                val o = best.first
                val tries = listOf(
                    o.nudged(pic.w, pic.h, dEast = step[0]), o.nudged(pic.w, pic.h, dEast = -step[0]),
                    o.nudged(pic.w, pic.h, dNorth = step[0]), o.nudged(pic.w, pic.h, dNorth = -step[0]),
                    o.nudged(pic.w, pic.h, dRotDeg = step[1]), o.nudged(pic.w, pic.h, dRotDeg = -step[1]),
                    o.nudged(pic.w, pic.h, scale = 1 + step[2]), o.nudged(pic.w, pic.h, scale = 1 - step[2]),
                )
                for (t in tries) {
                    val sc = score(pic, t, satPavement)
                    if (sc > best.second + 1e-4) { best = t to sc; improved = true }
                }
            }
            step = doubleArrayOf(step[0] / 2, step[1] / 2, step[2] / 2)
        }
        return best.first.copy(how = "Placed on the start and finish marks, fine-tuned to the satellite pavement (match %.0f%%)".format(best.second * 100))
    }

    /** Agreement between the picture's lot area and satellite pavement, sampled on a grid. */
    fun score(pic: MapPicture, o: MapOverlay, satPavement: (Double, Double) -> Double?): Double {
        val step = max(2, min(pic.w, pic.h) / 60)
        var agree = 0.0; var n = 0
        var v = 0
        while (v < pic.h) {
            var u = 0
            while (u < pic.w) {
                val lot = pic.isLot(u, v)
                val (lat, lon) = o.toGround(u.toDouble(), v.toDouble())
                val p = satPavement(lat, lon)
                if (p != null) { agree += if (lot) p else 1 - p; n++ }
                u += step
            }
            v += step
        }
        return if (n == 0) 0.0 else agree / n
    }

    /**
     * How pavement-like a satellite color is. Imagery often has a color cast (Barber's is green), so
     * hue can't be trusted; what holds is that asphalt and concrete are the least colorful things on
     * the ground. Lower saturation -> higher score. Deep shadow counts as unknown-ish.
     */
    fun pavementLikeness(argb: Int): Double {
        val r = (argb shr 16 and 0xFF) / 255.0; val g = (argb shr 8 and 0xFF) / 255.0; val b = (argb and 0xFF) / 255.0
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val sat = if (mx <= 0) 0.0 else (mx - mn) / mx
        val v = (1 - sat * 2.2).coerceIn(0.0, 1.0)
        return if (mx < 0.2) v * 0.5 else v
    }

    @Suppress("unused")
    private fun rms(a: DoubleArray) = sqrt(a.sumOf { it * it } / max(1, a.size))
}
