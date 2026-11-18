package com.kimes.daq.gopro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.kimes.daq.data.Sample
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Reads a video file in place (no copy; GoPro files are gigabytes). */
class ChannelSource(private val ch: FileChannel) : ByteSource {
    override val size: Long = ch.size()
    override fun read(pos: Long, len: Int): ByteArray {
        val n = len.coerceAtMost((size - pos).coerceAtLeast(0).toInt())
        val bb = ByteBuffer.allocate(n)
        var p = pos
        while (bb.hasRemaining()) { val r = ch.read(bb, p); if (r <= 0) break; p += r }
        return bb.array()
    }
}

object VideoImport {
    class Result(val link: VideoLink?, val message: String)

    /** Read the GoPro's GPS track from a picked video and sync it to the session. Runs off the main thread. */
    fun import(ctx: Context, uri: Uri, session: List<Sample>): Result {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "video"
        // Keep access to the file after the app restarts
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val tel = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { Gpmf.read(ChannelSource(it)) }
            }
        } catch (e: Exception) { null }
            ?: return Result(null, "$name: no GoPro telemetry found in this file.")
        return sync(tel, name, uri.toString(), session)
    }

    /** Same, for a file the app downloaded itself (e.g. an .LRV from [Offload]). Runs off the main thread. */
    fun import(file: java.io.File, session: List<Sample>): Result {
        val name = file.name
        val tel = try { FileInputStream(file).channel.use { Gpmf.read(ChannelSource(it)) } } catch (e: Exception) { null }
            ?: return Result(null, "$name: no GoPro telemetry found in this file.")
        return sync(tel, name, Uri.fromFile(file).toString(), session)
    }

    private fun sync(tel: GoProTelemetry, name: String, uri: String, session: List<Sample>): Result =
        when (val r = VideoSync.sync(tel, session)) {
            is VideoSync.Outcome.Fail -> Result(null, "$name: ${r.reason}")
            is VideoSync.Outcome.Ok -> {
                val note = "Synced by GPS time, fine-tuned %+.2f s by speed match (fit ±%.1f mph over %.0f s)".format(r.fineMs / 1000.0, r.rmsMps * 2.237, r.overlapS)
                Result(VideoLink(uri, name, r.startUtc, (tel.durationS * 1000).toLong(), r.fineMs, note), "$name: synced.")
            }
        }
}
