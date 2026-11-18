package com.kimes.daq.run

import com.kimes.daq.data.Gate
import com.kimes.daq.data.Sample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Geometric racing line through an autocross course.
 *
 * Each cone with a pass side is a constraint: the car's center must stay at least [Car.clearance]
 * meters on that side of the cone. The line starts on the start line, ends on the finish line and
 * visits the cones in course order. Among all such paths it finds the one that bends the least
 * (minimum summed squared curvature, with a little weight on length), which is the classic
 * minimum-curvature racing line. A point-mass speed profile with a friction circle then gives an
 * estimated time, so the line can be compared with real runs.
 *
 * Everything is in local meters (x east, y north).
 */
object OptimalLine {
    const val G = 9.81

    /** A cone and the unit direction from it toward the side the car passes. */
    data class ConeSide(val x: Double, val y: Double, val ux: Double, val uy: Double)

    /** Point-mass car. Defaults suit a stock ND Miata on 200-treadwear tires. */
    data class Car(
        val latG: Double = 1.0,
        val accelG: Double = 0.35,
        val brakeG: Double = 1.0,
        val vMax: Double = 35.0,
        val vStart: Double = 4.0,       // rolling across the start line
        val clearance: Double = 1.4,    // half the car's width (0.87 m) plus margin, from cone center
        /** Power at the wheels per kg (W/kg); 0 = constant [accelG] at every speed. */
        val powerPerKg: Double = 0.0,
        /** Air drag and rolling resistance, m/s² per (m/s)². */
        val drag: Double = 0.0004,
        /** Where these numbers came from, for the screen. */
        val source: String = "default",
    ) {
        /** Forward acceleration available at speed [v] in a straight line, m/s². */
        fun drive(v: Double): Double {
            val trac = accelG * G
            val pw = if (powerPerKg > 0) powerPerKg / max(v, 2.0) else trac
            return min(trac, pw) - drag * v * v
        }

        companion object {
            /** Grip and acceleration measured from real driving (robust percentiles), falling back to defaults. */
            fun fromSamples(ss: List<Sample>): Car {
                val moving = ss.filter { it.speed > 6 }
                if (moving.size < 200) return Car()
                fun pct(v: List<Double>, p: Double) = v.sorted().let { it[((it.size - 1) * p).toInt()] }
                val lat = pct(moving.map { abs(it.latG) }, 0.98)
                val acc = pct(moving.map { it.lonG }, 0.98)
                val brk = pct(moving.map { -it.lonG }, 0.98)
                return Car(
                    latG = lat.coerceIn(0.7, 1.4),
                    accelG = acc.coerceIn(0.2, 0.6),
                    brakeG = brk.coerceIn(0.6, 1.4),
                )
            }
        }
    }

    class Result(
        val xs: DoubleArray,
        val ys: DoubleArray,
        val speed: DoubleArray,      // m/s at each point
        val dist: DoubleArray,       // cumulative meters
        val timeAt: DoubleArray,     // cumulative seconds
        val worstClearance: Double,  // smallest margin to any cone's required side (negative = violated)
    ) {
        val time get() = timeAt.last()
        val length get() = dist.last()

        /** Estimated time at which the line crosses each gate, or null if it doesn't. */
        fun crossingTime(g: Gate): Double? {
            for (k in 1 until xs.size) {
                val f = g.crossing(xs[k - 1], ys[k - 1], xs[k], ys[k]) ?: continue
                return timeAt[k - 1] + (timeAt[k] - timeAt[k - 1]) * f
            }
            return null
        }
    }

    private const val STEP = 1.0   // point spacing, m

