package com.kimes.daq

import com.kimes.daq.course.CourseDef
import com.kimes.daq.data.Gate
import com.kimes.daq.data.LocalProjection
import com.kimes.daq.data.Sample
import com.kimes.daq.gopro.AutoRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AutoRecordTest {
    private val start = Gate(0.0, 0.0, 0.0, 1.0, 25.0)   // heading north
    private val finish = Gate(0.0, 200.0, 0.0, 1.0, 25.0)

    private fun s(t: Double, x: Double, y: Double, v: Double, hdg: Double = 0.0) =
        Sample(t, 0.0, 0.0, x, y, v, hdg, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0, 20, 3)

    /** Feeds a path and returns (time, on/off) for each change. */
    private fun drive(path: List<Sample>, a: AutoRecord = AutoRecord(start, finish)): List<Pair<Double, Boolean>> {
        val out = ArrayList<Pair<Double, Boolean>>()
        var p: Sample? = null
        for (x in path) { a.feed(p, x)?.let { out += x.t to it }; p = x }
        return out
    }

    @Test
    fun recordsFromFiftyFeetBeforeStartToTenSecondsPastFinish() {
        val path = ArrayList<Sample>()
        var t = 0.0; var y = -60.0
        fun go(toY: Double, v: Double) { while (y < toY) { y += v * 0.04; t += 0.04; path += s(t, 0.0, y, v) } }
        fun wait(sec: Double) { repeat((sec / 0.04).toInt()) { t += 0.04; path += s(t, 0.0, y, 0.0) } }
        wait(20.0)            // in the grid, 60 m back
        go(-25.0, 2.0); wait(10.0) // pull forward, still outside 50 ft
        go(-8.0, 2.0); wait(30.0)  // roll to the stage line and wait for the starter
        go(240.0, 15.0)            // the run, and past the finish (y = 200)
        wait(20.0)                 // stopped in the shutdown area
        val ch = drive(path)
        println(ch)
        assertEquals(2, ch.size)
        assertTrue(ch[0].second); assertTrue(!ch[1].second)
        val yOn = path.first { it.t == ch[0].first }.y; val yOff = path.first { it.t == ch[1].first }.y
        println("on at y=%.1f, off at y=%.1f".format(yOn, yOff))
        assertEquals(-15.24, yOn, 0.2)
        assertTrue("still running at y=%.1f".format(yOff), yOff >= 239.0)
        val tFinish = path.first { it.y >= 200.0 }.t
        assertEquals(10.0, ch[1].first - tFinish, 0.1)
    }

    @Test
    fun drivingPastTheStartAreaDoesNotRecordForLong() {
        // Crosses the approach box sideways (heading east): never starts
        val east = (0 until 500).map { k -> s(k * 0.04, -40.0 + k * 0.04 * 5, -8.0, 5.0, 90.0) }
        assertEquals(emptyList<Pair<Double, Boolean>>(), drive(east))
        // Heads at the line, then turns away before it: starts, then stops 3 s after leaving
        val path = (0 until 100).map { k -> s(k * 0.04, 0.0, -20.0 + k * 0.04 * 4, 4.0) } +
            (1 until 500).map { k -> s(4 + k * 0.04, k * 0.04 * 4, -2.0, 4.0, 90.0) }
        val ch = drive(path)
        println(ch)
        assertEquals(2, ch.size)
        assertTrue(ch[1].first - ch[0].first < 15) // 25 m to the edge of the line's width, then 3 s
    }

    /** The parking-lot session of 2026-11-12, with its start and finish lines (skipped when not pulled). */
    @Test
    fun parkingLotRuns() {
        val d = File("../pulled/lot/20261112_074335")
        assumeTrue(d.isDirectory)
        val o = JSONObject(File(d, "course.json").readText())
        val c = CourseDef.from(o.getJSONObject("courseDef"))
        val p = LocalProjection(o.getDouble("lat0"), o.getDouble("lon0"))
        val samples = File(d, "gps.csv").readLines().drop(1).mapNotNull { Sample.parse(it) }
        val ch = drive(samples, AutoRecord(c.start!!.gate(p), c.finish!!.gate(p)))
        val ev = File(d, "events.csv").readLines().map { it.split(',') }.filter { it[1].startsWith("run_") }
        println("runs: " + ev.joinToString { "%s %.1f".format(it[1], it[0].toDouble()) })
        println("camera: " + ch.joinToString { "%s %.1f".format(if (it.second) "on" else "off", it.first) })
        val starts = ev.filter { it[1] == "run_start" }.map { it[0].toDouble() }
        val ends = ev.filter { it[1].startsWith("run_end") }.map { it[0].toDouble() }
        // Every timed run is inside a recording that starts before it and stops about 10 s after the finish
        for ((a, b) in starts.zip(ends)) {
            val on = ch.lastOrNull { it.second && it.first <= a + 0.1 }
            val off = ch.firstOrNull { !it.second && it.first >= b }
            assertTrue("run at %.1f is recorded".format(a), on != null && off != null && ch.none { !it.second && it.first in on.first..b })
            assertTrue("stops about 10 s after the finish: %.1f".format(off!!.first - b), off.first - b in 8.0..12.0)
        }
    }
}
