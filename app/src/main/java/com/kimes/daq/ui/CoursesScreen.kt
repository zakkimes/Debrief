package com.kimes.daq.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.Hub
import com.kimes.daq.TabletGps
import com.kimes.daq.course.Cone
import com.kimes.daq.course.CourseDef
import com.kimes.daq.course.CourseMode
import com.kimes.daq.course.CornerMark
import androidx.compose.ui.graphics.nativeCanvas
import com.kimes.daq.course.GeoLine
import com.kimes.daq.course.LineKind
import com.kimes.daq.course.bearingDeg
import com.kimes.daq.geo.Tiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CoursesScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val active by Hub.activeCourse.collectAsState()
    var list by remember { mutableStateOf(Hub.courses.all()) }
    var editing by remember { mutableStateOf<CourseDef?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val newId = { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) { msg = "Couldn't open that file."; return@rememberLauncherForActivityResult }
        val id = newId()
        // A course shared from another Debrief tablet (JSON), or a RaceChrono track (zip)
        val fromDebrief = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() } == '{'.code.toByte()
        val c = if (fromDebrief) CourseDef.importJson(String(bytes), id) { png ->
            runCatching { java.io.File(ctx.filesDir, "overlays").also { it.mkdirs() }.let { d -> java.io.File(d, "$id.png").also { it.writeBytes(png) }.absolutePath } }.getOrNull()
        } else runCatching { CourseDef.fromRcz(bytes.inputStream(), id) }.getOrNull()
        if (c == null || (c.lines.isEmpty() && c.cones.isEmpty())) {
            msg = "Couldn't read a course from that file. Debrief reads courses shared from another Debrief tablet, and RaceChrono .rcz tracks."
            return@rememberLauncherForActivityResult
        }
        // Keep names apart from this tablet's own courses
        val named = if (Hub.courses.all().any { it.name == c.name }) c.copy(name = c.name + " (shared)") else c
        Hub.courses.save(named)
        list = Hub.courses.all()
        msg = "Imported \"${named.name}\": ${named.lines.size} lines, ${named.cones.size} cones, ${named.corners.size} corners."
        if (!fromDebrief) editing = named
    }

    editing?.let { c ->
        CourseEditor(c) {
            // Left without placing anything: don't keep it
            Hub.courses.get(c.id)?.let { saved -> if (saved.isEmpty && Hub.activeCourse.value?.id != saved.id) Hub.courses.delete(saved.id) }
            editing = null; list = Hub.courses.all()
        }
        return
    }

    @Composable fun rowButtons(c: CourseDef, sel: Boolean) {
        OutlineButton("Edit") { editing = c }
        OutlineButton("Share") { msg = shareCourse(ctx, c) }
        OutlineButton("Delete", color = Dim) { confirmDelete = c.id }
    }
    // Delete asks first: a pop-up naming the course, with Delete and Keep
    confirmDelete?.let { id -> list.firstOrNull { it.id == id } }?.let { c ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmDelete = null }) {
            Column(Modifier.width(440.dp).background(Paper).border(4.dp, Ink, androidx.compose.ui.graphics.RectangleShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Delete \"${c.name}\"?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Its lines, cones and corners are removed from this tablet. Sessions already recorded keep their own copy.", color = Dim, fontSize = 15.sp, lineHeight = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Primary("Delete", Modifier.weight(1f), height = 48, fill = RedFill) {
                        val sel = active?.id == c.id
                        Hub.courses.delete(c.id); if (sel) Hub.selectCourse(null); list = Hub.courses.all(); confirmDelete = null
                    }
                    Secondary("Keep", Modifier.weight(1f), height = 48) { confirmDelete = null }
                }
            }
        }
    }
    Panes(Modifier.fillMaxSize().padding(20.dp), 20.dp) {
        Column(Modifier.share(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton("Home") { back() }
            Text("Courses", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Hi)
            Text("The selected course times your runs: the clock starts at its start line and stops at its finish line.", color = Dim, fontSize = 14.sp)
            msg?.let { Text(it, color = Amber, fontSize = 15.sp) }
            if (list.isEmpty()) Text("No courses yet. Import a RaceChrono track or make one on the course walk.", color = Dim)
            for (c in list) {
                val sel = active?.id == c.id
                Row(
                    Modifier.fillMaxWidth().background(if (sel) Surface else Color.Transparent).bottomRule(RuleFaint)
                        .clickable { Hub.selectCourse(if (sel) null else c) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name + if (sel) "  · in use" else "", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Hi)
                        Text(
                            (if (c.mode == CourseMode.LAPS) "Track (laps)" else "Autocross") +
                                " · ${c.sectors.size} sector lines · ${c.cones.size} cones" + if (!c.timed) " · needs start and finish lines" else "",
                            color = if (c.timed) Dim else Amber, fontSize = 14.sp,
                        )
                        if (port) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { rowButtons(c, sel) }
                    }
                    if (!port) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { rowButtons(c, sel) }
                }
            }
        }
        Column(Modifier.then(if (port) Modifier.fillMaxWidth() else Modifier.width(300.dp)), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("New course", Color(0xFF1565C0), Modifier.fillMaxWidth()) {
                val c = CourseDef(newId(), "Course " + SimpleDateFormat("MMM d", Locale.US).format(Date()))
                Hub.courses.save(c); list = Hub.courses.all(); editing = c
            }
            OutlineButton("Import a course", Modifier.fillMaxWidth()) { pick.launch(arrayOf("*/*")) }
            Text("A course shared from another Debrief tablet, or a RaceChrono .rcz track. Share sends a course by Quick Share, email or messages.", color = Dim, fontSize = 13.sp)
            Text("Tap a course to use it (tap again to stop using it). Without a course, runs are timed from launch until you slow down.", color = Dim, fontSize = 13.sp)
        }
    }
}

