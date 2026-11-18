package com.kimes.daq

import com.kimes.daq.data.Gate
import com.kimes.daq.data.Sample
import com.kimes.daq.run.Analyzer
import com.kimes.daq.run.Course
import com.kimes.daq.run.PathFollower
import com.kimes.daq.run.RefPath
import com.kimes.daq.run.RunTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Drives a synthetic autocross course and checks run detection, finish timing, delta and insights. */
class SimulatedAutocrossTest {
    // Course: straight, right 90 (R=15), straight, left 180 (R=12), straight, right 90 (R=20), straight
    private val segs = listOf(60.0 to 0.0, 15.0 to -90.0, 50.0 to 0.0, 12.0 to 180.0, 70.0 to 0.0, 20.0 to -90.0, 60.0 to 0.0)

    private class Pt(val x: Double, val y: Double, val heading: Double, val radius: Double)

    private fun path(): List<Pt> {
        val pts = ArrayList<Pt>()
        var x = 0.0; var y = 0.0; var h = 0.0 // heading radians, 0 = north
        val ds = 0.5
        for ((a, b) in segs) {
            if (b == 0.0) {
                repeat((a / ds).toInt()) { x += sin(h) * ds; y += cos(h) * ds; pts += Pt(x, y, h, Double.MAX_VALUE) }
            } else {
                val len = a * Math.toRadians(kotlin.math.abs(b))
                val turn = Math.toRadians(b) / (len / ds) * -1 // positive b = left
                repeat((len / ds).toInt()) { h += turn; x += sin(h) * ds; y += cos(h) * ds; pts += Pt(x, y, h, a) }
            }
        }
        return pts
    }

