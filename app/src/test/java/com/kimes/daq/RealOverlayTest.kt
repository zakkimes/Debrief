package com.kimes.daq

import com.kimes.daq.course.MapAlign
import com.kimes.daq.course.MapPicture
import com.kimes.daq.course.MapSearch
import com.kimes.daq.course.PavementGrid
import com.kimes.daq.geo.Merc
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Places the real organizer map (page 6) on real Barber satellite imagery, both as raw ARGB dumps in
 * ./pulled (skipped when absent), and writes a blended picture to check by eye.
 */
class RealOverlayTest {
    private fun raw(f: File, header: Int): Triple<IntArray, IntArray, IntArray> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val hdr = IntArray(header) { b.getInt(it * 4) }
        val w = hdr[0]; val h = hdr[1]
        return Triple(hdr, IntArray(w * h) { b.getInt(header * 4 + it * 4) }, intArrayOf(w, h))
    }

    @Test
    fun placesPage6OnBarber() {
        val mapF = File("../pulled/course_map.argb"); val satF = File("../pulled/barber_mosaic.argb")
        assumeTrue(mapF.exists() && satF.exists())
        val (_, mapPx, mapWh) = raw(mapF, 2)
        val pic = MapPicture(mapWh[0], mapWh[1], mapPx)
        val (hdr, sat, satWh) = raw(satF, 4)
        val sw = satWh[0]; val sh = satWh[1]; val tx0 = hdr[2]; val ty0 = hdr[3]
        val z = 18; val n = (1 shl z).toDouble()
        fun satPixel(lat: Double, lon: Double): Int? {
            val px = (Merc.x(lon) * n / 256 - tx0) * 256; val py = (Merc.y(lat) * n / 256 - ty0) * 256
            val i = px.toInt(); val j = py.toInt()
            return if (i in 0 until sw && j in 0 until sh) sat[j * sw + i] else null
        }
        fun latOf(py: Double) = Merc.lat((ty0 + py / 256) * 256 / n)
        fun lonOf(px: Double) = Merc.lon((tx0 + px / 256) * 256 / n)
        // Seed: roughly the middle of the lot, as if the map view were panned to it
        val seedLat = latOf(2240.0); val seedLon = lonOf(1900.0)
        val t0 = System.currentTimeMillis()
        val grid = PavementGrid.build(seedLat, seedLon, 450.0, 2.0) { la, lo -> satPixel(la, lo)?.let { MapAlign.pavementLikeness(it) } }
        val t1 = System.currentTimeMillis()
        val r = MapSearch.search(pic, grid, "page6", searchRadiusM = 180.0)!!
        val t2 = System.currentTimeMillis()
        val o = r.overlay
        println("Grid ${t1 - t0} ms, search ${t2 - t1} ms · rotation %.1f°, %.3f m/px (%.0f ft across), runner-up gap %.0f m · %s"
            .format(o.rotationDeg, o.metersPerPixel, pic.w * o.metersPerPixel * 3.28, r.runnerUpGapM, o.how))
        // Checked by eye against the satellite photo: about 36 degrees, 0.43 m per pixel
        org.junit.Assert.assertEquals(36.0, o.rotationDeg, 3.0)
        org.junit.Assert.assertEquals(0.43, o.metersPerPixel, 0.03)

        render(o, pic, sat, sw, sh, tx0, ty0, seedLat, seedLon, "../pulled/overlay_check.argb")
    }

    private fun render(o: com.kimes.daq.course.MapOverlay, pic: MapPicture, sat: IntArray, sw: Int, sh: Int, tx0: Int, ty0: Int, seedLat: Double, seedLon: Double, path: String) {
        val n = (1 shl 18).toDouble()
        fun latOf(py: Double) = Merc.lat((ty0 + py / 256) * 256 / n)
        fun lonOf(px: Double) = Merc.lon((tx0 + px / 256) * 256 / n)
        // Blend for eyeballing: crop around the seed, map at 55% over the satellite
        val cx = ((Merc.x(seedLon) * n / 256 - tx0) * 256).toInt(); val cy = ((Merc.y(seedLat) * n / 256 - ty0) * 256).toInt()
        val cw = 1400; val ch = 1100
        val out = IntArray(cw * ch)
        for (j in 0 until ch) for (i in 0 until cw) {
            val sx = (cx - cw / 2 + i).coerceIn(0, sw - 1); val sy = (cy - ch / 2 + j).coerceIn(0, sh - 1)
            val s = sat[sy * sw + sx]
            val (u, v) = o.toPixel(latOf(sy.toDouble()), lonOf(sx.toDouble()))
            val ui = u.toInt(); val vi = v.toInt()
            out[j * cw + i] = if (ui in 0 until pic.w && vi in 0 until pic.h) {
                val m = pic.at(ui, vi)
                val white = (m shr 16 and 0xFF) > 245 && (m shr 8 and 0xFF) > 245 && (m and 0xFF) > 245
                if (white) s else blend(s, m, 0.55)
            } else s
        }
        val bb = ByteBuffer.allocate(8 + out.size * 4).order(ByteOrder.LITTLE_ENDIAN).putInt(cw).putInt(ch)
        out.forEach { bb.putInt(it) }
        File(path).writeBytes(bb.array())
    }

    private fun blend(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
