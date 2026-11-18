package com.kimes.daq

import com.kimes.daq.course.MapPicture
import com.kimes.daq.course.MapSearch
import com.kimes.daq.course.PavementGrid
import com.kimes.daq.course.distM
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The shape search places a map with no timing lines: lot plus asymmetric islands, rotated, scaled and shifted. */
class MapSearchTest {
    private val W = 400; private val H = 260
    private fun lot(u: Double, v: Double) = u in 40.0..360.0 && v in 50.0..220.0 && !island(u, v)
    private fun island(u: Double, v: Double) = (u in 90.0..170.0 && v in 90.0..140.0) || (u in 240.0..300.0 && v in 150.0..200.0) || (u in 300.0..340.0 && v in 70.0..100.0)

    @Test
    fun findsRotatedScaledShiftedMap() {
        val px = IntArray(W * H) { i ->
            val u = (i % W).toDouble(); val v = (i / W).toDouble()
            when { lot(u, v) -> 0xFFE5E5E5.toInt(); island(u, v) -> 0xFFC9C9C9.toInt(); else -> 0xFFFFFFFF.toInt() }
        }
        val pic = MapPicture(W, H, px)
        // Truth: 0.7 m per pixel, rotated 37 degrees, lot center 45 m east and 30 m south of the seed
        val rot = 37 * PI / 180; val mpp = 0.7; val tx = 45.0; val ty = -30.0
        val cu = 200.0; val cv = 135.0
        fun truthPixel(x: Double, y: Double): Pair<Double, Double> {
            val dx = (x - tx) / mpp; val dy = (y - ty) / mpp
            val du = dx * cos(-rot) - dy * sin(-rot); val dv = dx * sin(-rot) + dy * cos(-rot)
            return (cu + du) to (cv - dv)
        }
        val lat0 = 33.53; val lon0 = -86.62
        val mLat = 111_320.0; val mLon = 111_320.0 * cos(lat0 * PI / 180)
        val grid = PavementGrid.build(lat0, lon0, 300.0, 2.0) { lat, lon ->
            val (u, v) = truthPixel((lon - lon0) * mLon, (lat - lat0) * mLat)
            when { lot(u, v) -> 0.85; island(u, v) -> 0.15; else -> 0.3 }
        }
        val t0 = System.currentTimeMillis()
        val r = MapSearch.search(pic, grid, "x")
        println("Search took ${System.currentTimeMillis() - t0} ms")
        assertNotNull(r)
        val o = r!!.overlay
        val err = listOf(60.0 to 60.0, 340.0 to 210.0, 200.0 to 135.0).maxOf { (u, v) ->
            val (a1, a2) = o.toGround(u, v)
            val gx = run { val du = u - cu; val dv = -(v - cv); (du * cos(rot) - dv * sin(rot)) * mpp + tx }
            val gy = run { val du = u - cu; val dv = -(v - cv); (du * sin(rot) + dv * cos(rot)) * mpp + ty }
            distM(a1, a2, lat0 + gy / mLat, lon0 + gx / mLon)
        }
        println("Placement error %.2f m, rotation %.1f°, %.3f m/px · %s".format(err, o.rotationDeg, o.metersPerPixel, o.how))
        assertTrue("within 3 m", err < 3.0)
    }
}