    /** Speed limited by lateral grip in corners, accel 0.45 g, braking 0.95 g. */
    private fun profile(p: List<Pt>, gripFor: (Int) -> Double): DoubleArray {
        val v = DoubleArray(p.size) { i -> min(sqrt(gripFor(i) * 9.81 * p[i].radius), 30.0) }
        v[0] = 0.0
        for (i in 1 until v.size) v[i] = min(v[i], sqrt(v[i - 1] * v[i - 1] + 2 * 0.45 * 9.81 * 0.5))
        for (i in v.size - 2 downTo 0) v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2 * 0.95 * 9.81 * 0.5))
        return v
    }

    private fun drive(out: MutableList<Sample>, p: List<Pt>, v: DoubleArray, t0: Double): Double {
        var t = t0
        // Staged for 3 s
        repeat(75) { out += sample(t, p[0], 0.0, 0.0, 0.0); t += 0.04 }
        var d = 0.0
        val total = (p.size - 1) * 0.5
        var prevV = 0.0
        while (d < total) {
            val i = min((d / 0.5).toInt(), p.size - 1)
            val sp = max(v[i], 1.6)
            val acc = (sp - prevV) / 0.04
            out += sample(t, p[i], sp, if (acc < -1.5) acc / -9.81 * 60 else 0.0, if (acc > 0.3 || i > 0 && v[i] >= 29.9) 100.0 else 0.0)
            prevV = sp
            d += sp * 0.04
            t += 0.04
        }
        // Slow down after the finish, roll to a stop
        var sp = prevV
        val last = p.last()
        var x = last.x; var y = last.y
        while (sp > 0) {
            sp = max(0.0, sp - 0.6 * 9.81 * 0.04)
            x += sin(last.heading) * sp * 0.04; y += cos(last.heading) * sp * 0.04
            out += sample(t, Pt(x, y, last.heading, 0.0), sp, 40.0, 0.0); t += 0.04
        }
        repeat(100) { out += sample(t, Pt(x, y, last.heading, 0.0), 0.0, 0.0, 0.0); t += 0.04 }
        return t
    }

    private fun sample(t: Double, p: Pt, v: Double, brake: Double, thr: Double) =
        Sample(t, p.y / 111_320.0, p.x / 111_320.0, p.x, p.y, v, Math.toDegrees(p.heading), brake, thr, 3000.0, 0.0,
            if (p.radius < 1e6 && p.radius > 0) v * v / p.radius / 9.81 else 0.0, if (brake > 0) -brake / 60 else 0.0, 20, 3)

    @Test
    fun detectsRunsTimesAndExplainsLoss() {
        val p = path()
        val fast = profile(p) { 1.0 }
        // Second run: much less grip through the second (180) corner only
        val c2start = ((60 + 15 * PI / 2 + 50) / 0.5).toInt()
        val c2end = c2start + (12 * PI / 0.5).toInt()
        val slow = profile(p) { i -> if (i in c2start..c2end) 0.7 else 1.0 }

        val s = ArrayList<Sample>()
        // Paddock shuffle: should not count as a run
        var t = 0.0
        repeat(50) { s += sample(t, Pt(-300.0, 0.0, 0.0, 0.0), 0.0, 0.0, 0.0); t += 0.04 }
        repeat(400) { s += sample(t, Pt(-300.0, it * 0.1, 0.0, 0.0), 2.5, 0.0, 10.0); t += 0.04 }
        repeat(100) { s += sample(t, Pt(-300.0, 40.0, 0.0, 0.0), 0.0, 0.0, 0.0); t += 0.04 }
        t = drive(s, p, fast, t)
        t = drive(s, p, slow, t)

        val course = Course()
        var runs = RunTracker.detectAll(s, course)
        assertEquals("two runs, paddock ignored", 2, runs.size)
        assertTrue(runs.none { it.finished })

        // Finish gate at the end of the course, as if tapped on the map
        val e = p.last(); val e0 = p[p.size - 7]
        val len = kotlin.math.hypot(e.x - e0.x, e.y - e0.y)
        course.finish = Gate(e0.x, e0.y, (e.x - e0.x) / len, (e.y - e0.y) / len)
        runs = RunTracker.detectAll(s, course)
        assertEquals(2, runs.size)
        assertTrue(runs.all { it.finished })
        println("Run times: " + runs.joinToString { "%d: %.3f".format(it.number, it.time) })
        assertTrue("slow run is slower", runs[1].time > runs[0].time + 0.2)

        // Live delta at the finish should match the time difference
        val f = PathFollower(RefPath(s, runs[0]))
        var lastDelta = 0.0
        for (i in runs[1].startIdx..runs[1].endIdx) if (f.update(s[i].x, s[i].y)) lastDelta = (s[i].t - runs[1].startT) - f.refT
        println("Live delta at finish: %.3f, actual diff %.3f".format(lastDelta, runs[1].time - runs[0].time))
        assertEquals(runs[1].time - runs[0].time, lastDelta, 0.15)

        val a = Analyzer.analyze(s, runs[1], runs[0])
        println("Zones: " + a.zones.joinToString { "%s %.0f-%.0f-%.0f".format(it.name, it.d0, it.dMin, it.d1) })
        a.insights.forEach { println("  • $it") }
        assertTrue("found the corners", a.zones.size >= 3)
        assertTrue("blames corner 2", a.insights[1].startsWith("Corner 2"))

        // Corners named on the course walk replace the numbers, and land on the same corners
        val refPath = RefPath(s, runs[0])
        val names = listOf("Kink", "Hairpin", "Sweeper")
        val marks = a.zones.take(3).mapIndexed { k, z ->
            val i = refPath.ds.indices.minBy { kotlin.math.abs(refPath.ds[it] - z.dMin) }
            val smp = s[runs[0].startIdx + i]
            // a few metres off, like a tablet GPS placement
            com.kimes.daq.course.CornerMark(smp.lat + 6.0 / 111_320.0, smp.lon, names[k])
        }
        val named = Analyzer.analyze(s, runs[1], runs[0], marks)
        println("Named zones: " + named.zones.joinToString { "%s %.0f-%.0f-%.0f".format(it.name, it.d0, it.dMin, it.d1) })
        assertEquals(names, named.zones.map { it.name })
        named.zones.zip(a.zones).forEach { (nz, z) -> assertEquals(z.dMin, nz.dMin, 20.0) }
        assertTrue("still blames the hairpin", named.insights[1].startsWith("Hairpin"))
        // Forgot to name the hairpin: it is still found, called after the corner before it
        val partial = Analyzer.analyze(s, runs[1], runs[0], listOf(marks[0], marks[2]))
        println("Partly named: " + partial.zones.joinToString { it.name })
        assertEquals(listOf("Kink", "After Kink", "Sweeper"), partial.zones.map { it.name })

        val first = Analyzer.analyze(s, runs[0], null)
        first.insights.forEach { println("  [single] $it") }
    }
}
