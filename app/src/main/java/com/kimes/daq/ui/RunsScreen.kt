package com.kimes.daq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.Hub
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import com.kimes.daq.run.Analysis
import com.kimes.daq.run.Analyzer
import com.kimes.daq.run.Opportunity
import com.kimes.daq.run.Launch
import com.kimes.daq.run.Slide
import com.kimes.daq.run.Standings
import com.kimes.daq.run.SlipEvent
import com.kimes.daq.run.SectionTable
import com.kimes.daq.run.Sections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private sealed class View { data object Overview : View(); data class Corner(val i: Int) : View(); data object Charts : View(); data object Video : View() }

private class Loaded(val a: Analysis, val samples: List<Sample>, val sec: SectionTable? = null, val usable: List<Run> = emptyList()) {
    val run get() = a.run
    val runS: List<Sample> = samples.subList(a.run.startIdx, a.run.endIdx + 1)
    val refS: List<Sample>? = a.ref?.let { samples.subList(it.startIdx, it.endIdx + 1) }
    /** Run sample nearest a reference distance. */
    fun runAt(d: Double): Sample? = a.dist.indices.minByOrNull { abs(a.dist[it] - d) }?.let { runS.getOrNull(it) }
}

@Composable
fun RunsScreen(back: () -> Unit, initialRun: Int? = null) {
    val runs by Hub.runs.collectAsState()
    val refN by Hub.refNumber.collectAsState()
    val pen by Hub.penalties.collectAsState()
    var sel by remember { mutableStateOf(initialRun) }
    var view by remember { mutableStateOf<View>(View.Overview) }
    val selected = runs.firstOrNull { it.number == sel } ?: runs.lastOrNull()
    // With co-drivers, each run is compared within its own driver's runs
    val runDrivers by Hub.runDriver.collectAsState()
    val selDriver = selected?.let { runDrivers[it.number] }
    val ref = remember(runs, refN, pen, selDriver, runDrivers) { Hub.referenceRun(selDriver) }
    val offs by Hub.official.collectAsState()
    fun shown(r: Run) = Hub.shownTime(r, runs, offs)
    val bestN = remember(runs, pen, selDriver, runDrivers, offs) { Standings.best(selDriver?.let { d -> runs.filter { runDrivers[it.number] == d } } ?: runs, pen) { shown(it) }?.number }

    val logging by Hub.logging.collectAsState()
    val sessionName by Hub.sessionName.collectAsState()
    val loadingP by Hub.loadProgress.collectAsState()
    var picking by remember { mutableStateOf(runs.isEmpty() && !logging && Hub.loadProgress.value == null) }
    // One step back at a time: a detail view returns to the run, the run list to Home
    fun goBack() { if (view != View.Overview) view = View.Overview else if (picking && runs.isNotEmpty()) picking = false else back() }
    androidx.activity.compose.BackHandler { goBack() }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    // Recomputed when an official time is entered: the sections are recast onto the official clock
    LaunchedEffect(selected, ref, offs) {
        loaded = null
        if (selected == null) return@LaunchedEffect
        loaded = withContext(Dispatchers.Default) {
            val snap = synchronized(Hub) { ArrayList(Hub.samples) }
            if (selected.endIdx >= snap.size || (ref != null && ref.endIdx >= snap.size)) null
            else {
                // Every run's time in every section, so even the best run sees where another run was quicker
                val pens = Hub.penalties.value
                val usable = Hub.sameDriver(Hub.runs.value, selected.number).filter { pens[it.number] != Standings.DNF && it.endIdx < snap.size }
                val named = Hub.sessionCourse.value?.corners ?: emptyList()
                val sec = runCatching { Sections.build(snap, usable, corners = named, corrections = Hub.corrections(Hub.runs.value)) }.getOrNull()
                // The reference run itself is compared with the next-best run: where it gained, and
                // where that run was still quicker
                val cmp = if (ref != null && ref.number == selected.number)
                    Standings.best(usable, pens, exclude = selected.number) ?: usable.filter { it.number != selected.number }.minByOrNull { it.time }
                else ref
                Loaded(Analyzer.analyze(snap, selected, cmp, named, sec, usable), snap, sec, usable)
            }
        }
    }

    @Composable fun Content() {
        val l = loaded
        when {
            loadingP != null -> Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Loading session…", fontFamily = Anton, fontSize = 30.sp, color = Hi)
                Box(Modifier.fillMaxWidth().height(14.dp).background(Surface)) {
                    Box(Modifier.fillMaxWidth(loadingP!!.coerceIn(0.02f, 1f)).fillMaxHeight().background(Cyan))
                }
                LoadingQuips()
            }
            picking -> SessionPicker(logging, Modifier.fillMaxSize()) { sel = null; view = View.Overview; picking = false }
            selected == null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("No timed runs in this session.", fontFamily = Anton, fontSize = 28.sp, color = Hi)
                Text("It recorded driving, but nothing was timed as a run.", color = Dim, fontSize = 16.sp)
                Secondary("Pick another day", Modifier.width(260.dp), height = 48) { picking = true }
            }
            l == null || l.run.number != selected.number -> Text("Analyzing…", color = Dim, modifier = Modifier.padding(20.dp))
            else -> when (val v = view) {
                View.Overview -> Overview(l, Hub.sameDriver(runs, l.run.number), pen, { view = View.Corner(it) }, { view = View.Charts }) { view = View.Video }
                is View.Corner -> CornerDetail(l, v.i, { view = View.Overview }, { view = View.Corner(it) })
                View.Charts -> Charts(l) { view = View.Overview }
                View.Video -> {
                    val vids by Hub.videos.collectAsState()
                    val ru0 = l.runS.firstOrNull { it.utc > 0 }?.utc
                    val clip = ru0?.let { u -> vids.firstOrNull { u in it.startUtc..it.endUtc } }
                    if (clip == null || ru0 == null) { view = View.Overview } else FullVideo(l, clip, ru0) { view = View.Overview }
                }
            }
        }
    }

    val port = isPortrait()
    @Composable fun backBtn(m: Modifier) = BackButton(if (view != View.Overview) "Run ${selected?.number ?: ""}" else "Home", m) { goBack() }
    // Which day you're looking at, and the way to any other
    @Composable fun sessionBox(m: Modifier) = Column(m.background(if (picking) Surface else Color.Transparent)
        .clickable { picking = !picking }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Label("Session", color = Dim)
        Text(sessionLabel(sessionName), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Hi, maxLines = 1)
        Label(if (picking) "Close list" else "Other days ›", color = Cyan)
    }
    @Composable fun runItem(r: Run, m: Modifier) {
        val isSel = r.number == selected?.number
        val p = pen[r.number] ?: 0
        val d = if (ref != null && ref.number != r.number) shown(r) - shown(ref) else null
        Column(
            m.background(if (isSel) Surface else Color.Transparent)
                .clickable { sel = r.number; view = View.Overview; picking = false }.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Official time once keyed in; "≈" marks this tablet's time corrected by the usual difference
            Num("${r.number}   " + (if (offs.isNotEmpty() && !offs.containsKey(r.number)) "≈" else "") + "%.3f".format(shown(r)) + if (r.finished) "" else "*", 17.sp, if (p != 0) Dim else Hi)
            runDrivers[r.number]?.let { Text(it, color = Ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            Text(
                when {
                    p == Standings.DNF -> "DNF"
                    p > 0 -> "+$p cone" + if (p > 1) "s" else ""
                    bestN == r.number && ref?.number == r.number -> "best"
                    bestN == r.number -> "best · " + (d?.let { signed(it) } ?: "")
                    ref?.number == r.number -> "comparing to this"
                    d != null -> signed(d)
                    else -> ""
                },
                color = when { p != 0 -> Amber; ref?.number == r.number || bestN == r.number -> Magenta; d != null -> deltaColor(d); else -> Dim },
                fontSize = 13.sp, maxLines = 1,
            )
        }
    }

    // Full-screen video: nothing else on screen
    if (view == View.Video) { Box(Modifier.fillMaxSize()) { Content() }; return }
    // Portrait: back, session and the runs in a strip across the top. Landscape: a column on the left.
    if (port) Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(Bg).bottomRule(Rule)) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                backBtn(Modifier.width(150.dp))
                sessionBox(Modifier.weight(1f))
            }
            if (runs.isEmpty()) Text("No runs yet.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
            androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().topRule(Rule)) {
                items(runs.reversed()) { r -> runItem(r, Modifier.width(118.dp).rightRule(RuleFaint)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { Content() }
    } else Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(170.dp).fillMaxHeight().background(Bg).rightRule(Rule).padding(top = 16.dp)) {
            backBtn(Modifier.padding(horizontal = 10.dp).fillMaxWidth())
            sessionBox(Modifier.fillMaxWidth().padding(top = 10.dp).topRule(Rule).bottomRule(Rule))
            if (runs.isEmpty()) Text("No runs yet.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(6.dp))
            LazyColumn {
                items(runs.reversed()) { r -> runItem(r, Modifier.fillMaxWidth().bottomRule(RuleFaint)) }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { Content() }
    }
}

/** " (best)" only for the real best clean run, never for whichever run is being compared against. */
/**
 * Gaining (-1), losing (+1), even (0) or unknown (2) per run sample. The time gap is measured over a
 * 2 s window, not sample to sample, and stretches shorter than 1.5 s join their neighbour, so the line
 * shows the real places time was won or lost instead of GPS noise flickering red/green.
 */
private fun trendOf(ss: List<Sample>, delta: DoubleArray): IntArray {
    val n = min(ss.size, delta.size)
    val out = IntArray(n) { 2 }
    var j0 = 0; var j1 = 0
    for (i in 0 until n) {
        val t = ss[i].t
        while (j0 < i && ss[j0].t < t - 1.0) j0++
        if (j1 < i) j1 = i
        while (j1 + 1 < n && ss[j1 + 1].t <= t + 1.0) j1++
        var a = j0; while (a < i && delta[a].isNaN()) a++
        var b = j1; while (b > i && delta[b].isNaN()) b--
        if (b <= a || delta[a].isNaN() || delta[b].isNaN()) continue
        val rate = (delta[b] - delta[a]) / max(ss[b].t - ss[a].t, 0.1)
        out[i] = if (rate > 0.04) 1 else if (rate < -0.04) -1 else 0
    }
    // Fold short stretches into the stretch before them (or after, at the start)
    var changed = true; var guard = 0
    while (changed && guard++ < 8) {
        changed = false
        var s0 = 0
        while (s0 < n) {
            var s1 = s0; while (s1 + 1 < n && out[s1 + 1] == out[s0]) s1++
            if (out[s0] != 2 && ss[s1].t - ss[s0].t < 1.5) {
                val fill = if (s0 > 0 && out[s0 - 1] != 2) out[s0 - 1] else if (s1 + 1 < n && out[s1 + 1] != 2) out[s1 + 1] else out[s0]
                if (fill != out[s0]) { for (k in s0..s1) out[k] = fill; changed = true }
            }
            s0 = s1 + 1
        }
    }
    return out
}

private val EvenGray = Color(0xFFD9D9D9)

/**
 * The other run as a thin dashed yellow line over this run's colored line: where the two lines
 * overlap the green/red still shows between the dashes, and where they split both are visible.
 */
private fun DrawScope.drawRefOver(pr: MapProj, ss: List<Sample>) {
    val p = Path()
    ss.forEachIndexed { i, s -> val o = pr.pt(s.lat, s.lon); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
    drawPath(p, MapRef, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f))))
}

