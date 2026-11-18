package com.kimes.daq

import com.kimes.daq.gopro.CameraMedia
import com.kimes.daq.gopro.MediaList
import org.junit.Assert.assertEquals
import org.junit.Test

class OffloadTest {
    private val json = """
        {"id":"1","media":[{"d":"100GOPRO","fs":[
          {"n":"GX010001.MP4","cre":"1763000000","mod":"1763000100","s":"500000000"},
          {"n":"GX010002.MP4","cre":"1763000500","mod":"1763000600","s":"90000000"},
          {"n":"GOPR0003.JPG","cre":"1763000700","s":"3000000"},
          {"n":"GX010004.MP4","cre":"1763001000","s":"80000000"}]}]}
    """

    @Test fun parsesList() {
        val l = MediaList.parse(json)
        assertEquals(4, l.size)
        assertEquals(CameraMedia("100GOPRO", "GX010002.MP4", 1763000500_000L, 90000000L), l[1])
    }

    @Test fun emptyOrMissingMedia() {
        assertEquals(0, MediaList.parse("""{"media":[]}""").size)
        assertEquals(0, MediaList.parse("{}").size)
    }

    @Test fun choosesNewVideosOnly() {
        val l = MediaList.parse(json)
        val c = MediaList.choose(l, 1763000400_000L)
        assertEquals(listOf("GX010002.MP4", "GX010004.MP4"), c.map { it.name })
    }

    @Test fun slackCatchesSlightlyEarlyClock() {
        val c = MediaList.choose(MediaList.parse(json), 1763000520_000L)
        assertEquals(listOf("GX010002.MP4", "GX010004.MP4"), c.map { it.name }) // 20 s earlier is within slack
        assertEquals(listOf("GX010004.MP4"), MediaList.choose(MediaList.parse(json), 1763000600_000L).map { it.name })
    }

    @Test fun oneRunsVideoNotTheNewest() {
        // Asking for the first run's video must not bring the later runs' (the newest) instead
        val l = MediaList.parse(json)
        assertEquals(listOf("GX010002.MP4"), MediaList.choose(l, 1763000400_000L, untilUtcMs = 1763000550_000L).map { it.name })
    }

    @Test fun capsCount() {
        assertEquals(1, MediaList.choose(MediaList.parse(json), 0, max = 1).size)
        assertEquals("GX010004.MP4", MediaList.choose(MediaList.parse(json), 0, max = 1)[0].name)
    }

    @Test fun lrvNames() {
        assertEquals("GL010123.LRV", MediaList.lrvName("GX010123.MP4"))
        assertEquals("GL020123.LRV", MediaList.lrvName("GH020123.mp4"))
        assertEquals("clip.LRV", MediaList.lrvName("clip.MP4"))
    }
}