    fun solve(
        start: Gate,
        finish: Gate,
        cones: List<ConeSide>,
        car: Car = Car(),
        corridor: Double = 12.0,    // how far the line may stray from the cone-to-cone path
        iterations: Int = 4000,
        drivable: ((Double, Double) -> Boolean)? = null, // false off the paved area (from the course map)
    ): Result {
        // Initial path: start, a few meters along the start direction, each cone's pass point, finish
        val wx = ArrayList<Double>(); val wy = ArrayList<Double>()
        wx += start.x; wy += start.y
        wx += start.x + start.hx * 4; wy += start.y + start.hy * 4
        for (c in cones) { wx += c.x + c.ux * (car.clearance + 1.0); wy += c.y + c.uy * (car.clearance + 1.0) }
        wx += finish.x - finish.hx * 4; wy += finish.y - finish.hy * 4
        wx += finish.x; wy += finish.y
        var px = DoubleArray(0); var py = DoubleArray(0)
        resample(wx.toDoubleArray(), wy.toDoubleArray()).let { px = it.first; py = it.second }
        val refX = px.copyOf(); val refY = py.copyOf()

        // Which path point each cone belongs to (kept current through resampling)
        val coneIdx = IntArray(cones.size) { i -> nearestIdx(px, py, cones[i].x + cones[i].ux * 2, cones[i].y + cones[i].uy * 2, 0, px.size - 1) }

        val beta = 0.045   // curvature smoothing (bi-Laplacian; stable below 1/16)
        val lambda = 0.004 // length smoothing
        for (it in 0 until iterations) {
            val n = px.size
            val nx = px.copyOf(); val ny = py.copyOf()
            for (k in 2 until n - 2) {
                val gx = px[k - 2] - 4 * px[k - 1] + 6 * px[k] - 4 * px[k + 1] + px[k + 2]
                val gy = py[k - 2] - 4 * py[k - 1] + 6 * py[k] - 4 * py[k + 1] + py[k + 2]
                nx[k] = px[k] - beta * gx + lambda * (px[k - 1] + px[k + 1] - 2 * px[k])
                ny[k] = py[k] - beta * gy + lambda * (py[k - 1] + py[k + 1] - 2 * py[k])
            }
            px = nx; py = ny
            // Keep the first stretch aligned with the start line's direction of travel
            px[1] = start.x + start.hx * STEP; py[1] = start.y + start.hy * STEP
            px[n - 2] = finish.x - finish.hx * STEP; py[n - 2] = finish.y - finish.hy * STEP
            enforceCones(px, py, cones, coneIdx, car.clearance)
            if (it % 10 == 0) clampCorridor(px, py, refX, refY, corridor)
            if (drivable != null && it % 5 == 4) pullToDrivable(px, py, drivable)
            if (it % 100 == 99) {
                val r = resample(px, py); px = r.first; py = r.second
                for (i in cones.indices) {
                    val guess = (coneIdx[i].toDouble() / n * px.size).toInt()
                    coneIdx[i] = nearestIdx(px, py, cones[i].x, cones[i].y, guess - 25, guess + 25)
                }
            }
        }
        val r = resample(px, py); px = r.first; py = r.second
        if (drivable != null) pullToDrivable(px, py, drivable)
        enforceCones(px, py, cones, IntArray(cones.size) { nearestIdx(px, py, cones[it].x, cones[it].y, 0, px.size - 1) }, car.clearance)

        return evaluate(px, py, car, cones)
    }

    /** Speed profile and time for any path (also used to score a driven run's line). */
    fun evaluate(px: DoubleArray, py: DoubleArray, car: Car = Car(), cones: List<ConeSide> = emptyList()): Result {
        val n = px.size
        val dist = DoubleArray(n)
        for (k in 1 until n) dist[k] = dist[k - 1] + hypot(px[k] - px[k - 1], py[k] - py[k - 1])
        val kappa = DoubleArray(n)
        for (k in 1 until n - 1) kappa[k] = curvature(px[k - 1], py[k - 1], px[k], py[k], px[k + 1], py[k + 1])
        val ks = DoubleArray(n) { k -> (max(0, k - 2)..min(n - 1, k + 2)).map { kappa[it] }.average() }
        val gLat = car.latG * G
        val v = DoubleArray(n) { k -> min(car.vMax, if (ks[k] < 1e-6) car.vMax else sqrt(gLat / ks[k])) }
        v[0] = min(v[0], car.vStart)
        for (k in 1 until n) {
            val ds = dist[k] - dist[k - 1]
            val ay = v[k - 1] * v[k - 1] * ks[k - 1]
            val ax = max(0.0, car.drive(v[k - 1])) * sqrt(max(0.0, 1 - (ay / gLat) * (ay / gLat)))
            v[k] = min(v[k], sqrt(v[k - 1] * v[k - 1] + 2 * ax * ds))
        }
        for (k in n - 2 downTo 0) {
            val ds = dist[k + 1] - dist[k]
            val ay = v[k + 1] * v[k + 1] * ks[k + 1]
            val ax = (car.brakeG * G + car.drag * v[k + 1] * v[k + 1]) * sqrt(max(0.0, 1 - (ay / gLat) * (ay / gLat)))
            v[k] = min(v[k], sqrt(v[k + 1] * v[k + 1] + 2 * ax * ds))
        }
        val t = DoubleArray(n)
        for (k in 1 until n) t[k] = t[k - 1] + (dist[k] - dist[k - 1]) / max(0.5, (v[k] + v[k - 1]) / 2)

        // How well the cones are respected
        var worst = Double.MAX_VALUE
        for (c in cones) {
            var best = Double.MAX_VALUE; var side = 0.0
            for (k in 0 until n) { val d = hypot(px[k] - c.x, py[k] - c.y); if (d < best) { best = d; side = (px[k] - c.x) * c.ux + (py[k] - c.y) * c.uy } }
            worst = min(worst, if (side > 0) best - car.clearance else -best)
        }
        return Result(px, py, v, dist, t, if (cones.isEmpty()) 0.0 else worst)
    }

