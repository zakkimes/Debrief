package com.kimes.daq

import com.kimes.daq.data.Sample
import com.kimes.daq.run.Coach
import com.kimes.daq.run.Course
import com.kimes.daq.run.RunTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Regression tests on real run windows cut from the 2026-11-14 event (see tools/cut_event1_fixtures.py). */
class Event1Test {
    private class Case(val name: String, val launchRpm: Double, val shiftRpm: Double)

    private val cases = listOf(
        Case("morning_run1", 5291.0, 6520.0),
        Case("morning_run2", 5451.0, 6838.0),
        Case("morning_run3", 5453.0, 7022.0),
        Case("morning_run4", 5427.0, 7174.0),
        Case("afternoon_run1", 5208.0, 6561.0),
        Case("afternoon_run2", 4820.0, 6565.0),
    )

    private fun loadRaw(name: String): List<Sample> {
        // The recordings stay in the private repo; elsewhere these tests are skipped
        val url = javaClass.classLoader!!.getResource("event1/$name.csv")
        assumeTrue("fixture $name not here", url != null)
        return url!!.openStream().bufferedReader().readLines().drop(1).mapNotNull { Sample.parse(it) }
    }

    /**
     * RunTracker.feed never records a standstill that begins at sample index 0 (slowEndIdx == -1 == i - 1,
     * so slowStartIdx is never set), so a window that starts parked finds no run. Prime with one synthetic
     * moving sample so the other tests don't depend on that; see [runIsFoundWhenWindowStartsStationary].
     */
    private fun load(name: String): List<Sample> {
        val raw = loadRaw(name)
        return if (raw.first().speed < 0.6) listOf(raw.first().copy(t = raw.first().t - 0.04, speed = 5.0)) + raw else raw
    }

    @Test
    fun runIsFoundWhenWindowStartsStationary() {
        for (c in cases) assertEquals("${c.name} (unprimed)", 1, RunTracker.detectAll(loadRaw(c.name), Course()).size)
    }

    private fun launchOf(c: Case): com.kimes.daq.run.Launch {
        val samples = load(c.name)
        val runs = RunTracker.detectAll(samples, Course())
        assertEquals("${c.name}: detected runs", 1, runs.size)
        val l = Coach.launch(samples, runs[0])
        assertNotNull("${c.name}: launch", l)
        return l!!
    }

    @Test
    fun eachFixtureYieldsExactlyOneRun() {
        for (c in cases) assertEquals(c.name, 1, RunTracker.detectAll(load(c.name), Course()).size)
    }

    @Test
    fun launchRpmMatchesTruth() {
        for (c in cases) {
            val l = launchOf(c)
            assertEquals("${c.name} launch rpm", c.launchRpm, l.launchRpm, 150.0)
        }
    }

    @Test
    fun shiftRpmMatchesTruth() {
        val bad = ArrayList<String>()
        for (c in cases) {
            val s = launchOf(c).shiftRpm
            println("${c.name}: shiftRpm=$s truth=${c.shiftRpm}")
            if (s == null || Math.abs(s - c.shiftRpm) > 200.0) bad += "${c.name}: got $s want ${c.shiftRpm}"
        }
        assertTrue("shift rpm off: $bad", bad.isEmpty())
    }

    @Test
    fun noHoldFirstLongerTipWhenShiftWasHigh() {
        val all = ArrayList<String>()
        for (c in cases) {
            val l = launchOf(c)
            println("${c.name}: tips=${l.tips}")
            if (c.shiftRpm > 6400) all += l.tips.filter { it.contains("hold 1st longer", true) || it.contains("shift later", true) }.map { "${c.name}: $it" }
        }
        assertTrue("bad tips: $all", all.isEmpty())
    }

    /** Informational: rpm / (mph * 203) in 1st from launch to the shift; > 1.15 suggests wheel slip. */
    @Test
    fun firstGearRatioInfo() {
        for (c in cases) {
            val samples = load(c.name)
            val run = RunTracker.detectAll(samples, Course()).firstOrNull() ?: continue
            var i = run.startIdx
            while (i > 0 && samples[i].speed > 0.3) i--
            while (i < run.endIdx && samples[i + 1].speed <= 0.3) i++
            val move = (i until run.endIdx).firstOrNull { samples[it].speed > 0.45 } ?: continue
            // to the first throttle lift or gear 2 after the first second
            val t0 = samples[move].t
            val end = (move until minOf(samples.size, move + 400)).firstOrNull { k ->
                samples[k].t - t0 > 1.0 && (samples[k].gear == 2 || samples[k].throttle < 5)
            } ?: (move + 100)
            var over = 0.0
            val sb = StringBuilder("${c.name} ratio rpm/(mph*203):")
            for (k in move..end) {
                val s = samples[k]
                val mph = s.speed * 2.236936
                if (mph < 3 || s.rpm.isNaN()) continue
                val r = s.rpm / (mph * 203.0)
                if (r > 1.15) over += if (k > 0) s.t - samples[k - 1].t else 0.0
                if ((k - move) % 5 == 0) sb.append(" t=%.2f:%.2f".format(s.t - samples[move].t, r))
            }
            println(sb)
            println("${c.name}: %.2f s with ratio > 1.15".format(over))
        }
    }
}