/** This run's line colored green (gaining), red (losing) or gray (even) against the other run; blue with nothing to compare. */
private fun DrawScope.drawTrendPath(pr: MapProj, ss: List<Sample>, trend: IntArray?, idx: List<Int>, w: Float) {
    for (n in 1 until idx.size) {
        val i = idx[n]; val h = idx[n - 1]
        val s0 = ss.getOrNull(h) ?: continue; val s1 = ss.getOrNull(i) ?: continue
        val c = when (trend?.getOrNull(i)) { 1 -> Red; -1 -> Green; 0 -> EvenGray; else -> RunBlue }
        drawLine(Color.Black.copy(alpha = 0.5f), pr.pt(s0.lat, s0.lon), pr.pt(s1.lat, s1.lon), w + 3f)
        drawLine(c, pr.pt(s0.lat, s0.lon), pr.pt(s1.lat, s1.lon), w)
    }
}

private fun bestTag(n: Int) = if (Standings.best(Hub.runs.value, Hub.penalties.value)?.number == n) " (best)" else ""

private val RunBlue = Ink
private val RefOrange = Color(0xFFA0907A)
private val MapRun = Color.White
private val MapRef = Yellow

/** Something to read while a session loads: one of these, picked fresh each time the loading page shows. */
private val QUIPS = listOf(
    "A lot to analyze here.",
    "Coach is wrapping up his cigarette break.",
    "You're driving faster than I can analyze. Still loading.",
    "Counting every cone you hit. And the ones you didn't.",
    "Comparing your line to the one you meant to drive.",
    "Asking the RaceBox what really happened.",
    "Finding the tenths you left out there.",
    "Lining up the runs for a fair fight.",
    "Measuring the slalom in excuses per second.",
    "Rewinding the tape. Coach is squinting.",
    "Working out where the brake pedal went.",
    "Checking whether that was oversteer or bravery.",
)

@Composable
private fun LoadingQuips() {
    val quip = remember { QUIPS.random() }
    Text(quip, color = Dim, fontSize = 17.sp, lineHeight = 23.sp)
}

