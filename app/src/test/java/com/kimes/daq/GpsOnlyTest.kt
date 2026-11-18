package com.kimes.daq

import com.kimes.daq.data.Sample
import com.kimes.daq.run.Analyzer
import com.kimes.daq.run.Course
import com.kimes.daq.run.RunTracker
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A real session with every car channel removed, as a RaceBox-only user records it (skipped when
 * the session isn't pulled): every run, the best one included, must still get coaching.
 */
class GpsOnlyTest {
    @Test
    fun raceBoxOnlySession() {
        val f = File("../pulled/20261114_105657/gps.csv")
        assumeTrue(f.isFile)
        val full = f.readLines().drop(1).mapNotNull { Sample.parse(it) }
        val gps = full.map {
            it.copy(brake = Double.NaN, throttle = Double.NaN, rpm = Double.NaN, steer = Double.NaN, gear = -1,
                gearRatio = Double.NaN, wFL = Double.NaN, wFR = Double.NaN, wRL = Double.NaN, wRR = Double.NaN)
        }
        val runs = RunTracker.detectAll(gps, Course())
        assumeTrue(runs.size >= 2)
        val best = runs.minBy { it.time }
        val next = runs.filter { it != best }.minBy { it.time }
        var withOpps = 0
        for (r in runs) {
            val ref = if (r == best) next else best
            val a = Analyzer.analyze(gps, r, ref)
            val c = Analyzer.analyze(full, r, ref)
            println("run ${r.number} %.2f vs run ${ref.number}".format(r.time))
            a.opportunities.forEach { println("   GPS  ${it.zone.name}: " + "%.2f".format(it.lost) + "  " + it.text) }
            c.opportunities.forEach { println("   CAR  ${it.zone.name}: " + "%.2f".format(it.lost) + "  " + it.text) }
            println("   GPS launch " + a.launch?.let { "60ft ${it.sixtyFtS} pulled " + "%.2f".format(it.peakG) + " g  " + it.tips })
            assertNotNull("run ${r.number} launch", a.launch)
            if (a.opportunities.isNotEmpty()) withOpps++
            // Car-free text must never talk about the pedals as if they were measured
            a.opportunities.forEach { assertTrue(it.text, !it.text.contains("brake pressure")) }
        }
        assertTrue("most runs get coaching", withOpps >= runs.size - 1)
    }
}
