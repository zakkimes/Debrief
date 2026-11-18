package com.kimes.daq

import com.kimes.daq.data.Backup
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Test

class BackupTest {
    private val old = "/storage/emulated/0/Android/data/com.kimes.daq/files"
    private val new = "/storage/emulated/0/Android/data/com.kimes.debrief/files"

    @Test
    fun movesPlainPaths() {
        assertEquals("<string>$new/maps/a.png</string>", Backup.movePath("<string>$old/maps/a.png</string>", old, new))
    }

    @Test
    fun movesJsonEscapedPaths() {
        // org.json writes "/" as "\/", which is how the 0.23 tablet migration lost its video links
        val saved = """{"uri":"file:\/\/\/storage\/emulated\/0\/Android\/data\/com.kimes.daq\/files\/videos\/GL010091.LRV"}"""
        val moved = Backup.movePath(saved, old, new)
        assertEquals("file://$new/videos/GL010091.LRV", JSONObject(moved).getString("uri"))
    }
}
