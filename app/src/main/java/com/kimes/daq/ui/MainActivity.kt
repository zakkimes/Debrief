package com.kimes.daq.ui

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.Hub
import com.kimes.daq.LoggerService
import com.kimes.daq.bt.ObdLink
import com.kimes.daq.course.CourseMode
import com.kimes.daq.gopro.GoPro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, LIVE, RUNS, CAN, COURSES, DEVICES }

class MainActivity : ComponentActivity() {
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = Paper, surface = Paper, onSurface = Ink, onBackground = Ink, primary = Blue)) {
                // Laid out for the Galaxy Tab A9 (about 766 dp on its long side). A sharper or larger screen
                // reports more dp and everything comes out small, so scale the whole UI up to the same layout.
                // The long side, so turning the tablet doesn't change the size of everything.
                val baseDensity = LocalDensity.current
                val conf = androidx.compose.ui.platform.LocalConfiguration.current
                val k = (maxOf(conf.screenWidthDp, conf.screenHeightDp) / 766f).coerceIn(1f, 2f)
                CompositionLocalProvider(
                    LocalTextStyle provides BaseText,
                    LocalDensity provides androidx.compose.ui.unit.Density(baseDensity.density * k, baseDensity.fontScale),
                    // No Android 12 "stretch" at the end of a scroll: it smears the text
                    androidx.compose.foundation.LocalOverscrollConfiguration provides null,
                ) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Hi) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) { App() }
                    }
                }
            }
        }
    }

    /** Truly full screen: no status bar, no navigation bar or Samsung taskbar. Swipe in from an edge to see them briefly. */
    private fun hideSystemBars() {
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() { super.onResume(); hideSystemBars() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var reviewRun by remember { mutableStateOf<Int?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        perms.launch(arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        ))
    }
    val logging by Hub.logging.collectAsState()
    val act = LocalContext.current as MainActivity
    LaunchedEffect(logging) { act.keepScreenOn(logging) }
    // Turn with the tablet, except on the live display: a bump mid-run mustn't flip the screen
    LaunchedEffect(screen) {
        act.requestedOrientation = if (screen == Screen.LIVE) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }
    BackHandler(screen != Screen.HOME) { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> Home { reviewRun = null; screen = it }
        Screen.LIVE -> LiveScreen({ screen = Screen.HOME }) { reviewRun = it; screen = Screen.RUNS }
        Screen.RUNS -> RunsScreen({ screen = if (reviewRun != null && logging) Screen.LIVE else Screen.HOME }, reviewRun)
        Screen.CAN -> CanScreen { screen = Screen.HOME }
        Screen.COURSES -> CoursesScreen { screen = Screen.HOME }
        Screen.DEVICES -> DevicesScreen { screen = Screen.HOME }
    }
}

