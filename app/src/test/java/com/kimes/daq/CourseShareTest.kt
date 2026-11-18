package com.kimes.daq

import com.kimes.daq.course.Cone
import com.kimes.daq.course.CornerMark
import com.kimes.daq.course.CourseDef
import com.kimes.daq.course.GeoLine
import com.kimes.daq.course.LineKind
import com.kimes.daq.course.MapOverlay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A course shared from one tablet and imported on another comes through whole, under a new id. */
class CourseShareTest {
    private val course = CourseDef(
        "20261115_080000", "ALSCCA Points10.2",
        lines = listOf(GeoLine(LineKind.START, 33.5287, -86.6294, 90.0), GeoLine(LineKind.FINISH, 33.5304, -86.6261, 270.0)),
        cones = listOf(Cone(33.529, -86.628, 45.0), Cone(33.5291, -86.6281)),
        corners = listOf(CornerMark(33.5286, -86.6275, "Chicane"), CornerMark(33.5291, -86.6276, "Horseshoe")),
    )

    @Test
    fun roundTripKeepsLinesConesAndCorners() {
        val text = CourseDef.exportJson(course, null)
        val back = CourseDef.importJson(text, "20261117_120000") { null }
        assertNotNull(back)
        assertEquals("20261117_120000", back!!.id)
        assertEquals(course.name, back.name)
        assertEquals(course.lines, back.lines)
        assertEquals(course.cones, back.cones)
        assertEquals(course.corners, back.corners)
        assertNull(back.overlay)
    }

    @Test
    fun mapPictureTravelsInsideTheFile() {
        val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
        val withMap = course.copy(overlay = MapOverlay("/data/elsewhere/map.png", 0.1, 0.2, 33.5, -86.6, 0.8, 0.9, 33.51, -86.61))
        var saved: ByteArray? = null
        val back = CourseDef.importJson(CourseDef.exportJson(withMap, png), "x") { b -> saved = b; "/here/x.png" }
        assertArrayEquals(png, saved)
        assertEquals("/here/x.png", back!!.overlay!!.file)
    }

    @Test
    fun notACourseIsRejected() {
        assertNull(CourseDef.importJson("{\"hello\": 1}", "x") { null })
        assertNull(CourseDef.importJson("not json", "x") { null })
    }
}
