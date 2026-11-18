package com.kimes.daq.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.Hub
import com.kimes.daq.MorningCheck
import com.kimes.daq.bt.toHex
import com.kimes.daq.data.CanFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

private data class Step(val label: String, val prompt: String, val secs: Int)

private val brakeScript = buildList {
    add(Step("REST", "Ignition ON (engine on is fine, in neutral).\nFeet off the pedals.", 6))
    repeat(3) { add(Step("BRAKE", "PRESS BRAKE HARD — HOLD", 5)); add(Step("REST", "Release brake", 5)) }
    repeat(2) { add(Step("BRAKE_LIGHT", "PRESS BRAKE LIGHTLY — HOLD", 5)); add(Step("REST", "Release brake", 5)) }
    add(Step("PEDAL", "Engine OFF only: throttle to the floor — HOLD\n(engine on: skip, keep foot off)", 5))
    add(Step("REST", "Release", 4))
}

private fun steerScript(polled: Int): List<Step> {
    val secs = ceil(2.5 + 4 * (polled + 1) * 0.05).toInt().coerceIn(8, 30)
    return buildList {
        add(Step("REST", "ENGINE RUNNING, parked, foot on the brake.\nWheel CENTERED.", secs))
        repeat(2) {
            add(Step("RIGHT", "Turn the wheel ALL THE WAY RIGHT — hold", secs)); add(Step("REST", "Back to CENTER — hold", secs))
            add(Step("LEFT", "Turn the wheel ALL THE WAY LEFT — hold", secs)); add(Step("REST", "Back to CENTER — hold", secs))
        }
    }
}

/** Phases sized so every polled signal is read a few times per phase (the engine computer may answer a lot). */
private fun clutchScript(polled: Int): List<Step> {
    val secs = ceil(2.0 + 4 * (polled + 1) * 0.05).toInt().coerceIn(6, 30)
    return buildList {
        add(Step("REST", "Engine running, in NEUTRAL, parking brake on.\nFeet off the pedals.", secs))
        repeat(3) { add(Step("CLUTCH", "Press the CLUTCH to the floor — HOLD", secs)); add(Step("REST", "Let the clutch out — feet off", secs)) }
    }
}

private val wheelScript = listOf(
    Step("REST", "Somewhere open. Stopped, wheel straight.", 6),
    Step("ROLL", "Drive STRAIGHT, slowly (about 10 mph)", 10),
    Step("REST", "Stop", 6),
    Step("CIRCLE", "Turn FULL LEFT and roll in a slow circle", 14),
    Step("REST", "Stop, straighten the wheel", 6),
    Step("CIRCLE_R", "Turn FULL RIGHT and roll in a slow circle", 14),
    Step("REST", "Stop, straighten the wheel", 6),
    Step("ACCEL", "Speed up firmly to about 15 mph, then brake firmly to a stop", 12),
    Step("REST", "Stopped", 5),
)

private enum class Test(val title: String) { BRAKE("Brake"), STEER("Steering"), CLUTCH("Clutch"), WHEELS("Wheel speeds") }

/** What each guided step found, kept while the app is open so leaving the screen doesn't lose it. */
private val done = mutableStateMapOf<String, String>()

private val corners = listOf("LF" to "LEFT FRONT", "RF" to "RIGHT FRONT", "LR" to "LEFT REAR")