@Composable
private fun Overview(l: Loaded, runs: List<Run>, pen: Map<Int, Int>, open: (Int) -> Unit, charts: () -> Unit, video: () -> Unit) {
    val a = l.a
    val course = Hub.sessionCourse.value
    val sectors = if (course != null && course.timed) course.sectorCount else 1
    val theo = Standings.theoreticalBest(runs, pen, sectors)
    val map = rememberMapState()
    val trend = remember(l) { trendOf(l.runS, a.delta) }
    var videoUtc by remember(l.run.number) { mutableStateOf(0L) }
    LaunchedEffect(videoUtc) { if (videoUtc > 0) posAtUtc(l.runS, videoUtc)?.let { map.keepInView(it.first, it.second) } }
    // Everything marked on the map, so a tap on any marker can say what it means
    val autoDnfs by Hub.autoDnf.collectAsState()
    val marks = remember(l, autoDnfs) {
        val out = ArrayList<MapMark>()
        autoDnfs[l.run.number]?.let { d -> l.samples.getOrNull(d.idx)?.let { s ->
            out += MapMark(s.lat, s.lon, "DNF", "Automatic DNF", d.what + ". If the cone was moved since the course walk, tap Not a DNF on the right.")
        } }
        a.opportunities.forEachIndexed { k, o ->
            (o.at?.let { l.samples.getOrNull(it) } ?: l.runAt(o.zone.dMin))?.let { s ->
                out += MapMark(s.lat, s.lon, "${k + 1}", o.zone.name + (o.sector?.let { " · S$it" } ?: ""),
                    (if (o.lost > 0) "Opportunity ${k + 1}: lost %.2f s against run %d here. ".format(o.lost, o.vs ?: a.ref?.number ?: 0) else "Opportunity ${k + 1}. ") + o.text, k, o.zone.name)
            }
        }
        a.slides.filter { sl -> a.opportunities.none { it.zone.name == sl.section } }.forEach { sl -> l.samples.getOrNull(sl.peakIdx)?.let { s ->
            out += MapMark(s.lat, s.lon, if (sl.spun) "S" else "!", slideTitle(sl),
                "The car rotated %.0f° more than its path here. ${sl.cause}. ${sl.fix}".format(sl.peakDeg) + (sl.compare?.let { " $it" } ?: ""))
        } }
        out
    }
    var tapped by remember(l) { mutableStateOf<MapMark?>(null) }
    // Short badges by default (numbers match the list on the right); Names swaps in the corner names
    val uiPrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("ui", 0)
    var showNames by remember { mutableStateOf(runCatching { uiPrefs.getBoolean("mapNames", false) }.getOrDefault(false)) }
    val shown = remember(marks, showNames) { marks.map { m -> if (showNames && m.name != null) m.copy(label = shortName(m.name)) else m } }
    val legendPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(map.size, l.run.number) {
        if (map.size.width > 0) map.fit(l.runS.map { it.lat to it.lon } + (l.refS?.map { it.lat to it.lon } ?: emptyList()), 40f, legendPx)
    }
    Panes(Modifier.fillMaxSize()) {
        Box(Modifier.share(1.1f, 0.9f)) {
            SatMap(map, Modifier.fillMaxSize(), onTap = { _, _, at ->
                val pr = map.proj()
                tapped = placed(shown, pr).map { (m, o) -> m to (o - at).getDistance() }.filter { it.second < 60f }.minByOrNull { it.second }?.first
            }) { pr ->
                l.sec?.optimalLatLon?.takeIf { it.size > 1 }?.let { pts ->
                    val path = androidx.compose.ui.graphics.Path()
                    pts.forEachIndexed { k, (la, lo) -> val o = pr.pt(la, lo); if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                    drawPath(path, Magenta, style = androidx.compose.ui.graphics.drawscope.Stroke(3f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
                }
                course?.let { c -> c.cones.forEach { drawConeMark(pr, it, false) }; c.lines.forEach { drawTimingLine(pr, it, false) } }

                // This run, colored by where time was gained (green) or lost (red) against the reference
                drawTrendPath(pr, l.runS, if (a.ref == null) null else trend, l.runS.indices.toList(), 8f)
                l.refS?.let { drawRefOver(pr, it) }
                placed(shown, pr).forEach { (m, at) -> marker(at, m.label, if (m == tapped) Cyan else Hi) }
                // This run's car white and the other run's a yellow ring, as on every other screen
                if (videoUtc > 0 && l.runS.isNotEmpty()) {
                    posAtUtc(l.runS, videoUtc)?.let { (la, lo) -> val o = pr.pt(la, lo); drawCircle(Color.Black, 13f, o); drawCircle(MapRun, 10f, o) }
                    l.refS?.takeIf { it.isNotEmpty() }?.let { r ->
                        val (la, lo) = posAtT(r, r.first().t + tAtUtc(l.runS, videoUtc) - l.runS.first().t)
                        val o = pr.pt(la, lo); drawCircle(Color.Black, 13f, o, style = Stroke(8f)); drawCircle(MapRef, 13f, o, style = Stroke(5f))
                    }
                }
            }
            tapped?.let { m ->
                Column(Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxWidth(0.78f).background(Ink).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.title, color = OnMap, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("✕", color = OnMap, fontSize = 20.sp, modifier = Modifier.clickable { tapped = null }.padding(start = 12.dp, end = 4.dp))
                    }
                    Text(m.body, color = OnMap, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
                    m.opp?.let { k -> Text("Open this corner ›", color = Color(0xFF9CC4FF), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp).clickable { open(k) }) }
                }
            }
            if (tapped == null && marks.any { it.name != null }) Text(
                if (showNames) "Names ✓" else "Names",
                color = if (showNames) Ink else OnMap, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(if (showNames) Bg else Ink)
                    .clickable { showNames = !showNames; runCatching { uiPrefs.edit().putBoolean("mapNames", showNames).apply() } }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            if (tapped == null) FitButton(Modifier.align(Alignment.TopEnd).padding(top = 58.dp)) {
                map.fit(l.runS.map { it.lat to it.lon } + (l.refS?.map { it.lat to it.lon } ?: emptyList()), 40f, legendPx)
            }
            Text(
                if (a.ref != null) "yellow dashes: run ${a.ref.number} · green gaining · red losing" else "only run so far",
                fontSize = 13.sp, color = OnMap, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 26.dp).background(Ink).padding(6.dp),
            )
        }
        Column(Modifier.share(1f).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The run on video, right at the top when the GoPro's copy has arrived; the map dot follows it
            val vids by Hub.videos.collectAsState()
            val ru0 = l.runS.firstOrNull { it.utc > 0 }?.utc
            val clip = ru0?.let { u -> vids.firstOrNull { u in it.startUtc..it.endUtc } }
            if (clip != null && ru0 != null) InlineVideo(clip, ru0 - 2000, null, Modifier.fillMaxWidth(), onFullScreen = video) { videoUtc = it }
            val p = pen[l.run.number] ?: 0
            Text(if (a.ref != null) "Run ${l.run.number} vs run ${a.ref.number}${bestTag(a.ref.number)}" else "Run ${l.run.number}", color = Dim, fontSize = 15.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                val offs by Hub.official.collectAsState()
                val me = Hub.shownTime(l.run, runs, offs)
                Num("%.3f  ".format(me), 34.sp)
                a.ref?.let { r -> val d = me - Hub.shownTime(r, runs, offs); Num(signed(d, 3), 34.sp, deltaColor(d)) }
                if (p != 0) Text(if (p == Standings.DNF) "  DNF" else "  +$p cone" + if (p > 1) "s" else "", color = Red, fontSize = 18.sp)
            }
            if (l.run.finished) OfficialTime(l.run)
            val autoDnf by Hub.autoDnf.collectAsState()
            autoDnf[l.run.number]?.let { d -> AutoDnfBlock(l.run.number, d.what) }
            // One line: what this session's best pieces add up to
            l.sec?.takeIf { !it.optimalTotal.isNaN() }?.let { t ->
                Text("Optimal %.3f · %.2f s under your best (magenta)".format(t.optimalTotal, t.optimalGain.coerceAtLeast(0.0)), color = Magenta, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if (a.opportunities.isEmpty()) Text(
                if (a.ref == null) "Only run so far: the next run is compared with this one."
                else if (l.run.time < a.ref.time) "Run ${a.ref.number} wasn't quicker anywhere."
                else "No corner lost more than a few hundredths.", color = Dim, fontSize = 15.sp,
            )
            // A slide in an opportunity's corner is told inside that card, not as a card of its own
            val folded = a.slides.filter { sl -> a.opportunities.any { it.zone.name == sl.section } }
            a.opportunities.forEachIndexed { k, o -> OppCard(k, o, a.slides.filter { it.section == o.zone.name }) { open(k) } }
            (a.slides - folded.toSet()).forEach { sl -> SlideCard(sl, l.run.startT) }
            OutlineButton("All charts", Modifier.fillMaxWidth().height(52.dp), onClick = charts)
            // No video yet: the way to get it stays in view
            if (clip == null) VideoSection(l, video)
            // Everything else, one tap away
            var more by remember(l.run.number) { mutableStateOf(false) }
            TextAction(if (more) "Less detail ▴" else "More detail ▾") { more = !more }
            if (more) {
                l.sec?.let { SectionsCard(it, l.run.number) }
                if (l.sec == null || l.sec.optimalTotal.isNaN()) theo?.let { t ->
                    val left = l.run.time - t
                    if (left > 0.005) Text("%.2f s to optimal (%.3f)".format(left, t), color = Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                a.slips.filter { it.first != "Launch" }.takeIf { it.isNotEmpty() }?.let { WheelspinCard(it, l.samples) }
                a.launch?.let { LaunchCard(it) }
                CarCard(l.run)
                if (clip != null) VideoSection(l, video)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cones", color = Dim, fontSize = 14.sp)
                    for ((label, v) in listOf("0" to 0, "+1" to 1, "+2" to 2, "DNF" to Standings.DNF)) {
                        Chip(label, p == v, modifier = Modifier.width(64.dp)) { Hub.setPenalty(l.run.number, v) }
                    }
                }
                val refN = Hub.refNumber.value
                if (refN != l.run.number) OutlineButton("Compare others with this run", Modifier.fillMaxWidth()) { Hub.setReference(l.run.number) }
                if (refN != null) OutlineButton("Compare with best clean run again", Modifier.fillMaxWidth()) { Hub.setReference(null) }
            }
        }
    }
}

/** Last logged value of a slow channel at or before session time [t]. */
private fun slowAt(name: String, t: Double): Double? = Hub.slowLog[name]?.let { l -> synchronized(l) { l.lastOrNull { it.first <= t }?.second } }

/** Tires and oil after the run, and what the run did to them. Only shown when the car gave them. */
@Composable
private fun CarCard(r: Run) {
    val after = r.endT + 30
    val press = (1..4).map { slowAt("tire$it", after) }
    val before = (1..4).map { slowAt("tire$it", r.startT - 5) }
    val temps = (1..4).map { slowAt("tiretemp$it", after) }
    val oil = slowAt("oiltemp", after)
    val iat = slowAt("iat", r.endT)
    if (press.all { it == null } && oil == null && iat == null) return
    Column(Modifier.fillMaxWidth().bottomRule(RuleFaint).padding(vertical = 12.dp)) {
        Text("TIRES AFTER THE RUN", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Dim)
        if (press.any { it != null }) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (k in 0 until 4) Column(Modifier.weight(1f)) {
                    Label(Hub.tireName(k + 1), color = Dim)
                    Num(press[k]?.let { "%.1f".format(it) } ?: "--", 22.sp)
                    val rise = press[k]?.let { p -> before[k]?.let { p - it } }
                    Text(listOfNotNull(rise?.let { "%+.1f psi".format(it) }, temps[k]?.let { "%.0f°F".format(it) }).joinToString(" · "), fontSize = 13.sp, color = Dim)
                }
            }
            Text(if ((1..4).all { Hub.tireCorner(it) != null }) "psi, from the car's tire sensors." else "psi, from the car's tire sensors. Which number is which corner isn't confirmed yet (Signals, step 4).", fontSize = 12.sp, color = Lo, modifier = Modifier.padding(top = 2.dp))
        }
        val temps2 = listOfNotNull(oil?.let { "Oil %.0f°F".format(it) }, iat?.let { "Intake air %.0f°F at the finish".format(it) })
        if (temps2.isNotEmpty()) Text(temps2.joinToString("   "), fontSize = 15.sp, color = Hi, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Every section of this run against the best anyone did there today, with why it was slower. */
@Composable
private fun SectionsCard(t: SectionTable, run: Int) {
    val mine = t.times[run] ?: return
    if (t.sections.size < 2) return
    Column(Modifier.fillMaxWidth().bottomRule(RuleFaint).padding(vertical = 12.dp)) {
        Text("SECTION BY SECTION · vs the best run in each", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Dim)
        for (sec in t.sections) {
            val i = sec.index
            val tm = mine.getOrNull(i) ?: Double.NaN
            if (tm.isNaN()) continue
            val bestBy = t.bestRun[i]
            val loss = t.loss(run, i)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sec.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Hi, modifier = Modifier.weight(1f))
                Num("%.2f".format(tm), 16.sp)
                Spacer(Modifier.width(12.dp))
                when {
                    bestBy == run -> Text("BEST", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Green, modifier = Modifier.width(96.dp))
                    loss.isNaN() -> Spacer(Modifier.width(96.dp))
                    else -> Text("+%.2f · run %d".format(loss, bestBy), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (loss > 0.05) Red else Dim, modifier = Modifier.width(96.dp))
                }
            }
            if (bestBy >= 0 && bestBy != run && !loss.isNaN() && loss > 0.05) {
                val a = t.stats[run]?.getOrNull(i); val b = t.stats[bestBy]?.getOrNull(i)
                val why = ArrayList<String>()
                if (a != null && b != null) {
                    val dv = (a.minSpeed - b.minSpeed) * 2.236936
                    if (!dv.isNaN() && kotlin.math.abs(dv) >= 1) why += "min speed %.0f vs %.0f mph".format(a.minSpeed * 2.236936, b.minSpeed * 2.236936)
                    val db = (b.brakeOnD - a.brakeOnD) * 3.28084
                    if (!db.isNaN() && db > 10) why += "braked %.0f ft earlier".format(db)
                    val dt = (a.throttleOnD - b.throttleOnD) * 3.28084
                    if (!dt.isNaN() && dt > 10) why += "throttle %.0f ft later".format(dt)
                }
                Text(if (why.isEmpty()) "Run $bestBy was quicker here: compare the lines on the map." else "Compared with run $bestBy: " + why.joinToString(", ") + ".", fontSize = 14.sp, color = Dim, lineHeight = 19.sp)
            }
        }
    }
}

/**
 * All of a run's wheelspin in one card: where, how much, and what the throttle did each time, so the
 * fix is a concrete input ("from 20% to full in 0.2 s") rather than general advice.
 */
@Composable
private fun WheelspinCard(slips: List<Pair<String, SlipEvent>>, samples: List<Sample>) {
    // Throttle in the second before each spin: where it started and how fast it got to its peak
    data class Squeeze(val from: Double, val to: Double, val secs: Double)
    val squeezes = slips.mapNotNull { (_, ev) ->
        val t0 = samples.getOrNull(ev.idx)?.t ?: return@mapNotNull null
        val win = (maxOf(0, ev.idx - 40)..minOf(samples.size - 1, ev.idx + 8)).filter { samples[it].t in (t0 - 1.0)..(t0 + 0.3) && !samples[it].throttle.isNaN() }
        if (win.size < 4) return@mapNotNull null
        val lowK = win.filter { samples[it].t <= t0 }.minByOrNull { samples[it].throttle } ?: return@mapNotNull null
        val highK = win.filter { it >= lowK }.maxByOrNull { samples[it].throttle } ?: return@mapNotNull null
        Squeeze(samples[lowK].throttle, samples[highK].throttle, (samples[highK].t - samples[lowK].t).coerceAtLeast(0.05))
    }
    val inside = slips.all { it.second.wheel?.contains("inside") == true }
    val wheel = if (inside) "Inside rear" else slips.firstNotNullOfOrNull { it.second.wheel }?.replaceFirstChar { it.uppercase() } ?: "The rears"
    val places = slips.map { it.first.removeSuffix(" exit").removePrefix("Into ") }.distinct()
    Column(Modifier.fillMaxWidth().bottomRule(RuleFaint).padding(vertical = 12.dp)) {
        Text("WHEELSPIN · " + if (slips.size == 1) slips[0].first else "${slips.size} times", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Amber)
        Text("$wheel spun at " + places.joinToString(", ") + ": up to %.0f%% faster than the fronts.".format(slips.maxOf { it.second.peakPct }),
            fontSize = 16.sp, color = Hi, lineHeight = 21.sp, modifier = Modifier.padding(top = 3.dp))
        if (squeezes.isNotEmpty()) {
            val from = squeezes.map { it.from }.average(); val to = squeezes.map { it.to }.average(); val secs = squeezes.map { it.secs }.average()
            Text("Each time the throttle went from about %.0f%% to %.0f%% in %.1f s.".format(from, to, secs), fontSize = 16.sp, color = Hi, modifier = Modifier.padding(top = 3.dp))
            Text("Unwind the steering first, then roll on over about %.1f s instead.".format((secs * 2).coerceIn(0.8, 1.5)) +
                if (inside) " The open diff lets the unloaded inside rear go first." else "", fontSize = 15.sp, color = Dim, lineHeight = 20.sp, modifier = Modifier.padding(top = 3.dp))
        } else Text("Unwind the steering first, then squeeze the throttle on." + if (inside) " The open diff lets the unloaded inside rear go first." else "",
            fontSize = 15.sp, color = Dim, lineHeight = 20.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun SlipCard(where: String, ev: SlipEvent) {
    Column(Modifier.fillMaxWidth().bottomRule(RuleFaint).padding(vertical = 12.dp)) {
        Text("WHEELSPIN · $where", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Amber)
        Text(
            if (ev.wheel != null) "${ev.wheel.replaceFirstChar { it.uppercase() }} spun for %.1f s, up to %.0f%% faster than the front wheels%s.".format(ev.durS, ev.peakPct, if (ev.gear > 0) " (gear ${ev.gear})" else "")
            else "%.1f s in gear %d, engine up to %.0f%% ahead of the road speed.".format(ev.durS, ev.gear, ev.peakPct),
            fontSize = 16.sp, color = Hi, modifier = Modifier.padding(top = 3.dp),
        )
        Text(if (ev.wheel?.contains("inside") == true) "Open diff: the unloaded inside rear lets go first. Unwind the steering before full throttle, and squeeze it on." else "Unwind the steering before full throttle, and squeeze it on.", fontSize = 15.sp, color = Dim, modifier = Modifier.padding(top = 3.dp))
    }
}

private fun slideTitle(sl: Slide) = (if (sl.spun) "Spin" else if (sl.big) "Slide" else "Rear stepped out") + (sl.section?.let { " · $it" } ?: "")

@Composable
private fun SlideCard(sl: Slide, runStart: Double) {
    Column(Modifier.fillMaxWidth().bottomRule(RuleFaint).padding(vertical = 12.dp)) {
        Text(
            slideTitle(sl).uppercase() + " · %.1f s into the run · %.0f° past its path".format(sl.t - runStart, sl.peakDeg),
            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (sl.big) Red else Amber,
        )
        Text(sl.cause, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Hi, modifier = Modifier.padding(top = 3.dp))
        if (sl.lost != null && sl.vs != null && sl.section != null)
            Text("${sl.section} was %.2f s slower than run ${sl.vs}, the quickest there.".format(sl.lost), fontSize = 15.sp, color = Red, modifier = Modifier.padding(top = 3.dp))
        sl.compare?.let { Text(it, fontSize = 15.sp, color = Hi, lineHeight = 21.sp, modifier = Modifier.padding(top = 3.dp)) }
        Text(sl.fix, fontSize = 15.sp, color = Dim, lineHeight = 21.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun LaunchCard(l: Launch) {
    Column(Modifier.fillMaxWidth().bottomRule(RuleFaint).padding(vertical = 12.dp)) {
        Text("LAUNCH", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Dim)
        Text(
            listOfNotNull(
                l.launchRpm.takeIf { !it.isNaN() }?.let { "%,.0f rpm".format(it) },
                l.dipRpm.takeIf { !it.isNaN() }?.let { "dipped to %,.0f".format(it) },
                l.slipS.takeIf { !it.isNaN() }?.let { "clutch %.1f s".format(it) },
                if (l.launchRpm.isNaN()) "pulled %.2f g".format(l.peakG) else null,
                l.wheelspinS?.let { "wheelspin %.1f s".format(it) },
                l.sixtyFtS?.let { "60 ft %.2f s".format(it) },
                l.shiftRpm?.let { "1-2 at %,.0f".format(it) + (l.shiftS?.let { s -> " (%.1f s)".format(s) } ?: "") },
            ).joinToString(" · "),
            fontSize = 15.sp, color = Hi, modifier = Modifier.padding(top = 3.dp),
        )
        l.tips.forEach { Text("• $it", fontSize = 15.sp, color = Dim, lineHeight = 21.sp, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable
private fun OppCard(k: Int, o: Opportunity, slides: List<Slide> = emptyList(), onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().bottomRule(RuleFaint).clickable(onClick = onClick).padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Num("${k + 1}", 18.sp, Dim)
        Column(Modifier.weight(1f)) {
            Text(o.zone.name + (o.sector?.let { " · S$it" } ?: ""), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Hi)
            Text(o.text, fontSize = 15.sp, color = Dim, lineHeight = 21.sp, modifier = Modifier.padding(top = 3.dp))
            // The card already says what to do with the brake: the slide note just names it
            val braked = o.text.contains("off the brake sooner") || o.text.contains("Trail the brake")
            slides.forEach { sl ->
                val more = if (braked && sl.kind == Slide.Kind.BRAKE) "" else (sl.compare?.let { "$it " } ?: "") + sl.fix
                Text(slideTitle(sl).substringBefore(" · ") + " here: " + sl.cause.replaceFirstChar { it.lowercase() } + ". " + more,
                    fontSize = 15.sp, color = if (sl.big) Red else Amber, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (o.lost > 0) Num("%.2f".format(o.lost), 18.sp, Red) else Num("%.1f s".format(o.coastS), 18.sp, Amber)
    }
}

@Composable
private fun CornerDetail(base: Loaded, i: Int, back: () -> Unit, go: (Int) -> Unit) {
    val o = base.a.opportunities.getOrNull(i) ?: run { back(); return }
    val count = base.a.opportunities.size
    // The corner is compared with the run that was quickest here, which may not be the run the
    // overview compares with: analyze against that run for this screen
    val vsRun = o.vs?.takeIf { it != base.a.ref?.number }?.let { n -> base.usable.firstOrNull { it.number == n } }
    val forVs by produceState<Loaded?>(if (vsRun == null) base else null, base, vsRun) {
        if (vsRun != null) value = withContext(Dispatchers.Default) {
            val named = Hub.sessionCourse.value?.corners ?: emptyList()
            Loaded(Analyzer.analyze(base.samples, base.run, vsRun, named), base.samples, base.sec, base.usable)
        }
    }
    val l = forVs ?: run { Text("Analyzing…", color = Dim, modifier = Modifier.padding(20.dp)); return }
    val a = l.a
    // This corner on the compared run's path (named corners match by name; Launch/Finish keep their distances)
    val z = a.zones.firstOrNull { it.name == o.zone.name } ?: o.zone
    val lo = z.d0 - 15; val hi = z.d1 + 15
    val runIdx = a.dist.indices.filter { a.dist[it] in lo..hi }
    val refIdx = a.refDist.indices.filter { a.refDist[it] in lo..hi }
    val runZ = runIdx.mapNotNull { l.runS.getOrNull(it) }
    val refZ = refIdx.mapNotNull { l.refS?.getOrNull(it) }
    val bMax = remember(l) { l.samples.maxOf { if (it.brake.isNaN()) 0.0 else it.brake }.coerceAtLeast(1.0) }
    fun brakeStart(ss: List<Sample>) = ss.firstOrNull { !it.brake.isNaN() && it.brake > 0.12 * bMax }
    val opt = rememberOptimal(Hub.sessionCourse.value)
    val map = rememberMapState()
    val legendPx = with(androidx.compose.ui.platform.LocalDensity.current) { (if (isPortrait()) 34.dp else 70.dp).toPx() }
    LaunchedEffect(map.size, i, l) { if (map.size.width > 0) map.fit((runZ + refZ).map { it.lat to it.lon }, 40f, legendPx) }
    val trend = remember(l) { trendOf(l.runS, a.delta) }
    // Drag along a chart: this run's car at that spot, and the other run's car at the same moment
    // (time counted from entering this corner), so both cars move together through the corner
    var chartCursor by remember(i) { mutableStateOf<Double?>(null) }
    val zoom = remember(i) { ChartZoom() }
    var played by remember(i) { mutableStateOf<Double?>(null) }
    val cursor = played ?: chartCursor
    val rk = cursor?.let { c -> runIdx.indices.minByOrNull { abs(a.dist[runIdx[it]] - lo - c) } }
    val gk = rk?.let { k -> refZ.takeIf { it.isNotEmpty() && runZ.isNotEmpty() }?.let { r ->
        val el = runZ[k].t - runZ.first().t
        r.indices.minByOrNull { abs((r[it].t - r.first().t) - el) }
    } }
    // A zoomed-in map follows the car as the chart is scrubbed
    LaunchedEffect(rk) { rk?.let { runZ.getOrNull(it) }?.let { map.keepInView(it.lat, it.lon) } }
    LinkChartsToMap(map, runZ, remember(runIdx, lo) { DoubleArray(runIdx.size) { a.dist[runIdx[it]] - lo } }, zoom)

    // The GoPro clip of just this corner, on repeat; it follows the chart cursor
    val vids by Hub.videos.collectAsState()
    val cu0 = runZ.firstOrNull { it.utc > 0 }?.utc; val cu1 = runZ.lastOrNull { it.utc > 0 }?.utc
    val clip = cu0?.let { u -> vids.firstOrNull { u in it.startUtc..it.endUtc } }
    val seekK = chartCursor?.let { c -> runIdx.indices.minByOrNull { abs(a.dist[runIdx[it]] - lo - c) } }
    val cursorUtc = seekK?.let { runZ.getOrNull(it) }?.utc?.takeIf { it > 0 }
    var cornerChart by remember { mutableStateOf(ChartKind.SPEED) }
    // Where each run apexed (its slowest point in the corner, as the coaching measures it), marked on the
    // map and chart when the advice talks about the apex, so it's clear which spot it means
    val showApex = o.text.contains("apex", ignoreCase = true)
    val runApex = if (!showApex) null else runIdx.indices.filter { a.dist[runIdx[it]] in z.d0..z.d1 && it < runZ.size }.minByOrNull { runZ[it].speed }
    val refApex = if (!showApex) null else refIdx.indices.filter { a.refDist[refIdx[it]] in z.d0..z.d1 && it < refZ.size }.minByOrNull { refZ[it].speed }
    val apexPins = listOfNotNull(
        refApex?.let { Pin(a.refDist[refIdx[it]] - lo, RefOrange, "run ${a.ref?.number} apex") },
        runApex?.let { Pin(a.dist[runIdx[it]] - lo, RunBlue, "run ${l.run.number} apex") },
    )
    // Map and video stay on screen while the charts scroll, so all three can be watched together
    ScrubLayout(isPortrait(), 0.8f,
        map = { m ->
            Box(m) {
                SatMap(map, Modifier.fillMaxSize()) { pr ->
                    opt?.let { drawOptimal(pr, it, 3f) }
                    Hub.sessionCourse.value?.cones?.forEach { drawConeMark(pr, it, false) }
                    if (runZ.size > 1) { if (a.ref != null) drawTrendPath(pr, l.runS, trend, runIdx, 8f) else drawPathOf(pr, runZ, MapRun, 6f) }
                    if (refZ.size > 1) drawRefOver(pr, refZ)
                    if (cursor == null) brakeStart(refZ)?.let { drawCircle(Color.Black, 12f, pr.pt(it.lat, it.lon)); drawCircle(MapRef, 10f, pr.pt(it.lat, it.lon)) }
                    if (cursor == null) brakeStart(runZ)?.let { drawCircle(Color.Black, 12f, pr.pt(it.lat, it.lon)); drawCircle(MapRun, 10f, pr.pt(it.lat, it.lon)) }
                    rk?.let { runZ.getOrNull(it) }?.let { s -> val o = pr.pt(s.lat, s.lon); drawCircle(Color.Black, 17f, o); drawCircle(Color.White, 14f, o); drawCircle(MapRun, 11f, o) }
                    gk?.let { refZ.getOrNull(it) }?.let { s -> val o = pr.pt(s.lat, s.lon); drawCircle(Color.Black, 17f, o, style = Stroke(8f)); drawCircle(MapRef, 17f, o, style = Stroke(5f)) }
                    // Each run's apex: a dashed line across its path, in its color
                    fun apexLine(ss: List<Sample>, k: Int, c: Color) {
                        val o = pr.pt(ss[k].lat, ss[k].lon)
                        val p0 = ss[(k - 3).coerceAtLeast(0)].let { pr.pt(it.lat, it.lon) }; val p1 = ss[(k + 3).coerceAtMost(ss.size - 1)].let { pr.pt(it.lat, it.lon) }
                        val dx = p1.x - p0.x; val dy = p1.y - p0.y; val len = kotlin.math.hypot(dx, dy).takeIf { it > 0.5f } ?: return
                        val nx = -dy / len * 34f; val ny = dx / len * 34f
                        val a0 = Offset(o.x - nx, o.y - ny); val a1 = Offset(o.x + nx, o.y + ny)
                        drawLine(Color.Black, a0, a1, 9f)
                        drawLine(c, a0, a1, 5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                    }
                    // Slides in this corner
                    base.a.slides.forEach { sl -> if (sl.peakIdx - l.run.startIdx in runIdx) l.samples.getOrNull(sl.peakIdx)?.let { s -> marker(pr.pt(s.lat, s.lon), if (sl.spun) "S" else "!", if (sl.big) Red else Amber) } }
                    // Apex lines over a slide mark (a slide often peaks right at the apex)
                    refApex?.takeIf { it < refZ.size }?.let { apexLine(refZ, it, MapRef) }
                    runApex?.takeIf { it < runZ.size }?.let { apexLine(runZ, it, MapRun) }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (a.ref != null && cursor == null) Text("yellow: run ${a.ref.number} · dots: brake on" + (if (apexPins.isNotEmpty()) " · line across: apex" else ""),color = OnMap, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xB3000000)).padding(4.dp))
                    if (rk != null && gk != null) {
                        val gapFt = (refIdx.getOrNull(gk)?.let { a.refDist[it] }?.minus(a.dist[runIdx[rk]]) ?: Double.NaN) * M_TO_FT
                        if (!gapFt.isNaN()) Text(if (gapFt >= 0) "Run ${a.ref?.number} is %.0f ft ahead".format(gapFt) else "Run ${l.run.number} is %.0f ft ahead".format(-gapFt),
                            color = if (gapFt > 0) Red else Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xD9000000)).padding(4.dp))
                    }
                }
                FitButton(Modifier.align(Alignment.TopEnd)) { map.fit((runZ + refZ).map { it.lat to it.lon }, 40f, legendPx) }
            }
        },
        video = if (clip != null && cu0 != null) { m -> InlineVideo(clip, cu0 - 1000, cu1?.plus(500), m, seekUtc = cursorUtc) { u ->
            runZ.indices.minByOrNull { abs(runZ[it].utc - u) }?.let { q -> played = a.dist[runIdx[q]] - lo }
        } } else null,
        scroll = false,
    ) {
        // The advice scrolls on its own above one chart (picked from its title) that stays put, so a
        // finger on the chart only ever scrubs it
        val xr = runIdx.map { a.dist[it] - lo }.toDoubleArray()
        val xf = refIdx.map { a.refDist[it] - lo }.toDoubleArray()
        val span = hi - lo
        fun br(s: Sample) = if (a.brakeIsCan) (if (s.brake.isNaN()) 0.0 else s.brake / bMax * 100) else max(0.0, -s.lonG * 100)
        fun th(s: Sample) = if (s.throttle.isNaN()) 0.0 else s.throttle
        val kinds = listOfNotNull(ChartKind.SPEED, ChartKind.PEDALS, if (a.ref != null) ChartKind.DELTA else null, ChartKind.LATG)
        val rs = rk?.let { runZ.getOrNull(it) }
        val fs = gk?.let { refZ.getOrNull(it) }
        val other = a.ref?.number
        val readout: String? = rs?.let { r -> when (cornerChart) {
            ChartKind.SPEED -> "%.0f".format(r.speed * MS_TO_MPH) + (fs?.let { "  · run $other %.0f".format(it.speed * MS_TO_MPH) } ?: "")
            ChartKind.PEDALS -> "%.0f%% · %.0f%%".format(br(r), th(r)) + (fs?.let { "  · run $other %.0f%% · %.0f%%".format(br(it), th(it)) } ?: "")
            ChartKind.DELTA -> rk?.let { a.delta.getOrNull(runIdx[it]) }?.takeIf { !it.isNaN() }?.let { signed(it) }
            ChartKind.LATG -> "%.2f".format(r.latG) + (fs?.let { "  · run $other %.2f".format(it.latG) } ?: "")
        } }
        val onScrub: (Double) -> Unit = { chartCursor = it; played = null }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val chartH = (maxHeight * 0.45f).coerceIn(130.dp, 320.dp)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Opportunity ${i + 1}", color = Dim)
                    Text(z.name + (o.sector?.let { " · S$it" } ?: ""), fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Hi)
                    if (o.lost > 0) Text("lost %.2f s vs run %d".format(o.lost, o.vs ?: a.ref?.number ?: 0), color = Red, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(o.text, fontSize = 16.sp, color = Dim, lineHeight = 23.sp)
                    base.a.slides.filter { sl -> sl.peakIdx - l.run.startIdx in runIdx }.forEach { sl ->
                        Text(slideTitle(sl) + ": " + sl.cause + ". " + (sl.compare?.let { "$it " } ?: "") + sl.fix, fontSize = 15.sp, color = if (sl.big) Red else Amber, lineHeight = 21.sp)
                    }
                }
                ChartPicker(cornerChart, kinds, a.brakeIsCan, readout) { cornerChart = it }
                when (cornerChart) {
                    ChartKind.SPEED -> Chart(span, emptyList(), chartH, listOfNotNull(
                        if (refZ.isNotEmpty()) Series(xf, refZ.map { it.speed * MS_TO_MPH }, RefOrange) else null,
                        Series(xr, runZ.map { it.speed * MS_TO_MPH }, RunBlue),
                    ), cursor = cursor, onScrub = onScrub, zoom = zoom, pins = apexPins)
                    ChartKind.PEDALS -> Chart(span, emptyList(), chartH, listOfNotNull(
                        if (refZ.isNotEmpty()) Series(xf, refZ.map(::br), Red.copy(alpha = 0.4f)) else null,
                        if (refZ.isNotEmpty()) Series(xf, refZ.map(::th), Green.copy(alpha = 0.4f)) else null,
                        Series(xr, runZ.map(::br), Red),
                        Series(xr, runZ.map(::th), Green),
                    ), fixed = 0.0 to 100.0, cursor = cursor, onScrub = onScrub, zoom = zoom, pins = apexPins)
                    ChartKind.DELTA -> Chart(span, emptyList(), chartH, listOf(Series(xr, runIdx.map { a.delta[it] }, Amber)), zero = true, delta = true, cursor = cursor, onScrub = onScrub, zoom = zoom, pins = apexPins)
                    ChartKind.LATG -> Chart(span, emptyList(), chartH, listOfNotNull(
                        if (refZ.isNotEmpty()) Series(xf, refZ.map { it.latG }, RefOrange) else null,
                        Series(xr, runZ.map { it.latG }, Color(0xFFB388FF)),
                    ), zero = true, cursor = cursor, onScrub = onScrub, zoom = zoom, pins = apexPins)
                }
                if (count > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (i > 0) OutlineButton("◀ Opportunity $i", Modifier.weight(1f)) { go(i - 1) }
                    if (i < count - 1) OutlineButton("Opportunity ${i + 2} ▶", Modifier.weight(1f)) { go(i + 1) }
                }
            }
        }
    }
}

@Composable
private fun Charts(l: Loaded, back: () -> Unit) {
    val a = l.a
    val runS = l.runS
    val refS = l.refS
    var chartCursor by remember { mutableStateOf<Double?>(null) }
    var played by remember { mutableStateOf<Double?>(null) }
    val cursor = played ?: chartCursor
    val zoom = remember { ChartZoom() }
    val maxD = max(a.dist.lastOrNull() ?: 0.0, a.refDist.lastOrNull() ?: 0.0)
    val corners = a.zones.map { it.dMin to it.name.removePrefix("Corner ") }
    val bMax = if (a.brakeIsCan) l.samples.maxOf { if (it.brake.isNaN()) 0.0 else it.brake }.coerceAtLeast(1.0) else 1.0
    fun br(s: Sample) = if (a.brakeIsCan) (if (s.brake.isNaN()) 0.0 else s.brake / bMax * 100) else max(0.0, -s.lonG * 100)
    fun th(s: Sample) = if (s.throttle.isNaN()) 0.0 else s.throttle
    val k = cursor?.let { d -> a.dist.indices.minByOrNull { abs(a.dist[it] - d) } }
    val j = cursor?.let { d -> a.refDist.indices.minByOrNull { abs(a.refDist[it] - d) } }
    val map = rememberMapState()
    LaunchedEffect(map.size) { if (map.size.width > 0) map.fit(runS.map { it.lat to it.lon }, 24f) }
    val trend = remember(l) { trendOf(l.runS, a.delta) }
    LaunchedEffect(k) { k?.let { runS.getOrNull(it) }?.let { map.keepInView(it.lat, it.lon) } }
    LinkChartsToMap(map, runS, a.dist, zoom)
    // Where the other run was at the same moment (a ghost car), and the gap between them
    val ghost = k?.let { kk -> refS?.takeIf { it.isNotEmpty() }?.let { r ->
        val el = runS[kk].t - runS.first().t
        r.indices.minByOrNull { abs((r[it].t - r.first().t) - el) }
    } }
    // The run's video, following the cursor
    val vids by Hub.videos.collectAsState()
    val ru0 = runS.firstOrNull { it.utc > 0 }?.utc
    val clip = ru0?.let { u -> vids.firstOrNull { u in it.startUtc..it.endUtc } }
    ScrubLayout(isPortrait(), 0.75f,
        map = { m ->
            Box(m) {
                SatMap(map, Modifier.fillMaxSize()) { pr ->
                    if (a.ref != null) drawTrendPath(pr, runS, trend, runS.indices.toList(), 6f) else drawPathOf(pr, runS, MapRun.copy(alpha = 0.9f), 4f)
                    refS?.let { drawRefOver(pr, it) }
                    k?.let { runS.getOrNull(it) }?.let { s -> val o = pr.pt(s.lat, s.lon); drawCircle(Color.Black, 14f, o); drawCircle(Color.White, 11f, o) }
                    ghost?.let { refS?.getOrNull(it) }?.let { s -> val o = pr.pt(s.lat, s.lon); drawCircle(Color.Black, 14f, o, style = Stroke(8f)); drawCircle(MapRef, 14f, o, style = Stroke(5f)) }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (a.ref != null) Text("white = run ${l.run.number} · yellow = run ${a.ref.number}", color = OnMap, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xB3000000)).padding(4.dp))
                    if (ghost != null && k != null) {
                        val gapFt = (a.refDist.getOrNull(ghost)?.minus(a.dist[k]) ?: Double.NaN) * M_TO_FT
                        if (!gapFt.isNaN()) Text(if (gapFt >= 0) "Run ${a.ref?.number} is %.0f ft ahead".format(gapFt) else "Run ${l.run.number} is %.0f ft ahead".format(-gapFt),
                            color = if (gapFt > 0) Red else Green, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color(0xD9000000)).padding(4.dp))
                    }
                }
                FitButton(Modifier.align(Alignment.TopEnd)) { map.fit(runS.map { it.lat to it.lon }, 24f) }
            }
        },
        video = if (clip != null && ru0 != null) { m -> InlineVideo(clip, ru0 - 2000, null, m,
            seekUtc = chartCursor?.let { d -> a.dist.indices.minByOrNull { abs(a.dist[it] - d) } }?.let { runS.getOrNull(it) }?.utc?.takeIf { it > 0 }) { u ->
            runS.indices.minByOrNull { abs(runS[it].utc - u) }?.let { q -> played = a.dist.getOrNull(q) }
        } } else null,
        scroll = false,
    ) {
        // Two charts filling the space, each switchable from its label: no scrolling on this page
        val kinds = listOfNotNull(ChartKind.SPEED, ChartKind.PEDALS, if (a.ref != null) ChartKind.DELTA else null, ChartKind.LATG)
        var top by remember { mutableStateOf(ChartKind.SPEED) }
        var bottom by remember { mutableStateOf(ChartKind.PEDALS) }
        val rs = k?.let { runS.getOrNull(it) }
        val fs = j?.let { refS?.getOrNull(it) }
        val other = a.ref?.number
        fun readout(kind: ChartKind): String? = rs?.let { r -> when (kind) {
            ChartKind.SPEED -> "%.0f".format(r.speed * MS_TO_MPH) + (fs?.let { "  · run $other %.0f".format(it.speed * MS_TO_MPH) } ?: "")
            ChartKind.PEDALS -> "%.0f%% · %.0f%%".format(br(r), th(r)) + (fs?.let { "  · run $other %.0f%% · %.0f%%".format(br(it), th(it)) } ?: "")
            ChartKind.DELTA -> k?.let { a.delta.getOrNull(it) }?.takeIf { !it.isNaN() }?.let { signed(it) }
            ChartKind.LATG -> "%.2f".format(r.latG) + (fs?.let { "  · run $other %.2f".format(it.latG) } ?: "")
        } }
        @Composable fun one(kind: ChartKind, h: Dp, pick: (ChartKind) -> Unit) {
            ChartPicker(kind, kinds, a.brakeIsCan, readout(kind), pick)
            when (kind) {
                ChartKind.SPEED -> Chart(maxD, corners, h, listOfNotNull(
                    refS?.let { Series(a.refDist, it.map { s -> s.speed * MS_TO_MPH }, Dim) },
                    Series(a.dist, runS.map { it.speed * MS_TO_MPH }, RunBlue),
                ), cursor = cursor, onScrub = { chartCursor = it; played = null }, zoom = zoom)
                ChartKind.PEDALS -> Chart(maxD, corners, h, listOfNotNull(
                    refS?.let { Series(a.refDist, it.map(::br), Red.copy(alpha = 0.35f)) },
                    refS?.let { Series(a.refDist, it.map(::th), Green.copy(alpha = 0.35f)) },
                    Series(a.dist, runS.map(::br), Red),
                    Series(a.dist, runS.map(::th), Green),
                ), fixed = 0.0 to 100.0, cursor = cursor, onScrub = { chartCursor = it; played = null }, zoom = zoom)
                ChartKind.DELTA -> Chart(maxD, corners, h, listOf(Series(a.dist, a.delta.toList(), Amber)), zero = true, delta = true, cursor = cursor, onScrub = { chartCursor = it; played = null }, zoom = zoom)
                ChartKind.LATG -> Chart(maxD, corners, h, listOfNotNull(
                    refS?.let { Series(a.refDist, it.map { s -> s.latG }, Dim) },
                    Series(a.dist, runS.map { it.latG }, Color(0xFFB388FF)),
                ), zero = true, cursor = cursor, onScrub = { chartCursor = it; played = null }, zoom = zoom)
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val h = ((maxHeight - 80.dp) / 2).coerceAtLeast(90.dp)
            Column {
                one(top, h) { top = it }
                one(bottom, h) { bottom = it }
            }
        }
    }
}

/**
 * When the map is zoomed in, the charts on the same screen show the same stretch of the run: the
 * stretch of [ss] in view around the middle of the map, as chart positions [xs]. Zooming back out
 * puts the charts back to the whole run.
 */
@Composable
private fun LinkChartsToMap(map: MapState, ss: List<Sample>, xs: DoubleArray, zoom: ChartZoom) {
    LaunchedEffect(map, ss, xs) {
        var linked = false
        androidx.compose.runtime.snapshotFlow { listOf(map.lat, map.lon, map.zoom, map.size.width.toDouble()) }.collect {
            if (map.size.width == 0 || ss.isEmpty()) return@collect
            if (map.zoom < map.fitZoom + 0.25) { if (linked) { zoom.reset(); linked = false }; return@collect }
            val pr = map.proj()
            val inView = BooleanArray(ss.size) { val o = pr.pt(ss[it].lat, ss[it].lon); o.x in 0f..pr.w && o.y in 0f..pr.h }
            // The stretch in view that passes nearest the middle (another part of the course may cross the view too)
            val mid = ss.indices.filter { inView[it] }.minByOrNull { val o = pr.pt(ss[it].lat, ss[it].lon); kotlin.math.hypot(o.x - pr.w / 2, o.y - pr.h / 2) } ?: return@collect
            var i0 = mid; while (i0 > 0 && inView[i0 - 1]) i0--
            var i1 = mid; while (i1 < ss.size - 1 && inView[i1 + 1]) i1++
            val x0 = xs.getOrNull(i0) ?: return@collect; val x1 = xs.getOrNull(i1) ?: return@collect
            if (x1 - x0 > 1.0) { zoom.x0 = x0; zoom.x1 = x1; linked = true }
        }
    }
}

/** Small / medium / large for an overlay on the full-screen video. */
private enum class OverlaySize(val course: Pair<Int, Int>, val chartW: Int, val chartH: Int) {
    // Large course + large chart still fit side by side on the tablet (about 766 dp wide)
    S(180 to 120, 280, 70), M(250 to 170, 340, 100), L(320 to 220, 400, 150);
    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * The run's video filling the screen, with the course (this run, the other run as a ghost) and one
 * chart (switchable) following it; dragging the chart moves the video. Landscape: both sit over the
 * bottom corners, each with a size button. Portrait: the video on top, the course and the chart
 * below it, so nothing covers the video.
 */
@Composable
private fun FullVideo(l: Loaded, clip: com.kimes.daq.gopro.VideoLink, ru0: Long, back: () -> Unit) {
    val a = l.a
    val runS = l.runS
    val refS = l.refS
    val port = isPortrait()
    var videoUtc by remember { mutableStateOf(0L) }
    var seekD by remember { mutableStateOf<Double?>(null) }
    var kind by remember { mutableStateOf(if (a.ref != null) ChartKind.DELTA else ChartKind.SPEED) }
    var courseSize by remember { mutableStateOf(OverlaySize.M) }
    var chartSize by remember { mutableStateOf(OverlaySize.M) }
    val maxD = max(a.dist.lastOrNull() ?: 0.0, a.refDist.lastOrNull() ?: 0.0)
    val corners = a.zones.map { it.dMin to it.name }
    val bMax = if (a.brakeIsCan) l.samples.maxOf { if (it.brake.isNaN()) 0.0 else it.brake }.coerceAtLeast(1.0) else 1.0
    fun br(s: Sample) = if (a.brakeIsCan) (if (s.brake.isNaN()) 0.0 else s.brake / bMax * 100) else max(0.0, -s.lonG * 100)
    fun th(s: Sample) = if (s.throttle.isNaN()) 0.0 else s.throttle
    // Where the car is: from the playing video, or from a finger on the chart
    val k = if (videoUtc > 0) runS.indices.minByOrNull { abs(runS[it].utc - videoUtc) } else seekD?.let { d -> a.dist.indices.minByOrNull { abs(a.dist[it] - d) } }
    val cursor = k?.let { a.dist.getOrNull(it) }
    val car = if (videoUtc > 0) posAtUtc(runS, videoUtc) else k?.let { runS.getOrNull(it) }?.let { it.lat to it.lon }
    // The other run's car at the same time into the run, between its samples (snapping to the nearest
    // sample made it jump while this run's car glided)
    val ghostPos = refS?.takeIf { it.isNotEmpty() && runS.isNotEmpty() }?.let { r ->
        val el = if (videoUtc > 0) tAtUtc(runS, videoUtc) - runS.first().t else k?.let { runS[it].t - runS.first().t }
        el?.let { posAtT(r, r.first().t + it) }
    }
    val zoom = remember { ChartZoom() }
    val trend = remember(l) { trendOf(l.runS, a.delta) }
    val map = rememberMapState()
    LaunchedEffect(map.size, courseSize) { if (map.size.width > 0) map.fit(runS.map { it.lat to it.lon }, 16f) }
    LaunchedEffect(car) { car?.let { map.keepInView(it.first, it.second) } }
    LinkChartsToMap(map, runS, a.dist, zoom)
    androidx.activity.compose.BackHandler { back() }

    @Composable fun sizeButton(m: Modifier, onClick: () -> Unit) = Text("⤡", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = m.background(Color(0xCC000000)).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 2.dp))
    @Composable fun course(m: Modifier, withSize: Boolean) = Box(m.background(Color(0xFF1B1F1A))) {
        SatMap(map, Modifier.fillMaxSize()) { pr ->
            if (a.ref != null) drawTrendPath(pr, runS, trend, runS.indices.toList(), 4f) else drawPathOf(pr, runS, Color.White, 3f)
            refS?.let { drawRefOver(pr, it) }
            car?.let { (la, lo) -> val o = pr.pt(la, lo); drawCircle(Color.Black, 11f, o); drawCircle(Color.White, 9f, o) }
            ghostPos?.let { (la, lo) -> val o = pr.pt(la, lo); drawCircle(Color.Black, 12f, o, style = Stroke(8f)); drawCircle(MapRef, 12f, o, style = Stroke(5f)) }
        }
        // Which car is which
        if (a.ref != null) Row(Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text("● run ${l.run.number}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("  ○ run ${a.ref.number}", color = MapRef, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (withSize) sizeButton(Modifier.align(Alignment.TopEnd).padding(4.dp)) { courseSize = courseSize.next() }
    }
    @Composable fun chart(m: Modifier, h: Dp, withSize: Boolean) = Column(m.background(Color(0xE6EFE6CF)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        val rs = k?.let { runS.getOrNull(it) }
        val kinds = listOfNotNull(ChartKind.SPEED, ChartKind.PEDALS, if (a.ref != null) ChartKind.DELTA else null, ChartKind.LATG)
        val readout = rs?.let { r -> when (kind) {
            ChartKind.SPEED -> "%.0f".format(r.speed * MS_TO_MPH)
            ChartKind.PEDALS -> "%.0f%% · %.0f%%".format(br(r), th(r))
            ChartKind.DELTA -> k.let { a.delta.getOrNull(it) }?.takeIf { !it.isNaN() }?.let { signed(it) }
            ChartKind.LATG -> "%.2f".format(r.latG)
        } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ChartPicker(kind, kinds, a.brakeIsCan, readout) { kind = it } }
            if (withSize) sizeButton(Modifier) { chartSize = chartSize.next() }
        }
        val onScrub: (Double) -> Unit = { seekD = it; videoUtc = 0L }
        when (kind) {
            ChartKind.SPEED -> Chart(maxD, corners, h, listOfNotNull(refS?.let { Series(a.refDist, it.map { s -> s.speed * MS_TO_MPH }, Dim) }, Series(a.dist, runS.map { it.speed * MS_TO_MPH }, RunBlue)), cursor = cursor, onScrub = onScrub, zoom = zoom)
            ChartKind.PEDALS -> Chart(maxD, corners, h, listOf(Series(a.dist, runS.map(::br), Red), Series(a.dist, runS.map(::th), Green)), fixed = 0.0 to 100.0, cursor = cursor, onScrub = onScrub, zoom = zoom)
            ChartKind.DELTA -> Chart(maxD, corners, h, listOf(Series(a.dist, a.delta.toList(), Amber)), zero = true, delta = true, cursor = cursor, onScrub = onScrub, zoom = zoom)
            ChartKind.LATG -> Chart(maxD, corners, h, listOf(Series(a.dist, runS.map { it.latG }, Color(0xFFB388FF))), zero = true, cursor = cursor, onScrub = onScrub, zoom = zoom)
        }
    }
    val seekUtc = seekD?.let { d -> a.dist.indices.minByOrNull { abs(a.dist[it] - d) } }?.let { runS.getOrNull(it) }?.utc?.takeIf { it > 0 }

    if (port) Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxWidth()) {
            InlineVideo(clip, ru0 - 2000, null, Modifier.fillMaxWidth(), seekUtc = seekUtc) { videoUtc = it }
            BackButton("Run ${l.run.number}", Modifier.align(Alignment.BottomStart).padding(8.dp).width(130.dp), height = 36) { back() }
        }
        course(Modifier.fillMaxWidth().weight(1f), withSize = false)
        chart(Modifier.fillMaxWidth(), 170.dp, withSize = false)
    } else Box(Modifier.fillMaxSize().background(Color.Black)) {
        InlineVideo(clip, ru0 - 2000, null, Modifier.align(Alignment.Center).fillMaxHeight(), seekUtc = seekUtc) { videoUtc = it }
        BackButton("Run ${l.run.number}", Modifier.align(Alignment.TopStart).padding(12.dp).width(140.dp)) { back() }
        course(Modifier.align(Alignment.BottomStart).padding(12.dp).size(width = courseSize.course.first.dp, height = courseSize.course.second.dp), withSize = true)
        chart(Modifier.align(Alignment.BottomEnd).padding(12.dp).width(chartSize.chartW.dp), chartSize.chartH.dp, withSize = true)
    }
}

/** What an All charts slot shows. */
private enum class ChartKind { SPEED, PEDALS, DELTA, LATG }

/**
 * A chart's title with a ▾ to switch it to another chart, and the value under the cursor.
 * Brake and throttle are named in their own colors instead of a long legend.
 */
@Composable
private fun ChartPicker(kind: ChartKind, kinds: List<ChartKind>, brakeIsCan: Boolean, readout: String?, pick: (ChartKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    @Composable fun title(kk: ChartKind) = when (kk) {
        ChartKind.SPEED -> Text("Speed mph", color = RunBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        ChartKind.PEDALS -> Row {
            Text(if (brakeIsCan) "Brake" else "Decel", color = Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(" · ", color = Dim, fontSize = 14.sp)
            Text("Throttle", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        ChartKind.DELTA -> Text("Time delta s · up = gaining", color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        ChartKind.LATG -> Text("Lateral g", color = Color(0xFFB388FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Box(Modifier.padding(top = 8.dp)) {
        Row(Modifier.clickable { open = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            title(kind)
            Text(" ▾", color = Dim, fontSize = 15.sp)
            if (readout != null) Text(readout, color = Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            kinds.forEach { kk -> androidx.compose.material3.DropdownMenuItem(text = { title(kk) }, onClick = { open = false; pick(kk) }) }
        }
    }
}

/**
 * Scrubbing screens: the map and the video stay put while the charts scroll, so all three can be
 * watched at once. Landscape: map over video on the left, charts on the right. Portrait: video,
 * then map, then the charts.
 */
@Composable
private fun ScrubLayout(port: Boolean, sideWeight: Float, map: @Composable (Modifier) -> Unit, video: (@Composable (Modifier) -> Unit)?, scroll: Boolean = true, body: @Composable ColumnScope.() -> Unit) {
    val sc = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
    if (port) Column(Modifier.fillMaxSize()) {
        // Video at its own shape (no bars, at most 230 dp tall), the map full width under it
        // Map and video side by side, so the page below has room for the advice and a chart
        if (video != null) Row(Modifier.fillMaxWidth().height(200.dp)) {
            map(Modifier.weight(1f).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) { video(Modifier.fillMaxWidth()) }
        } else map(Modifier.fillMaxWidth().height(280.dp))
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).then(sc), verticalArrangement = Arrangement.spacedBy(6.dp)) { body() }
    } else Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(sideWeight).fillMaxHeight()) {
            map(Modifier.weight(1f).fillMaxWidth())
            if (video != null) video(Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp).then(sc), verticalArrangement = Arrangement.spacedBy(6.dp)) { body() }
    }
}

/** Back to the whole run (or corner) on the map, after panning away or zooming in. */
@Composable
private fun FitButton(modifier: Modifier, onClick: () -> Unit) = Text(
    "⤢ Fit", color = OnMap, fontSize = 15.sp, fontWeight = FontWeight.Bold,
    modifier = modifier.padding(10.dp).background(Ink).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
)

@Composable
private fun CursorRow(name: String, run: String, ref: String?) {
    Row(Modifier.fillMaxWidth().bottomRule(RuleFaint).padding(vertical = 6.dp)) {
        Text(name, color = Dim, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Num(run, 14.sp, Hi, Modifier.width(62.dp))
        if (ref != null) Num(ref, 14.sp, Dim, Modifier.width(56.dp))
    }
}

private fun DrawScope.drawPathOf(pr: MapProj, ss: List<Sample>, c: Color, w: Float) {
    val p = Path()
    ss.forEachIndexed { i, s -> val o = pr.pt(s.lat, s.lon); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
    drawPath(p, c, style = Stroke(w))
}

/** A marker on the Review map and what it means when tapped. opp = opportunity index to open. */
private data class MapMark(val lat: Double, val lon: Double, val label: String, val title: String, val body: String, val opp: Int? = null, val name: String? = null)

/** A corner name short enough for a map tag. */
private fun shortName(n: String) = if (n.length <= 14) n else n.take(13).trimEnd() + "…"

/** Where each marker is drawn: a marker that would cover one already placed is nudged up until it's clear. */
private fun placed(marks: List<MapMark>, pr: MapProj): List<Pair<MapMark, Offset>> {
    fun halfW(m: MapMark) = if (m.label.length <= 2) 20f else m.label.length * 7.5f + 12f
    val out = ArrayList<Pair<MapMark, Offset>>()
    for (m in marks.sortedBy { it.label.length <= 2 }) { // corner tags keep their spot; letters move
        var at = pr.pt(m.lat, m.lon)
        var tries = 0
        while (tries++ < 6 && out.any { (o, p) -> kotlin.math.abs(p.x - at.x) < halfW(o) + halfW(m) + 2f && kotlin.math.abs(p.y - at.y) < 42f }) at = at.copy(y = at.y - 44f)
        out += m to at
    }
    return out
}

/** Round badge for a single letter, a rounded tag for a corner name; the text sits centered either way. */
private fun DrawScope.marker(at: Offset, label: String, ring: Color) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb((Hi.alpha * 255).toInt(), (Hi.red * 255).toInt(), (Hi.green * 255).toInt(), (Hi.blue * 255).toInt())
        textSize = 26f; isAntiAlias = true; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER
    }
    val w = paint.measureText(label)
    val half = if (label.length <= 2) 20f else w / 2 + 12f
    val tl = Offset(at.x - half, at.y - 20f); val sz = androidx.compose.ui.geometry.Size(half * 2, 40f)
    val r = androidx.compose.ui.geometry.CornerRadius(20f, 20f)
    drawRoundRect(Bg, tl, sz, r)
    drawRoundRect(ring, tl, sz, r, style = Stroke(if (ring == Hi) 2.5f else 5f))
    // Center vertically on the font's real ascent/descent, not a guessed offset
    drawContext.canvas.nativeCanvas.drawText(label, at.x, at.y - (paint.ascent() + paint.descent()) / 2, paint)
}

@Composable
private fun ChartLabel(t: String, c: Color, readout: String? = null) = Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
    Text(t, color = c, fontSize = 13.sp)
    // The value under the cursor, big enough to read at a glance
    if (readout != null) Text(readout, color = Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
}

/** The run's clock ([Sample.t]) at GPS time [utc], between samples. */
private fun tAtUtc(ss: List<Sample>, utc: Long): Double {
    if (utc <= ss.first().utc) return ss.first().t
    if (utc >= ss.last().utc) return ss.last().t
    var lo = 0; var hi = ss.size - 1
    while (hi - lo > 1) { val m = (lo + hi) / 2; if (ss[m].utc <= utc) lo = m else hi = m }
    val a = ss[lo]; val b = ss[hi]
    val f = if (b.utc == a.utc) 0.0 else (utc - a.utc).toDouble() / (b.utc - a.utc)
    return a.t + (b.t - a.t) * f
}

/** Where the car was at clock time [t], between the two samples either side. */
private fun posAtT(ss: List<Sample>, t: Double): Pair<Double, Double> {
    if (t <= ss.first().t) return ss.first().lat to ss.first().lon
    if (t >= ss.last().t) return ss.last().lat to ss.last().lon
    var lo = 0; var hi = ss.size - 1
    while (hi - lo > 1) { val m = (lo + hi) / 2; if (ss[m].t <= t) lo = m else hi = m }
    val a = ss[lo]; val b = ss[hi]
    val f = if (b.t == a.t) 0.0 else (t - a.t) / (b.t - a.t)
    return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
}

/** Where the car was at GPS time [utc], between the two samples either side (not snapped to one). */
private fun posAtUtc(ss: List<Sample>, utc: Long): Pair<Double, Double>? {
    if (ss.isEmpty()) return null
    var lo = 0; var hi = ss.size - 1
    if (utc <= ss[lo].utc) return ss[lo].lat to ss[lo].lon
    if (utc >= ss[hi].utc) return ss[hi].lat to ss[hi].lon
    while (hi - lo > 1) { val m = (lo + hi) / 2; if (ss[m].utc <= utc) lo = m else hi = m }
    val a = ss[lo]; val b = ss[hi]
    val f = if (b.utc == a.utc) 0.0 else (utc - a.utc).toDouble() / (b.utc - a.utc)
    return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
}

class Series(val x: DoubleArray, val y: List<Double>, val color: Color)

/** A labelled mark across a chart (an apex). */
class Pin(val x: Double, val color: Color, val label: String)

/** The stretch of the run the charts on one screen show; pinch to zoom, all of them together. */
@androidx.compose.runtime.Stable
class ChartZoom {
    var x0 by androidx.compose.runtime.mutableDoubleStateOf(Double.NaN)
    var x1 by androidx.compose.runtime.mutableDoubleStateOf(Double.NaN)
    fun window(maxX: Double) = if (x0.isNaN()) 0.0 to maxX else x0 to x1
    fun reset() { x0 = Double.NaN; x1 = Double.NaN }
}

@Composable
private fun Chart(
    maxX: Double, marks: List<Pair<Double, String>>, height: Dp, series: List<Series>,
    fixed: Pair<Double, Double>? = null, zero: Boolean = false,
    cursor: Double? = null, onScrub: ((Double) -> Unit)? = null,
    zoom: ChartZoom? = null,
    /** Time delta: gaining (negative) drawn upward, shaded green; losing below, shaded red. */
    delta: Boolean = false,
    /** Apex marks: a solid line labelled "apex" in each run's color. */
    pins: List<Pin> = emptyList(),
) {
    val (w0, w1) = zoom?.window(maxX) ?: (0.0 to maxX)
    // One finger: scrub (the cursor follows, the map and video show that spot). Two fingers: pinch to
    // zoom into a stretch and slide along it. Double tap: back to the whole run.
    val touch = Modifier.pointerInput(maxX, zoom) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun xAt(px: Float): Double { val (a0, a1) = zoom?.window(maxX) ?: (0.0 to maxX); return (a0 + px / size.width * (a1 - a0)).coerceIn(0.0, maxX) }
            onScrub?.invoke(xAt(down.position.x))
            var lastSpan = 0f; var lastC = 0f; var pinched = false
            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2 && zoom != null) {
                    pinched = true
                    val span = kotlin.math.abs(pressed[0].position.x - pressed[1].position.x)
                    val c = (pressed[0].position.x + pressed[1].position.x) / 2
                    if (lastSpan > 10f && span > 10f) {
                        val (a0, a1) = zoom.window(maxX)
                        val anchor = a0 + lastC / size.width * (a1 - a0)
                        val newW = ((a1 - a0) * lastSpan / span).coerceIn(maxX * 0.03, maxX)
                        var n0 = anchor - c / size.width * newW
                        n0 = n0.coerceIn(0.0, maxX - newW)
                        if (newW >= maxX * 0.999) zoom.reset() else { zoom.x0 = n0; zoom.x1 = n0 + newW }
                    }
                    lastSpan = span; lastC = c
                } else if (!pinched) {
                    onScrub?.invoke(xAt(pressed[0].position.x))
                }
                ev.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }.pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom?.reset() }) }
    Canvas(Modifier.fillMaxWidth().height(height).bottomRule(Rule).then(if (onScrub != null || zoom != null) touch else Modifier)) {
        if (maxX <= 0 || w1 <= w0) return@Canvas
        fun inWin(x: Double) = x >= w0 && x <= w1
        var lo = fixed?.first ?: Double.MAX_VALUE
        var hi = fixed?.second ?: -Double.MAX_VALUE
        // Scale to what's in view, so a zoomed stretch shows its own detail
        if (fixed == null) for (sr in series) for (i in 0 until min(sr.x.size, sr.y.size)) { val v = sr.y[i]; if (!v.isNaN() && inWin(sr.x[i])) { lo = min(lo, v); hi = max(hi, v) } }
        if (lo > hi) return@Canvas
        if (zero) { val m = max(abs(lo), abs(hi)).coerceAtLeast(0.05); lo = -m; hi = m }
        if (hi - lo < 1e-6) hi = lo + 1
        val pad = (hi - lo) * 0.05
        lo -= pad; hi += pad
        fun px(d: Double) = ((d - w0) / (w1 - w0) * size.width).toFloat()
        fun py(v: Double) = if (delta) ((v - lo) / (hi - lo) * size.height).toFloat()
            else (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
        clipRect {
            // Dotted lines where each section starts (names left off: they cluttered the charts)
            for ((d, _) in marks) if (inWin(d)) drawLine(Color(0xFF333333), Offset(px(d), 0f), Offset(px(d), size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            if (zero) drawLine(Color(0xFF555555), Offset(0f, py(0.0)), Offset(size.width, py(0.0)), 2f)
            for (sr in series) {
                val n = min(sr.x.size, sr.y.size)
                if (delta) {
                    // Shade between the line and zero: green where gaining, red where losing
                    val z = py(0.0)
                    for (i in 1 until n) {
                        val v0 = sr.y[i - 1]; val v1 = sr.y[i]
                        if (v0.isNaN() || v1.isNaN() || sr.x[i] < w0 || sr.x[i - 1] > w1) continue
                        val q = Path().apply { moveTo(px(sr.x[i - 1]), z); lineTo(px(sr.x[i - 1]), py(v0)); lineTo(px(sr.x[i]), py(v1)); lineTo(px(sr.x[i]), z); close() }
                        drawPath(q, (if ((v0 + v1) / 2 < 0) Green else Red).copy(alpha = 0.22f))
                    }
                }
                val path = Path()
                var started = false
                // x and y can differ in length if data changed underneath: only draw what both have
                for (i in 0 until n) {
                    val v = sr.y[i]
                    if (v.isNaN()) { started = false; continue }
                    if (!started) { path.moveTo(px(sr.x[i]), py(v)); started = true } else path.lineTo(px(sr.x[i]), py(v))
                }
                drawPath(path, sr.color, style = Stroke(3f))
            }
            pins.forEachIndexed { n, p -> if (inWin(p.x)) {
                drawLine(p.color, Offset(px(p.x), 0f), Offset(px(p.x), size.height), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 6f)))
                drawText(p.label, px(p.x) + 6f, 56f + n * 28f, p.color)
            } }
            cursor?.let { if (inWin(it)) drawLine(Cyan,Offset(px(it), 0f), Offset(px(it), size.height), 2f) }
        }
        // Scale: the top and bottom values
        val topV = if (delta) lo + pad else hi - pad; val botV = if (delta) hi - pad else lo + pad
        val fmt = if (hi - lo < 4) "%+.1f" else "%.0f"
        drawText(fmt.format(topV), 4f, 26f, Color.DarkGray)
        drawText(fmt.format(botV), 4f, size.height - 6f, Color.DarkGray)
        if (zoom != null && !zoom.x0.isNaN()) drawText("zoomed · double tap for all", size.width - 330f, 26f, Color.Gray)
    }
}

private fun DrawScope.drawText(t: String, x: Float, y: Float, c: Color, sizePx: Float = 26f) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())
        textSize = sizePx; isAntiAlias = true; isFakeBoldText = true
    }
    drawContext.canvas.nativeCanvas.drawText(t, x, y, paint)
}

/** Watch this run on GoPro video, or link videos to the session. */
@Composable
private fun VideoSection(l: Loaded, watch: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val vids by Hub.videos.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var msgs by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true; msgs = listOf("Reading GPS from ${uris.size} video${if (uris.size > 1) "s" else ""}…")
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val snap = synchronized(Hub) { ArrayList(Hub.samples) }
                uris.map { com.kimes.daq.gopro.VideoImport.import(ctx, it, snap) }
            }
            Hub.addVideos(results.mapNotNull { it.link })
            msgs = results.map { it.message }
            busy = false
        }
    }
    val startUtc = l.runS.firstOrNull { it.utc > 0 }?.utc
    val link = startUtc?.let { u -> vids.firstOrNull { u in it.startUtc..it.endUtc } }
    Section("Video", Modifier.padding(top = 6.dp), right = if (vids.isNotEmpty()) "${vids.size} linked" else null) {
        when {
            link != null -> {
                Secondary("Watch run ${l.run.number} on video", Modifier.fillMaxWidth().padding(top = 8.dp), height = 52, onClick = watch)
                Text(link.name + " · " + link.note, color = Lo, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            startUtc == null -> Text("This session was recorded before GPS time was logged, so video can't be matched to it automatically.", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            else -> Text("No linked video covers this run.", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (startUtc != null) {
            val off by com.kimes.daq.gopro.Offload.status.collectAsState()
            // This run's own video: started at most a minute before the run, and before it ended
            val endUtc = l.runS.lastOrNull { it.utc > 0 }?.utc ?: startUtc
            if (link == null) TextAction("Get it from the GoPro now") { Hub.fetchVideo(startUtc - 60_000, endUtc, max = 3) }
            // Every run of the session in one go
            val missing = Hub.runs.value.count { r -> Hub.samples.getOrNull(r.startIdx)?.utc?.takeIf { it > 0 }?.let { u -> vids.none { u in it.startUtc..it.endUtc } } == true }
            if (missing > 1) TextAction("Get all $missing missing runs' videos from the GoPro") { Hub.fetchSessionVideos() }
            if (off != "idle") Text(off, color = Dim, fontSize = 13.sp)
            TextAction(if (busy) "Reading videos…" else "Add GoPro videos from a file") { if (!busy) pick.launch(arrayOf("video/*")) }
        }
        msgs.forEach { Text(it, color = if (it.endsWith("synced.")) Dim else Amber, fontSize = 13.sp) }
    }
}


/** "Thu 12 Nov · 07:43" from a session folder name like 20261112_074335. */
private fun sessionLabel(name: String?): String = name?.let {
    runCatching {
        java.text.SimpleDateFormat("EEE d MMM · HH:mm", java.util.Locale.US).format(java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse(it)!!)
    }.getOrDefault(it)
} ?: "None"

/** Every day you've recorded, newest first. Tap one to review its runs. */
@Composable
private fun SessionPicker(logging: Boolean, modifier: Modifier, picked: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Hub.PastSession>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { Hub.pastSessions() } }
    val current = Hub.sessionName.collectAsState().value
    Column(modifier.fillMaxHeight().background(Bg).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text("Past sessions", fontFamily = Anton, fontSize = 30.sp, color = Hi)
        if (logging) Text("A session is recording. Stop it on Home to look at another day.", color = Red, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
        busy?.let { Text("Loading $it…", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 6.dp)) }
        val l = list
        when {
            l == null -> Text("Reading sessions…", color = Dim, fontSize = 15.sp)
            l.isEmpty() -> Text("Nothing recorded yet.", color = Dim, fontSize = 15.sp)
            else -> LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
                items(l) { p ->
                    val isCur = p.dir.name == current
                    Row(
                        Modifier.fillMaxWidth().background(if (isCur) Surface else Color.Transparent).bottomRule(Rule, 2.dp)
                            .clickable(enabled = !logging && busy == null) {
                                if (isCur) { picked(); return@clickable }
                                busy = sessionLabel(p.dir.name)
                                Hub.loadInBackground(p.dir) { scope.launch { busy = null; picked() } }
                            }.padding(vertical = 12.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sessionLabel(p.dir.name), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Hi, modifier = Modifier.width(210.dp))
                        Text(p.course ?: "No course", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Dim, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(if (isCur) "open" else "${p.minutes} min", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (isCur) Cyan else Dim)
                    }
                }
            }
        }
        if (l != null && l.isEmpty()) Spacer(Modifier.weight(1f))
        BackupRow(logging)
    }
}

/** Save everything to one file (for a new tablet, or before reinstalling), or put a backup back. */
@Composable
private fun BackupRow(logging: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var withVideos by remember { mutableStateOf(true) }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val sizes by produceState<Pair<Long, Long>?>(null) { value = withContext(Dispatchers.IO) { runCatching { com.kimes.daq.data.Backup.sizes(ctx) }.getOrNull() } }
    fun gb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)
    val save = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true; status = "Backing up…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { out -> var last = -1; com.kimes.daq.data.Backup.export(ctx, out, withVideos) { f -> val pc = (f * 100).toInt(); if (pc != last) { last = pc; status = "Backing up… $pc%" } } }
            } }
            working = false
            status = r.fold({ "Backed up. Keep the file somewhere off the tablet too (a computer or cloud drive)." }, { "Backup failed: ${it.message}" })
        }
    }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineButton("Back up everything", Modifier.weight(1f)) {
                if (working) return@OutlineButton
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                save.launch("Debrief backup $day.zip")
            }
            OutlineButton("Restore a backup", Modifier.weight(1f)) {
                if (working || logging) { if (logging) status = "Stop the recording session on Home first."; return@OutlineButton }
                open.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }
        sizes?.let { (all, vids) ->
            if (vids > 0) Row(Modifier.clickable { withVideos = !withVideos }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (withVideos) "☑" else "☐", color = Hi, fontSize = 20.sp)
                Text("  Include videos (${gb(vids)}). Backup ${gb(if (withVideos) all else all - vids)}.", color = Dim, fontSize = 15.sp)
            }
        }
        status?.let { Text(it, color = if (it.contains("failed")) Red else Dim, fontSize = 15.sp) }
        confirmRestore?.let { uri ->
            Column(Modifier.fillMaxWidth().background(Surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Restore this backup? Sessions, courses and settings in it replace any with the same name here. Debrief closes when it's done; open it again.", color = Hi, fontSize = 15.sp, lineHeight = 21.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlineButton("Cancel", Modifier.weight(1f)) { confirmRestore = null }
                    OutlineButton("Restore", Modifier.weight(1f)) {
                        confirmRestore = null; working = true; status = "Restoring…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { com.kimes.daq.data.Backup.restore(ctx, it) { m -> status = "Restoring… $m" } } } }
                            working = false
                            r.fold({ n ->
                                status = "Restored $n files. Closing Debrief: open it again."
                                kotlinx.coroutines.delay(2500)
                                // Settings and the open session are held in memory: start fresh
                                android.os.Process.killProcess(android.os.Process.myPid())
                            }, { status = "Restore failed: ${it.message}" })
                        }
                    }
                }
            }
        }
    }
}
