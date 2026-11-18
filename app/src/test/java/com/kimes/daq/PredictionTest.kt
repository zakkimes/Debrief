package com.kimes.daq

import com.kimes.daq.course.CourseDef
import com.kimes.daq.data.LocalProjection
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import com.kimes.daq.run.CarModel
import com.kimes.daq.run.CarProfile
import com.kimes.daq.run.Drive
import com.kimes.daq.run.OptimalLine
import com.kimes.daq.run.TireType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Predicted times against the second event (ALSCCA Points 10.2, best run 45.88 s). The old
 * minimum-curvature line with a stock car said 57.9 s.
 */
class PredictionTest {
    private val dir = File("../pulled/event2/20261115_100917")
    private val nd = CarProfile("Mazda MX-5 Miata ND3 (2024-)", 2340, 181, Drive.RWD, TireType.EXTREME)

    @Test
    fun predictionsMatchTheEvent() {
        assumeTrue(dir.isDirectory)
        val c = CourseDef.from(JSONObject(File(dir, "course.json").readText()).getJSONObject("courseDef"))
        val samples = File(dir, "gps.csv").readLines().drop(1).mapNotNull { Sample.parse(it) }
        val ev = File(dir, "events.csv").readLines().map { it.split(',') }
        val st = ev.filter { it[1].startsWith("run_start") }.map { it[0].toDouble() }; val en = ev.filter { it[1].startsWith("run_end") }.map { it[0].toDouble() }
        val runs = st.zip(en).mapIndexed { n, p -> val si = samples.indexOfFirst { it.t >= p.first }; val ei = samples.indexOfLast { it.t <= p.second }; Run(n + 1, si, ei, samples[si].t, samples[ei].t, true) }
        val (lat0, lon0) = c.center()!!
        val proj = LocalProjection(lat0, lon0)
        val cones = c.cones.filter { it.passBearing != null }.map { val r = it.passBearing!! * PI / 180; OptimalLine.ConeSide(proj.x(it.lon), proj.y(it.lat), sin(r), cos(r)) }
        fun xy(ss: List<Sample>) = OptimalLine.smoothPath(ss.map { proj.x(it.lon) }.toDoubleArray(), ss.map { proj.y(it.lat) }.toDoubleArray(), 50)
        val best = runs.minBy { it.time }

        // Before any runs: the car profile alone, on the line the search finds from the geometric one
        val prior = nd.toCar()
        val geo = OptimalLine.solve(c.start!!.gate(proj), c.finish!!.gate(proj), cones, prior, iterations = 2500)
        val pre = OptimalLine.refine(geo.xs, geo.ys, cones, prior, iterations = 9000)
        println("before runs: geometric %.2f, searched %.2f (best run %.2f)".format(geo.time, pre.time, best.time))
        assertEquals(best.time, pre.time, best.time * 0.04)

        // The profile car driven along the best run's own path lands close to that run's time
        val bp = xy(samples.subList(best.startIdx, best.endIdx + 1))
        assertEquals(best.time, OptimalLine.evaluate(bp.first, bp.second, prior).time, best.time * 0.04)

        // Measured from the runs: grip near the 1.2 g the driver confirms, and the light braking the data shows
        val car = CarModel.fromRuns(runs.map { samples.subList(it.startIdx, it.endIdx + 1) }, bp, best.time, prior)
        println("measured: $car")
        assertTrue(car.latG in 1.0..1.3)
        assertTrue(car.brakeG in 0.55..0.8)
        // The car learned on Sunday predicts both events' courses from the cones alone (best runs 45.88 and 46.28)
        for ((d, actual) in listOf("../pulled/event2/20261115_100917" to 45.88, "../pulled/20261114_105657" to 46.28)) {
            val cc = CourseDef.from(JSONObject(File(d, "course.json").readText()).getJSONObject("courseDef"))
            val (la, lo) = cc.center()!!; val pj = LocalProjection(la, lo)
            val cs = cc.cones.filter { it.passBearing != null }.map { val r = it.passBearing!! * PI / 180; OptimalLine.ConeSide(pj.x(it.lon), pj.y(it.lat), sin(r), cos(r)) }
            for ((name, cr) in listOf("profile" to prior, "learned" to car)) {
                val g = OptimalLine.solve(cc.start!!.gate(pj), cc.finish!!.gate(pj), cs, cr, iterations = 2500)
                val rf = OptimalLine.refine(g.xs, g.ys, cs, cr, iterations = 9000)
                println("${cc.name} $name car: %.2f (best run %.2f)".format(rf.time, actual))
                if (name == "learned") assertEquals(actual, rf.time, actual * 0.03)
            }
        }
        // and every other run, driven by that car along its own path, comes out no slower than it was
        for (r in runs) {
            val p = xy(samples.subList(r.startIdx, r.endIdx + 1))
            val t = OptimalLine.evaluate(p.first, p.second, car).time
            assertTrue("run ${r.number} model %.2f vs %.2f".format(t, r.time), t <= r.time + 0.3 && t >= r.time - 4.0)
        }
    }
}
