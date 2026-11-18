package com.kimes.daq

import com.kimes.daq.course.CourseDef
import com.kimes.daq.data.LocalProjection
import com.kimes.daq.run.OptimalLine
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Solves the line on a course file (skipped when absent) and prints each cone's margin on its pass side. */
class DemoCourseLineTest {
    @Test
    fun marginsOnDemoCourse() {
        val f = File(System.getenv("DEMO_COURSE") ?: "../pulled/demo_course.json")
        assumeTrue(f.exists())
        val c = CourseDef.from(JSONObject(f.readText()))
        val (lat0, lon0) = c.center()!!
        val p = LocalProjection(lat0, lon0)
        val cones = c.cones.filter { it.passBearing != null }.map {
            val r = it.passBearing!! * PI / 180
            OptimalLine.ConeSide(p.x(it.lon), p.y(it.lat), sin(r), cos(r))
        }
        val r = OptimalLine.solve(c.start!!.gate(p), c.finish!!.gate(p), cones)
        println("time %.2f s, worst %.2f m".format(r.time, r.worstClearance))
        for ((i, k) in cones.withIndex()) {
            var best = Double.MAX_VALUE; var side = 0.0
            for (j in r.xs.indices) { val d = hypot(r.xs[j] - k.x, r.ys[j] - k.y); if (d < best) { best = d; side = (r.xs[j] - k.x) * k.ux + (r.ys[j] - k.y) * k.uy } }
            println("cone %d: closest %.2f m, along pass side %.2f m".format(i, best, side))
        }
    }
}