/** Hand a course to another app (Quick Share, email, messages) as a file another Debrief can import. */
private fun shareCourse(ctx: android.content.Context, c: CourseDef): String? = runCatching {
    val png = c.overlay?.file?.let { java.io.File(it) }?.takeIf { it.exists() }?.readBytes()
    val dir = java.io.File(ctx.cacheDir, "share").also { it.mkdirs() }
    val safe = c.name.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifEmpty { "course" }
    val f = java.io.File(dir, "$safe.debrief-course.json")
    f.writeText(CourseDef.exportJson(c, png))
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Debrief course: ${c.name}")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "Share ${c.name}"))
    null
}.getOrElse { "Couldn't share the course: ${it.message}" }

/** A position less sure than this (about 10 ft) is averaged over 10 s of standing still before it's placed. */
private const val HOLD_ABOVE_M = 3.0f

/** What a tap on the map, or "where I stand", adds. */
private enum class What(val label: String) { START("Start"), FINISH("Finish"), SECTOR("Sector"), CONE("Cone"), CORNER("Corner") }

private enum class Place { NONE, LINE, CONE, CORNER, MOVE }
private sealed class Sel { data class L(val i: Int) : Sel(); data class C(val i: Int) : Sel(); data class K(val i: Int) : Sel() }

private fun LineKind.label() = when (this) { LineKind.START -> "Start"; LineKind.FINISH -> "Finish"; LineKind.SECTOR -> "Sector"; LineKind.START_FINISH -> "Start/Finish" }
private fun LineKind.color() = when (this) { LineKind.SECTOR -> Dim; else -> Hi }

