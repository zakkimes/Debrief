package com.kimes.daq

import com.kimes.daq.course.MapPicture
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs the course-map analysis on a real organizer map in ./pulled (raw ARGB dump; skipped when absent). */
class RealCourseMapTest {
    @Test
    fun analyzesRealMap() {
        val f = File("../pulled/course_map.argb")
        assumeTrue(f.exists())
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val w = b.getInt(0); val h = b.getInt(4)
        val px = IntArray(w * h) { b.getInt(8 + it * 4) }  // BGRA bytes, little-endian = 0xAARRGGBB
        val p = MapPicture(w, h, px)
        val m = p.marks()
        println("Map ${w}x$h: green ${m.green}, orange ${m.orange}, red ${m.red}, lot gray ${p.lotGray}")
        for (v in 0 until p.h step 22) println("|" + (0 until p.w step 11).joinToString("") { u -> if (p.isLot(u, v)) "#" else "." })
    }
}