@Composable
fun CanScreen(back: () -> Unit) {
    val logging by Hub.logging.collectAsState()
    val obd by Hub.obdStatus.collectAsState()
    val scanState by Hub.scan.collectAsState()
    val pcmState by Hub.pcmScan.collectAsState()
    val log by Hub.obdLog.collectAsState()
    val modState by Hub.modScan.collectAsState()
    val morning by MorningCheck.state.collectAsState()
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("signals", android.content.Context.MODE_PRIVATE) }
    val dash = remember { mutableStateListOf(*listOf("LF" to "32", "RF" to "31", "LR" to "28", "RR" to "32").map { (c, d) -> prefs.getString("dash$c", d) ?: d }.toTypedArray()) }
    var ambient by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<CanFinder.Candidate>?>(null) }
    var found by remember { mutableStateOf<Pair<Test, List<Pair<String, CanFinder.Scaled>>>?>(null) }
    var clutchFound by remember { mutableStateOf<List<CanFinder.Bit>?>(null) }
    var motion by remember { mutableStateOf<List<String>?>(null) }
    var applied by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableLongStateOf(0L) }
    var tireJob by remember { mutableStateOf<Job?>(null) }
    var tireMsg by remember { mutableStateOf<String?>(null) }
    var skipCorner by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        Hub.logAllCan = true
        onDispose { Hub.logAllCan = false; Hub.setTestPlan(null); tireJob?.cancel() }
    }
    LaunchedEffect(Unit) { while (true) { delay(250); tick = SystemClock.elapsedRealtimeNanos() } }

    val dscHits = scanState?.takeIf { it.done == it.total }?.hits?.keys?.toList().orEmpty()
    val pcmHits = pcmState?.takeIf { it.done == it.total }?.hits?.keys?.toList().orEmpty()
    val modHits = modState?.hits.orEmpty()
    val busy = prompt != null || tireJob != null || modState?.finished == false || morning?.running == true ||
        scanState?.let { it.done < it.total } == true || pcmState?.let { it.done < it.total } == true
    // Steering: the power steering module and the stability module (if scanned); the cluster and body module too when they're small
    val steerReqs = modHits[0x730].orEmpty().keys.map { 0x730 to it } + dscHits.map { Hub.DSC to it } +
        modHits.filterKeys { it != 0x730 }.flatMap { (h, m) -> m.keys.map { h to it } }.let { if (it.size <= 60) it else emptyList() }

    fun save(file: String, text: String) {
        Hub.sessionName.value?.let { name -> File(File(Hub.sessionsRoot(), name), file).writeText(text) }
    }

    fun runTest(test: Test, reqs: List<Pair<Int, Int>>) = scope.launch {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        results = null; found = null; clutchFound = null; motion = null; tireMsg = null
        Hub.setTestPlan(reqs)
        delay(1500)
        Hub.startCapture()
        val script = when (test) {
            Test.BRAKE -> brakeScript; Test.STEER -> steerScript(reqs.size); Test.WHEELS -> wheelScript; Test.CLUTCH -> clutchScript(reqs.size)
        }
        for (s in script) {
            prompt = s.prompt
            tone.startTone(if (s.label == "REST") ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_BEEP2, 250)
            val t0 = Hub.now()
            for (k in s.secs downTo 1) { countdown = k; delay(1000) }
            Hub.phase(s.label, t0, Hub.now())
        }
        Hub.stopCapture()
        Hub.setTestPlan(null)
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600)
        prompt = "Analyzing…"
        withContext(Dispatchers.Default) {
            val frames = synchronized(Hub.capFrames) { ArrayList(Hub.capFrames) }
            val head = "frames=${frames.size} polled=${reqs.joinToString { "%03X:%04X".format(it.first, it.second) }}\n"
            val brakeKeys = Hub.decoders.filter { it.name == "brake" && it.header != 0 }.map { (it.header shl 16) or it.id }.toSet()
            val (file, text) = when (test) {
                Test.BRAKE -> {
                    val res = CanFinder.rank(frames, Hub.capPhases, "BRAKE", lightLabel = "BRAKE_LIGHT")
                    results = res
                    done["brake"] = if (res.isEmpty()) "Nothing clear found" else "Found ${res.first().name}"
                    "brake_test_results.txt" to res.joinToString("\n")
                }
                Test.STEER -> {
                    val res = CanFinder.steering(frames, Hub.capPhases).map { "steer" to it }
                    found = test to res
                    done["steer"] = res.firstOrNull()?.let { "Found ${it.second.c.name}: ${it.second.note}" } ?: "Nothing clear found"
                    "steering_test_results.txt" to res.joinToString("\n") { it.second.toString() }
                }
                Test.CLUTCH -> {
                    val bits = CanFinder.bits(frames, Hub.capPhases, "CLUTCH")
                    val cont = CanFinder.rank(frames, Hub.capPhases, "CLUTCH")
                    clutchFound = bits; results = cont
                    done["clutch"] = when {
                        bits.isNotEmpty() -> "Found ${bits.first()}"
                        cont.isNotEmpty() -> "No switch bit, but ${cont.first().name} moved with the pedal"
                        else -> "Nothing followed the clutch"
                    }
                    "clutch_test_results.txt" to (bits.joinToString("\n") + "\n--- moving values\n" + cont.joinToString("\n"))
                }
                Test.WHEELS -> {
                    val res = CanFinder.wheelSpeeds(frames, Hub.capPhases, Hub::speedAt, brakeKeys)
                    found = test to res
                    // The car's own motion sensors, compared with the RaceBox over the circles and the stop
                    val skip = brakeKeys + res.map { it.second.c.id } +
                        Hub.decoders.filter { it.name == "steer" }.map { (it.header shl 16) or it.id } + ((Hub.DSC shl 16) or 0x2033)
                    val yaw = CanFinder.follows(frames, Hub.capPhases, { Hub.sampleAt(it)?.yaw }, "deg/s", exclude = skip)
                    val lat = CanFinder.follows(frames, Hub.capPhases, { Hub.sampleAt(it)?.latG }, "g", exclude = skip)
                    val lon = CanFinder.follows(frames, Hub.capPhases, { Hub.sampleAt(it)?.lonG }, "g", minR = 0.75, exclude = skip)
                    motion = listOf("Yaw rate" to yaw, "Sideways g" to lat, "Forward g" to lon).map { (n, l) ->
                        "$n: " + (l.firstOrNull()?.toString() ?: "not found")
                    }
                    done["wheels"] = when {
                        res.isEmpty() -> "No wheel speeds found"
                        res.size == 4 && res.none { it.first.startsWith("wheel") } -> "Found all four wheels, corners known"
                        else -> "Found ${res.size} speed signal(s)"
                    }
                    "wheel_test_results.txt" to (res.joinToString("\n") { "${it.first}: ${it.second}" } + "\n--- car motion sensors\n" +
                        listOf("yaw" to yaw, "latG" to lat, "lonG" to lon).joinToString("\n") { (n, l) -> "$n: " + l.joinToString(" | ") })
                }
            }
            save(file, head + text)
            // Keep the raw capture too, so a miss can be re-analyzed offline
            Hub.sessionName.value?.let { name ->
                File(File(Hub.sessionsRoot(), name), "${test.name.lowercase()}_capture.csv").printWriter().use { w ->
                    Hub.capPhases.forEach { w.println("phase,${it.label},%.3f,%.3f".format(it.t0, it.t1)) }
                    frames.forEach { f -> w.println("%.4f,%X,%s".format(f.t, f.id, f.data.toHex())) }
                }
            }
        }
        prompt = null
        tone.release()
    }

    /** Let air out of one corner at a time; the sensor number that drops is that corner. */
    fun runTires() {
        results = null; found = null; clutchFound = null; motion = null
        tireJob = scope.launch {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            val tlog = StringBuilder()
            try {
                fun now() = (1..4).map { Hub.canValues["tire$it"] }
                val start = now()
                if (start.all { it == null }) {
                    tireMsg = "The car hasn't sent tire pressures yet. Drive a minute (the sensors wake up when rolling), then try again."
                    return@launch
                }
                tlog.append("start ${start.joinToString()}\n")
                val taken = HashMap<Int, String>()
                for ((code, name) in corners) {
                    skipCorner = false
                    val base = now()
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                    val t0 = SystemClock.elapsedRealtime()
                    var hit: Int? = null
                    while (hit == null && !skipCorner) {
                        val secs = (SystemClock.elapsedRealtime() - t0) / 1000
                        if (secs > 300) break
                        tireMsg = "Let air out of the $name tire: press the valve for about 5 seconds (about 2 psi).\n" +
                            "Then wait. The sensor reports a drop within a minute or two. Waiting ${secs}s…"
                        val cur = now()
                        hit = (1..4).filter { it !in taken }.firstOrNull { k ->
                            val b = base[k - 1]; val c = cur[k - 1]
                            b != null && c != null && b - c >= 1.0
                        }
                        delay(1000)
                    }
                    if (hit != null) {
                        taken[hit] = code
                        Hub.setTireCorner(hit, code)
                        tlog.append("$code = tire$hit\n")
                        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
                    } else tlog.append("$code skipped\n")
                }
                // The one left over is the right rear
                val left = (1..4).filter { it !in taken }
                if (taken.size == 3 && left.size == 1) { Hub.setTireCorner(left[0], "RR"); taken[left[0]] = "RR" }
                done["tires"] = if (taken.isEmpty()) "No tire dropped" else (1..4).joinToString(", ") { k -> "Tire $k = ${taken[k] ?: "?"}" }
                tireMsg = "Done. Put the air back to what the car read before the test: " +
                    (1..4).joinToString(", ") { k -> "${Hub.tireName(k)} " + (start[k - 1]?.let { "%.1f psi".format(it) } ?: "--") } + "."
                save("tire_test_results.txt", tlog.toString())
            } finally {
                tone.release()
                tireJob = null
            }
        }
    }

    Panes(Modifier.fillMaxSize().padding(8.dp)) {
        Column(Modifier.share(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackButton("Home") { back() }
            Text("Car signal setup", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp))
            Text("OBDLink: $obd", color = Dim, fontSize = 13.sp)
            if (!logging) Text("Start a session on the home screen first (OBDLink mode: Diagnostic), engine running.", color = Amber, fontWeight = FontWeight.Bold)
            @Suppress("UNUSED_VARIABLE") val t = tick
            Text(
                "Brake %s   Pedal %s   RPM %s   Gear %s   Steer %s   Clutch %s".format(
                    Hub.canValues["brake"]?.let { "%.1f".format(it) } ?: "--",
                    Hub.canValues["throttle"]?.let { "%.0f%%".format(it) } ?: "--",
                    Hub.canValues["rpm"]?.let { "%.0f".format(it) } ?: "--",
                    Hub.canValues["gear"]?.let { "%.0f".format(it) } ?: "--",
                    Hub.canValues["steer"]?.let { "%+.0f°".format(it) } ?: "--",
                    Hub.canValues["clutch"]?.let { if (it > 0.5) "IN" else "out" } ?: "--",
                ), fontSize = 17.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                (1..4).joinToString("   ") { k ->
                    "${Hub.tireName(k)} %s %s".format(
                        Hub.canValues["tire$k"]?.let { "%.1f psi".format(it) } ?: "--",
                        Hub.canValues["tiretemp$k"]?.let { "%.0f°F".format(it) } ?: "",
                    )
                }, fontSize = 15.sp,
            )
            Text(
                "Oil %s   Intake air %s   Outside air %s".format(
                    Hub.canValues["oiltemp"]?.let { "%.0f°F".format(it) } ?: "--",
                    Hub.canValues["iat"]?.let { "%.0f°F".format(it) } ?: "--",
                    Hub.canValues["ambient"]?.let { "%.0f°F".format(it) } ?: "--",
                ), fontSize = 15.sp,
            )

            val p = prompt
            if (p != null) {
                Text(p, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Amber, lineHeight = 36.sp)
                Text("$countdown", fontSize = 64.sp, fontFamily = FontFamily.Monospace)
            }
            if (tireJob != null) tireMsg?.let { m ->
                Text(m, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Amber, lineHeight = 30.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Secondary("Skip this corner", height = 44) { skipCorner = true }
                    Secondary("Stop", height = 44) { tireJob?.cancel(); tireMsg = null }
                }
            }

            if (p == null && tireJob == null) {
                Text("High beep = do it, low beep = back to rest. Each test saves its results for a closer look on the computer.", fontSize = 14.sp, color = Dim)
                val mc = morning
                Column(Modifier.fillMaxWidth().border(3.dp, Ink).background(Color.White).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MORNING AUTO-CHECK", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Start it parked, then just drive to the track. It searches the whole car for the tire pressures and the steering angle, " +
                            "uses what it confirms, and stops on its own when your first timed run starts. Brake and throttle aren't recorded while it runs. " +
                            "Turn normally on the way: the steering search needs left and right turns.",
                        fontSize = 14.sp, color = Dim,
                    )
                    Text("Tire pressures on the dash right now (psi):", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("LF", "RF", "LR", "RR").forEachIndexed { i, c ->
                            NumField(c, dash[i], Modifier.weight(1f)) { v -> dash[i] = v; prefs.edit().putString("dash$c", v).apply() }
                        }
                    }
                    if (mc?.running == true) {
                        Text("${mc.stage} · ${mc.detail}", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Amber)
                        mc.found.forEach { Text(it, fontSize = 14.sp, color = Green, fontWeight = FontWeight.Bold) }
                        Secondary("Stop", height = 44) { MorningCheck.cancel() }
                    } else {
                        mc?.let { m ->
                            Text("${m.stage} · ${m.detail}", fontSize = 14.sp, color = Dim)
                            m.found.forEach { Text(it, fontSize = 14.sp, color = if (it.endsWith("not found")) Red else Green, fontWeight = FontWeight.Bold) }
                        }
                        Primary(if (mc == null) "Start" else "Start again", Modifier.fillMaxWidth(), height = 52, enabled = logging && !busy && dash.all { it.toDoubleOrNull() != null }) {
                            Hub.startMorningCheck(dash.map { it.toDouble() })
                        }
                    }
                }
                Text("MANUAL STEPS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Dim, modifier = Modifier.padding(top = 6.dp))

                GuideCard(
                    "1", "Scan the other modules", "Parked, engine running · up to 9 min · instrument cluster, body module, power steering. Needed for steps 2–4.",
                    modState?.let { m ->
                        val found = m.hits.entries.joinToString(", ") { (h, x) -> "%03X: %d".format(h, x.size) }
                        if (!m.finished) "Scanning module ${m.idx + 1} of ${Hub.modHeaders.size}: ${m.done}/${m.total}" + if (found.isNotEmpty()) " · found $found" else ""
                        else "Done · found $found"
                    },
                ) {
                    Secondary("Scan", height = 44, enabled = logging && !busy) { Hub.startModScan() }
                }
                GuideCard(
                    "2", "Tire pressures and corners", "Type what the dash shows right now (psi), then Match. No air needed.",
                    done["tires"] ?: tireMsg,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("LF", "RF", "LR", "RR").forEachIndexed { i, c ->
                            NumField(c, dash[i], Modifier.weight(1f)) { v -> dash[i] = v; prefs.edit().putString("dash$c", v).apply() }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Secondary("Match to the dash", height = 44, enabled = !busy && modHits.isNotEmpty() && dash.all { it.toDoubleOrNull() != null }) {
                            val m = CanFinder.matchTires(modHits, dash.map { it.toDouble() })
                            if (m == null) {
                                done["tires"] = "No match in the scan. Were the four numbers what the dash showed during the scan?"
                            } else {
                                Hub.setSlow(m.decoders)
                                m.corners.forEachIndexed { i, c -> Hub.setTireCorner(i + 1, c) }
                                done["tires"] = "Found: " + m.corners.mapIndexed { i, c -> "tire ${i + 1} = $c" }.joinToString(", ") + " · ${m.note}. Check the numbers at the top match the dash."
                                save("tire_match_results.txt", m.toString())
                            }
                        }
                        Secondary("Let-air-out test", height = 44, enabled = logging && !busy) { runTires() }
                    }
                }
                GuideCard("3", "Steering angle", "Parked, engine running · 1–3 min · wheel right, center, left", done["steer"]) {
                    Secondary("Start", height = 44, enabled = logging && !busy && steerReqs.isNotEmpty()) { runTest(Test.STEER, steerReqs) }
                }
                GuideCard(
                    "4", "Outside temperature", "Type the outside temperature the dash shows (°F), then Find.",
                    done["ambient"],
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("°F", ambient, Modifier.width(110.dp)) { ambient = it }
                        Secondary("Find", height = 44, enabled = !busy && modHits.isNotEmpty() && ambient.toDoubleOrNull() != null) {
                            val f = ambient.toDouble()
                            // Common encodings: degC = A - 40, or A/2 - 40
                            val c = modHits.flatMap { (h, m) -> m.flatMap { (d, b) -> b.indices.mapNotNull { i ->
                                val a = b[i].toInt() and 0xFF
                                val v1 = (a - 40) * 1.8 + 32; val v2 = (a / 2.0 - 40) * 1.8 + 32
                                when {
                                    kotlin.math.abs(v1 - f) <= 2.5 -> "%03X:%04X byte %d (A-40)".format(h, d, i)
                                    kotlin.math.abs(v2 - f) <= 2.0 -> "%03X:%04X byte %d (A/2-40)".format(h, d, i)
                                    else -> null
                                }
                            } } }
                            done["ambient"] = if (c.isEmpty()) "No candidates" else "${c.size} candidate(s), saved for a closer look: " + c.take(6).joinToString("; ")
                            save("ambient_candidates.txt", "dash $f F\n" + c.joinToString("\n"))
                        }
                    }
                }

                Text("DONE ON 11/14 · redo only if something looks wrong", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Dim, modifier = Modifier.padding(top = 6.dp))
                GuideCard(
                    "5", "Scan the stability control module", "Parked, engine running · about 3 min · needed to redo 6 or 8",
                    scanState?.let { if (it.done < it.total) "Scanning ${it.done}/${it.total}, ${it.hits.size} found" else "${it.hits.size} signals found" },
                ) {
                    Secondary("Scan", height = 44, enabled = logging && !busy) { Hub.startDscScan() }
                }
                GuideCard(
                    "6", "Wheel speeds", "Found: left front 2B06, right front 2B07, left rear 2B08, right rear 2B09 · recorded every session",
                    done["wheels"],
                ) {
                    Secondary("Redo (empty lot)", height = 44, enabled = logging && !busy && dscHits.isNotEmpty()) { runTest(Test.WHEELS, dscHits.map { Hub.DSC to it }) }
                }
                GuideCard(
                    "7", "Clutch pedal switch", "Found: engine computer 1E04 · recorded every session",
                    done["clutch"] ?: pcmState?.let { if (it.done < it.total) "Scanning engine computer ${it.done}/${it.total}" else null },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Secondary("Scan engine computer", height = 44, enabled = logging && !busy) { Hub.startPcmScan() }
                        Secondary("Clutch test", height = 44, enabled = logging && !busy && pcmHits.isNotEmpty() && dscHits.isNotEmpty()) {
                            runTest(Test.CLUTCH, dscHits.map { Hub.DSC to it } + pcmHits.map { Hub.PCM to it })
                        }
                    }
                }
                GuideCard("8", "Brake", "Already set up · parked · 1 min", done["brake"]) {
                    Secondary("Redo", height = 44, enabled = logging && !busy && dscHits.isNotEmpty()) { runTest(Test.BRAKE, dscHits.map { Hub.DSC to it }) }
                }
                Text(
                    "ABS: the stability module's status word is logged in every session, so ABS activity can be found later by matching it to your hardest stops.",
                    fontSize = 13.sp, color = Dim,
                )
            }

            // Results of the last test, with the choice to use them
            results?.takeIf { clutchFound == null }?.let { r ->
                Text("BRAKE candidates", fontWeight = FontWeight.Bold, color = Amber, modifier = Modifier.padding(top = 6.dp))
                if (r.isEmpty()) Text("No clear signal found", color = Dim)
                r.forEach { c ->
                    Row {
                        Text(c.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Secondary(if (applied == c.toString()) "Using" else "Use", height = 40) {
                            // Raw units; the live trace and analysis scale brake to the session's peak
                            Hub.setDecoders(Hub.decoders.filter { it.name != "brake" } + c.decoder("brake").copy(offset = -c.rest))
                            applied = c.toString()
                        }
                    }
                }
            }
            clutchFound?.let { bits ->
                Text("CLUTCH found", fontWeight = FontWeight.Bold, color = Amber, modifier = Modifier.padding(top = 6.dp))
                if (bits.isEmpty()) Text("No on/off signal followed the clutch.", color = Dim)
                bits.forEach { b ->
                    Row {
                        Text(b.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Secondary(if (applied == b.toString()) "Using" else "Use", height = 40) {
                            Hub.setDecoders(Hub.decoders.filter { it.name != "clutch" } + b.decoder("clutch"))
                            applied = b.toString()
                        }
                    }
                }
                results?.takeIf { it.isNotEmpty() }?.let { r ->
                    Text("Values that moved with the pedal (saved for later):", fontSize = 12.sp, color = Dim)
                    r.forEach { Text(it.toString(), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
            found?.let { (test, list) ->
                Text("${test.title.uppercase()} found", fontWeight = FontWeight.Bold, color = Amber, modifier = Modifier.padding(top = 6.dp))
                if (list.isEmpty()) Text(
                    if (test == Test.WHEELS) "No signal followed your speed. The capture is saved for a closer look."
                    else "No clear signal found. Was the engine running?", color = Dim,
                )
                list.forEach { (name, sc) -> Text("$name  $sc", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                if (list.isNotEmpty()) Secondary(if (applied == test.title) "Using" else if (test == Test.STEER) "Use the top one" else "Use these", height = 40) {
                    val use = if (test == Test.STEER) list.take(1) else list
                    val names = use.map { it.first }.toSet()
                    Hub.setDecoders(Hub.decoders.filter { it.name !in names } + use.map { (n, sc) -> sc.decoder(n) })
                    applied = test.title
                }
            }
            motion?.let { m ->
                Text("CAR MOTION SENSORS (saved for later)", fontWeight = FontWeight.Bold, color = Amber, modifier = Modifier.padding(top = 6.dp))
                m.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }

            Secondary(if (showRaw) "Hide details" else "Details", height = 40) { showRaw = !showRaw }
            if (showRaw) {
                scanState?.takeIf { it.done == it.total }?.let { s ->
                    Text("Stability module: " + s.hits.entries.joinToString("  ") { "%04X=%s".format(it.key, it.value.toHex()) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Dim)
                }
                pcmState?.takeIf { it.done == it.total }?.let { s ->
                    Text("Engine computer: " + s.hits.entries.joinToString("  ") { "%04X=%s".format(it.key, it.value.toHex()) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Dim)
                }
                Text("Active signals", fontWeight = FontWeight.Bold)
                Hub.decoders.forEach {
                    Text(
                        "${it.name}: %s b%d len%d %s%s ×%.4g %+.4g".format(
                            if (it.header != 0) "%03X:%04X".format(it.header, it.id) else "0x%03X".format(it.id),
                            it.start, it.len, if (it.bigEndian) "BE" else "LE", if (it.signed) " signed" else "", it.scale, it.offset,
                        ), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                }
                Text("Adapter log (also saved as obd_log.txt)", fontSize = 12.sp, color = Dim)
                Column(Modifier.fillMaxWidth().background(Color(0xFF0D0D0D)).padding(6.dp)) {
                    log.takeLast(18).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFB0BEC5)) }
                }
            }
        }
        // Live signal table, changed bytes highlighted
        Column(Modifier.side(330.dp, 0.6f).background(Color(0xFF0D0D0D)).padding(6.dp)) {
            Text("Signal    count  data (yellow = changing)", fontSize = 12.sp, color = Color.Gray)
            val now = tick
            val rows = Hub.frameStats.entries.sortedBy { it.key }
            LazyColumn {
                items(rows, key = { it.key }) { (id, st) ->
                    Text(buildAnnotatedString {
                        append(if (id > 0xFFF) "%03X:%04X".format(id ushr 16, id and 0xFFFF) else "%03X     ".format(id))
                        append(" %6d  ".format(st.count))
                        st.data.forEachIndexed { k, b ->
                            val hot = k < 8 && now - st.changedAt[k] < 400_000_000L
                            withStyle(SpanStyle(color = if (hot) Color(0xFFFFC107) else Color(0xFF9E9E9E))) { append("%02X ".format(b)) }
                        }
                    }, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun GuideCard(n: String, title: String, how: String, status: String?, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().border(2.dp, RuleFaint).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(n, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = if (status != null) Green else Dim, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(how, fontSize = 13.sp, color = Dim)
            status?.let { Text(it, fontSize = 13.sp, color = Green, fontWeight = FontWeight.Bold) }
            actions()
        }
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(5)) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