@Composable
private fun CourseEditor(initial: CourseDef, done: () -> Unit) {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(initial) }
    var sel by remember { mutableStateOf<Sel?>(null) }
    var place by remember { mutableStateOf(Place.NONE) }
    var nextKind by remember { mutableStateOf(if (initial.start == null) LineKind.START else LineKind.SECTOR) }
    // What the next tap or "where I stand" adds: whatever the course still needs first
    var what by remember { mutableStateOf(when { initial.start == null -> What.START; initial.mode == CourseMode.AUTOX && initial.finish == null -> What.FINISH; else -> What.CONE }) }
    val history = remember { mutableStateListOf<CourseDef>() }
    var editingName by remember { mutableStateOf(false) }
    val fix by TabletGps.fix.collectAsState()
    val gpsStatus by TabletGps.status.collectAsState()
    val az by TabletGps.azimuth.collectAsState()
    val saving by Tiles.saving.collectAsState()
    val map = rememberMapState()
    var dragTick by remember { mutableIntStateOf(0) }
    val opt = rememberOptimal(c)
    val overlayImg = rememberOverlayImage(c.overlay)

    // Every position source at once (the tablet, and the RaceBox when it's nearby); the walk uses the most accurate
    DisposableEffect(Unit) { TabletGps.start(ctx); if (!Hub.logging.value) Hub.startWalkRaceBox(); onDispose { TabletGps.stop(); Hub.stopWalkRaceBox() } }
    val walkRb by Hub.walkRaceBox.collectAsState()
    val rbStatus by Hub.rbStatus.collectAsState()
    val logging by Hub.logging.collectAsState()
    fun save(n: CourseDef, retime: Boolean = true) {
        if (n != c) { history.add(c); if (history.size > 40) history.removeAt(0) }
        c = n; Hub.courses.save(n); if (retime) Hub.courseEdited(n)
    }
    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        c = prev; Hub.courses.save(prev); Hub.courseEdited(prev); Hub.cornersEdited(prev); sel = null
    }

    // Path of the reference run, if the session uses this course: handy for placing lines
    val refPath = remember(c.id) {
        if (Hub.sessionCourse.value?.id != c.id) emptyList() else Hub.referenceRun()?.let { r ->
            synchronized(Hub) { Hub.samples.subList(r.startIdx, r.endIdx + 1).map { it.lat to it.lon } }
        } ?: emptyList()
    }
    LaunchedEffect(map.size, fix != null) {
        if (!map.fitted && map.size.width > 0) {
            val pts = c.lines.map { it.lat to it.lon } + c.cones.map { it.lat to it.lon } + refPath
            when {
                pts.size >= 2 -> { map.fit(pts, 80f); map.zoom = map.zoom.coerceAtMost(19.5) }
                pts.size == 1 -> { map.lat = pts[0].first; map.lon = pts[0].second; map.zoom = 18.0; map.fitted = true }
                fix != null -> { map.lat = fix!!.lat; map.lon = fix!!.lon; map.zoom = 18.0; map.fitted = true }
            }
        }
    }

    LaunchedEffect(fix, map.follow) {
        val f = fix
        if (map.follow && f != null) { map.lat = f.lat; map.lon = f.lon; map.fitted = true }
    }

    fun addLine(lat: Double, lon: Double, bearing: Double) {
        val kind = if (c.mode == CourseMode.LAPS && nextKind != LineKind.SECTOR) LineKind.START_FINISH else nextKind
        // Only one start and one finish: placing another replaces it
        val kept = if (kind == LineKind.SECTOR) c.lines else c.lines.filter { it.kind != kind }
        val n = c.copy(lines = kept + GeoLine(kind, lat, lon, bearing))
        save(n)
        sel = Sel.L(n.lines.size - 1)
        nextKind = when {
            n.start == null -> LineKind.START
            n.finish == null && c.mode == CourseMode.AUTOX -> LineKind.SECTOR
            else -> LineKind.SECTOR
        }
    }
    fun addCone(lat: Double, lon: Double) {
        val n = c.copy(cones = c.cones + Cone(lat, lon))
        save(n, retime = false)
        sel = Sel.C(n.cones.size - 1)
    }
    // Corners are named in driving order: the next one is "Corner N" until the driver renames it
    fun addCorner(lat: Double, lon: Double) {
        val n = c.copy(corners = c.corners + CornerMark(lat, lon, "Corner ${c.corners.size + 1}"))
        save(n, retime = false); Hub.cornersEdited(n)
        sel = Sel.K(n.corners.size - 1)
    }
    fun addWhat(w: What, lat: Double, lon: Double) {
        when (w) {
            What.START -> { nextKind = LineKind.START; addLine(lat, lon, az ?: 0.0) }
            What.FINISH -> { nextKind = LineKind.FINISH; addLine(lat, lon, az ?: 0.0) }
            What.SECTOR -> { nextKind = LineKind.SECTOR; addLine(lat, lon, az ?: 0.0) }
            What.CONE -> addCone(lat, lon)
            What.CORNER -> addCorner(lat, lon)
        }
        // On to whatever the course still needs
        what = when {
            c.start == null -> What.START
            c.mode == CourseMode.AUTOX && c.finish == null -> What.FINISH
            w == What.START || w == What.FINISH -> What.CONE
            else -> w
        }
    }
    fun selPos(): Pair<Double, Double>? = when (val s = sel) {
        is Sel.L -> c.lines.getOrNull(s.i)?.let { it.lat to it.lon }
        is Sel.C -> c.cones.getOrNull(s.i)?.let { it.lat to it.lon }
        is Sel.K -> null // corners have no direction to drag
        null -> null
    }

    Panes(Modifier.fillMaxSize()) {
        SatMap(
            map, Modifier.share(1f, 1.25f),
            controls = {
                // Find yourself and the course quickly
                Column(Modifier.align(Alignment.TopEnd).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Snap to the tablet and keep following; waits for a fix if there isn't one yet
                    MapButton(if (!map.follow) "Me" else if (fix == null) "Finding you…" else "Following", map.follow) {
                        map.follow = !map.follow
                        if (map.follow && map.zoom < 18.0) map.zoom = 18.5
                        // No fix yet: jump to Android's last known spot now, then the fix takes over
                        if (map.follow && fix == null) TabletGps.lastKnown()?.let { (la, lo) -> map.lat = la; map.lon = lo }
                    }
                    MapButton("Course") {
                        val pts = c.lines.map { it.lat to it.lon } + c.cones.map { it.lat to it.lon }
                        if (pts.size >= 2) map.fit(pts, 60f) else pts.firstOrNull()?.let { map.lat = it.first; map.lon = it.second }
                    }
                }
            },
            onTap = { lat, lon, at ->
                // Nearest placed line or cone to the finger, in screen pixels
                val pr = map.proj()
                val near = (c.lines.mapIndexed { i, l -> Sel.L(i) to (pr.pt(l.lat, l.lon) - at).getDistance() } +
                    c.cones.mapIndexed { i, k -> Sel.C(i) to (pr.pt(k.lat, k.lon) - at).getDistance() } +
                    c.corners.mapIndexed { i, k -> Sel.K(i) to (pr.pt(k.lat, k.lon) - at).getDistance() }).minByOrNull { it.second }
                when (place) {
                    Place.LINE, Place.CONE, Place.CORNER -> place = Place.NONE
                    Place.MOVE -> {
                        when (val s = sel) {
                            is Sel.L -> save(c.copy(lines = c.lines.toMutableList().also { it[s.i] = it[s.i].copy(lat = lat, lon = lon) }))
                            is Sel.C -> save(c.copy(cones = c.cones.toMutableList().also { it[s.i] = it[s.i].copy(lat = lat, lon = lon) }), retime = false)
                            is Sel.K -> c.copy(corners = c.corners.toMutableList().also { it[s.i] = it[s.i].copy(lat = lat, lon = lon) }).let { save(it, retime = false); Hub.cornersEdited(it) }
                            null -> {}
                        }
                        place = Place.NONE
                    }
                    Place.NONE -> {
                        // On something: select it. Empty spot: drop the selection, or add the chosen item
                        val hit = near?.takeIf { it.second < 60f }?.first
                        when {
                            hit != null -> sel = hit
                            sel != null -> sel = null
                            else -> addWhat(what, lat, lon)
                        }
                    }
                }
            },
            onDrag = { phase, lat, lon, at ->
                when (phase) {
                    0 -> {
                        // Drag starting on the selected item aims it: line direction or the cone's pass side
                        val p = selPos() ?: return@SatMap false
                        (map.proj().pt(p.first, p.second) - at).getDistance() < 90f
                    }
                    1 -> {
                        val p = selPos() ?: return@SatMap false
                        val b = bearingDeg(p.first, p.second, lat, lon)
                        when (val s = sel) {
                            is Sel.L -> c = c.copy(lines = c.lines.toMutableList().also { it[s.i] = it[s.i].copy(bearing = b) })
                            // Drag toward where the pointer lies; the car passes on the other side
                            is Sel.C -> c = c.copy(cones = c.cones.toMutableList().also { it[s.i] = it[s.i].copy(passBearing = (b + 180) % 360) })
                            is Sel.K, null -> {}
                        }
                        dragTick++
                        true
                    }
                    else -> { save(c, retime = sel is Sel.L); true }
                }
            },
        ) { pr ->
            dragTick.hashCode()
            overlayImg?.let { img -> c.overlay?.let { drawMapOverlay(pr, img, it) } }
            opt?.let { drawOptimal(pr, it) }
            if (refPath.size > 1) {
                val p = Path(); refPath.forEachIndexed { i, (la, lo) -> val o = pr.pt(la, lo); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                drawPath(p, Hi.copy(alpha = 0.45f), style = Stroke(3f))
            }
            c.lines.forEachIndexed { i, l -> drawTimingLine(pr, l, (sel as? Sel.L)?.i == i) }
            c.cones.forEachIndexed { i, k -> drawConeMark(pr, k, (sel as? Sel.C)?.i == i) }
            c.corners.forEachIndexed { i, k -> drawCornerMark(pr, k, (sel as? Sel.K)?.i == i) }
            fix?.let { f ->
                val o = pr.pt(f.lat, f.lon)
                drawCircle(Cyan.copy(alpha = 0.18f), f.accM * pr.pxPerM, o)
                drawCircle(Color.Black, 10f, o); drawCircle(Cyan, 8f, o)
                az?.let { a -> val r = a * PI / 180; drawLine(Cyan, o, o + Offset((sin(r) * 34).toFloat(), (-cos(r) * 34).toFloat()), 5f) }
            }
        }

        var holding by remember { mutableStateOf<String?>(null) } // a What name while averaging a weak position
        var holdLeft by remember { mutableStateOf(0) }
        Column(
            Modifier.side(310.dp, 1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackButton("All courses") { done() }
            if (editingName) {
                OutlinedTextField(c.name, { save(c.copy(name = it), retime = false) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlineButton("Done naming", Modifier.fillMaxWidth()) { editingName = false; Hub.courseEdited(c) }
            } else {
                Text(c.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = Hi, modifier = Modifier.clickable { editingName = true })
            }
            // Timing can't work yet: say what's missing
            if (c.start == null || (c.mode == CourseMode.AUTOX && c.finish == null))
                Text(if (c.mode == CourseMode.LAPS) "Needs a start/finish line." else "Needs a start and a finish line.", color = Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            // One way to add anything: pick it, then add it where you stand or tap the map
            val whats = if (c.mode == CourseMode.LAPS) listOf(What.START, What.SECTOR, What.CONE, What.CORNER) else What.entries
            Segmented(whats.map { (if (it == What.START && c.mode == CourseMode.LAPS) "Start/fin" else it.label) to it }, what, Modifier.fillMaxWidth(), height = 44) { what = it; place = Place.NONE; sel = null }
            BigButton(
                if (holding != null) "Hold still… $holdLeft" else "Add ${what.label.lowercase()} where I stand",
                Color(0xFF1565C0), Modifier.fillMaxWidth(), 64, enabled = fix != null && holding == null,
            ) { fix?.let { f -> if (f.accM > HOLD_ABOVE_M) holding = what.name else addWhat(what, f.lat, f.lon) } }
            Text((if (fix == null) "No position yet" else gpsStatus) + " · or tap the map", color = if (fix != null && fix!!.accM < 3) Dim else Amber, fontSize = 13.sp)
            Secondary("Undo", Modifier.fillMaxWidth(), 44, enabled = history.isNotEmpty()) { undo() }
            sel?.let { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s is Sel.L) OutlineButton("Flip", Modifier.weight(1f)) {
                        save(c.copy(lines = c.lines.toMutableList().also { it[s.i] = it[s.i].copy(bearing = (it[s.i].bearing + 180) % 360) }))
                    }
                    if (s is Sel.C) OutlineButton("Clear side", Modifier.weight(1f)) {
                        save(c.copy(cones = c.cones.toMutableList().also { it[s.i] = it[s.i].copy(passBearing = null) }), retime = false)
                    }
                    OutlineButton(if (place == Place.MOVE) "Tap new spot…" else "Move", Modifier.weight(1f), if (place == Place.MOVE) Amber else Cyan) {
                        place = if (place == Place.MOVE) Place.NONE else Place.MOVE
                    }
                    OutlineButton("Delete", Modifier.weight(1f), Red) {
                        when (s) {
                            is Sel.L -> save(c.copy(lines = c.lines.filterIndexed { i, _ -> i != s.i }))
                            is Sel.C -> save(c.copy(cones = c.cones.filterIndexed { i, _ -> i != s.i }), retime = false)
                            is Sel.K -> c.copy(corners = c.corners.filterIndexed { i, _ -> i != s.i }).let { save(it, retime = false); Hub.cornersEdited(it) }
                        }
                        sel = null
                    }
                }
                if (s !is Sel.K) Text(if (s is Sel.L) "Drag from the line to aim it the way you drive." else "Drag from the cone toward its pointer cone.", color = Dim, fontSize = 13.sp)
            }

            // Predicted time, once the course can be timed
            if (optimalLineMissing(c) == null) opt?.let { o ->
                Text("Predicted best %.2f s".format(o.time) + if (o.basis.isNotEmpty()) " (${o.basis})" else "", color = Magenta, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            var more by remember { mutableStateOf(false) }
            TextAction(if (more) "Less ▴" else "More ▾") { more = !more }
            if (more) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Autocross", c.mode == CourseMode.AUTOX, modifier = Modifier.weight(1f)) {
                        save(c.copy(mode = CourseMode.AUTOX, lines = c.lines.map { if (it.kind == LineKind.START_FINISH) it.copy(kind = LineKind.START) else it }))
                    }
                    Chip("Laps", c.mode == CourseMode.LAPS, modifier = Modifier.weight(1f)) {
                        save(c.copy(mode = CourseMode.LAPS, lines = c.lines.filter { it.kind != LineKind.FINISH }.map { if (it.kind == LineKind.START) it.copy(kind = LineKind.START_FINISH) else it }))
                    }
                }
                // Lines in timing order
                val ordered = c.lines.withIndex().sortedBy { (_, l) -> when (l.kind) { LineKind.START, LineKind.START_FINISH -> 0; LineKind.SECTOR -> 1; LineKind.FINISH -> 2 } }
                var sn = 0
                for ((i, l) in ordered) {
                    val label = if (l.kind == LineKind.SECTOR) "Sector line ${++sn}" else l.kind.label()
                    val isSel = (sel as? Sel.L)?.i == i
                    Row(
                        Modifier.fillMaxWidth().background(if (isSel) Surface else Color.Transparent).bottomRule(RuleFaint)
                            .clickable { sel = Sel.L(i); map.lat = l.lat; map.lon = l.lon }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(label, color = l.kind.color(), fontWeight = FontWeight.Medium, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("%.0f°".format(l.bearing), color = Dim, fontSize = 14.sp, modifier = Modifier.padding(end = 16.dp))
                        Text("Delete", color = Red, fontSize = 14.sp, modifier = Modifier.clickable {
                            save(c.copy(lines = c.lines.filterIndexed { j, _ -> j != i })); if ((sel as? Sel.L)?.i == i) sel = null
                        })
            }
        }
                if (c.cones.isNotEmpty()) {
                    // Step through cones: handy when they're close together on the map
                    val ci = (sel as? Sel.C)?.i
                    fun pickCone(i: Int) { val k = c.cones[i]; sel = Sel.C(i); map.lat = k.lat; map.lon = k.lon; if (map.zoom < 19.0) map.zoom = 19.5 }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TextAction("‹ Prev") { pickCone(((ci ?: 0) - 1 + c.cones.size) % c.cones.size) }
                        Text(if (ci != null) "Cone ${ci + 1} of ${c.cones.size}" else "${c.cones.size} cones", color = Hi, fontSize = 15.sp)
                        TextAction("Next ›") { pickCone(((ci ?: -1) + 1) % c.cones.size) }
            }
        }
                Text("${c.cones.size} cones · ${c.cones.count { it.passBearing != null }} with a pass side", color = Dim, fontSize = 14.sp)
                // Named corners: Review uses these names (same corners on every run) instead of numbering speed dips
                Column(Modifier.fillMaxWidth().padding(top = 6.dp).topRule(Rule).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Corners")
                    if (c.corners.isEmpty()) Text("Add corners in driving order; name them here.", color = Dim, fontSize = 13.sp)
                    c.corners.forEachIndexed { i, k ->
                        val isSel = (sel as? Sel.K)?.i == i
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(if (isSel) Surface else Color.Transparent)) {
                            OutlinedTextField(
                                k.name, { v -> c.copy(corners = c.corners.toMutableList().also { it[i] = it[i].copy(name = v) }).let { save(it, retime = false); Hub.cornersEdited(it) } },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Hi),
                            )
                            Text("Show", color = Cyan, fontSize = 14.sp, modifier = Modifier.clickable { sel = Sel.K(i); map.lat = k.lat; map.lon = k.lon }.padding(start = 10.dp))
                        }
            }
        }
                if (!logging) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Secondary(if (walkRb) "Stop RaceBox" else "Use RaceBox", Modifier.weight(1f), 44, color = if (walkRb) Red else Cyan) { if (walkRb) Hub.stopWalkRaceBox() else Hub.startWalkRaceBox() }
                    Secondary("Reset tablet GPS", Modifier.weight(1f), 44) { TabletGps.coldStart() }
                }
                val center = c.center() ?: fix?.let { it.lat to it.lon }
                OutlineButton(
                    saving?.let { if (it.done < it.total) "Saving ${it.done}/${it.total}" else "Saved ${it.total - it.failed}/${it.total}" } ?: "Save imagery offline",
                    Modifier.fillMaxWidth(),
                ) { center?.let { Tiles.saveArea(it.first, it.second, 600.0) } }
                CourseMapSection(c, { map.lat to map.lon }) { save(it, retime = false) }
            }
            // Tablet GPS wanders: stand still for 10 s and place the accuracy-weighted average.
            // (RaceBox fixes are good enough to place at once.)
            LaunchedEffect(holding) {
                val what = holding ?: return@LaunchedEffect
                var sw = 0.0; var sLat = 0.0; var sLon = 0.0
                val until = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < until) {
                    holdLeft = ((until - System.currentTimeMillis()) / 1000 + 1).toInt()
                    TabletGps.fix.value?.let { f -> val w = 1.0 / (f.accM.toDouble() * f.accM).coerceAtLeast(1.0); sw += w; sLat += f.lat * w; sLon += f.lon * w }
                    kotlinx.coroutines.delay(250)
                }
                if (sw > 0) addWhat(What.valueOf(what), sLat / sw, sLon / sw)
                holding = null
            }
        }
    }
}

