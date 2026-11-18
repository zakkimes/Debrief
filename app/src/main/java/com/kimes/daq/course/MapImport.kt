package com.kimes.daq.course

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.kimes.daq.geo.Tiles
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** Brings an organizer's course map (picture or PDF page) into a course and places it on the satellite view. */
object MapImport {
    private const val MAX_PX = 1600

    fun pdfPageCount(ctx: Context, uri: Uri): Int = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { PdfRenderer(it).use { r -> r.pageCount } } ?: 0
    } catch (e: Exception) { 0 }

    /** Render a PDF page or decode a picture, scaled to at most 1600 px on the long side. */
    fun load(ctx: Context, uri: Uri, pdfPage: Int?): Bitmap? = try {
        if (pdfPage != null) {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { r ->
                    r.openPage(pdfPage.coerceIn(0, r.pageCount - 1)).use { page ->
                        val k = MAX_PX.toDouble() / max(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * k).toInt(), (page.height * k).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } else {
            val raw = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            raw?.let {
                val k = MAX_PX.toDouble() / max(it.width, it.height)
                if (k < 1) Bitmap.createScaledBitmap(it, (it.width * k).toInt(), (it.height * k).toInt(), true) else it
            }
        }
    } catch (e: Exception) { null }

    fun file(ctx: Context, courseId: String) = File(ctx.getExternalFilesDir(null), "courses/${courseId}_map.png")

    fun picture(bmp: Bitmap): MapPicture {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return MapPicture(bmp.width, bmp.height, px)
    }

    fun loadPicture(path: String): MapPicture? = BitmapFactory.decodeFile(path)?.let { picture(it) }

    class Result(val overlay: MapOverlay, val message: String)

    /**
     * Save the picture with the course and place it. Off the main thread; takes a few seconds.
     * 1. By shape: the map's lot and islands are matched to satellite pavement around [near]
     *    (the course, else the map view, else you). Needs no timing lines.
     * 2. Otherwise by the start/finish marks, when the course has lines.
     * 3. Otherwise roughly, for nudging by hand.
     */
    fun place(ctx: Context, course: CourseDef, bmp: Bitmap, near: Pair<Double, Double>?, progress: (String) -> Unit = {}): Result {
        val f = file(ctx, course.id)
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pic = picture(bmp)
        val seed = course.center() ?: near
        if (seed != null) {
            progress("Getting satellite imagery around the lot…")
            val tiles = Tiles.ensure(seed.first, seed.second, 450.0)
            if (tiles > 0) {
                progress("Matching the map's lot and islands to the satellite photo…")
                val grid = PavementGrid.build(seed.first, seed.second, 450.0, 2.0) { lat, lon -> Tiles.satPixel(lat, lon)?.let { MapAlign.pavementLikeness(it) } }
                val found = MapSearch.search(pic, grid, f.path, searchRadiusM = 180.0)
                if (found != null && found.score >= 0.45) {
                    val warn = if (found.runnerUpGapM > 30) " Another spot ${found.runnerUpGapM.toInt()} m away fit almost as well, so check it." else ""
                    return Result(found.overlay, found.overlay.how + ". Check it with the fade slider and nudge if needed." + warn)
                }
            }
        }
        val auto = MapAlign.auto(pic, course, f.path) { lat, lon -> Tiles.satPixel(lat, lon)?.let { MapAlign.pavementLikeness(it) } }
        if (auto != null) return Result(auto, auto.how + ". Check it with the fade slider and nudge if needed.")
        // Rough placement: centered on the course (or you), about 0.5 m per pixel, north up
        val (lat, lon) = course.center() ?: near ?: return Result(
            MapOverlay(f.path, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.00001, how = "Not placed"),
            "Couldn't place the map: pan the satellite map to the lot first, then add the map again.",
        )
        val mpp = 0.5
        val dLon = bmp.width * mpp / (111_320.0 * cos(lat * PI / 180))
        val o = MapOverlay(f.path, 0.0, bmp.height / 2.0, lat, lon - dLon / 2, bmp.width.toDouble(), bmp.height / 2.0, lat, lon + dLon / 2,
            how = "Placed roughly (no start/finish marks found to anchor on)")
        val why = when {
            else -> "Its shapes didn't match the satellite photo near here. Pan the map to the lot and try again, or nudge it into place."
        }
        return Result(o, "Placed roughly. $why")
    }
}
