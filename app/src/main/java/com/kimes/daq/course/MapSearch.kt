package com.kimes.daq.course

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pavement likeness (0..1) on a square grid of ground cells around a center, built once from the
 * satellite imagery so the search can test millions of placements quickly. NaN = no imagery.
 */
class PavementGrid(val lat0: Double, val lon0: Double, val cell: Double, val n: Int, val v: FloatArray) {
    val half get() = n * cell / 2
    private val mLat = 111_320.0
    private val mLon = 111_320.0 * cos(lat0 * PI / 180)
    val mean: Float = v.filter { !it.isNaN() }.average().toFloat()

    /** Value at local meters (x east, y north), NaN outside. */
    fun at(x: Double, y: Double): Float {
        val i = ((x + half) / cell).toInt(); val j = ((half - y) / cell).toInt()
        if (i < 0 || j < 0 || i >= n || j >= n) return Float.NaN
        return v[j * n + i]
    }

    fun toLatLon(x: Double, y: Double) = (lat0 + y / mLat) to (lon0 + x / mLon)

    companion object {
        fun build(lat0: Double, lon0: Double, radiusM: Double, cell: Double, sat: (Double, Double) -> Double?): PavementGrid {
            val n = (2 * radiusM / cell).toInt()
            val mLat = 111_320.0; val mLon = 111_320.0 * cos(lat0 * PI / 180)
            val v = FloatArray(n * n)
            for (j in 0 until n) for (i in 0 until n) {
                val x = -radiusM + (i + 0.5) * cell; val y = radiusM - (j + 0.5) * cell
                v[j * n + i] = sat(lat0 + y / mLat, lon0 + x / mLon)?.toFloat() ?: Float.NaN
            }
            return PavementGrid(lat0, lon0, cell, n, v)
        }
    }
}

/**
 * Places a course map by its shapes alone (no timing lines needed): the paved lot, the grass
 * islands inside it and the lot's outer edge are matched against satellite pavement, searching
 * every rotation, a range of scales and positions around a seed, then refining the best few.
 */
object MapSearch {
    /** A placement in local meters: picture pixel (u, v) -> ground around the picture's lot center. */
    data class Pose(val rotDeg: Double, val mpp: Double, val tx: Double, val ty: Double, val score: Double = 0.0)

    class Point(val u: Double, val v: Double, val sign: Float, val w: Float)

    class Result(val overlay: MapOverlay, val score: Double, val runnerUpGapM: Double)

    /** Sample points: lot (+1), islands (-1), and a band just outside the lot (-0.5, usually grass or trees). */
    fun points(pic: MapPicture, max: Int = 420): Pair<List<Point>, DoubleArray> {
        val lotPx = ArrayList<Pair<Int, Int>>(); val islandPx = ArrayList<Pair<Int, Int>>()
        var minU = pic.w; var maxU = 0; var minV = pic.h; var maxV = 0
        val step = max(2, min(pic.w, pic.h) / 120)
        for (v in 0 until pic.h step step) for (u in 0 until pic.w step step) {
            if (pic.isLot(u, v)) {
                lotPx += u to v; minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
            }
        }
        if (lotPx.size < 20) return emptyList<Point>() to doubleArrayOf(0.0, 0.0, 1.0)
        // Islands: non-lot gray pixels surrounded by lot (inside the lot's box, not page white)
        val outside = ArrayList<Pair<Int, Int>>()
        val band = max(6, (maxU - minU) / 25)
        for (v in max(0, minV - band) until min(pic.h, maxV + band) step step) for (u in max(0, minU - band) until min(pic.w, maxU + band) step step) {
            if (pic.isLot(u, v)) continue
            val c = pic.at(u, v); val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
            val white = r > 245 && g > 245 && b > 245
            val inBox = u in minU..maxU && v in minV..maxV
            val nearLot = pic.isLot(u + band / 2, v) || pic.isLot(u - band / 2, v) || pic.isLot(u, v + band / 2) || pic.isLot(u, v - band / 2)
            val gray = (r + g + b) / 3
            // Islands are mid grays; skip black/dark ink (cone dots, lines, text), which sits on the lot
            if (!white && inBox && abs(r - g) < 12 && abs(g - b) < 12 && gray in 150..222) islandPx += u to v
            else if (white && nearLot) outside += u to v
        }
        fun pick(src: List<Pair<Int, Int>>, k: Int): List<Pair<Int, Int>> = if (src.size <= k) src else List(k) { src[(it.toLong() * src.size / k).toInt()] }
        val pts = pick(lotPx, max / 2).map { Point(it.first.toDouble(), it.second.toDouble(), 1f, 1f) } +
            pick(islandPx, max / 4).map { Point(it.first.toDouble(), it.second.toDouble(), -1f, 1f) } +
            pick(outside, max / 4).map { Point(it.first.toDouble(), it.second.toDouble(), -1f, 0.5f) }
        return pts to doubleArrayOf((minU + maxU) / 2.0, (minV + maxV) / 2.0, (maxU - minU).toDouble().coerceAtLeast(1.0))
    }