    /**
     * Fastest line near [px]/[py], by trying small smooth sideways moves of short stretches and keeping
     * each one that lowers the estimated time while every cone stays passed on its side (with
     * [Car.clearance]) and, with [drivable], the line stays on the paved area. Started from a line
     * someone actually drove, it finds where that line can be quicker; it doesn't assume shortest is
     * fastest.
     */
    fun refine(
        x0: DoubleArray, y0: DoubleArray, cones: List<ConeSide>, car: Car,
        iterations: Int = 12000, drivable: ((Double, Double) -> Boolean)? = null, seed: Int = 1,
        /** How far the line may move from where it started, m: a driver adjusts a line by about a car width, not more. */
        maxMove: Double = Double.MAX_VALUE,
    ): Result {
        val (rx, ry) = resample(x0, y0)
        val px = rx.copyOf(); val py = ry.copyOf()
        val n = px.size
        val rnd = kotlin.random.Random(seed)
        fun coneScore(c: ConeSide, xs: DoubleArray, ys: DoubleArray): Double {
            var best = Double.MAX_VALUE; var side = 0.0
            for (k in xs.indices) { val d = hypot(xs[k] - c.x, ys[k] - c.y); if (d < best) { best = d; side = (xs[k] - c.x) * c.ux + (ys[k] - c.y) * c.uy } }
            return if (side > 0) best - car.clearance else -best
        }
        val score = DoubleArray(cones.size) { coneScore(cones[it], px, py) }
        var cur = evaluate(px, py, car).time
        val cx = px.copyOf(); val cy = py.copyOf()
        for (it in 0 until iterations) {
            val c = rnd.nextInt(4, n - 4)
            val w = rnd.nextInt(4, 36)
            val amp = (rnd.nextDouble() * 2 - 1) * 0.8
            val lo = max(3, c - w); val hi = min(n - 4, c + w)
            System.arraycopy(px, 0, cx, 0, n); System.arraycopy(py, 0, cy, 0, n)
            var ok = true
            for (k in lo..hi) {
                var tx = px[k + 1] - px[k - 1]; var ty = py[k + 1] - py[k - 1]
                val tl = hypot(tx, ty); if (tl < 1e-9) continue
                tx /= tl; ty /= tl
                val bump = amp * 0.5 * (1 + kotlin.math.cos(PI * (k - c) / (w + 1)))
                cx[k] = px[k] - ty * bump; cy[k] = py[k] + tx * bump
                if (drivable != null && !drivable(cx[k], cy[k])) { ok = false; break }
                if (maxMove < 1e6 && hypot(cx[k] - rx[k], cy[k] - ry[k]) > maxMove) { ok = false; break }
            }
            if (!ok) continue
            // Cones near the moved stretch must stay on their side (no worse than before when the start line already broke it)
            val mx = cx[c]; val my = cy[c]
            val newScore = DoubleArray(cones.size)
            for (i in cones.indices) {
                if (hypot(cones[i].x - mx, cones[i].y - my) > w + 12) { newScore[i] = score[i]; continue }
                newScore[i] = coneScore(cones[i], cx, cy)
                if (newScore[i] < min(score[i], 0.0) - 1e-9) { ok = false; break }
            }
            if (!ok) continue
            val t = evaluate(cx, cy, car).time
            if (t < cur - 1e-5) {
                cur = t
                System.arraycopy(cx, 0, px, 0, n); System.arraycopy(cy, 0, py, 0, n)
                for (i in cones.indices) score[i] = newScore[i]
            }
        }
        return evaluate(px, py, car, cones)
    }

    /** A driven path (GPS points, meters) made smooth enough for the speed model: 1 m spacing, noise averaged out. */
    fun smoothPath(x: DoubleArray, y: DoubleArray, passes: Int = 6): Pair<DoubleArray, DoubleArray> {
        val (px, py) = resample(x, y)
        repeat(passes) {
            val nx = px.copyOf(); val ny = py.copyOf()
            for (k in 1 until px.size - 1) { nx[k] = (px[k - 1] + 2 * px[k] + px[k + 1]) / 4; ny[k] = (py[k - 1] + 2 * py[k] + py[k + 1]) / 4 }
            System.arraycopy(nx, 0, px, 0, px.size); System.arraycopy(ny, 0, py, 0, py.size)
        }
        return px to py
    }