fun DrawScope.drawTimingLine(pr: MapProj, l: GeoLine, selected: Boolean) {
    val o = pr.pt(l.lat, l.lon)
    val r = l.bearing * PI / 180
    val dir = Offset(sin(r).toFloat(), -cos(r).toFloat()) // screen: up = north
    val perp = Offset(-dir.y, dir.x)
    val half = (l.width / 2 * pr.pxPerM).toFloat().coerceIn(30f, 600f)
    val col = l.kind.color()
    if (selected) drawLine(Cyan, o - perp * (half + 4), o + perp * (half + 4), 14f)
    drawLine(col, o - perp * half, o + perp * half, 8f)
    // Arrow: direction of travel
    val tip = o + dir * 46f
    drawLine(col, o, tip, 5f)
    drawLine(col, tip, tip - dir * 14f + perp * 10f, 5f)
    drawLine(col, tip, tip - dir * 14f - perp * 10f, 5f)
}

fun DrawScope.drawConeMark(pr: MapProj, k: Cone, selected: Boolean) {
    val o = pr.pt(k.lat, k.lon)
    val orange = ConeOrange
    if (selected) drawCircle(Cyan, 16f, o, style = Stroke(3f))
    k.passBearing?.let { b ->
        // Pointer cone, autocross style: it lies on the side you DON'T pass, tip at the standing cone,
        // so it reads as an arrow pointing the way around (dir = toward the pass side)
        val r = b * PI / 180
        val dir = Offset(sin(r).toFloat(), -cos(r).toFloat()); val perp = Offset(-dir.y, dir.x)
        val base = o - dir * 32f
        val p = Path().apply {
            moveTo((base + perp * 8f).x, (base + perp * 8f).y); lineTo((base - perp * 8f).x, (base - perp * 8f).y)
            val t = o - dir * 10f; lineTo(t.x, t.y); close()
        }
        drawPath(p, orange); drawPath(p, Color.Black, style = Stroke(2f))
    }
    drawCircle(Color.Black, 8f, o); drawCircle(orange, 6f, o)
}

/** A named corner: a rounded tag with its name, centered on the spot. */
fun DrawScope.drawCornerMark(pr: MapProj, k: CornerMark, selected: Boolean) {
    val o = pr.pt(k.lat, k.lon)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE; textSize = 26f; isAntiAlias = true; isFakeBoldText = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val half = paint.measureText(k.name) / 2 + 12f
    val tl = Offset(o.x - half, o.y - 20f); val sz = androidx.compose.ui.geometry.Size(half * 2, 40f)
    val r = androidx.compose.ui.geometry.CornerRadius(20f, 20f)
    drawRoundRect(Color(0xE6203A5C), tl, sz, r)
    drawRoundRect(if (selected) Cyan else Color.White, tl, sz, r, style = Stroke(if (selected) 5f else 2f))
    drawContext.canvas.nativeCanvas.drawText(k.name, o.x, o.y - (paint.ascent() + paint.descent()) / 2, paint)
}