    /** Weighted correlation between the map's labels (lot +1, not lot -1) and satellite pavement. */
    private fun score(pts: List<Point>, cu: Double, cv: Double, g: PavementGrid, p: Pose): Double {
        val r = p.rotDeg * PI / 180; val c = cos(r) * p.mpp; val s = sin(r) * p.mpp
        var w = 0.0; var ss = 0.0; var sp = 0.0; var sss = 0.0; var spp = 0.0; var ssp = 0.0; var miss = 0
        for (q in pts) {
            val du = q.u - cu; val dv = -(q.v - cv)
            val x = du * c - dv * s + p.tx; val y = du * s + dv * c + p.ty
            val pv = g.at(x, y)
            if (pv.isNaN()) { miss++; continue }
            val sg = q.sign.toDouble(); val wt = q.w.toDouble()
            w += wt; ss += wt * sg; sp += wt * pv; sss += wt * sg * sg; spp += wt * pv * pv; ssp += wt * sg * pv
        }
        if (w == 0.0 || miss > pts.size / 3) return -1.0
        val ms = ss / w; val mp = sp / w
        val cov = ssp / w - ms * mp
        val vs = sss / w - ms * ms; val vp = spp / w - mp * mp
        // Floor the photo's variance so a uniform patch (all pavement or all grass) can't score by noise
        return cov / sqrt(max(vs, 1e-9) * max(vp, 0.01))
    }

    /**
     * Search placements around the grid's center. lotSizeM: plausible real width range of the
     * map's lot. Returns the best placement as an overlay, with how distinct it is from the runner-up.
     */
    fun search(pic: MapPicture, g: PavementGrid, file: String, lotSizeM: ClosedFloatingPointRange<Double> = 120.0..900.0, searchRadiusM: Double = 150.0): Result? {
        val (pts, box) = points(pic)
        if (pts.isEmpty()) return null
        val cu = box[0]; val cv = box[1]; val wPx = box[2]
        val mppLo = lotSizeM.start / wPx; val mppHi = lotSizeM.endInclusive / wPx
        val scales = 14
        val coarse = ArrayList<Pose>()
        val few = pts.filterIndexed { i, _ -> i % 2 == 0 } // first pass on half the points; refining uses all
        for (si in 0 until scales) {
            val mpp = exp(ln(mppLo) + (ln(mppHi) - ln(mppLo)) * si / (scales - 1))
            for (rot in 0 until 360 step 6) {
                val step = max(3.0, mpp * wPx / 45)
                var tx = -searchRadiusM
                while (tx <= searchRadiusM) {
                    var ty = -searchRadiusM
                    while (ty <= searchRadiusM) {
                        val p = Pose(rot.toDouble(), mpp, tx, ty)
                        val sc = score(few, cu, cv, g, p)
                        if (sc > 0) coarse += p.copy(score = sc)
                        ty += step
                    }
                    tx += step
                }
            }
        }
        if (coarse.isEmpty()) return null
        // Refine the best few distinct candidates
        val seeds = ArrayList<Pose>()
        for (p in coarse.sortedByDescending { it.score }.take(1500)) {
            if (seeds.none { abs(it.tx - p.tx) < 20 && abs(it.ty - p.ty) < 20 && angDiff(it.rotDeg, p.rotDeg) < 15 }) seeds += p
            if (seeds.size >= 12) break
        }
        val refined = seeds.map { refine(pts, cu, cv, g, it) }.sortedByDescending { it.score }
        val best = refined.first()
        val gap = refined.getOrNull(1)?.let { sqrt((it.tx - best.tx) * (it.tx - best.tx) + (it.ty - best.ty) * (it.ty - best.ty)) } ?: 0.0
        val o = overlayFor(pic, g, best, file)
        return Result(o, best.score, if (refined.size > 1 && refined[1].score > best.score * 0.92) gap else 0.0)
    }

