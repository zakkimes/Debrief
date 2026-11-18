package com.kimes.daq.gopro

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Random-access bytes: a video file on disk or in memory (tests). Files can exceed 4 GB. */
interface ByteSource {
    val size: Long
    fun read(pos: Long, len: Int): ByteArray
}

class ArraySource(private val b: ByteArray) : ByteSource {
    override val size get() = b.size.toLong()
    override fun read(pos: Long, len: Int) = b.copyOfRange(pos.toInt(), (pos + len).toInt().coerceAtMost(b.size))
}

/** One GoPro GPS fix: when it is in the video, and when it is in the world. */
data class GoProFix(
    val videoT: Double,   // seconds from the start of the video
    val utcMs: Long,      // GPS UTC time, epoch ms (0 when the camera didn't report it)
    val lat: Double,
    val lon: Double,
    val speed: Double,    // m/s, 2D
    val fix: Int,
)

class GoProTelemetry(val durationS: Double, val fixes: List<GoProFix>)

/**
 * Reads GoPro telemetry (GPMF) from an MP4: walks moov to the track whose sample description is
 * 'gpmd', then decodes each telemetry sample's GPS9 (HERO11 and later; HERO13 records only GPS9)
 * or GPS5 + GPSU (older cameras). Only the small metadata track is read, never the video.
 */
object Gpmf {
    private class Box(val type: String, val start: Long, val headerLen: Int, val size: Long) {
        val body get() = start + headerLen
        val end get() = start + size
    }

