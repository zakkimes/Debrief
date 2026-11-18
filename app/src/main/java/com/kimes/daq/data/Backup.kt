package com.kimes.daq.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything Debrief keeps, in one zip: sessions, courses, signal decoders, linked videos (optional),
 * course overlays and settings. Restoring it on another tablet, or into a reinstalled app (even one
 * with a different package name), puts it all back. Map tiles are left out: they download again.
 */
object Backup {
    private const val EXT = "ext/"
    private const val FILES = "files/"
    private const val PREFS = "prefs/"

    private fun ext(ctx: Context) = ctx.getExternalFilesDir(null)!!
    private fun prefsDir(ctx: Context) = File(ctx.dataDir, "shared_prefs")

    /** Bytes the backup would hold: everything, and of that the videos. */
    fun sizes(ctx: Context): Pair<Long, Long> {
        val all = filesToSave(ctx, true).sumOf { it.second.length() }
        val vids = File(ext(ctx), "videos").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return all to vids
    }

    private fun filesToSave(ctx: Context, videos: Boolean): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        val e = ext(ctx)
        e.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(e).invariantSeparatorsPath
            if (rel.startsWith("tiles/") || (!videos && rel.startsWith("videos/")) || rel.endsWith(".tmp")) return@forEach
            out += (EXT + rel) to f
        }
        ctx.filesDir.walkTopDown().filter { it.isFile }.forEach { f -> out += (FILES + f.relativeTo(ctx.filesDir).invariantSeparatorsPath) to f }
        prefsDir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { out += (PREFS + it.name) to it }
        return out
    }

    /** Write the backup to [out]. [progress] gets 0..1. */
    fun export(ctx: Context, out: OutputStream, videos: Boolean, progress: (Float) -> Unit) {
        val files = filesToSave(ctx, videos)
        val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
        var done = 0L
        ZipOutputStream(out.buffered()).use { z ->
            // Fast over small: sessions are text and squeeze well even so; videos are already compressed
            z.setLevel(1)
            val m = JSONObject().put("format", 1).put("app", ctx.packageName)
                .put("ext", ext(ctx).absolutePath).put("files", ctx.filesDir.absolutePath)
                .put("created", System.currentTimeMillis()).put("videos", videos)
            z.putNextEntry(ZipEntry("manifest.json")); z.write(m.toString().toByteArray()); z.closeEntry()
            val buf = ByteArray(1 shl 16)
            for ((name, f) in files) {
                z.putNextEntry(ZipEntry(name))
                f.inputStream().use { inp -> while (true) { val n = inp.read(buf); if (n < 0) break; z.write(buf, 0, n); done += n; progress(done.toFloat() / total) } }
                z.closeEntry()
            }
        }
    }

    /**
     * Put a backup back. Paths saved inside it (video files, course overlays) are moved to this app's
     * folders. Files with the same name are replaced; anything else already here stays. The app must
     * restart afterwards, as settings and the open session are held in memory.
     */
    fun restore(ctx: Context, input: InputStream, progress: (String) -> Unit): Int {
        var oldExt: String? = null; var oldFiles: String? = null
        val newExt = ext(ctx).absolutePath; val newFiles = ctx.filesDir.absolutePath
        var n = 0
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name
                if (name.contains("..") || e.isDirectory) continue
                if (name == "manifest.json") {
                    val m = JSONObject(z.readBytes().toString(Charsets.UTF_8))
                    if (m.optInt("format") != 1) throw IllegalArgumentException("This isn't a Debrief backup this version can read.")
                    oldExt = m.optString("ext"); oldFiles = m.optString("files")
                    continue
                }
                val dest = when {
                    name.startsWith(EXT) -> File(newExt, name.removePrefix(EXT))
                    name.startsWith(FILES) -> File(newFiles, name.removePrefix(FILES))
                    name.startsWith(PREFS) -> File(prefsDir(ctx), name.removePrefix(PREFS))
                    else -> continue
                }
                dest.parentFile?.mkdirs()
                val text = name.endsWith(".json") || name.startsWith(PREFS)
                if (text && oldExt != null) {
                    // Saved paths point at the old app's folders: point them at this one's
                    var s = z.readBytes().toString(Charsets.UTF_8)
                    oldExt?.takeIf { it.isNotEmpty() }?.let { s = s.replace(it, newExt) }
                    oldFiles?.takeIf { it.isNotEmpty() }?.let { s = s.replace(it, newFiles) }
                    dest.writeText(s)
                } else dest.outputStream().use { z.copyTo(it) }
                n++
                if (n % 20 == 0) progress("$n files")
            }
        }
        if (oldExt == null) throw IllegalArgumentException("This isn't a Debrief backup.")
        return n
    }
}