/** Plain-words pit-board row: name on the left, state on the right, a colored block when it needs you. */
@Composable
private fun PitRow(name: String, value: String, alert: Color? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(alert ?: Color.Transparent).bottomRule(Ink, 2.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (alert != null) 8.dp else 0.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (alert == RedFill) Color.White else Ink
        Text(name.uppercase(), color = fg, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
        Text(value, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
fun Home(go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val logging by Hub.logging.collectAsState()
    val rb by Hub.rbStatus.collectAsState()
    val rbInfo by Hub.rbInfo.collectAsState()
    val obd by Hub.obdStatus.collectAsState()
    val session by Hub.sessionName.collectAsState()
    val runs by Hub.runs.collectAsState()
    val course by Hub.activeCourse.collectAsState()
    val sessionCourse by Hub.sessionCourse.collectAsState()
    val tick by Hub.tick.collectAsState()
    val mode by Hub.carLinkMode.collectAsState()

    var choosing by remember { mutableStateOf<List<Pair<java.io.File, Int>>?>(null) }
    val autoStop by Hub.autoStopNote.collectAsState()
    val battery = remember(tick) {
        ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { i ->
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (lvl < 0) null else (lvl * 100 / scale) to plugged
        }
    }

    val port = isPortrait()
    Column(Modifier.fillMaxSize().background(Paper)) {
        @Composable fun navs(m: (Float) -> Modifier) {
            NavBlock("Courses", m(1f)) { go(Screen.COURSES) }
            NavBlock("Review", m(1f)) {
                // Open the most recent session that recorded something; Review has a picker for older ones
                if (session == null || (runs.isEmpty() && !logging)) Hub.latestRecorded()?.let { Hub.loadInBackground(it) }
                go(Screen.RUNS)
            }
            NavBlock("Devices", m(1f)) { go(Screen.DEVICES) }
            NavBlock("Signals", m(1f)) { go(Screen.CAN) }
        }
        // Masthead: forward-leaning wordmark and twin stripes
        Row(Modifier.fillMaxWidth().then(if (port) Modifier else Modifier.bottomRule(Ink, 4.dp)).padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("DEBRIEF", fontFamily = Anton, fontStyle = FontStyle.Italic, fontSize = 24.sp, color = Ink, letterSpacing = 0.5.sp)
            Column(Modifier.padding(start = 14.dp).width(70.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(RedFill)); Box(Modifier.fillMaxWidth().height(4.dp).background(RedFill))
            }
            Label(
                SimpleDateFormat("EEE d MMM · HH:mm", Locale.US).format(Date()) +
                    if (logging) " · recording · ${runs.size} runs" else "",
                Modifier.padding(start = 16.dp).weight(1f), Ink,
            )
            // Always on screen: the other pages
            if (!port) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { navs { Modifier.width(96.dp) } }
        }
        if (port) Row(Modifier.fillMaxWidth().bottomRule(Ink, 4.dp).padding(start = 18.dp, end = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            navs { Modifier.weight(it) }
        }

        // Today so far: best clean run and the optimal (best sections stitched), once there are runs
        val pens by Hub.penalties.collectAsState()
        val offs by Hub.official.collectAsState()
        val today by androidx.compose.runtime.produceState<Pair<com.kimes.daq.data.Run, Double?>?>(null, runs, pens, sessionCourse, offs) {
            val best = com.kimes.daq.run.Standings.best(runs, pens) { Hub.shownTime(it, runs) }
            value = best?.let { b -> b to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val snap = synchronized(Hub) { ArrayList(Hub.samples) }
                    val usable = runs.filter { pens[it.number] != com.kimes.daq.run.Standings.DNF && it.endIdx < snap.size }
                    com.kimes.daq.run.Sections.build(snap, usable, corners = sessionCourse?.corners ?: emptyList(), corrections = Hub.corrections(runs)).optimalTotal.takeIf { !it.isNaN() }
                }.getOrNull()
            } }
        }
        @Composable fun poster(m: Modifier) = CoursePoster(if (logging) sessionCourse else course, runs.size + 1, m, port, today) { go(Screen.COURSES) }
        @Composable fun board(m: Modifier) = Column(m.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!logging) {
                    // With runs already timed today, ask: carry on with them, or start fresh
                    GoBlock("GO", "Start\nsession", RedFill) {
                        val today = Hub.todaysRunSessions()
                        if (today.isEmpty()) LoggerService.start(ctx, null, mode) else choosing = today
                    }
                    autoStop?.let { Text(it, color = Ink, fontSize = 14.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp).background(Yellow).border(2.dp, Ink, RectangleShape).padding(8.dp)) }
                } else {
                    GoBlock("LIVE", "Open live\ndisplay", RedFill) { go(Screen.LIVE) }
                    Secondary("Stop session", Modifier.fillMaxWidth().padding(top = 6.dp), height = 40, color = Red) { LoggerService.stop(ctx) }
                }

                // Pit board: plain readouts when fine, a colored block when something needs you
                tick.hashCode()
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(2.dp).background(Ink))
                DriverRow()
                fun ok(st: String) = st.startsWith("connected") || st.startsWith("CAN monitor") || st.startsWith("OBD poll")
                // Tap to pick which RaceBox is yours
                PitRow("RaceBox", if (rb == "off") (com.kimes.daq.bt.RaceBox.label(ctx)?.let { "$it · off" } ?: "off · tap to pick yours") else if (ok(rb)) rbInfo.ifEmpty { rb } else rb, if (rb != "off" && !ok(rb)) Yellow else null) { Hub.devicesFocus.value = "racebox"; go(Screen.DEVICES) }
                PitRow("Car", if (obd == "off") "off" else obd, if (obd != "off" && !ok(obd)) Yellow else null) { Hub.devicesFocus.value = "car"; go(Screen.DEVICES) }
                if (logging) {
                    val b = Hub.canValues["brake"]; val t = Hub.canValues["throttle"]
                    PitRow("Brake · throttle", "%s · %s".format(b?.let { "%.1f".format(it) } ?: "no signal", t?.let { "%.0f%%".format(it) } ?: "no signal"), if (b == null) RedFill else null)
                }
                // Status only; pairing, recording and the auto switch are on the Devices page
                GoProStatus { Hub.devicesFocus.value = "gopro"; go(Screen.DEVICES) }
                battery?.let { (pct, plugged) ->
                    PitRow("Tablet", "$pct%" + if (plugged) " · charging" else if (pct < 30) " · plug in" else "", if (pct < 30 && !plugged) RedFill else null)
                }

            }
        if (port) Column(Modifier.weight(1f).fillMaxWidth()) {
            poster(Modifier.weight(0.4f).fillMaxWidth())
            board(Modifier.weight(0.6f).fillMaxWidth())
        } else Row(Modifier.weight(1f).fillMaxWidth()) {
            poster(Modifier.weight(0.54f).fillMaxHeight())
            board(Modifier.weight(0.46f).fillMaxHeight())
        }
        Checker(Modifier.fillMaxWidth().height(10.dp).topRule(Ink, 3.dp), 10.dp)
    }
    choosing?.let { list ->
        ResumeChoice(list, onPick = { d -> choosing = null; LoggerService.start(ctx, d, mode) }, onCancel = { choosing = null })
    }
}

