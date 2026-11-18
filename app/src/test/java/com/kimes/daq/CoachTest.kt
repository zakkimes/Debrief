package com.kimes.daq

import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import com.kimes.daq.run.Coach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CoachTest {
    /** The parking-lot session of 2026-11-12, runs taken from its events (skipped when not pulled). */
    @Test
    fun launchesFromTheParkingLot() {
        val d = File("../pulled/lot/20261112_074335")
        assumeTrue(d.isDirectory)
        val samples = File(d, "gps.csv").readLines().drop(1).mapNotNull { Sample.parse(it) }
        val ev = File(d, "events.csv").readLines().map { it.split(',') }
        val starts = ev.filter { it[1] == "run_start" }.map { it[0].toDouble() }
        val ends = ev.filter { it[1].startsWith("run_end") }.map { it[0].toDouble() }
        var found = 0
        for ((n, pair) in starts.zip(ends).withIndex()) {
            val si = samples.indexOfFirst { it.t >= pair.first }; val ei = samples.indexOfLast { it.t <= pair.second }
            val run = Run(n + 1, si, ei, samples[si].t, samples[ei].t, true)
            val l = Coach.launch(samples, run)
            println("run ${n + 1}: " + (l?.let {
                "launch %.0f rpm, dip %.0f, slip %.2f s, 60ft %s, peak %.2f g, shift %s rpm / %s s\n   ".format(
                    it.launchRpm, it.dipRpm, it.slipS, it.sixtyFtS?.let { s -> "%.2f".format(s) }, it.peakG,
                    it.shiftRpm?.let { s -> "%.0f".format(s) }, it.shiftS?.let { s -> "%.2f".format(s) }) + it.tips.joinToString("\n   ")
            } ?: "no launch found"))
            if (l != null) found++
        }
        assertTrue("launches found: $found", found >= 3)
    }

    /** A synthetic snap oversteer: steady left turn, throttle lifted, then the car rotates 80 degrees past its path. */
    @Test
    fun liftOffSpinIsFoundAndExplained() {
        val ss = ArrayList<Sample>()
        var x = 0.0; var y = 0.0; var course = 0.0
        for (k in 0 until 250) {
            val t = k * 0.04
            val v = if (t < 6.2) 12.0 else (12.0 - (t - 6.2) * 6).coerceAtLeast(0.5)
            val turn = if (t > 3) 20.0 else 0.0 // path turning left, deg/s
            val extra = if (t in 6.0..7.0) 80.0 else 0.0 // car rotating beyond the path
            val thr = if (t < 5.6) 60.0 else 0.0
            course -= turn * 0.04 // compass heading decreases turning left
            x += v * 0.04 * sin(course * PI / 180); y += v * 0.04 * cos(course * PI / 180)
            ss += Sample(t, 0.0, 0.0, x, y, v, (course + 360) % 360, 0.0, thr, 4000.0, Double.NaN,
                if (turn > 0) v * turn * PI / 180 / 9.81 else 0.0, 0.0, 20, 3, yaw = turn + extra)
        }
        val slides = Coach.slides(ss, Run(1, 0, ss.size - 1, 0.0, ss.last().t, true), 100.0)
        println(slides)
        assertEquals(1, slides.size)
        assertTrue(slides[0].spun)
        assertEquals("Lifted off the throttle mid-corner", slides[0].cause)
    }
}
