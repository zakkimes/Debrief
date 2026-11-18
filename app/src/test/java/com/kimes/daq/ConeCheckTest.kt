package com.kimes.daq

import com.kimes.daq.course.CourseDef
import com.kimes.daq.data.LocalProjection
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import com.kimes.daq.run.ConeCheck
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Wrong-side cone DNF: never on the clean runs from both events; caught when the path is moved. */
class ConeCheckTest {
    private fun load(dir: File): Triple<List<Sample>, List<Run>, List<ConeCheck.Cone>> {
        val o = JSONObject(File(dir, "course.json").readText())
        val c = CourseDef.from(o.getJSONObject("courseDef"))
        val proj = LocalProjection(o.getDouble("lat0"), o.getDouble("lon0"))
        val samples = File(dir, "gps.csv").readLines().drop(1).mapNotNull { Sample.parse(it) }
        val ev = File(dir, "events.csv").readLines().map { it.split(',') }
        val st = ev.filter { it[1].startsWith("run_start") }.map { it[0].toDouble() }; val en = ev.filter { it[1].startsWith("run_end") }.map { it[0].toDouble() }
        val runs = st.zip(en).mapIndexed { n, p -> val si = samples.indexOfFirst { it.t >= p.first }; val ei = samples.indexOfLast { it.t <= p.second }; Run(n + 1, si, ei, samples[si].t, samples[ei].t, true) }
        val cones = c.cones.mapIndexedNotNull { i, k -> k.passBearing?.let { b -> val r = b * PI / 180; ConeCheck.Cone(i, proj.x(k.lon), proj.y(k.lat), sin(r), cos(r)) } }
        return Triple(samples, runs, cones)
    }

    @Test
    fun cleanRunsAreNotDnf() {
        val dirs = listOf("../pulled/event2/20261115_100917", "../pulled/20261114_105657", "../pulled/20261114_151843").map(::File).filter { it.isDirectory && File(it, "course.json").exists() }
        assumeTrue(dirs.isNotEmpty())
        for (d in dirs) {
            val (samples, runs, cones) = load(d)
            for (r in runs) {
                // How close the call is: the smallest margin that would still pass
                val tight = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5).firstOrNull { ConeCheck.wrongSide(samples, r, cones, margin = it) == null }
                val m0 = ConeCheck.wrongSide(samples, r, cones, margin = 0.0)
                println("${d.name} run ${r.number}: " + (m0?.let { "at margin 0 crosses cone ${it.cone + 1} at %.1f m".format(it.byM) } ?: "clear even at margin 0") + "; passes from margin $tight")
                // 11-14 afternoon: cone 13 was moved at lunch but not in the app; both runs went 5-6 m past
                // its old wrong side. The driver taps "Not a DNF" and the app ignores that cone for the day.
                if (d.name == "20261114_151843") {
                    assertEquals(12, ConeCheck.wrongSide(samples, r, cones)?.cone)
                    assertNull(ConeCheck.wrongSide(samples, r, cones.filter { it.index != 12 }))
                } else assertNull("${d.name} run ${r.number}", ConeCheck.wrongSide(samples, r, cones))
            }
        }
    }

    @Test
    fun aRunMovedAcrossACone() {
        val d = File("../pulled/event2/20261115_100917"); assumeTrue(d.isDirectory)
        val (samples, runs, cones) = load(d)
        val r = runs[7]
        // Put one cone 4 m to the run's other side, as if the driver went around it the wrong way
        val k = (r.startIdx + r.endIdx) / 2; val s = samples[k]
        val c = cones.minBy { Math.hypot(it.x - s.x, it.y - s.y) }
        val moved = cones.map { if (it == c) it.copy(x = s.x + it.ux * 3.0, y = s.y + it.uy * 3.0) else it }
        assertNotNull(ConeCheck.wrongSide(samples, r, moved))
    }
}
