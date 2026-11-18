package com.kimes.daq

import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import com.kimes.daq.run.Course
import com.kimes.daq.run.RunTracker
import com.kimes.daq.run.Sections
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SectionsTest {
    private fun smp(t: Double, x: Double, v: Double, lonG: Double) = Sample(
        t, 0.0, 0.0, x, 0.0, v, 90.0, Double.NaN, Double.NaN, 0.0, 0.0, 0.0, lonG, 10, 3,
    )

    /** One run down the x axis; mins = corner minimum speeds at 150/350/550 m (triangular dips). */
    private fun build(all: MutableList<Sample>, number: Int, mins: DoubleArray, finished: Boolean = true, length: Double = 700.0): Run {
        fun v(d: Double): Double {
            if (d < 50) return 5 + 20 * d / 50
            var best = 25.0
            for ((k, c) in doubleArrayOf(150.0, 350.0, 550.0).withIndex()) {
                val dip = (25 - mins[k]) * (1 - kotlin.math.abs(d - c) / 100).coerceAtLeast(0.0)
                best = minOf(best, 25 - dip)
            }
            return best
        }
        val start = all.size
        var d = 0.0; var t = 0.0; val dt = 0.04
        var prev = v(0.0)
        while (d < length) {
            val sp = v(d)
            all += smp(t, d, sp, (sp - prev) / dt / 9.81)
            prev = sp
            d += sp * dt; t += dt
        }
        return Run(number, start, all.size - 1, 0.0, t, finished)
    }

    @Test
    fun slowestRunCanWinASection() {
        val all = ArrayList<Sample>()
        val r1 = build(all, 1, doubleArrayOf(4.0, 4.0, 14.0))
        val r2 = build(all, 2, doubleArrayOf(9.0, 9.0, 9.0))
        val r3 = build(all, 3, doubleArrayOf(8.0, 8.0, 8.0))
        val tab = Sections.build(all, listOf(r1, r2, r3))
        println(tab.sections.joinToString { "${it.name} %.0f-%.0f".format(it.d0, it.d1) })
        assertEquals(listOf("Launch", "Corner 1", "Corner 2", "Corner 3"), tab.sections.map { it.name })
        assertEquals(2, tab.base!!.number)
        assertTrue(r1.time > r2.time && r1.time > r3.time)   // run 1 slowest overall
        assertEquals(1, tab.bestRun[3])                      // ...but best through corner 3
        assertTrue(tab.times[1]!![3] < tab.times[3]!![3])
        assertEquals(2, tab.bestRun[1])
        for (r in listOf(r1, r2, r3)) assertEquals(r.time, tab.times[r.number]!!.sum(), 0.2)
        assertEquals(tab.bestTime.sum(), tab.optimalTotal, 1e-9)
        assertTrue(tab.optimalTotal < tab.bestActualTime)
        assertEquals(2, tab.bestActualRun)
        assertEquals(0.0, tab.loss(1, 3), 1e-9)
        assertTrue(tab.loss(1, 1) > 0.3)
        assertTrue(tab.optimalPath.size > 100)
        assertEquals(4.0, tab.stats[1]!![1].minSpeed, 0.5)
        assertEquals(listOf(3), tab.sectionsWon(1).map { it.index })
        assertEquals(150.0, tab.sections[1].apexX, 15.0)
        val t2 = Sections.build(all, listOf(r1, r2, r3), base = r1)
        assertEquals(tab.sections.size, t2.sections.size)
    }

    @Test
    fun unfinishedRunDoesNotCountForBest() {
        val all = ArrayList<Sample>()
        val r1 = build(all, 1, doubleArrayOf(8.0, 8.0, 8.0))
        val r2 = build(all, 2, doubleArrayOf(14.0, 14.0, 14.0), finished = false, length = 400.0)
        val tab = Sections.build(all, listOf(r1, r2))
        assertEquals(1, tab.bestRun[1])
        assertFalse(tab.times[2]!![1].isNaN())
        assertTrue(tab.times[2]!![3].isNaN())
        assertEquals(r1.time, tab.optimalTotal, 0.25)
    }

    @Test
    fun tinyInputsDoNotCrash() {
        val all = ArrayList<Sample>()
        assertTrue(Sections.build(all, emptyList()).sections.isEmpty())
        val r1 = build(all, 1, doubleArrayOf(8.0, 8.0, 8.0))
        val one = Sections.build(all, listOf(r1))
        assertEquals(r1.time, one.optimalTotal, 0.25)
        val short = Run(2, 0, 3, 0.0, 0.1, true)
        Sections.build(all, listOf(short))
        Sections.build(all, listOf(short, r1))
    }

    @Test
    fun fastEnough() {
        val all = ArrayList<Sample>()
        val runs = (1..8).map { build(all, it, doubleArrayOf(8.0 + it * 0.2, 8.0, 8.0), length = 560.0) }
        Sections.build(all, runs)
        val t0 = System.nanoTime()
        Sections.build(all, runs)
        val ms = (System.nanoTime() - t0) / 1e6
        println("Sections.build 8 runs x ${runs[0].endIdx + 1} samples: %.1f ms".format(ms))
        assertTrue(ms < 200)
    }

    @Test
    fun realSession() {
        val f = File("../pulled/20261114_105657/gps.csv")
        assumeTrue(f.isFile)
        val samples = f.readLines().drop(1).mapNotNull { Sample.parse(it) }
        // Course() has no finish gate, so nothing is "finished"; treat full-length runs as finished here.
        val runs = RunTracker.detectAll(samples, Course()).map { it.copy(finished = it.time > 100) }
        assumeTrue(runs.isNotEmpty())
        val t0 = System.nanoTime()
        val tab = Sections.build(samples, runs)
        println("build: %.1f ms, base run ${tab.base?.number}".format((System.nanoTime() - t0) / 1e6))
        println("%-9s %6s %6s ".format("section", "d0", "d1") + runs.joinToString(" ") { "run${it.number}".padStart(7) } + "   best")
        for (s in tab.sections) {
            println("%-9s %6.0f %6.0f ".format(s.name, s.d0, s.d1) +
                runs.joinToString(" ") { "%7.2f".format(tab.times[it.number]!![s.index]) } + "   run${tab.bestRun[s.index]} %.2f".format(tab.bestTime[s.index]))
        }
        println("totals    " + runs.joinToString(" ") { "%.2f(%s)".format(it.time, if (it.finished) "F" else "-") })
        println("section sums " + runs.joinToString(" ") { "%.2f".format(tab.times[it.number]!!.sum()) })
        println("optimal %.2f vs best actual %.2f (run ${tab.bestActualRun}), gain %.2f".format(tab.optimalTotal, tab.bestActualTime, tab.optimalGain))
        for (r in runs) {
            val sum = tab.times[r.number]!!.sum()
            if (!sum.isNaN()) assertEquals(r.time, sum, 0.2)
        }
    }
}
