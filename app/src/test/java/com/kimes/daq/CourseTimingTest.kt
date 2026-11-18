package com.kimes.daq

import com.kimes.daq.course.CourseDef
import com.kimes.daq.course.CourseMode
import com.kimes.daq.course.LineKind
import com.kimes.daq.data.Gate
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import com.kimes.daq.run.LineTimer
import com.kimes.daq.run.Standings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CourseTimingTest {
    private fun s(t: Double, x: Double, y: Double, v: Double) =
        Sample(t, 0.0, 0.0, x, y, v, 0.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0, 20, 3)

    /** Drive north along x=0 from y0 to y1 at constant speed, 25 Hz. */
    private fun drive(out: MutableList<Sample>, t0: Double, y0: Double, y1: Double, v: Double): Double {
        var t = t0; var y = y0
        while (y < y1) { out += s(t, 0.0, y, v); y += v * 0.04; t += 0.04 }
        return t
    }

    private fun gateAt(y: Double) = Gate(0.0, y, 0.0, 1.0, 10.0)

    @Test
    fun startToFinishWithSectors() {
        val ss = ArrayList<Sample>()
        var t = drive(ss, 0.0, -50.0, 0.0, 2.0)          // rolling around in the grid, before the start line
        t = drive(ss, t, 0.0, 200.0, 20.0)                // 10 s down the course
        repeat(50) { ss += s(t, 0.0, 205.0, 0.0); t += 0.04 }
        val timer = LineTimer(gateAt(1.0), gateAt(190.0), listOf(gateAt(61.0), gateAt(121.0)), laps = false)
        val runs = ss.indices.mapNotNull { timer.feed(ss, it) }
        assertEquals(1, runs.size)
        val r = runs[0]
        assertTrue(r.finished)
        assertEquals(189.0 / 20.0, r.time, 0.01)           // start line to finish line, interpolated
        assertEquals(2, r.splits.size)
        assertEquals(3.0, r.sectorTimes[0], 0.01)
        assertEquals(3.0, r.sectorTimes[1], 0.01)
        assertEquals(189.0 / 20.0 - 6.0, r.sectorTimes[2], 0.01)
    }

    @Test
    fun wrongWayAndStallsDontCount() {
        val ss = ArrayList<Sample>()
        // Drive south across both lines: nothing starts
        var t = 0.0; var y = 220.0
        while (y > -20) { ss += s(t, 0.0, y, 15.0); y -= 0.6; t += 0.04 }
        // Start, then stop on course for 10 s: abandoned, no run
        t = drive(ss, t, -10.0, 50.0, 15.0)
        repeat(260) { ss += s(t, 0.0, 50.0, 0.0); t += 0.04 }
        val timer = LineTimer(gateAt(0.0), gateAt(190.0), emptyList(), laps = false)
        val runs = ss.indices.mapNotNull { timer.feed(ss, it) }
        assertEquals(0, runs.size)
        assertTrue(!timer.isRunning)
    }

    @Test
    fun lapsChainOneIntoTheNext() {
        // Circle of radius 50 m around (0, 50), start/finish line at the origin heading east
        val ss = ArrayList<Sample>()
        var t = 0.0
        val v = 20.0
        val period = 2 * PI * 50 / v
        while (t < period * 3.5) {
            val a = t / period * 2 * PI
            ss += s(t, 50 * sin(a), 50 - 50 * cos(a), v); t += 0.04
        }
        val timer = LineTimer(Gate(0.0, 0.0, 1.0, 0.0, 10.0), Gate(0.0, 0.0, 1.0, 0.0, 10.0), emptyList(), laps = true)
        val laps = ss.indices.mapNotNull { timer.feed(ss, it) }
        assertEquals(2, laps.size) // timing starts at the first crossing (one lap in), then 2 full laps
        laps.forEach { assertEquals(period, it.time, 0.05) }
    }

    @Test
    fun importsRaceChronoTrack() {
        val json = """{"track":{"name":"2026 ALSCCA Event 4","traps":[
            {"bearing":0,"width":50000,"centerLatitude":201173234,"uniDirectional":true,"type":1,"orderValue":0,"name":"New trap","centerLongitude":-519769008},
            {"bearing":231414,"width":50000,"centerLatitude":201181007,"uniDirectional":true,"type":2,"orderValue":0,"name":"New trap (1)","centerLongitude":-519761445}]}}"""
        val c = CourseDef.fromRaceChronoTrack(JSONObject(json).getJSONObject("track"), "x")
        assertEquals("2026 ALSCCA Event 4", c.name)
        assertEquals(CourseMode.AUTOX, c.mode)
        assertEquals(LineKind.START, c.start!!.kind)
        assertEquals(33.528872, c.start!!.lat, 1e-5)
        assertEquals(-86.628168, c.start!!.lon, 1e-5)
        assertEquals(231.414, c.finish!!.bearing, 1e-6)
        assertEquals(50.0, c.finish!!.width, 1e-6)
        assertTrue(c.timed)
        // Round trip through our own format
        assertEquals(c, CourseDef.from(c.json()))
    }

    @Test
    fun conedRunsNeverBecomeTheReference() {
        val runs = listOf(
            Run(1, 0, 1, 0.0, 55.0, true, listOf(12.0, 26.0)),
            Run(2, 0, 1, 0.0, 53.0, true, listOf(11.5, 26.5)),  // fastest, but hit a cone
            Run(3, 0, 1, 0.0, 54.0, true, listOf(12.5, 25.5)),
        )
        val pen = mapOf(2 to 1)
        assertEquals(3, Standings.best(runs, pen)!!.number)
        assertEquals(1, Standings.best(runs, pen, exclude = 3)!!.number)
        assertEquals(55.0, Standings.scored(runs[1], pen)!!, 1e-9)
        assertNull(Standings.scored(runs[1], mapOf(2 to Standings.DNF)))
        // Theoretical best from clean runs 1 and 3: 12 + 13 (run 3: 25.5-12.5) + 28.5 (run 3: 54-25.5)
        val sb = Standings.sectorBests(runs, pen, 3)
        assertNotNull(sb[0]); assertEquals(1, sb[0]!!.run)
        assertEquals(12.0 + 13.0 + 28.5, Standings.theoreticalBest(runs, pen, 3)!!, 1e-9)
    }
}
