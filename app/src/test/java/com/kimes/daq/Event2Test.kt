package com.kimes.daq

import com.kimes.daq.course.CornerMark
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import com.kimes.daq.run.Analyzer
import com.kimes.daq.run.Coach
import com.kimes.daq.run.LineCoach
import com.kimes.daq.run.Sections
import com.kimes.daq.run.Slide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The second event (2026-11-15, ALSCCA Points 10.2), skipped when the session isn't pulled.
 * Run 7 lost 0.50 s in the Horseshoe to run 4 (0.44 s with the Slolom after it) and must be told so; run 3 slid off the throttle
 * between the Chicane and the Horseshoe; run 4 slid on too much throttle before the Chicane.
 */
class Event2Test {
    private val dir = File("../pulled/event2/20261115_100917")
    private val corners = listOf(
        CornerMark(33.52869866282079, -86.62938590289626, "Banking"), CornerMark(33.528609844570354, -86.62747775074271, "Chicane"),
        CornerMark(33.52910572164597, -86.62758351851585, "Horseshoe"), CornerMark(33.52977588962127, -86.62666657230345, "Slolom"),
        CornerMark(33.53039760707436, -86.62607249447564, "Sweeper"),
    )

    private fun load(): Pair<List<Sample>, List<Run>> {
        val samples = File(dir, "gps.csv").readLines().drop(1).mapNotNull { Sample.parse(it) }
        val ev = File(dir, "events.csv").readLines().map { it.split(',') }
        val starts = ev.filter { it[1] == "run_start" }.map { it[0].toDouble() }
        val ends = ev.filter { it[1].startsWith("run_end") }.map { it[0].toDouble() }
        val runs = starts.zip(ends).mapIndexed { n, p ->
            val si = samples.indexOfFirst { it.t >= p.first }; val ei = samples.indexOfLast { it.t <= p.second }
            Run(n + 1, si, ei, samples[si].t, samples[ei].t, true)
        }
        return samples to runs
    }

    @Test
    fun run7IsToldAboutTheHorseshoe() {
        assumeTrue(dir.isDirectory)
        val (samples, all) = load()
        val runs = all.take(7) // as it stood after run 7, the fastest at the time
        val tab = Sections.build(samples, runs, corners = corners)
        val r7 = runs[6]
        val a = Analyzer.analyze(samples, r7, runs[5], corners, tab, runs)
        a.opportunities.forEach { println("run 7  ${it.zone.name}: " + "%.2f".format(it.lost) + " vs run ${it.vs}  ${it.text}") }
        val hs = a.opportunities.firstOrNull { it.zone.name == "Horseshoe" }
        assertNotNull("Horseshoe opportunity", hs)
        assertEquals(4, hs!!.vs)
        // Judged with the Slolom after it: 0.44 s to run 4 over the pair
        assertEquals(0.44, hs.lost, 0.03)
        // The tips account for most of the gap to the optimal lap
        assertTrue(a.opportunities.sumOf { it.lost } > 0.8)
    }

    @Test
    fun slidesAreFoundExplainedAndCompared() {
        assumeTrue(dir.isDirectory)
        val (samples, runs) = load()
        println("gyro bias %.2f deg/s".format(Coach.gyroBias(samples)))
        val tab = Sections.build(samples, runs, corners = corners)
        val best = runs.minBy { it.time }
        val found = HashMap<Int, List<Slide>>()
        for (r in runs) {
            val a = Analyzer.analyze(samples, r, if (r == best) runs.filter { it != best }.minBy { it.time } else best, corners, tab, runs)
            found[r.number] = a.slides
            a.slides.forEach { sl ->
                println("run ${r.number} t+" + "%.1f %.0f°".format(sl.t - r.startT, sl.peakDeg) + " ${sl.kind} ${sl.section} lost=${sl.lost} vs=${sl.vs}: ${sl.cause}. ${sl.compare ?: ""}")
            }
        }
        // Run 3: off both pedals into the turn after the Chicane
        val s3 = found[3]!!.firstOrNull { it.t - runs[2].startT in 22.0..24.5 }
        assertNotNull("run 3 slide", s3)
        assertEquals(Slide.Kind.LIFT, s3!!.kind)
        assertTrue("run 3 rotation ${s3.peakDeg}", s3.peakDeg >= 8)
        assertNotNull("run 3 told what the quickest run did", s3.compare)
        // Run 4: the left rear spun up on throttle before the Chicane
        val s4 = (Coach.slides(samples, runs[3], samples.maxOf { if (it.brake.isNaN()) 0.0 else it.brake }, Coach.gyroBias(samples)))
            .firstOrNull { it.t - runs[3].startT in 17.0..19.5 }
        assertNotNull("run 4 rotation found", s4)
        assertEquals(Slide.Kind.POWER, s4!!.kind)
        // Small rotations that cost nothing stay quiet: no run is buried in notes
        assertTrue(found.values.all { it.size <= 3 })
    }