    private fun boxes(src: ByteSource, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            val h = ByteBuffer.wrap(src.read(p, 16)).order(ByteOrder.BIG_ENDIAN)
            var size = h.getInt(0).toLong() and 0xFFFFFFFFL
            val type = String(src.read(p + 4, 4), Charsets.ISO_8859_1)
            var hl = 8
            if (size == 1L) { size = h.getLong(8); hl = 16 } else if (size == 0L) size = to - p
            if (size < hl) break
            out += Box(type, p, hl, size)
            p += size
        }
        return out
    }

    private fun child(src: ByteSource, b: Box, type: String) = boxes(src, b.body, b.end).firstOrNull { it.type == type }

    private fun body(src: ByteSource, b: Box): ByteBuffer =
        ByteBuffer.wrap(src.read(b.body, (b.size - b.headerLen).toInt())).order(ByteOrder.BIG_ENDIAN)

    fun read(src: ByteSource): GoProTelemetry? {
        val moov = boxes(src, 0, src.size).firstOrNull { it.type == "moov" } ?: return null
        var duration = 0.0
        child(src, moov, "mvhd")?.let { m ->
            val b = body(src, m)
            duration = if (b.get(0).toInt() == 1) b.getLong(24).toDouble() / b.getInt(20) else (b.getInt(16).toLong() and 0xFFFFFFFFL).toDouble() / b.getInt(12)
        }
        for (trak in boxes(src, moov.body, moov.end).filter { it.type == "trak" }) {
            val mdia = child(src, trak, "mdia") ?: continue
            val minf = child(src, mdia, "minf") ?: continue
            val stbl = child(src, minf, "stbl") ?: continue
            val stsd = child(src, stbl, "stsd") ?: continue
            if (String(src.read(stsd.body + 12, 4), Charsets.ISO_8859_1) != "gpmd") continue
            val mdhd = body(src, child(src, mdia, "mdhd") ?: continue)
            val timescale = if (mdhd.get(0).toInt() == 1) mdhd.getInt(20) else mdhd.getInt(12)
            return GoProTelemetry(duration, samples(src, stbl, timescale))
        }
        return null
    }

    /** Offset, size, start time and duration of each telemetry sample, then the fixes inside. */
    private fun samples(src: ByteSource, stbl: Box, timescale: Int): List<GoProFix> {
        val stsz = body(src, child(src, stbl, "stsz") ?: return emptyList())
        val fixedSize = stsz.getInt(4); val count = stsz.getInt(8)
        val sizes = IntArray(count) { if (fixedSize != 0) fixedSize else stsz.getInt(12 + it * 4) }

        val chunkOffsets: LongArray = child(src, stbl, "co64")?.let { b -> val bb = body(src, b); LongArray(bb.getInt(4)) { bb.getLong(8 + it * 8) } }
            ?: child(src, stbl, "stco")?.let { b -> val bb = body(src, b); LongArray(bb.getInt(4)) { bb.getInt(8 + it * 4).toLong() and 0xFFFFFFFFL } }
            ?: return emptyList()

        // stsc: how many samples sit in each chunk
        val perChunk = IntArray(chunkOffsets.size) { 1 }
        child(src, stbl, "stsc")?.let { b ->
            val bb = body(src, b); val n = bb.getInt(4)
            for (e in 0 until n) {
                val first = bb.getInt(8 + e * 12) - 1; val spc = bb.getInt(12 + e * 12)
                val next = if (e + 1 < n) bb.getInt(8 + (e + 1) * 12) - 1 else chunkOffsets.size
                for (c in first until next.coerceAtMost(chunkOffsets.size)) perChunk[c] = spc
            }
        }
        val offsets = LongArray(count)
        var s = 0
        for (c in chunkOffsets.indices) {
            var p = chunkOffsets[c]
            for (j in 0 until perChunk[c]) { if (s >= count) break; offsets[s] = p; p += sizes[s]; s++ }
        }

        // stts: sample durations
        val starts = DoubleArray(count); val durs = DoubleArray(count)
        child(src, stbl, "stts")?.let { b ->
            val bb = body(src, b); var k = 0; var t = 0L
            for (e in 0 until bb.getInt(4)) {
                val n = bb.getInt(8 + e * 8); val d = bb.getInt(12 + e * 8)
                repeat(n) { if (k < count) { starts[k] = t.toDouble() / timescale; durs[k] = d.toDouble() / timescale; k++; t += d } }
            }
        }

        val out = ArrayList<GoProFix>()
        for (k in 0 until count) {
            if (sizes[k] <= 0 || offsets[k] + sizes[k] > src.size) continue
            decode(ByteBuffer.wrap(src.read(offsets[k], sizes[k])).order(ByteOrder.BIG_ENDIAN), starts[k], durs[k], out)
        }
        return out
    }

    /** Decode one GPMF payload (KLV: 4-char key, type, struct size, repeat, data padded to 4 bytes). */
    fun decode(b: ByteBuffer, t0: Double, dur: Double, out: MutableList<GoProFix>) {
        var scal: DoubleArray? = null
        var gpsu = 0L
        var fixCode = 3
        var p = 0
        val end = b.limit()
        while (p + 8 <= end) {
            val key = String(byteArrayOf(b.get(p), b.get(p + 1), b.get(p + 2), b.get(p + 3)), Charsets.ISO_8859_1)
            val type = b.get(p + 4).toInt().toChar()
            val ss = b.get(p + 5).toInt() and 0xFF
            val rep = b.getShort(p + 6).toInt() and 0xFFFF
            val len = ss * rep
            val data = p + 8
            val next = data + ((len + 3) / 4) * 4
            if (next > end) break
            if (type.code == 0) {
                // Container (DEVC, STRM): descend; SCAL/TYPE are scoped to the stream
                val sub = b.duplicate().order(ByteOrder.BIG_ENDIAN)
                sub.position(data); sub.limit(data + len)
                decode(sub.slice().order(ByteOrder.BIG_ENDIAN), t0, dur, out)
            } else when (key) {
                "SCAL" -> scal = DoubleArray(rep * (ss / sizeOf(type)).coerceAtLeast(1)) { i -> num(b, data + i * sizeOf(type), type) }
                "GPSF" -> fixCode = b.getInt(data)
                "GPSU" -> gpsu = parseGpsu(String(ByteArray(len.coerceAtMost(16)) { b.get(data + it) }, Charsets.ISO_8859_1))
                "GPS9" -> {
                    // lat, lon, alt, 2D speed, 3D speed, days since 2000, seconds since midnight, DOP, fix
                    val sc = scal ?: DoubleArray(9) { 1.0 }
                    for (i in 0 until rep) {
                        val e = data + i * ss
                        if (e + 32 > end) break
                        fun v(j: Int) = b.getInt(e + j * 4).toDouble() / sc.getOrElse(j) { 1.0 }
                        val days = v(5); val secs = v(6)
                        val fx = b.getShort(e + 30).toInt() and 0xFFFF
                        val utc = ((946_684_800.0 + days * 86_400 + secs) * 1000).toLong()
                        out += GoProFix(t0 + dur * i / rep, utc, v(0), v(1), v(3), fx)
                    }
                }
                "GPS5" -> {
                    val sc = scal ?: DoubleArray(5) { 1.0 }
                    for (i in 0 until rep) {
                        val e = data + i * ss
                        fun v(j: Int) = b.getInt(e + j * 4).toDouble() / sc.getOrElse(j) { 1.0 }
                        // GPSU stamps the payload; fixes are spread evenly across the sample
                        val utc = if (gpsu > 0) gpsu + (dur * 1000 * i / rep).toLong() else 0L
                        out += GoProFix(t0 + dur * i / rep, utc, v(0), v(1), v(3), fixCode)
                    }
                }
            }
            p = next
        }
    }

    private fun sizeOf(t: Char) = when (t) { 'b', 'B', 'c' -> 1; 's', 'S' -> 2; 'l', 'L', 'f' -> 4; 'd', 'j', 'J' -> 8; else -> 4 }

    private fun num(b: ByteBuffer, at: Int, t: Char): Double = when (t) {
        'b' -> b.get(at).toDouble(); 'B' -> (b.get(at).toInt() and 0xFF).toDouble()
        's' -> b.getShort(at).toDouble(); 'S' -> (b.getShort(at).toInt() and 0xFFFF).toDouble()
        'l' -> b.getInt(at).toDouble(); 'L' -> (b.getInt(at).toLong() and 0xFFFFFFFFL).toDouble()
        'f' -> b.getFloat(at).toDouble(); 'd' -> b.getDouble(at)
        else -> b.getInt(at).toDouble()
    }

    /** "yymmddhhmmss.sss" to epoch ms (UTC). */
    fun parseGpsu(s: String): Long = try {
        java.time.LocalDateTime.of(
            2000 + s.substring(0, 2).toInt(), s.substring(2, 4).toInt(), s.substring(4, 6).toInt(),
            s.substring(6, 8).toInt(), s.substring(8, 10).toInt(), s.substring(10, 12).toInt(),
        ).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() + (s.substring(12).toDoubleOrNull()?.times(1000)?.toLong() ?: 0L)
    } catch (e: Exception) { 0L }
}