    /** Push path points near each cone out to the pass side, sideways relative to the local direction of travel. */
    private fun enforceCones(px: DoubleArray, py: DoubleArray, cones: List<ConeSide>, idx: IntArray, clear: Double) {
        val n = px.size
        for ((i, c) in cones.withIndex()) {
            val k0 = idx[i].coerceIn(1, n - 2)
            for (k in max(1, k0 - 12)..min(n - 2, k0 + 12)) {
                var tx = px[k + 1] - px[k - 1]; var ty = py[k + 1] - py[k - 1]
                val tl = hypot(tx, ty); if (tl < 1e-9) continue
                tx /= tl; ty /= tl
                // Pass direction made perpendicular to travel, so the push is purely sideways
                var sx = c.ux - (c.ux * tx + c.uy * ty) * tx; var sy = c.uy - (c.ux * tx + c.uy * ty) * ty
                val sl = hypot(sx, sy); if (sl < 1e-6) continue
                sx /= sl; sy /= sl
                val along = (px[k] - c.x) * tx + (py[k] - c.y) * ty
                if (abs(along) > 2.5) continue
                val side = (px[k] - c.x) * sx + (py[k] - c.y) * sy
                if (side < clear) { px[k] += sx * (clear - side); py[k] += sy * (clear - side) }
            }
        }
    }

    /** Points off the paved area move to the nearest paved spot (searched in growing rings up to 8 m). */
    private fun pullToDrivable(px: DoubleArray, py: DoubleArray, ok: (Double, Double) -> Boolean) {
        for (k in 2 until px.size - 2) {
            if (ok(px[k], py[k])) continue
            var r = 0.2
            search@ while (r <= 8.0) {
                for (a in 0 until 24) {
                    val ang = a * PI / 12
                    val qx = px[k] + r * kotlin.math.cos(ang); val qy = py[k] + r * kotlin.math.sin(ang)
                    if (ok(qx, qy)) { px[k] = qx; py[k] = qy; break@search }
                }
                r += 0.2
            }
        }
    }

    private fun clampCorridor(px: DoubleArray, py: DoubleArray, rx: DoubleArray, ry: DoubleArray, w: Double) {
        val n = px.size; val m = rx.size
        for (k in 1 until n - 1) {
            val guess = (k.toDouble() / n * m).toInt()
            var best = Double.MAX_VALUE; var bx = 0.0; var by = 0.0
            for (j in max(0, guess - 60)..min(m - 2, guess + 60)) {
                val dx = rx[j + 1] - rx[j]; val dy = ry[j + 1] - ry[j]
                val l2 = dx * dx + dy * dy
                val f = if (l2 < 1e-12) 0.0 else (((px[k] - rx[j]) * dx + (py[k] - ry[j]) * dy) / l2).coerceIn(0.0, 1.0)
                val qx = rx[j] + dx * f; val qy = ry[j] + dy * f
                val d = hypot(px[k] - qx, py[k] - qy)
                if (d < best) { best = d; bx = qx; by = qy }
            }
            if (best > w) { px[k] = bx + (px[k] - bx) * w / best; py[k] = by + (py[k] - by) * w / best }
        }
    }

    private fun resample(x: DoubleArray, y: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val d = DoubleArray(x.size)
        for (k in 1 until x.size) d[k] = d[k - 1] + hypot(x[k] - x[k - 1], y[k] - y[k - 1])
        val n = max(5, (d.last() / STEP).toInt() + 1)
        val ox = DoubleArray(n); val oy = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val s = d.last() * k / (n - 1)
            while (j < x.size - 2 && d[j + 1] < s) j++
            val seg = d[j + 1] - d[j]
            val f = if (seg < 1e-12) 0.0 else ((s - d[j]) / seg).coerceIn(0.0, 1.0)
            ox[k] = x[j] + (x[j + 1] - x[j]) * f; oy[k] = y[j] + (y[j + 1] - y[j]) * f
        }
        return ox to oy
    }

    private fun nearestIdx(x: DoubleArray, y: DoubleArray, qx: Double, qy: Double, lo: Int, hi: Int): Int {
        var best = -1; var bd = Double.MAX_VALUE
        for (k in max(0, lo)..min(x.size - 1, hi)) { val d = hypot(x[k] - qx, y[k] - qy); if (d < bd) { bd = d; best = k } }
        return max(best, 0)
    }

    /** Menger curvature of three points, 1/m. */
    private fun curvature(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
        val area2 = abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax))
        val a = hypot(bx - ax, by - ay); val b = hypot(cx - bx, cy - by); val c = hypot(cx - ax, cy - ay)
        val den = a * b * c
        return if (den < 1e-12) 0.0 else 2 * area2 / den
    }
}