    /**
     * Line coaching, backtested: with only runs 1-7, the advice should point toward the line run 8 then
     * drove. Run 7 gave up 0.10 s to run 2 in the Banking but left it faster, and was quickest over the
     * Banking and the straight after it, so it's not told to drive the Banking like run 2. It apexed the
     * Horseshoe far too late and is told to apex earlier, which run 8 did.
     */
    @Test
    fun lineCoachingPointsToRun8sLine() {
        assumeTrue(dir.isDirectory)
        val (samples, all) = load()
        val runs = all.take(7)
        val tab = Sections.build(samples, runs, corners = corners)
        fun opps(n: Int) = Analyzer.analyze(samples, runs[n - 1], runs[6], corners, tab, runs).opportunities
        val o7 = opps(7)
        o7.forEach { println("run 7  ${it.zone.name} vs ${it.vs}: ${it.text}") }
        assertTrue("no Banking card for run 7", o7.none { it.zone.name == "Banking" })
        val hs = o7.first { it.zone.name == "Horseshoe" }
        assertTrue(hs.text, Regex("apex [0-9]+ ft earlier").containsMatchIn(hs.text))
        assertTrue(o7.first { it.zone.name == "Chicane" }.text.contains("ft earlier"))
        // Run 5 left the Banking at 46 mph against run 7's 52: told to move right and apex later, in feet
        val b5 = opps(5).first { it.zone.name == "Banking" }
        println("run 5  Banking: ${b5.text}")
        assertEquals(7, b5.vs)
        assertTrue(b5.text, b5.text.contains("ft further right") && b5.text.contains("later"))

        // What run 8 then did: apexed the Horseshoe and the Chicane earlier than run 7
        val full = Sections.build(samples, all, corners = corners)
        fun apex(n: Int, name: String): Double {
            val r = all[n - 1]; val sec = full.sections.first { it.name == name }
            return LineCoach.shape(samples.subList(r.startIdx, r.endIdx + 1), full.dists.getValue(n), sec.d0, sec.d1)!!.apex
        }
        assertTrue(apex(8, "Horseshoe") < apex(7, "Horseshoe") - 5)
        assertTrue(apex(8, "Chicane") < apex(7, "Chicane"))
    }

    /**
     * Trail braking and rotation, with the car's brake pressure and without it (RaceBox only): run 7
     * held the brake into the Sweeper, the car rotated 9 degrees more than run 5's and was 4 mph slower
     * at the slowest point; run 3 was off the brake well before turning in after the Banking, where
     * run 8 carried it in. Both must be told, the same way with or without the car's data.
     */
    @Test
    fun trailBrakingWithAndWithoutTheCar() {
        assumeTrue(dir.isDirectory)
        val (full, runs) = load()
        val raceBoxOnly = full.map { it.copy(brake = Double.NaN, throttle = Double.NaN, rpm = Double.NaN, gear = -1, gearRatio = Double.NaN) }
        for ((label, samples) in listOf("car" to full, "RaceBox only" to raceBoxOnly)) {
            val tab = Sections.build(samples, runs, corners = corners)
            fun opps(n: Int) = Analyzer.analyze(samples, runs[n - 1], runs[7], corners, tab, runs).opportunities
            val sw = opps(7).firstOrNull { it.zone.name == "Sweeper" }
            println("$label  run 7 Sweeper: ${sw?.text}")
            assertNotNull("$label: run 7 Sweeper card", sw)
            assertTrue(sw!!.text, sw.text.contains("Come off the brake sooner") && sw.text.contains("rotated"))
            for (r in runs) opps(r.number).filter { it.text.contains("brake") }.forEach { println("$label  run ${r.number} ${it.zone.name}: ${it.text}") }
            // No trail-braking advice where the corner has no braking (the Slolom)
            for (r in runs) assertTrue(opps(r.number).none { it.zone.name == "Slolom" && it.text.contains("brake in") })
        }
    }

    @Test
    fun officialTimesRecastTheSections() {
        assumeTrue(dir.isDirectory)
        val (samples, runs) = load()
        // Sunday's official times, as keyed in from the slips
        val official = mapOf(1 to 50.225, 2 to 47.444, 3 to 49.342, 4 to 47.073, 5 to 47.514, 6 to 47.342, 7 to 46.861, 8 to 46.376)
        val corr = runs.associate { it.number to (official.getValue(it.number) - it.time) }
        val plain = Sections.build(samples, runs, corners = corners)
        val tab = Sections.build(samples, runs, corners = corners, corrections = corr)
        for (r in runs) {
            val t = tab.times.getValue(r.number)
            assertEquals("run ${r.number} sections add up to its official time", official.getValue(r.number), t.sum(), 0.02)
            // Only the first and last sections change
            val p = plain.times.getValue(r.number)
            for (k in 1 until t.size - 1) assertEquals(p[k], t[k], 1e-9)
        }
        println("optimal: tablet %.3f, official %.3f".format(plain.optimalTotal, tab.optimalTotal))
        assertTrue(tab.optimalTotal > plain.optimalTotal + 0.3 && tab.optimalTotal < official.values.min())
    }
}
