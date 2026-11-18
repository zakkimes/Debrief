package com.kimes.daq

import com.kimes.daq.data.Gate
import com.kimes.daq.data.Sample
import com.kimes.daq.run.OptimalLine
import com.kimes.daq.run.OptimalLine.ConeSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OptimalLineTest {
    private val start = Gate(0.0, 0.0, 0.0, 1.0, 10.0)      // heading north
    private val finish = Gate(0.0, 160.0, 0.0, 1.0, 10.0)

    /** x of the line where it passes a given y. */
    private fun xAt(r: OptimalLine.Result, y: Double): Double {
        for (k in 1 until r.ys.size) if ((r.ys[k - 1] - y) * (r.ys[k] - y) <= 0) {
            val f = (y - r.ys[k - 1]) / (r.ys[k] - r.ys[k - 1]).let { if (abs(it) < 1e-9) 1.0 else it }
            return r.xs[k - 1] + (r.xs[k] - r.xs[k - 1]) * f
        }
        error("line never reaches y=$y")
    }

    @Test
    fun slalomPassesEveryConeOnItsSide() {
        // Five cones in a row, 15 m apart: pass right (east), left, right, left, right
        val cones = (0 until 5).map { i -> ConeSide(0.0, 40.0 + 15 * i, if (i % 2 == 0) 1.0 else -1.0, 0.0) }
        val r = OptimalLine.solve(start, finish, cones)
        println("Slalom: %.1f m, %.2f s, worst clearance %.2f m".format(r.length, r.time, r.worstClearance))
        assertTrue("every cone respected", r.worstClearance > -0.05)
        for ((i, c) in cones.withIndex()) {
            val x = xAt(r, c.y)
            assertTrue("cone $i on the right side (x=%.2f)".format(x), x * c.ux >= 1.3)
        }
        assertTrue("plausible time", r.time in 9.0..25.0)

        // The naive cone-to-cone path is slower than the optimized line
        val naiveX = doubleArrayOf(0.0) + cones.map { it.ux * 2.4 } + doubleArrayOf(0.0)
        val naiveY = doubleArrayOf(0.0) + cones.map { it.y } + doubleArrayOf(160.0)
        val dense = densify(naiveX, naiveY)
        val naive = OptimalLine.evaluate(dense.first, dense.second)
        println("Naive cone-to-cone path: %.2f s".format(naive.time))
        assertTrue("optimized is faster than the naive line", r.time < naive.time - 0.2)
    }

    @Test
    fun offsetGateTakesASmoothArc() {
        // Pass left of a cone at 60 m, then right of one 10 m to the east at 90 m: a 13 m lane change in 30 m
        val cones = listOf(ConeSide(0.0, 60.0, -1.0, 0.0), ConeSide(10.0, 90.0, 1.0, 0.0))
        val r = OptimalLine.solve(start, finish, cones, corridor = 20.0)
        println("Offset: %.2f s, worst clearance %.2f, x at cones %.1f / %.1f".format(r.time, r.worstClearance, xAt(r, 60.0), xAt(r, 90.0)))
        assertTrue(r.worstClearance > -0.05)
        assertTrue(xAt(r, 60.0) <= -1.3)
        assertTrue(xAt(r, 90.0) >= 11.3)
        // The lane change costs time against a straight run
        assertTrue(r.time > OptimalLine.solve(start, finish, emptyList()).time + 0.2)
    }

    @Test
    fun staysOnThePavement() {
        // Slalom passing right of cones 0, 2, 4 (needs x >= 1.4). The pavement ends at x = 1.7 on that side.
        val cones = (0 until 5).map { i -> ConeSide(0.0, 40.0 + 20 * i, if (i % 2 == 0) 1.0 else -1.0, 0.0) }
        val free = OptimalLine.solve(start, finish, cones)
        val walled = OptimalLine.solve(start, finish, cones, drivable = { x, _ -> x <= 1.7 })
        val freeMax = free.xs.max(); val walledMax = walled.xs.max()
        println("Furthest east: free %.2f m, with pavement edge at 1.7 m: %.2f m; times %.2f / %.2f s".format(freeMax, walledMax, free.time, walled.time))
        assertTrue("the free line goes past the edge, so the edge matters", freeMax > 1.75)
        assertTrue("stays on the pavement", walledMax <= 1.8)
        assertTrue("still passes the cones", walled.worstClearance > -0.15)
    }

    @Test
    fun straightIsAStraightLine() {
        val r = OptimalLine.solve(start, finish, emptyList())
        for (k in r.xs.indices) assertEquals(0.0, r.xs[k], 0.05)
        // 4 m/s start, 0.35 g for 160 m: v = sqrt(16 + 2*3.43*160) = 33.4 m/s, t about 8.8 s
        assertEquals(8.8, r.time, 0.4)
        assertEquals(1, r.crossingTime(Gate(0.0, 80.0, 0.0, 1.0, 5.0))?.let { if (it > 0) 1 else 0 })
    }

    @Test
    fun utcSurvivesTheCsv() {
        val s = Sample(1.0, 33.5, -86.6, 1.0, 2.0, 3.0, 90.0, 10.0, 50.0, 3000.0, 0.0, 0.1, -0.2, 20, 3, 1_794_441_600_123L,
            yaw = -12.5, gear = 2, gearRatio = 2.991, wRL = 8.2)
        val back = Sample.parse(s.csv())!!
        assertEquals(1_794_441_600_123L, back.utc)
        assertEquals(-12.5, back.yaw, 1e-9); assertEquals(2, back.gear); assertEquals(2.991, back.gearRatio, 1e-9)
        assertEquals(8.2, back.wRL, 1e-9); assert(back.wFL.isNaN())
        // Older files without the newer columns still load
        val old = Sample.parse(s.csv().split(',').take(15).joinToString(","))!!
        assertEquals(0L, old.utc); assertEquals(-1, old.gear); assert(old.yaw.isNaN())
    }

    private fun densify(x: DoubleArray, y: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val ox = ArrayList<Double>(); val oy = ArrayList<Double>()
        for (k in 1 until x.size) {
            val n = (kotlin.math.hypot(x[k] - x[k - 1], y[k] - y[k - 1]) / 0.5).toInt().coerceAtLeast(1)
            for (j in 0 until n) { ox += x[k - 1] + (x[k] - x[k - 1]) * j / n; oy += y[k - 1] + (y[k] - y[k - 1]) * j / n }
        }
        ox += x.last(); oy += y.last()
        return ox.toDoubleArray() to oy.toDoubleArray()
    }
}
