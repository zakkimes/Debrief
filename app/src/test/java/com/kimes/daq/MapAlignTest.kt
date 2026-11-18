package com.kimes.daq

import com.kimes.daq.course.CourseDef
import com.kimes.daq.course.GeoLine
import com.kimes.daq.course.LineKind
import com.kimes.daq.course.MapAlign
import com.kimes.daq.course.MapOverlay
import com.kimes.daq.course.MapPicture
import com.kimes.daq.course.distM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapAlignTest {
    private val W = 320; private val H = 220
    private val white = 0xFFFFFFFF.toInt(); private val lot = 0xFFE3E3E3.toInt(); private val island = 0xFFC8C8C8.toInt()
    private val green = 0xFF2EA043.toInt(); private val red = 0xFFD7263D.toInt(); private val black = 0xFF111111.toInt()

    private fun inLot(u: Double, v: Double) = u in 30.0..290.0 && v in 40.0..180.0 && !inIsland(u, v)
    private fun inIsland(u: Double, v: Double) = u in 120.0..200.0 && v in 80.0..130.0

    private fun picture(): MapPicture {
        val px = IntArray(W * H) { i ->
            val u = (i % W).toDouble(); val v = (i / W).toDouble()
            when {
                inIsland(u, v) -> island
                inLot(u, v) -> lot
                else -> white
            }
        }
        // Timing marks: short vertical colored lines, plus a few black cone dots
        for (v in 140..156) { px[v * W + 60] = green; px[v * W + 61] = green; px[v * W + 250] = red; px[v * W + 251] = red }
        for ((u, v) in listOf(90 to 60, 100 to 62, 240 to 70)) for (du in 0..2) for (dv in 0..2) px[(v + dv) * W + u + du] = black
        return MapPicture(W, H, px)
    }

    // Ground truth: 0.8 m per pixel, rotated 25 degrees, anchored at a lot in Alabama
    private val truth = MapOverlay("x", 60.5, 148.0, 33.5290, -86.6280, 250.5, 148.0, 0.0, 0.0).let { base ->
        val g = MapOverlay("x", 0.0, 0.0, 33.5290, -86.6280, 1.0, 0.0, 33.5290, -86.6280 + 0.8 / (111_320.0 * kotlin.math.cos(33.529 * Math.PI / 180)))
            .nudged(1, 1, dRotDeg = 25.0)
        // Re-express with anchors at the two marks
        val a = g.toGround(60.5, 148.0); val b = g.toGround(250.5, 148.0)
        base.copy(lat1 = a.first, lon1 = a.second, lat2 = b.first, lon2 = b.second)
    }

    private fun satPavement(lat: Double, lon: Double): Double {
        val (u, v) = truth.toPixel(lat, lon)
        return if (inLot(u, v)) 0.9 else 0.1
    }

    @Test
    fun pictureAnalysisFindsMarksAndLot() {
        val p = picture()
        val m = p.marks()
        assertNotNull(m.green); assertNotNull(m.red)
        assertEquals(60.5, m.green!!.first, 0.6); assertEquals(148.0, m.green!!.second, 0.6)
        assertEquals(0.89f, p.lotGray, 0.011f)
        assertTrue(p.isLot(50, 50)); assertTrue(!p.isLot(150, 100)); assertTrue(!p.isLot(5, 5)); assertTrue(!p.isLot(91, 61))
    }

    @Test
    fun overlayMathRoundTrips() {
        val (lat, lon) = truth.toGround(200.0, 50.0)
        val (u, v) = truth.toPixel(lat, lon)
        assertEquals(200.0, u, 1e-6); assertEquals(50.0, v, 1e-6)
        assertEquals(0.8, truth.metersPerPixel, 1e-4)
    }

    @Test
    fun autoAlignFromMarksThenRefinesToPavement() {
        val p = picture()
        // The course's lines (as from RaceChrono) are 3 m off where the map draws them
        val s = truth.toGround(60.5 + 3.75, 148.0); val f = truth.toGround(250.5, 148.0 - 3.75)
        val course = CourseDef("c", "test", lines = listOf(
            GeoLine(LineKind.START, s.first, s.second, 0.0), GeoLine(LineKind.FINISH, f.first, f.second, 0.0),
        ))
        val marksOnly = MapAlign.auto(p, course, "x", null)!!
        val refined = MapAlign.auto(p, course, "x", ::satPavement)!!
        fun err(o: MapOverlay) = listOf(40.0 to 50.0, 280.0 to 170.0, 160.0 to 100.0).maxOf { (u, v) ->
            val a = o.toGround(u, v); val b = truth.toGround(u, v); distM(a.first, a.second, b.first, b.second)
        }
        println("Placement error: marks only %.2f m, refined %.2f m · %s".format(err(marksOnly), err(refined), refined.how))
        assertTrue("refining improves the fit", err(refined) < err(marksOnly))
        assertTrue("refined within 2 m", err(refined) < 2.0)
    }
}
