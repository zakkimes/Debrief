package com.kimes.daq.run

import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample

/**
 * Wrong side of a pointer cone: a DNF in autocross.
 *
 * Each cone with a pass side gets a short "wrong side" gate: a segment starting [margin] meters
 * from the cone and reaching [reach] meters out on the side the car must NOT pass. Driving across
 * that segment means the car went around the cone the wrong way. The [margin] keeps GPS error and
 * small errors in where the cone was placed from calling a close-but-correct pass a DNF; the short
 * [reach] keeps other parts of the course that pass nearby from counting.
 */
object ConeCheck {
    /**
     * Meters past the cone (car center) before it counts. Clean runs on 2026-11-14/15 came within
     * 1.6 m of the wrong side where a cone was placed about 2 m off on the course walk, so only a
     * clear miss is called; a closer one is left to the driver.
     */
    const val MARGIN = 2.5

    /** [cone] is the index in the course's cone list; [byM] how far from the cone the car crossed. */
    data class Miss(val cone: Int, val t: Double, val idx: Int, val byM: Double)

    /** A cone in the session's x/y meters: position and the unit direction toward its pass side. */
    data class Cone(val index: Int, val x: Double, val y: Double, val ux: Double, val uy: Double)

    fun wrongSide(samples: List<Sample>, run: Run, cones: List<Cone>, margin: Double = MARGIN, reach: Double = 7.0): Miss? {
        var first: Miss? = null
        for (c in cones) {
            // Passed on the right side at some point: then a crossing on the other side is another
            // part of the course going by, not this cone
            if (crosses(samples, run, c.x, c.y, c.x + c.ux * RIGHT_REACH, c.y + c.uy * RIGHT_REACH) != null) continue
            // The wrong-side segment: from cone - u*margin to cone - u*reach
            val ax = c.x - c.ux * margin; val ay = c.y - c.uy * margin
            val bx = c.x - c.ux * reach; val by = c.y - c.uy * reach
            for (k in run.startIdx + 1..run.endIdx) {
                val p = samples[k - 1]; val q = samples[k]
                if (q.t - p.t > 1.0) continue
                val f = intersect(p.x, p.y, q.x, q.y, ax, ay, bx, by) ?: continue
                val hx = p.x + (q.x - p.x) * f.first; val hy = p.y + (q.y - p.y) * f.first
                val m = Miss(c.index, p.t + (q.t - p.t) * f.first, k, Math.hypot(hx - c.x, hy - c.y))
                if (first == null || m.t < first.t) first = m
                break
            }
        }
        return first
    }

    /** How far out on the correct side a pass still counts as passing this cone. */
    private const val RIGHT_REACH = 8.0

    /** First sample index where the run's path crosses segment a->b, or null. */
    private fun crosses(samples: List<Sample>, run: Run, ax: Double, ay: Double, bx: Double, by: Double): Int? {
        for (k in run.startIdx + 1..run.endIdx) {
            val p = samples[k - 1]; val q = samples[k]
            if (q.t - p.t > 1.0) continue
            if (intersect(p.x, p.y, q.x, q.y, ax, ay, bx, by) != null) return k
        }
        return null
    }

    /** Where segments p->q and a->b cross: (fraction along p->q, fraction along a->b), or null. */
    private fun intersect(px: Double, py: Double, qx: Double, qy: Double, ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double>? {
        val rx = qx - px; val ry = qy - py; val sx = bx - ax; val sy = by - ay
        val den = rx * sy - ry * sx
        if (kotlin.math.abs(den) < 1e-12) return null
        val t = ((ax - px) * sy - (ay - py) * sx) / den
        val u = ((ax - px) * ry - (ay - py) * rx) / den
        return if (t in 0.0..1.0 && u in 0.0..1.0) t to u else null
    }
}