    /** For checking: score a placement given as rotation, scale and the ground position of the lot center. */
    fun evaluate(pic: MapPicture, g: PavementGrid, rotDeg: Double, mpp: Double, tx: Double, ty: Double, refineIt: Boolean = false): Pose {
        val (pts, box) = points(pic)
        val p = Pose(rotDeg, mpp, tx, ty).let { it.copy(score = score(pts, box[0], box[1], g, it)) }
        return if (refineIt) refine(pts, box[0], box[1], g, p) else p
    }

    fun overlayFor(pic: MapPicture, g: PavementGrid, best: Pose, file: String): MapOverlay {
        val (cu, cv) = lotCenter(pic)
        fun ground(u: Double, v: Double): Pair<Double, Double> {
            val r = best.rotDeg * PI / 180; val du = u - cu; val dv = -(v - cv)
            val x = (du * cos(r) - dv * sin(r)) * best.mpp + best.tx; val y = (du * sin(r) + dv * cos(r)) * best.mpp + best.ty
            return g.toLatLon(x, y)
        }
        val a = ground(cu, cv); val b = ground(cu + 100, cv)
        return MapOverlay(file, cu, cv, a.first, a.second, cu + 100, cv, b.first, b.second,
            how = "Placed by matching the lot and islands to the satellite photo (match %.0f%%)".format(quality(best.score, g) * 100))
    }

    fun lotCenter(pic: MapPicture): Pair<Double, Double> = points(pic).second.let { it[0] to it[1] }

    private fun angDiff(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    private fun refine(pts: List<Point>, cu: Double, cv: Double, g: PavementGrid, start: Pose): Pose {
        var best = start
        var step = doubleArrayOf(4.0, 3.0, 0.04) // meters, degrees, scale fraction
        repeat(6) {
            var improved = true
            while (improved) {
                improved = false
                val p = best
                for (t in listOf(
                    p.copy(tx = p.tx + step[0]), p.copy(tx = p.tx - step[0]), p.copy(ty = p.ty + step[0]), p.copy(ty = p.ty - step[0]),
                    p.copy(rotDeg = p.rotDeg + step[1]), p.copy(rotDeg = p.rotDeg - step[1]),
                    p.copy(mpp = p.mpp * (1 + step[2])), p.copy(mpp = p.mpp * (1 - step[2])),
                )) {
                    val sc = score(pts, cu, cv, g, t)
                    if (sc > best.score + 1e-5) { best = t.copy(score = sc); improved = true }
                }
            }
            step = doubleArrayOf(step[0] / 2, step[1] / 2, step[2] / 2)
        }
        return best
    }

    /** Rough 0..1 quality: the score relative to the most it could be for this imagery. */
    @Suppress("UNUSED_PARAMETER")
    private fun quality(score: Double, g: PavementGrid) = score.coerceIn(0.0, 1.0)
}
