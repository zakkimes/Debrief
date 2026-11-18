package com.kimes.daq

import com.kimes.daq.data.Sample
import com.kimes.daq.gopro.ArraySource
import com.kimes.daq.gopro.Gpmf
import com.kimes.daq.gopro.VideoSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.sin

class GoProSyncTest {
    // ---- tiny MP4 writer: just enough boxes for a GPMF track -----------------------------------
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()
    }
    private fun ints(vararg v: Int) = ByteBuffer.allocate(v.size * 4).apply { v.forEach { putInt(it) } }.array()
    private fun klv(key: String, type: Char, structSize: Int, repeat: Int, data: ByteArray): ByteArray {
        val padded = data.copyOf(((data.size + 3) / 4) * 4)
        return ByteBuffer.allocate(8 + padded.size).put(key.toByteArray()).put(type.code.toByte()).put(structSize.toByte())
            .putShort(repeat.toShort()).put(padded).array()
    }

    /** One GPMF payload holding [n] GPS9 fixes. */
    private fun gps9Payload(fixes: List<DoubleArray>): ByteArray {
        val scal = ints(10_000_000, 10_000_000, 1000, 1000, 100, 1, 1000).let { s ->
            ByteBuffer.allocate(36).put(s).putInt(100).putInt(1).array()
        }
        val data = ByteBuffer.allocate(32 * fixes.size)
        for (f in fixes) {
            // f: lat, lon, speed, days, secs
            data.putInt((f[0] * 1e7).toInt()).putInt((f[1] * 1e7).toInt()).putInt(200_000)
                .putInt((f[2] * 1000).toInt()).putInt((f[2] * 100).toInt())
                .putInt(f[3].toInt()).putInt((f[4] * 1000).toInt())
                .putShort(120).putShort(3)
        }
        val strm = klv("SCAL", 'l', 4, 9, scal) + klv("TYPE", 'c', 1, 9, "lllllllSS".toByteArray()) + klv("GPS9", '?', 32, fixes.size, data.array())
        val devc = klv("STRM", 0.toChar(), 1, strm.size, strm)
        return klv("DEVC", 0.toChar(), 1, devc.size, devc)
    }

    private fun mp4(payloads: List<ByteArray>, timescale: Int, sampleDur: Int): ByteArray {
        // Layout: ftyp, mdat (payloads), moov. Chunk offsets point into mdat.
        val ftyp = box("ftyp", "mp41".toByteArray(), ints(0))
        val mdatBody = payloads.fold(ByteArray(0)) { a, b -> a + b }
        val mdat = box("mdat", mdatBody)
        var off = ftyp.size + 8
        val offsets = payloads.map { val o = off; off += it.size; o }
        val n = payloads.size
        val stsd = box("stsd", ints(0, 1), box("gpmd", ByteArray(8)))
        val stts = box("stts", ints(0, 1, n, sampleDur))
        val stsz = box("stsz", ints(0, 0, n), ints(*payloads.map { it.size }.toIntArray()))
        val stsc = box("stsc", ints(0, 1, 1, 1, 1))
        val stco = box("stco", ints(0, n), ints(*offsets.toIntArray()))
        val stbl = box("stbl", stsd, stts, stsz, stsc, stco)
        val mdhd = box("mdhd", ints(0, 0, 0, timescale, n * sampleDur, 0))
        val trak = box("trak", box("mdia", mdhd, box("minf", stbl)))
        val mvhd = box("mvhd", ints(0, 0, 0, 1000, n * 1000))
        val moov = box("moov", mvhd, trak)
        return ByteArrayOutputStream().apply { write(ftyp); write(mdat); write(moov) }.toByteArray()
    }

    // ---- the scenario ------------------------------------------------------------------------
    private val utc0 = 1_794_441_600_000L // 2026-11-12 00:00 UTC
    private fun speedAt(s: Double) = 12 + 8 * sin(s * 0.7) + 4 * sin(s * 1.9) // m/s, wiggly like an autocross run

    @Test
    fun readsGps9AndSyncsToTheSession() {
        // Video starts 30.000 s into the session. The camera's GPS timestamps lag reality by 0.32 s.
        val videoStart = utc0 + 30_000
        val lag = 320L
        val days = ((utc0 / 1000 - 946_684_800) / 86_400).toDouble()
        val payloads = (0 until 40).map { sec ->
            gps9Payload((0 until 10).map { i ->
                val vt = sec + i / 10.0                   // video time of this fix
                val trueUtc = videoStart + (vt * 1000).toLong()
                val stamped = trueUtc - lag
                val secsOfDay = (stamped / 1000.0) - (946_684_800 + days * 86_400)
                doubleArrayOf(33.52 + vt * 1e-5, -86.62, speedAt((trueUtc - utc0) / 1000.0), days, secsOfDay)
            })
        }
        val file = mp4(payloads, 1000, 1000)
        val tel = Gpmf.read(ArraySource(file))!!
        assertEquals(400, tel.fixes.size)
        assertEquals(40.0, tel.durationS, 1e-9)
        assertEquals(33.52, tel.fixes[0].lat, 1e-6)
        assertEquals(speedAt(30.0), tel.fixes[0].speed, 0.01)

        // RaceBox session at 25 Hz with correct UTC
        val session = (0 until 25 * 120).map { k ->
            val s = k / 25.0
            Sample(s, 33.52, -86.62, 0.0, 0.0, speedAt(s), 0.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.0, 0.0, 20, 3, utc0 + (s * 1000).toLong())
        }
        val r = VideoSync.sync(tel, session)
        println("Sync: $r")
        assertTrue(r is VideoSync.Outcome.Ok)
        r as VideoSync.Outcome.Ok
        assertEquals("video start found to within 40 ms", videoStart.toDouble(), r.startUtc.toDouble(), 40.0)
        assertEquals("speed match corrected the camera's lag", lag.toDouble(), r.fineMs.toDouble(), 40.0)
    }

    @Test
    fun explainsWhyItCannotSync() {
        val days = ((utc0 / 1000 - 946_684_800) / 86_400).toDouble()
        val file = mp4((0 until 5).map { sec -> gps9Payload((0 until 10).map { i -> doubleArrayOf(33.5, -86.6, 10.0, days, 100.0 + sec + i / 10.0) }) }, 1000, 1000)
        val tel = Gpmf.read(ArraySource(file))!!
        val oldSession = (0 until 500).map { Sample(it / 25.0, 0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 20, 3) }
        val r = VideoSync.sync(tel, oldSession)
        assertTrue(r is VideoSync.Outcome.Fail && r.reason.contains("no GPS time"))
    }

    @Test
    fun parsesGpsu() {
        assertEquals(1_794_441_600_500L, Gpmf.parseGpsu("261112000000.500"))
    }
}