/** Resume one of today's sessions (its runs keep counting) or start a new one. */
@Composable
private fun ResumeChoice(today: List<Pair<java.io.File, Int>>, onPick: (java.io.File?) -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).background(Paper).border(4.dp, Ink, RectangleShape).clickable(enabled = false) {}.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ALREADY RAN TODAY", fontFamily = Anton, fontSize = 34.sp, color = Ink)
            Text("Resume to keep numbering and comparing with today's runs, or start a new session.", fontSize = 16.sp, color = Ink)
            today.take(3).forEach { (d, n) ->
                val time = runCatching { SimpleDateFormat("HH:mm", Locale.US).format(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(d.name)!!) }.getOrDefault(d.name)
                GoBlock("RESUME", "Started $time\n$n run" + if (n == 1) "" else "s", RedFill) { onPick(d) }
            }
            Secondary("Start a new session", Modifier.fillMaxWidth(), height = 52) { onPick(null) }
            BackButton("Cancel", Modifier.fillMaxWidth()) { onCancel() }
        }
    }
}

/** Today's course as an event poster, with the next run's number roundel. */
@Composable
private fun CoursePoster(c: com.kimes.daq.course.CourseDef?, nextRun: Int, modifier: Modifier, stacked: Boolean = false, today: Pair<com.kimes.daq.data.Run, Double?>? = null, onClick: () -> Unit) {
    BoxWithConstraints(modifier.background(Yellow).then(if (stacked) Modifier.bottomRule(Ink, 4.dp) else Modifier.rightRule(Ink, 4.dp)).clickable(onClick = onClick).padding(start = 18.dp, end = 22.dp, top = 12.dp, bottom = 12.dp)) {
        val h = maxHeight
        val name = (c?.name ?: "No course").uppercase()
        // As big as fits: by height, and by the longest word across the width left of the roundel (Anton caps ≈ 0.5 em wide)
        val longest = name.split(' ').maxOf { it.length }.coerceAtLeast(1)
        val byWidth = (maxWidth - h * 0.32f) / (longest * 0.55f)
        val nameSp = with(LocalDensity.current) { minOf(h * if (name.length <= 12) 0.30f else 0.21f, byWidth).toSp() }
        Column(Modifier.fillMaxSize()) {
            Label(
                when {
                    c == null -> "Course · tap to choose"
                    c.mode == CourseMode.LAPS -> "Track · laps"
                    else -> "Autocross · today's course"
                }, color = Ink,
            )
            // Shrink until the whole name fits on two lines without breaking a word
            var shrink by remember(name, nameSp) { mutableStateOf(1f) }
            val sz = nameSp * shrink
            Text(name, fontFamily = Anton, fontSize = sz, lineHeight = sz * 0.9f, color = Ink, maxLines = 2,
                modifier = Modifier.padding(top = 4.dp, end = h * 0.32f),
                onTextLayout = { r ->
                    val brokeWord = (0 until r.lineCount - 1).any { i -> val e = r.getLineEnd(i, visibleEnd = true); e in 1 until name.length && name[e - 1] != ' ' && name[e] != ' ' }
                    if ((r.hasVisualOverflow || brokeWord) && shrink > 0.3f) shrink *= 0.9f
                })
            Spacer(Modifier.weight(1f))
            today?.let { (best, optimal) ->
                Row(Modifier.fillMaxWidth().background(Paper).border(3.dp, Ink, RectangleShape)) {
                    @Composable fun stat(v: String, k: String, m: Modifier) = Column(m.padding(horizontal = 12.dp, vertical = 6.dp)) { Num(v, 30.sp); Label(k, color = Ink) }
                    stat("%.3f".format(Hub.shownTime(best, Hub.runs.value)), "Best · run ${best.number}", Modifier.weight(1f).rightRule(Ink, 3.dp))
                    stat(optimal?.let { "%.3f".format(it) } ?: "--", "Optimal", Modifier.weight(1f))
                }
            }
            if (c == null) Text("Tap to choose a course. Without one, runs are timed from launch until you slow down.", fontSize = 15.sp, lineHeight = 18.sp, color = Ink)
            else if (!c.timed) Text("NEEDS START AND FINISH LINES · TAP", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Ink, modifier = Modifier.background(Paper).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Roundel("%02d".format(nextRun), h * 0.3f, Modifier.align(Alignment.TopEnd).padding(top = 18.dp).rotate(-6f), "next run")
    }
}

/** The big start block: tall word on the left, what it does on the right. */
@Composable
private fun GoBlock(word: String, what: String, fill: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(86.dp).background(fill).border(4.dp, Ink, RectangleShape).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(word, fontFamily = Anton, fontSize = 58.sp, color = Color.White, modifier = Modifier.weight(1f))
        Text(what.uppercase(), fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun NavBlock(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(38.dp).background(Color.White).border(3.dp, Ink, RectangleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text.uppercase(), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = Ink)
    }
}

/** GoPro on the pit board: state, pair/record, and the auto-record switch. */
@Composable
private fun GoProStatus(open: () -> Unit) {
    val st by GoPro.status.collectAsState()
    val rec by GoPro.recording.collectAsState()
    val paired = remember(st) { GoPro.paired }
    val needsRepair by GoPro.needsRepair.collectAsState()
    val off by com.kimes.daq.gopro.Offload.status.collectAsState()
    PitRow(
        "GoPro",
        when {
            needsRepair -> "needs pairing again · tap"
            st != "off" -> st
            paired -> "starts with the session"
            else -> "not paired · tap to pair"
        },
        if (rec) RedFill else if (needsRepair) Yellow else if (st.startsWith("connected") || st == "off") null else Yellow,
        open,
    )
    if (off != "idle") Text("Video: $off", color = Ink2, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, modifier = Modifier.padding(vertical = 2.dp))
}

/** GoPro pairing, recording by hand and the auto-record switch (Devices page). */
@Composable
fun GoProControls() {
    val st by GoPro.status.collectAsState()
    val rec by GoPro.recording.collectAsState()
    val auto by Hub.autoGoPro.collectAsState()
    val paired = remember(st) { GoPro.paired }
    val course by Hub.sessionCourse.collectAsState()
    val needsRepair by GoPro.needsRepair.collectAsState()
    val step by GoPro.repairStep.collectAsState()
    val off by com.kimes.daq.gopro.Offload.status.collectAsState()
    if (step.isNotEmpty()) Text(step, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 4.dp).background(Yellow).padding(6.dp))
    if (off != "idle") Text("Video: $off", color = Ink2, fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, modifier = Modifier.padding(vertical = 2.dp))
    if (!paired && st == "off" && step.isEmpty()) Text(
        "On the GoPro: swipe down, Connections, Connect Device, GoPro Quik App. Then tap Pair.",
        color = Ink2, fontSize = 14.sp, lineHeight = 17.sp, modifier = Modifier.padding(vertical = 4.dp),
    )
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (st == "off") Secondary(if (paired) "Connect" else "Pair", Modifier.weight(1f), height = 36) { GoPro.start() }
        else Secondary(if (rec || GoPro.want) "Stop recording" else "Record", Modifier.weight(1f), height = 36, enabled = st.startsWith("connected"),
            color = if (rec) Red else Cyan) { GoPro.record(!GoPro.want, "button") }
        if (paired || needsRepair) TextAction("Re-pair", Modifier.padding(horizontal = 6.dp), if (needsRepair) Red else Ink2) { GoPro.repair() }
        Label(if (course?.mode == CourseMode.LAPS) "Auto (autocross)" else "Auto each run", color = Ink)
        Segmented(listOf("Off" to false, "On" to true), auto, Modifier.width(84.dp), height = 32) { Hub.setAutoGoPro(it) }
    }
}
