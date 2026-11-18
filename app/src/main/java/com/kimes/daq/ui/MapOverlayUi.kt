package com.kimes.daq.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.TabletGps
import com.kimes.daq.course.CourseDef
import com.kimes.daq.course.MapImport
import com.kimes.daq.course.MapOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot

/** The overlay's picture, loaded off the main thread (reloads when the file changes). */
@Composable
fun rememberOverlayImage(o: MapOverlay?): ImageBitmap? {
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    val stamp = o?.file?.let { File(it).lastModified() } ?: 0L
    LaunchedEffect(o?.file, stamp) {
        img = o?.file?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f)?.asImageBitmap() } }
    }
    return img
}

/** Draw the course map where its two anchors say it belongs. */
fun DrawScope.drawMapOverlay(pr: MapProj, img: ImageBitmap, o: MapOverlay, alpha: Float = o.alpha.toFloat()) {
    val s1 = pr.pt(o.lat1, o.lon1); val s2 = pr.pt(o.lat2, o.lon2)
    val p1 = Offset(o.u1.toFloat(), o.v1.toFloat()); val p2 = Offset(o.u2.toFloat(), o.v2.toFloat())
    val k = hypot((s2 - s1).x, (s2 - s1).y) / hypot((p2 - p1).x, (p2 - p1).y).coerceAtLeast(1e-3f)
    val ang = atan2((s2 - s1).y, (s2 - s1).x) - atan2((p2 - p1).y, (p2 - p1).x)
    translate(s1.x, s1.y) {
        rotateRad(ang, pivot = Offset.Zero) {
            scale(k, k, pivot = Offset.Zero) {
                translate(-p1.x, -p1.y) { drawImage(img, alpha = alpha) }
            }
        }
    }
}

/** Course-map controls for the course editor. */
@Composable
fun CourseMapSection(c: CourseDef, viewCenter: () -> Pair<Double, Double>?, onChange: (CourseDef) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var pdf by remember { mutableStateOf<Uri?>(null) }
    var pages by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    val o = c.overlay
    val img = rememberOverlayImage(o)

    fun place(uri: Uri, pdfPage: Int?) {
        busy = true; msg = "Placing the map…"
        scope.launch {
            val r = withContext(Dispatchers.Default) {
                val bmp = MapImport.load(ctx, uri, pdfPage) ?: return@withContext null
                MapImport.place(ctx, c, bmp, viewCenter() ?: TabletGps.fix.value?.let { it.lat to it.lon }) { msg = it }
            }
            busy = false
            if (r == null) { msg = "Couldn't open that file as a picture or PDF."; return@launch }
            msg = r.message
            onChange(c.copy(overlay = r.overlay))
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val type = ctx.contentResolver.getType(uri) ?: ""
        if (type == "application/pdf") {
            pdf = uri; pages = MapImport.pdfPageCount(ctx, uri); page = 0
            msg = if (pages > 1) "Pick the page with the course map." else null
            if (pages <= 1) place(uri, 0)
        } else { pdf = null; place(uri, null) }
    }

    Section("Course map", Modifier.padding(top = 10.dp)) {
        Text("The organizer's map over the satellite view. Pan the map to the lot first: the app matches the map's lot and islands to the satellite photo there (needs internet the first time).", color = Dim, fontSize = 13.sp, lineHeight = 18.sp)
        pdf?.let { uri ->
            if (pages > 1) Row(Modifier.padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextAction("‹") { if (page > 0) page-- }
                Text("Page ${page + 1} of $pages", color = Hi, fontSize = 15.sp)
                TextAction("›") { if (page < pages - 1) page++ }
                Secondary("Use this page", Modifier.width(140.dp), height = 40) { place(uri, page) }
            }
        }
        if (o == null) {
            TextAction(if (busy) "Placing…" else "Add course map (picture or PDF)") { if (!busy) pick.launch(arrayOf("image/*", "application/pdf")) }
        } else {
            Text(o.how + " · %.2f ft per pixel".format(o.metersPerPixel * M_TO_FT), color = Lo, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Label("Fade", Modifier.width(44.dp))
                Slider(
                    value = o.alpha.toFloat(), onValueChange = { onChange(c.copy(overlay = o.copy(alpha = it.toDouble()))) }, valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Rule), modifier = Modifier.weight(1f),
                )
            }
            if (img != null) {
                val w = img.width; val h = img.height
                Label("Nudge")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((t, f) in listOf<Pair<String, () -> MapOverlay>>(
                        "←" to { o.nudged(w, h, dEast = -1.0) }, "→" to { o.nudged(w, h, dEast = 1.0) },
                        "↑" to { o.nudged(w, h, dNorth = 1.0) }, "↓" to { o.nudged(w, h, dNorth = -1.0) },
                    )) Secondary(t, Modifier.weight(1f), height = 40) { onChange(c.copy(overlay = f().copy(how = "Adjusted by hand"))) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    for ((t, f) in listOf<Pair<String, () -> MapOverlay>>(
                        "⟲" to { o.nudged(w, h, dRotDeg = 1.0) }, "⟳" to { o.nudged(w, h, dRotDeg = -1.0) },
                        "−" to { o.nudged(w, h, scale = 0.99) }, "+" to { o.nudged(w, h, scale = 1.01) },
                    )) Secondary(t, Modifier.weight(1f), height = 40) { onChange(c.copy(overlay = f().copy(how = "Adjusted by hand"))) }
                }
                Text("Arrows move 1 m (3 ft), ⟲ ⟳ rotate 1°, − + scale 1%.", color = Lo, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TextAction("Replace map") { pick.launch(arrayOf("image/*", "application/pdf")) }
                TextAction("Remove", color = Red) { onChange(c.copy(overlay = null)) }
            }
        }
        msg?.let { Text(it, color = if (it.startsWith("Couldn't") || it.startsWith("Placed roughly")) Amber else Dim, fontSize = 13.sp) }
    }
}
