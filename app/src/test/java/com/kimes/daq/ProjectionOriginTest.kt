package com.kimes.daq

import com.kimes.daq.data.Sample
import com.kimes.daq.data.projectionOrigin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A session whose saved origin was lost (written as 0,0 after reopening it) gets it back from its
 * own samples: each one holds both lat/lon and the x/y the origin produced.
 */
class ProjectionOriginTest {
    @Test
    fun originComesBackFromTheRecordedSamples() {
        val dir = File("../pulled/event2/20261115_100917")
        assumeTrue(dir.isDirectory)
        val saved = JSONObject(File(dir, "course.json").readText())
        val s = File(dir, "gps.csv").useLines { l -> l.drop(1).mapNotNull { Sample.parse(it) }.first { it.lat != 0.0 && (it.x != 0.0 || it.y != 0.0) } }
        val (lat0, lon0) = projectionOrigin(s.lat, s.lon, s.x, s.y)
        // Within a centimetre or so of the real origin
        assertEquals(saved.getDouble("lat0"), lat0, 2e-7)
        assertEquals(saved.getDouble("lon0"), lon0, 2e-7)
    }
}
