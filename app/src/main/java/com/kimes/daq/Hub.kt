package com.kimes.daq

import android.content.Context
import android.os.SystemClock
import com.kimes.daq.bt.ObdLink
import com.kimes.daq.bt.RaceBox
import com.kimes.daq.bt.RaceBoxPacket
import com.kimes.daq.bt.toHex
import com.kimes.daq.course.CourseDef
import com.kimes.daq.course.CourseMode
import com.kimes.daq.course.CourseStore
import com.kimes.daq.geo.Tiles
import com.kimes.daq.gopro.AutoRecord
import com.kimes.daq.gopro.GoPro
import com.kimes.daq.gopro.VideoLink
import com.kimes.daq.data.CanDecoder
import com.kimes.daq.data.CanDecoders
import com.kimes.daq.data.CanFinder
import com.kimes.daq.data.Gate
import com.kimes.daq.data.LocalProjection
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import com.kimes.daq.run.Course
import com.kimes.daq.run.LineTimer
import com.kimes.daq.run.OptimalLine
import com.kimes.daq.run.RunTiming
import com.kimes.daq.run.Standings
import com.kimes.daq.run.PathFollower
import com.kimes.daq.run.RefPath
import com.kimes.daq.run.RunTracker
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI

/** Live display state. Deliberately nothing the car's own dash already shows (speed, RPM, gear). */
data class LiveState(
    val running: Boolean = false,
    val elapsed: Double = 0.0,
    val delta: Double? = null,       // seconds vs the reference run, + = behind
    val brake: Double = 0.0,         // 0..1 of session max CAN brake pressure
    val throttle: Double = 0.0,      // 0..1
    val brakeIsCan: Boolean = false,
    val brakeFromG: Boolean = false, // car gives no brake signal: trace is RaceBox braking g (1.0 = 1 g)
    val gpsOk: Boolean = false,
    val runNumber: Int = 0,          // run in progress, or the last finished one
    val refNumber: Int? = null,
    val sectorCount: Int = 1,
    val sectorDeltas: List<Double?> = emptyList(), // finished sectors, vs the reference's sector time
    val currentSectorDelta: Double? = null,
    val lineTimed: Boolean = false,  // a course with start/finish lines is timing the runs
    val toStartM: Double? = null,    // rolling up to the start line: meters to go (within 50 ft of it)
    val brakeTrace: FloatArray = FloatArray(0),
    val throttleTrace: FloatArray = FloatArray(0),
)

class FrameStat(var count: Long, var data: ByteArray, val changedAt: LongArray)

object Hub {
    lateinit var ctx: Context
    val obdStatus = MutableStateFlow("off")
    val obdLog = MutableStateFlow<List<String>>(emptyList())
    val rbStatus = MutableStateFlow("off")
    val rbInfo = MutableStateFlow("")
    val logging = MutableStateFlow(false)
    val live = MutableStateFlow(LiveState())
    val runs = MutableStateFlow<List<Run>>(emptyList())
    val refNumber = MutableStateFlow<Int?>(null)
    val sessionName = MutableStateFlow<String?>(null)
    /** Course picked on Home (used for the next session); the session keeps its own copy. */
    val activeCourse = MutableStateFlow<CourseDef?>(null)
    val sessionCourse = MutableStateFlow<CourseDef?>(null)
    /** Run number -> cones hit (Standings.DNF for a DNF). */
    val penalties = MutableStateFlow<Map<Int, Int>>(emptyMap())
    /** Why a run was made a DNF automatically: the cone, where it is, and the sample where it happened. */
    data class AutoDnf(val cone: Int, val what: String, val idx: Int)
    /** Run number -> the automatic DNF (wrong side of a pointer cone). Undone with [notDnf]. */
    val autoDnf = MutableStateFlow<Map<Int, AutoDnf>>(emptyMap())
    /** Cones the driver said were wrong (moved since the course walk): not checked again this session. */
    val ignoredCones = MutableStateFlow<Set<Int>>(emptySet())

    /** Run number -> the official (timing lights) raw time, before cones, as keyed in from the timing slip. */
    val official = MutableStateFlow<Map<Int, Double>>(emptyMap())
    lateinit var courses: CourseStore
    /** GoPro videos linked to this session, synced by GPS time. */
    val videos = MutableStateFlow<List<VideoLink>>(emptyList())

    fun addVideos(links: List<VideoLink>) {
        synchronized(this) {
            videos.value = (videos.value.filter { v -> links.none { it.uri == v.uri } } + links).sortedBy { it.startUtc }
            saveCourse()
        }
    }

    /** Download the GoPro's preview of recent runs and link it to this session (Offload retries are the caller's). */
    /**
     * Every run's video in this session that the tablet doesn't have yet, in one trip to the camera
     * (files already on the tablet are skipped).
     */
    fun fetchSessionVideos(done: (Boolean) -> Unit = {}) {
        val snap = synchronized(this) { ArrayList(samples) }
        val utcs = runs.value.mapNotNull { r ->
            val a = snap.getOrNull(r.startIdx)?.utc?.takeIf { it > 0 }; val b = snap.getOrNull(r.endIdx)?.utc?.takeIf { it > 0 }
            if (a != null && b != null) a to b else null
        }
        if (utcs.isEmpty()) { done(false); return }
        fetchVideo(utcs.minOf { it.first } - 120_000, utcs.maxOf { it.second } + 30_000, max = 40, done = done)
    }

    /**
     * After a run: every video of this session the tablet doesn't have yet (so a run missed earlier
     * comes along too), tried again a minute later if it fails, up to [attempt] 3, never while recording.
     */
    private fun autoFetch(attempt: Int = 1) {
        if (GoPro.recording.value || GoPro.want) return
        fetchSessionVideos { ok ->
            if (!ok && attempt < 3 && logging.value)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ autoFetch(attempt + 1) }, 60_000)
        }
    }

    fun fetchVideo(sinceUtc: Long, untilUtc: Long = Long.MAX_VALUE, max: Int = 3, done: (Boolean) -> Unit = {}) {
        // Each step of the download lands in the session's events, so a failure says where it stopped
        com.kimes.daq.gopro.Offload.log = { m -> if (!m.startsWith("downloading ")) event("video " + m) }
        event("video fetch_start")
        com.kimes.daq.gopro.Offload.fetchLatest(ctx, sinceUtc, { ok, msg -> event("video fetch_" + (if (ok) "ok " else "failed ") + msg); done(ok || msg.contains("already")) }, untilUtc, max) { f ->
            // Already linked to this session: nothing to read again
            if (videos.value.any { it.name == f.name }) return@fetchLatest
            val snap = synchronized(this) { ArrayList(samples) }
            com.kimes.daq.gopro.VideoImport.import(f, snap).link?.let { addVideos(listOf(it)) }
        }
    }

    /** The video that shows a given moment, if any. */
    fun videoFor(utc: Long): VideoLink? = videos.value.firstOrNull { utc in it.startUtc..it.endUtc }
    val tick = MutableStateFlow(0L)

    val canValues = ConcurrentHashMap<String, Double>()
    private val canTimes = ConcurrentHashMap<String, Long>()
    val frameStats = ConcurrentHashMap<Int, FrameStat>()
    @Volatile var logAllCan = false

    val samples = ArrayList<Sample>()          // guarded by `this`
    val course = Course()
    var decoders: List<CanDecoder> = CanDecoders.defaults
        private set
    private var decoderIds = decoders.map { it.id }.toSet()

    private var obd: ObdLink? = null
    private var rb: RaceBox? = null
    private var dir: File? = null
    private var gpsOut: BufferedWriter? = null
    private var canOut: BufferedWriter? = null
    private var evOut: BufferedWriter? = null
    private var obdOut: BufferedWriter? = null
    private var t0Ns = 0L
    private var tOffset = 0.0
    private var proj: LocalProjection? = null
    private var tracker: RunTiming = RunTracker(course)
    private var follower: PathFollower? = null
    private var lastFlush = 0L
    private var lastRbCount = 0
    private var rbCount = 0
    private var lastRbInfo = 0L
    private var brakeMax = 1.0
    private var sawCanBrake = false // the car has given a brake value this session
    private var lonGf = 0.0
    private val traceLen = 200
    private val bTrace = ArrayDeque<Float>()
    private val tTrace = ArrayDeque<Float>()

    // Guided CAN test capture
    @Volatile var capturing = false
    val capFrames = ArrayList<CanFinder.Frame>()
    val capPhases = ArrayList<CanFinder.Phase>()

    fun init(c: Context) {
        ctx = c.applicationContext
        decoders = CanDecoders.load(File(ctx.getExternalFilesDir(null), "decoders.json"))
        decoderIds = decoders.map { it.id }.toSet()
        courses = CourseStore(File(ctx.getExternalFilesDir(null), "courses"))
        Tiles.init(ctx.getExternalFilesDir(null)!!)
        prefs.getString("activeCourse", null)?.let { activeCourse.value = courses.get(it) }
        // Courses started and abandoned (New course, then back) clutter the list
        runCatching { courses.dropEmpty(activeCourse.value?.id) }
        GoPro.init(ctx)
        GoPro.onEvent = { event(it) }
        autoGoPro.value = prefs.getBoolean("autoGoPro", true)
        runCatching { prefs.getString("carLink", null)?.let { carLinkMode.value = com.kimes.daq.bt.ObdLink.Mode.valueOf(it) } }
        drivers.value = prefs.getString("drivers", "")!!.split('|').filter { it.isNotBlank() }
        prefs.getString("carProfile", null)?.let { runCatching { carProfile.value = com.kimes.daq.run.CarProfile.from(JSONObject(it)) } }
        prefs.getString("carLearned", null)?.let { runCatching { learnedCar.value = com.kimes.daq.run.CarModel.carFrom(JSONObject(it)) } }
        driver.value = prefs.getString("driver", null)?.takeIf { it in drivers.value } ?: drivers.value.firstOrNull()
    }

    // ---- the car ----------------------------------------------------------------------
    /** The car and its tires, as the driver described them (Devices page). */
    val carProfile = MutableStateFlow(com.kimes.daq.run.CarProfile())
    /** The car as measured from runs: grip, braking, launch and power. Kept between sessions. */
    val learnedCar = MutableStateFlow<OptimalLine.Car?>(null)

    fun setCarProfile(p: com.kimes.daq.run.CarProfile) {
        val changedCar = p.label != carProfile.value.label || p.tire != carProfile.value.tire
        carProfile.value = p
        prefs.edit().putString("carProfile", p.json().toString()).apply()
        // A different car or tires: what was learned no longer applies
        if (changedCar) { learnedCar.value = null; prefs.edit().remove("carLearned").apply() }
    }

    fun learnCar(c: OptimalLine.Car) {
        learnedCar.value = c
        prefs.edit().putString("carLearned", com.kimes.daq.run.CarModel.carJson(c).toString()).apply()
    }

    /** The car to predict with before it has run on this course. */
    fun carPrior(): OptimalLine.Car = learnedCar.value ?: carProfile.value.toCar()

    // ---- co-drivers -------------------------------------------------------------------
    /** Everyone driving this car (kept between sessions). Two or more turns on co-driver mode. */
    val drivers = MutableStateFlow<List<String>>(emptyList())
    /** Who drives the next run. */
    val driver = MutableStateFlow<String?>(null)
    /** Run number -> who drove it (this session). */
    val runDriver = MutableStateFlow<Map<Int, String>>(emptyMap())
    /** Who was selected when the run in progress started: the run is theirs even if the switch is flipped mid-run. */
    private var driverAtStart: String? = null

    val coDriving get() = drivers.value.size >= 2

    fun setDrivers(list: List<String>) {
        val clean = list.map { it.replace("|", "").trim() }.filter { it.isNotEmpty() }.distinct()
        drivers.value = clean
        if (driver.value !in clean) driver.value = clean.firstOrNull()
        prefs.edit().putString("drivers", clean.joinToString("|")).putString("driver", driver.value).apply()
    }

    fun selectDriver(name: String) {
        driver.value = name
        prefs.edit().putString("driver", name).apply()
        follower = null // the live delta now follows this driver's best
    }

    fun driverOf(run: Int): String? = runDriver.value[run]

    @Synchronized
    fun setRunDriver(run: Int, name: String) {
        runDriver.value = runDriver.value + (run to name)
        follower = null
        saveCourse()
    }

    /** The runs to compare [run] with: the same driver's, when runs have drivers; else all of them. */
    fun sameDriver(rs: List<Run>, run: Int): List<Run> {
        val d = driverOf(run) ?: return rs
        return rs.filter { driverOf(it.number) == d }
    }

    /** Record the GoPro from 50 ft before the start line to 50 ft past the finish (autocross courses). */
    val autoGoPro = MutableStateFlow(true)
    private var autoRec: AutoRecord? = null

    /** Which part of the Devices page to open at ("racebox", "gopro", "car"), set by a Home status line. */
    val devicesFocus = MutableStateFlow<String?>(null)

    /** How the OBDLink talks to the car (Devices page); used when a session starts. */
    val carLinkMode = MutableStateFlow(com.kimes.daq.bt.ObdLink.Mode.DIAG)

    fun setCarLinkMode(m: com.kimes.daq.bt.ObdLink.Mode) {
        carLinkMode.value = m
        prefs.edit().putString("carLink", m.name).apply()
    }

    fun setAutoGoPro(on: Boolean) {
        autoGoPro.value = on
        prefs.edit().putBoolean("autoGoPro", on).apply()
        if (!on && autoRec?.state != AutoRecord.State.IDLE) GoPro.record(false, "auto off")
        autoRec = makeAutoRec()
    }

    /**
     * Meters to the start line along the course direction, inside the same box that turns the
     * GoPro on (50 ft in front of the line, as wide as the line); null outside it.
     */
    private fun toStartM(s: Sample): Double? {
        val c = sessionCourse.value ?: return null
        val p = proj ?: return null
        if (!c.timed || c.mode != CourseMode.AUTOX) return null
        val g = c.start!!.gate(p)
        val dx = s.x - g.x; val dy = s.y - g.y
        val along = dx * g.hx + dy * g.hy
        val lateral = kotlin.math.abs(dx * -g.hy + dy * g.hx)
        return if (along in -AutoRecord.LEAD_M..0.0 && lateral <= g.halfWidth) -along else null
    }

    private fun makeAutoRec(): AutoRecord? {
        val c = sessionCourse.value ?: return null
        val p = proj ?: return null
        if (!autoGoPro.value || c.mode != CourseMode.AUTOX || !c.timed) return null
        return AutoRecord(c.start!!.gate(p), c.finish!!.gate(p))
    }

    /** Choose the course for timing. During or after a session it re-times every run with the new lines. */
    @Synchronized
    fun selectCourse(c: CourseDef?) {
        activeCourse.value = c
        prefs.edit().putString("activeCourse", c?.id).apply()
        if (sessionName.value != null) {
            sessionCourse.value = c
            follower = null
            autoRec = makeAutoRec()
            recomputeRuns()
            saveCourse()
        }
    }

    /** Corner names/places changed: nothing to re-time, just keep the session's copy current. */
    @Synchronized
    fun cornersEdited(c: CourseDef) {
        if (activeCourse.value?.id == c.id) activeCourse.value = c
        if (sessionCourse.value?.id == c.id && sessionName.value != null) { sessionCourse.value = c; saveCourse() }
    }

    /** A saved course was edited: pick up the change if it's the one in use. */
    fun courseEdited(c: CourseDef) {
        if (activeCourse.value?.id == c.id) activeCourse.value = c
        if (sessionCourse.value?.id == c.id) selectCourse(c)
    }

    @Synchronized
    fun setPenalty(run: Int, cones: Int) {
        // Taking an automatic DNF back by hand is the same as "Not a DNF"
        if (cones != Standings.DNF && autoDnf.value.containsKey(run)) { notDnf(run); if (cones == 0) return }
        penalties.value = penalties.value + (run to cones)
        follower = null
        saveCourse()
    }

    /** The RaceBox connected now (session or course walk), by Bluetooth address. */
    fun raceBoxAddress(): String? = rb?.address ?: walkRb?.address

    /**
     * Use only this RaceBox from now on (null: the first one found). A session already running
     * drops its current RaceBox link and looks for the chosen one.
     */
    fun chooseRaceBox(address: String?, name: String?) {
        RaceBox.choose(ctx, address, name)
        val cur = rb ?: return
        if (address != null && cur.address.equals(address, ignoreCase = true)) return
        cur.stop()
        rb = RaceBox(ctx, ::onRaceBox) { rbStatus.value = it }.also { it.start() }
        event("racebox_chosen " + (address ?: "any"))
    }

    /** At the end of a run: a clear wrong-side pass of a pointer cone makes it a DNF (the driver can undo it). */
    private fun checkCones(r: Run) {
        val c = sessionCourse.value ?: return
        val p = proj ?: return
        if (!c.timed || !r.finished || (penalties.value[r.number] ?: 0) != 0) return
        val cones = c.cones.mapIndexedNotNull { i, k ->
            if (i in ignoredCones.value) null
            else k.passBearing?.let { b -> val rad = Math.toRadians(b); com.kimes.daq.run.ConeCheck.Cone(i, p.x(k.lon), p.y(k.lat), kotlin.math.sin(rad), kotlin.math.cos(rad)) }
        }
        val m = runCatching { com.kimes.daq.run.ConeCheck.wrongSide(samples, r, cones) }.getOrNull() ?: return
        val k = c.cones[m.cone]
        val near = c.corners.map { it to com.kimes.daq.course.distM(k.lat, k.lon, it.lat, it.lon) }.filter { it.second < 40 }.minByOrNull { it.second }?.first?.name
        val what = "Wrong side of cone ${m.cone + 1}" + (near?.let { " near $it" } ?: "") + ", about %.0f ft out".format(m.byM * 3.281)
        autoDnf.value = autoDnf.value + (r.number to AutoDnf(m.cone, what, m.idx))
        penalties.value = penalties.value + (r.number to Standings.DNF)
        follower = null
        event("auto_dnf ${r.number} cone ${m.cone + 1} %.1f m".format(m.byM))
    }

    /** The automatic DNF was wrong: the run counts, and that cone isn't checked again this session. */
    @Synchronized
    fun notDnf(run: Int) {
        val a = autoDnf.value[run] ?: return
        autoDnf.value = autoDnf.value - run
        ignoredCones.value = ignoredCones.value + a.cone
        penalties.value = penalties.value + (run to 0)
        follower = null
        event("not_dnf $run cone ${a.cone + 1}")
        saveCourse()
    }

    @Synchronized
    fun setOfficial(run: Int, t: Double?) {
        official.value = if (t == null) official.value - run else official.value + (run to t)
        saveCourse()
    }

    /**
     * How far the official timer reads from this app's timer (official minus ours), the median over
     * the runs with an official time keyed in; null until one is. Our line crossings come from GPS,
     * the official ones from light beams, so the two differ by a nearly constant amount.
     */
    fun timingOffset(rs: List<Run> = runs.value, off: Map<Int, Double> = official.value): Double? {
        val d = rs.mapNotNull { r -> off[r.number]?.let { it - r.time } }.sorted()
        if (d.isEmpty()) return null
        return if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
    }

    /**
     * The run's time as the timing lights would read it: the official time where one was keyed in,
     * else this tablet's time corrected by the usual difference (once any official time exists).
     */
    fun shownTime(r: Run, rs: List<Run> = runs.value, off: Map<Int, Double> = official.value): Double =
        off[r.number] ?: (r.time + (timingOffset(rs, off) ?: 0.0))

    /** Per run: official time minus this tablet's time (keyed in, or the usual difference); empty without any official time. */
    fun corrections(rs: List<Run> = runs.value, off: Map<Int, Double> = official.value): Map<Int, Double> {
        if (off.isEmpty()) return emptyMap()
        return rs.associate { it.number to (shownTime(it, rs, off) - it.time) }
    }

    /** True when [shownTime] is the keyed-in official time, not an estimate. */
    fun isOfficial(r: Run) = official.value.containsKey(r.number)

    private fun makeTiming(): RunTiming {
        val c = sessionCourse.value
        val p = proj
        if (c != null && c.timed && p != null) {
            return LineTimer(c.start!!.gate(p), c.finish!!.gate(p), c.sectors.map { it.gate(p) }, c.mode == CourseMode.LAPS)
        }
        return RunTracker(course)
    }

    fun setDecoders(list: List<CanDecoder>) {
        CanDecoders.save(File(ctx.getExternalFilesDir(null), "decoders.json"), list)
        decoders = list
        decoderIds = list.map { it.id }.toSet()
        if (testDids == null) obd?.plan = basePlan()
    }

    // ---- diagnostic polling plan (2024+ ND3) -----------------------------------------

    data class ScanState(val done: Int, val total: Int, val hits: Map<Int, ByteArray>)
    val scan = MutableStateFlow<ScanState?>(null)
    private var testDids: List<Int>? = null
    private val pcmReq = ObdLink.Req(0x7E0, "01490C") // accelerator pedal D + RPM in one request
    /** Gear as the car reports it (SAE PID A4, transmission actual gear + ratio); asked on its own so the reply fits one CAN frame. */
    private val gearReq = ObdLink.Req(0x7E0, "01A4")

    /**
     * One polling cycle: brake three times per half-cycle, pedal/RPM once, any other found signals
     * (steering, wheel speeds) once, and gear once per cycle.
     */
    /** Slow channels by name -> (session time, value), for Review: tire pressures and temps, oil temp. */
    val slowLog = ConcurrentHashMap<String, MutableList<Pair<Double, Double>>>()
    @Volatile private var steerFast = false
    private fun slowDecoders() = slowList().filter { d -> decoders.none { it.name == d.name } }
    private fun slowPlan() = slowDecoders().filter { !(it.name == "steer" && steerFast) }.map {
        val key = "%03X:%04X".format(it.header, it.id)
        val ext = prefs.getStringSet("extHeaders", emptySet())?.contains(key) == true
        ObdLink.Req(it.header, (if (ext) "1003|" else "") + "22%04X".format(it.id))
    } +
        ObdLink.Req(0x7E0, "010F") // intake air temperature (0146 "outside air" sits in the engine bay on this car: 55°F vs 42°F on the dash)

    private fun basePlan(): List<ObdLink.Req> {
        fun req(d: CanDecoder) = ObdLink.Req(d.header, "22%04X".format(d.id))
        // Steering joins the fast plan once the slow probe shows the car answers it
        val polled = decoders.filter { it.header != 0 } + (if (steerFast) slowDecoders().filter { it.name == "steer" } else emptyList())
        val brake = polled.filter { it.name == "brake" }.map(::req).distinct()
        val wheels = polled.filter { it.name in setOf("wFL", "wFR", "wRL", "wRR") }.map(::req).distinct()
        // DSC status word 2B00: its top bit follows the brake switch; ABS activity is likely another bit. Logged raw for now.
        val extras = (polled.filter { it.name != "brake" && it !in polled.filter { d -> d.name in setOf("wFL", "wFR", "wRL", "wRR") } }.map(::req) +
            ObdLink.Req(DSC, "222B00")).distinct()
        // Each half: brake 3x, pedal/RPM 2x, all four wheels once, one extra. About 0.7 s per cycle:
        // brake ~9 Hz, pedal/RPM ~6 Hz, wheels ~3 Hz.
        fun half(x: List<ObdLink.Req>) = brake + wheels.take(2) + pcmReq + brake + wheels.drop(2) + pcmReq + brake + x
        val e1 = extras.filterIndexed { i, _ -> i % 2 == 0 }; val e2 = extras.filterIndexed { i, _ -> i % 2 == 1 }
        return half(e1) + half(e2) + gearReq
    }

    fun startDscScan() = startScan(DSC, (0x2000..0x2FFF).toList(), scan)

    /** Engine computer: scanned for the clutch switch and anything else it answers. ~5 min. */
    val pcmScan = MutableStateFlow<ScanState?>(null)
    fun startPcmScan() = startScan(PCM, (0x0000..0x1FFF).toList(), pcmScan)

    private fun startScan(header: Int, dids: List<Int>, into: MutableStateFlow<ScanState?>) {
        val o = obd ?: return
        into.value = ScanState(0, dids.size, emptyMap())
        o.pendingScan = ObdLink.Scan(header, dids) { done, hits -> into.value = ScanState(done, dids.size, hits) }
    }

    /**
     * Other modules, for tire pressures, the dash's outside temperature and steering angle:
     * instrument cluster 0x720, body module 0x726, power steering 0x730 (Ford/Mazda addressing).
     * Each is checked first and skipped if it doesn't answer. Up to ~9 min.
     */
    data class ModScan(val idx: Int, val done: Int, val total: Int, val hits: Map<Int, Map<Int, ByteArray>>, val finished: Boolean)
    val modScan = MutableStateFlow<ModScan?>(null)
    val modHeaders = listOf(0x720, 0x726, 0x730)
    private val modDids = (0x2000..0x2FFF).toList() + (0xDD00..0xDDFF).toList()
    fun startModScan() {
        modScan.value = ModScan(0, 0, modDids.size, emptyMap(), false)
        scanModule(0, emptyMap())
    }
    private fun scanModule(i: Int, acc: Map<Int, Map<Int, ByteArray>>) {
        val o = obd ?: return
        val h = modHeaders[i]
        o.pendingScan = ObdLink.Scan(h, modDids, probe = true) { done, hits ->
            val all = acc + (h to hits)
            val last = done == modDids.size
            modScan.value = ModScan(i, done, modDids.size, all, last && i == modHeaders.size - 1)
            if (last) {
                saveModHits(all)
                if (i + 1 < modHeaders.size) scanModule(i + 1, all)
            }
        }
    }
    private fun saveModHits(all: Map<Int, Map<Int, ByteArray>>) {
        val name = sessionName.value ?: return
        runCatching {
            File(File(sessionsRoot(), name), "module_scan.txt").writeText(all.entries.joinToString("\n") { (h, m) ->
                m.entries.joinToString("\n") { (d, b) -> "%03X:%04X,%s".format(h, d, b.toHex()) }
            })
        }
    }

    /** Found slow channels (tire pressures from the module scan) replacing the community guesses. */
    private val slowFile by lazy { File(ctx.getExternalFilesDir(null), "slow_decoders.json") }
    @Volatile private var slowOverride: List<CanDecoder>? = null
    private fun slowList(): List<CanDecoder> {
        val o = slowOverride ?: (if (slowFile.exists()) CanDecoders.read(slowFile) else emptyList()).also { slowOverride = it }
        return CanDecoders.slow.filter { d -> o.none { it.name == d.name } } + o
    }
    fun setSlow(list: List<CanDecoder>, extended: Boolean = false) {
        val cur = slowList().filter { d -> CanDecoders.slow.none { it == d } }
        val merged = cur.filter { d -> list.none { it.name == d.name } } + list
        CanDecoders.save(slowFile, merged)
        slowOverride = merged
        // Signals that only answer in the extended diagnostic session get it requested right before each read
        val ext = (prefs.getStringSet("extHeaders", emptySet()) ?: emptySet()).toMutableSet()
        list.forEach { d -> if (extended) ext += "%03X:%04X".format(d.header, d.id) else ext -= "%03X:%04X".format(d.header, d.id) }
        prefs.edit().putStringSet("extHeaders", ext).apply()
        obd?.slow = slowPlan()
    }

    fun startMorningCheck(dash: List<Double>?) { obd?.let { MorningCheck.start(it, dash) } }

    /** A timed run is in progress (the morning auto-check gives the adapter back). */
    fun timingActive(): Boolean = tracker.isRunning

    /** During a guided test, poll these (module, data id) pairs (plus pedal) instead of the normal plan. */
    fun setTestPlan(reqs: List<Pair<Int, Int>>?) {
        testDids = reqs?.map { it.second }
        obd?.plan = reqs?.let { r -> r.map { ObdLink.Req(it.first, "22%04X".format(it.second)) } + pcmReq } ?: basePlan()
    }

    /** Which corner each tire sensor number is ("LF", "RF", "LR", "RR"), once the guided tire test has found it. */
    fun tireCorner(k: Int): String? = prefs.getString("tireCorner$k", null)
    fun setTireCorner(k: Int, corner: String?) {
        val e = prefs.edit()
        // A corner belongs to one sensor only
        if (corner != null) (1..4).filter { tireCorner(it) == corner }.forEach { e.remove("tireCorner$it") }
        if (corner == null) e.remove("tireCorner$k") else e.putString("tireCorner$k", corner)
        e.apply()
    }
    fun tireName(k: Int): String = tireCorner(k)?.let { mapOf("LF" to "Left front", "RF" to "Right front", "LR" to "Left rear", "RR" to "Right rear")[it] } ?: "Tire $k"

    /** The logged sample nearest session time t (within half a second). */
    fun sampleAt(t: Double): Sample? = synchronized(this) {
        if (samples.isEmpty() || t < samples.first().t || t > samples.last().t) return null
        var lo = 0; var hi = samples.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (samples[m].t <= t) lo = m else hi = m }
        if (samples[hi].t - samples[lo].t > 0.5) null else samples[lo]
    }

    const val DSC = 0x760
    const val PCM = 0x7E0

    /** GPS speed (m/s) at a session time, from the samples, for the wheel-speed test. */
    fun speedAt(t: Double): Double? = synchronized(this) {
        if (samples.isEmpty() || t < samples.first().t || t > samples.last().t) return null
        var lo = 0; var hi = samples.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (samples[m].t <= t) lo = m else hi = m }
        if (samples[hi].t - samples[lo].t > 0.5) null else samples[lo].speed
    }

    fun sessionsRoot() = File(ctx.getExternalFilesDir(null), "sessions").apply { mkdirs() }

    /** A recorded session on disk, for the picker in Review. */
    data class PastSession(val dir: File, val course: String?, val minutes: Int)

    /** Sessions that recorded driving, newest first. */
    fun pastSessions(): List<PastSession> =
        sessionsRoot().listFiles()?.filter { it.isDirectory && File(it, "gps.csv").length() > 2000 }?.sortedByDescending { it.name }?.map { d ->
            val course = runCatching {
                File(d, "course.json").takeIf { it.exists() }?.let { JSONObject(it.readText()).optJSONObject("courseDef")?.optString("name") }
            }.getOrNull()
            PastSession(d, course?.ifBlank { null }, (lastT(File(d, "gps.csv")) / 60).toInt())
        } ?: emptyList()

    fun latestRecorded(): File? = pastSessions().firstOrNull()?.dir

    /** Today's sessions that timed at least one run, newest first, with their run counts (for Resume). */
    fun todaysRunSessions(): List<Pair<File, Int>> {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        return sessionsRoot().listFiles()?.filter { it.isDirectory && it.name.startsWith(today) }?.sortedByDescending { it.name }?.mapNotNull { d ->
            val n = runCatching { File(d, "events.csv").readLines().count { it.contains(",run_end") } }.getOrDefault(0)
            if (n > 0) d to n else null
        } ?: emptyList()
    }

    /** Set when a session stopped itself because the car left the course; Home shows it once. */
    val autoStopNote = MutableStateFlow<String?>(null)
    private var samplesAtStart = 0
    private var createdDir = false
    private var autoStopping = false

    /** Seconds into the session of the last sample, read from the end of the file. */
    private fun lastT(f: File): Double = runCatching {
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(4096L, r.length()); r.seek(r.length() - n)
            val b = ByteArray(n.toInt()); r.readFully(b)
            String(b).trimEnd().lines().last().substringBefore(',').toDouble()
        }
    }.getOrDefault(0.0)

    fun now(): Double = (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1e9 + tOffset

    // ---- session lifecycle ------------------------------------------------------------

    @Synchronized
    fun startSession(continueDir: File?, obdMode: ObdLink.Mode) {
        if (logging.value) return
        if (continueDir != null) load(continueDir) else {
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            clear()
            dir = File(sessionsRoot(), name).apply { mkdirs() }
            sessionName.value = name
        }
        steerFast = false
        createdDir = continueDir == null
        samplesAtStart = samples.size
        autoStopping = false
        autoStopNote.value = null
        activeCourse.value?.let { sessionCourse.value = it }
        tracker = makeTiming().apply { setNextNumber(runs.value.size + 1) }
        autoRec = makeAutoRec()
        val d = dir!!
        val newGps = !File(d, "gps.csv").exists()
        gpsOut =java.io.FileWriter(File(d, "gps.csv"), true).buffered(1 shl 16)
        if (newGps) { gpsOut!!.write(Sample.CSV_HEADER); gpsOut!!.newLine() }
        canOut = java.io.FileWriter(File(d, "can.csv"), true).buffered(1 shl 16)
        evOut = java.io.FileWriter(File(d, "events.csv"), true).buffered()
        obdOut = java.io.FileWriter(File(d, "obd_log.txt"), true).buffered()
        t0Ns = SystemClock.elapsedRealtimeNanos()
        tOffset = samples.lastOrNull()?.t?.plus(5.0) ?: 0.0
        event("session_start")
        logging.value = true

        stopWalkRaceBox()  // one BLE link to the RaceBox at a time
        rb = RaceBox(ctx, ::onRaceBox) { rbStatus.value = it }.also { it.start() }
        obd = ObdLink(ctx, ::onFrame, ::onDiag, { obdStatus.value = it }, ::onObdLog).also {
            it.mode = obdMode
            it.plan = basePlan()
            it.slow = slowPlan()
            it.start()
        }
        if (GoPro.paired) GoPro.start()
    }

    @Synchronized
    fun stopSession() {
        if (!logging.value) return
        obd?.stop(); obd = null
        rb?.stop(); rb = null
        GoPro.stop()
        autoRec = null
        event("session_stop")
        listOf(gpsOut, canOut, evOut, obdOut).forEach { try { it?.close() } catch (_: Exception) {} }
        gpsOut = null; canOut = null; evOut = null; obdOut = null
        saveCourse()
        logging.value = false
        // A start that never got a position (car off, RaceBox asleep) leaves nothing behind
        if (createdDir && samples.size == samplesAtStart) {
            dir?.deleteRecursively()
            dir = null
            sessionName.value = null
        }
    }

    private fun clear() {
        synchronized(this) { samples.clear() }
        course.startX = null; course.startY = null; course.finish = null
        sessionCourse.value = null
        penalties.value = emptyMap()
        official.value = emptyMap()
        runDriver.value = emptyMap()
        autoDnf.value = emptyMap()
        ignoredCones.value = emptySet()
        videos.value = emptyList()
        tracker = RunTracker(course)
        runs.value = emptyList()
        refNumber.value = null
        proj = null
        lat0 = 0.0; lon0 = 0.0
        brakeMax = 1.0
        sawCanBrake = false
        canValues.clear()
        slowLog.clear()
    }

    /** 0..1 while a session is being read from disk for Review; null when idle. */
    val loadProgress = MutableStateFlow<Float?>(null)

    /** Review: read a session off the main thread (a long session is 15+ MB), showing progress. */
    fun loadInBackground(d: File, then: () -> Unit = {}) {
        if (loadProgress.value != null) return
        loadProgress.value = 0f
        Thread({
            try { load(d) } catch (e: Exception) { android.util.Log.e("Hub", "load failed", e) } finally { loadProgress.value = null; then() }
        }, "load-session").start()
    }

    /** Load a session from disk (to review it, or to keep appending to it). */
    @Synchronized
    fun load(d: File) {
        clear()
        dir = d
        sessionName.value = d.name
        File(d, "course.json").takeIf { it.exists() }?.let {
            val o = JSONObject(it.readText())
            if (o.has("startX")) { course.startX = o.getDouble("startX"); course.startY = o.getDouble("startY") }
            o.optJSONObject("finish")?.let { f ->
                course.finish = Gate(f.getDouble("x"), f.getDouble("y"), f.getDouble("hx"), f.getDouble("hy"))
            }
            // Keep the origin itself too: course.json is saved again from these (a 0,0 origin breaks timing)
            if (o.has("lat0")) { lat0 = o.getDouble("lat0"); lon0 = o.getDouble("lon0") }
            if (o.has("ref")) refNumber.value = o.getInt("ref")
            o.optJSONObject("courseDef")?.let { sessionCourse.value = CourseDef.from(it) }
            o.optJSONArray("videos")?.let { a -> videos.value = (0 until a.length()).mapNotNull { runCatching { VideoLink.from(a.getJSONObject(it)) }.getOrNull() } }
            o.optJSONObject("penalties")?.let { pj -> penalties.value = pj.keys().asSequence().associate { it.toInt() to pj.getInt(it) } }
            o.optJSONObject("official")?.let { oj -> official.value = oj.keys().asSequence().associate { it.toInt() to oj.getDouble(it) } }
            o.optJSONObject("drivers")?.let { dj -> runDriver.value = dj.keys().asSequence().associate { it.toInt() to dj.getString(it) } }
            o.optJSONObject("autoDnf")?.let { aj -> autoDnf.value = aj.keys().asSequence().associate { k -> val a = aj.getJSONObject(k); k.toInt() to AutoDnf(a.getInt("cone"), a.getString("what"), a.getInt("idx")) } }
            o.optJSONArray("ignoredCones")?.let { a -> ignoredCones.value = (0 until a.length()).map { a.getInt(it) }.toSet() }
        }
        File(d, "gps.csv").takeIf { it.exists() }?.let { f ->
            val total = f.length().coerceAtLeast(1)
            var read = 0L; var n = 0
            f.useLines { lines ->
                lines.drop(1).forEach { l ->
                    Sample.parse(l)?.let { samples += it }
                    read += l.length + 1
                    if (++n % 4000 == 0 && loadProgress.value != null) loadProgress.value = (read.toFloat() / total * 0.9f).coerceAtMost(0.9f)
                }
            }
        }
        restoreProjection()
        brakeMax = samples.maxOfOrNull { if (it.brake.isNaN()) 0.0 else it.brake }?.coerceAtLeast(1.0) ?: 1.0
        sawCanBrake = samples.any { !it.brake.isNaN() }
        // Slow channels come back from the car log (only their lines, to keep loading fast)
        File(d, "can.csv").takeIf { it.exists() }?.let { f ->
            val wanted = slowList().filter { it.name != "steer" }.associateBy { "%03X:%04X".format(it.header, it.id) }
            runCatching {
                f.forEachLine { l ->
                    val c1 = l.indexOf(','); val c2 = if (c1 < 0) -1 else l.indexOf(',', c1 + 1)
                    if (c2 < 0) return@forEachLine
                    val key = l.substring(c1 + 1, c2)
                    if (key !in wanted && !key.startsWith("7E0:01")) return@forEachLine
                    val p = l.split(','); if (p.size < 3) return@forEachLine
                    if (p[1] == "7E0:010F" || p[1] == "7E0:0146") {
                        val a = p[2].toIntOrNull(16); val t = p[0].toDoubleOrNull()
                        if (a != null && t != null) slowLog.getOrPut(if (p[1].endsWith("0F")) "iat" else "ambient") { java.util.Collections.synchronizedList(ArrayList()) } += t to (a - 40) * 1.8 + 32
                        return@forEachLine
                    }
                    val dec = wanted[p[1]] ?: return@forEachLine
                    val bytes = p[2].chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                    val t = p[0].toDoubleOrNull() ?: return@forEachLine
                    dec.decode(bytes)?.let { v -> slowLog.getOrPut(dec.name) { java.util.Collections.synchronizedList(ArrayList()) } += t to v }
                }
            }
        }
        recomputeRuns()
    }

    private var lat0 = 0.0
    private var lon0 = 0.0

    /**
     * The local x/y origin the session was recorded with. Versions up to 0.5 could save it as 0,0
     * after a session was reopened (a penalty, reference or video saved from Review), which left
     * the timing lines nowhere near the recorded path: no runs. The recorded samples carry both
     * lat/lon and x/y, so the true origin is recovered from them.
     */
    private fun restoreProjection() {
        if (lat0 == 0.0 && lon0 == 0.0) {
            val s = samples.firstOrNull { it.lat != 0.0 && (it.x != 0.0 || it.y != 0.0) } ?: return
            val (a, b) = com.kimes.daq.data.projectionOrigin(s.lat, s.lon, s.x, s.y)
            lat0 = a; lon0 = b
            event("projection_restored")
        }
        proj = LocalProjection(lat0, lon0)
    }

    private fun saveCourse() {
        val d = dir ?: return
        val o = JSONObject()
        course.startX?.let { o.put("startX", it).put("startY", course.startY) }
        course.finish?.let { o.put("finish", JSONObject().put("x", it.x).put("y", it.y).put("hx", it.hx).put("hy", it.hy)) }
        if (proj != null) o.put("lat0", lat0).put("lon0", lon0)
        refNumber.value?.let { o.put("ref", it) }
        sessionCourse.value?.let { o.put("courseDef", it.json()) }
        o.put("videos", org.json.JSONArray().apply { videos.value.forEach { put(it.json()) } })
        o.put("penalties", JSONObject().apply { penalties.value.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("official", JSONObject().apply { official.value.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("drivers", JSONObject().apply { runDriver.value.forEach { (k, v) -> put(k.toString(), v) } })
        o.put("autoDnf", JSONObject().apply { autoDnf.value.forEach { (k, v) -> put(k.toString(), JSONObject().put("cone", v.cone).put("what", v.what).put("idx", v.idx)) } })
        o.put("ignoredCones", org.json.JSONArray().apply { ignoredCones.value.forEach { put(it) } })
        File(d, "course.json").writeText(o.toString(2))
    }

    @Synchronized
    fun setFinish(g: Gate?) {
        course.finish = g
        recomputeRuns()
        saveCourse()
    }

    @Synchronized
    fun setReference(n: Int?) {
        refNumber.value = n
        follower = null
        saveCourse()
    }

    @Synchronized
    fun recomputeRuns() {
        val snapshot = ArrayList(samples)
        val t = makeTiming()
        runs.value = snapshot.indices.mapNotNull { t.feed(snapshot, it) }
        tracker = makeTiming().apply { setNextNumber(runs.value.size + 1) }
        // Replay tracker state onto the tail so a run in progress keeps going
        val from = runs.value.lastOrNull()?.endIdx?.plus(1) ?: 0
        for (i in from until snapshot.size) tracker.feed(snapshot, i)
    }

    /**
     * The run to compare with: the one picked with "Compare others with this run", else the best
     * clean run. With co-drivers, [forDriver]'s best (default: whoever drives next).
     */
    fun referenceRun(forDriver: String? = if (coDriving) driver.value else null): Run? {
        val all = runs.value
        refNumber.value?.let { n -> all.firstOrNull { it.number == n }?.let { return it } }
        val rs = if (forDriver != null && runDriver.value.isNotEmpty()) all.filter { driverOf(it.number) == forDriver } else all
        return Standings.best(rs, penalties.value) { shownTime(it, all) } ?: rs.filter { it.finished }.minByOrNull { it.time }
    }

    fun event(label: String) {
        evOut?.let { synchronized(it) { it.write("%.3f,%s".format(now(), label.replace(',', ';').replace("\n", " "))); it.newLine() } }
    }

    // ---- data input -------------------------------------------------------------------

    private fun stat(key: Int, data: ByteArray, tNs: Long) {
        val st = frameStats.getOrPut(key) { FrameStat(0, data, LongArray(8)) }
        for (k in data.indices) if (k < 8 && (k >= st.data.size || st.data[k] != data[k])) st.changedAt[k] = tNs
        st.data = data
        st.count++
    }

    private fun setValue(name: String, v: Double, tNs: Long) {
        canValues[name] = if (name == "brake") v.coerceAtLeast(0.0) else v
        canTimes[name] = tNs
    }

    private fun onFrame(tNs: Long, id: Int, data: ByteArray) {
        val t = (tNs - t0Ns) / 1e9 + tOffset
        stat(id, data, tNs)
        for (d in decoders) if (d.header == 0 && d.id == id) d.decode(data)?.let { setValue(d.name, it, tNs) }
        if (logAllCan || id in decoderIds) {
            canOut?.let { w ->
                synchronized(w) {
                    w.write("%.4f,%03X,".format(t, id))
                    for (b in data) w.write("%02X".format(b))
                    w.newLine()
                }
            }
        }
        if (capturing) synchronized(capFrames) { capFrames += CanFinder.Frame(t, id, data) }
    }

    private fun onObdLog(line: String) {
        val l = "%.3f %s".format(now(), line)
        obdOut?.let { synchronized(it) { it.write(l); it.newLine(); it.flush() } }
        obdLog.value = (obdLog.value + l).takeLast(40)
    }

    /** Replies to diagnostic polls: 0x62 (data identifier) from the DSC, 0x41 (OBD-II) from the PCM. */
    private fun onDiag(tNs: Long, header: Int, resp: ByteArray) {
        val t = (tNs - t0Ns) / 1e9 + tOffset
        val sid = resp[0].toInt() and 0xFF
        if (sid == 0x62 && resp.size >= 3) {
            val did = ((resp[1].toInt() and 0xFF) shl 8) or (resp[2].toInt() and 0xFF)
            val payload = resp.copyOfRange(3, resp.size)
            val key = (header shl 16) or did
            stat(key, payload, tNs)
            for (d in decoders) if (d.header == header && d.id == did) d.decode(payload)?.let { setValue(d.name, it, tNs) }
            for (d in slowDecoders()) if (d.header == header && d.id == did) d.decode(payload)?.let { v ->
                setValue(d.name, v, tNs)
                if (d.name == "steer") {
                    if (!steerFast) { steerFast = true; obd?.let { o -> o.plan = basePlan(); o.slow = slowPlan() } }
                } else slowLog.getOrPut(d.name) { java.util.Collections.synchronizedList(ArrayList()) } += t to v
            }
            canOut?.let { w ->
                synchronized(w) { w.write("%.4f,%03X:%04X,%s".format(t, header, did, payload.toHex())); w.newLine() }
            }
            if (capturing) synchronized(capFrames) { capFrames += CanFinder.Frame(t, key, payload) }
        } else if (sid == 0x41) {
            var i = 1
            while (i < resp.size) {
                val pid = resp[i].toInt() and 0xFF
                val n = when (pid) { 0x0C -> 2; 0x05, 0x0D, 0x0F, 0x11, 0x46, 0x49, 0x4A -> 1; 0xA4 -> 4; else -> return }
                if (i + n >= resp.size) return
                val a = resp[i + 1].toInt() and 0xFF
                when (pid) {
                    0x0C -> setValue("rpm", (a * 256 + (resp[i + 2].toInt() and 0xFF)) / 4.0, tNs)
                    0x49 -> onPedal(a * 100.0 / 255.0, tNs)
                    0x11 -> setValue("throttle_body", a * 100.0 / 255.0, tNs)
                    0x0F, 0x46 -> {
                        val name = if (pid == 0x0F) "iat" else "ambient"
                        val f = (a - 40) * 1.8 + 32
                        setValue(name, f, tNs)
                        slowLog.getOrPut(name) { java.util.Collections.synchronizedList(ArrayList()) } += t to f
                        canOut?.let { w -> synchronized(w) { w.write("%.4f,7E0:01%02X,%02X".format(t, pid, a)); w.newLine() } }
                    }
                    0xA4 -> {
                        // A = which parts are supported, B high nibble = actual gear (0 = neutral), C-D = ratio x1000
                        val b = resp[i + 2].toInt() and 0xFF
                        val ratio = ((resp[i + 3].toInt() and 0xFF) * 256 + (resp[i + 4].toInt() and 0xFF)) / 1000.0
                        if (a and 0x01 != 0) setValue("gear", (b shr 4).toDouble(), tNs)
                        if (a and 0x02 != 0) setValue("gear_ratio", ratio, tNs)
                        setValue("gear_raw", b.toDouble(), tNs)
                    }
                }
                i += n + 1
            }
        }
    }

    // Accelerator pedal D never reads 0-100: learn its resting and floored values and rescale
    private val prefs by lazy { ctx.getSharedPreferences("cal", Context.MODE_PRIVATE) }
    private var pedalMin = Double.NaN
    private var pedalMax = Double.NaN

    private fun onPedal(raw: Double, tNs: Long) {
        if (pedalMin.isNaN()) {
            pedalMin = prefs.getFloat("pedalMin", raw.toFloat()).toDouble()
            pedalMax = prefs.getFloat("pedalMax", (raw + 40).toFloat()).toDouble()
        }
        var changed = false
        if (raw < pedalMin) { pedalMin = raw; changed = true }
        if (raw > pedalMax) { pedalMax = raw; changed = true }
        if (changed) prefs.edit().putFloat("pedalMin", pedalMin.toFloat()).putFloat("pedalMax", pedalMax.toFloat()).apply()
        setValue("pedal_raw", raw, tNs)
        setValue("throttle", ((raw - pedalMin) / (pedalMax - pedalMin) * 100).coerceIn(0.0, 100.0), tNs)
    }

    /** A channel value, or NaN if it hasn't updated in the last half second. */
    private fun fresh(name: String, maxAgeNs: Long = 500_000_000L): Double {
        val t = canTimes[name] ?: return Double.NaN
        return if (SystemClock.elapsedRealtimeNanos() - t < maxAgeNs) canValues[name] ?: Double.NaN else Double.NaN
    }

    private val yaw = com.kimes.daq.data.YawAxis()

    /**
     * RaceBox as a position source for the course walk, with no session and nothing recorded.
     * For when the tablet's own GPS has no fix (this tablet's has never heard a satellite).
     */
    private var walkRb: RaceBox? = null
    val walkRaceBox = MutableStateFlow(false)

    @Synchronized
    fun startWalkRaceBox() {
        if (logging.value || walkRb != null) return
        walkRaceBox.value = true
        walkRb = RaceBox(ctx, { p ->
            rbInfo.value = "fix ${p.fix}  sats ${p.sats}  ±%.1fm  batt %d%%".format(p.hAcc, p.battery)
            if (p.fix >= 3 && p.lat != 0.0) TabletGps.external(p.lat, p.lon, p.hAcc.toFloat())
        }) { rbStatus.value = it }.also { it.start() }
    }

    @Synchronized
    fun stopWalkRaceBox() {
        walkRb?.stop(); walkRb = null
        if (walkRaceBox.value) { walkRaceBox.value = false; if (!logging.value) rbStatus.value = "off" }
    }

    private fun onRaceBox(p: RaceBoxPacket) {
        rbCount++
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastRbInfo >= 1000) {
            rbInfo.value = "fix ${p.fix}  sats ${p.sats}  ±%.1fm  %d Hz  batt %d%%".format(p.hAcc, rbCount - lastRbCount, p.battery)
            lastRbCount = rbCount
            lastRbInfo = nowMs
        }
        // Only real 3D fixes: on 2026-11-15 one corrupt packet (position 0,0, fix code 87) passed the
        // old check, became the session's "home" and stopped the session as "left the course"
        if (p.fix !in 3..4 || p.sats < 4 || p.hAcc > 50.0 || kotlin.math.abs(p.lat) < 0.01 || kotlin.math.abs(p.lon) < 0.01 || kotlin.math.abs(p.lat) > 90 || kotlin.math.abs(p.lon) > 180) return
        TabletGps.external(p.lat, p.lon, p.hAcc.toFloat())

        synchronized(this) {
            if (proj == null) {
                lat0 = p.lat; lon0 = p.lon; proj = LocalProjection(lat0, lon0)
                if (sessionCourse.value == null) courses.nearest(p.lat, p.lon)?.let { activeCourse.value = it; sessionCourse.value = it }
                if (!tracker.isRunning) tracker = makeTiming().apply { setNextNumber(runs.value.size + 1) }
                autoRec = makeAutoRec()
                saveCourse()
            }
            val pr = proj!!
            val t = now()
            val prev = samples.lastOrNull()
            val x = pr.x(p.lon)
            val y = pr.y(p.lat)
            var latG = 0.0
            if (prev != null && t - prev.t in 0.005..0.5) {
                val dt = t - prev.t
                val rawLon = (p.speed - prev.speed) / dt / 9.81
                lonGf += 0.35 * (rawLon - lonGf)
                if (p.speed > 3) {
                    var dh = p.heading - prev.heading
                    if (dh > 180) dh -= 360
                    if (dh < -180) dh += 360
                    latG = -p.speed * (dh * PI / 180 / dt) / 9.81
                }
            }
            val brake = fresh("brake")
            if (!brake.isNaN() && brake > brakeMax) brakeMax = brake
            val gearV = fresh("gear", 1_500_000_000L)
            val s = Sample(
                t, p.lat, p.lon, x, y, p.speed, p.heading, brake,
                fresh("throttle"), fresh("rpm"), fresh("steer"),
                if (prev != null) prev.latG * 0.5 + latG * 0.5 else latG, lonGf, p.sats, p.fix, p.utcMs,
                yaw = yaw.rate(p, prev, t), gear = if (gearV.isNaN()) -1 else gearV.toInt(),
                gearRatio = fresh("gear_ratio", 1_500_000_000L),
                wFL = fresh("wFL"), wFR = fresh("wFR"), wRL = fresh("wRL"), wRR = fresh("wRR"),
            )
            samples += s
            gpsOut?.let { it.write(s.csv()); it.newLine() }
            // Driving home with the session still on: stop once well away from where it started
            if (!autoStopping && !tracker.isRunning && samples.size > samplesAtStart) {
                val home = samples[samplesAtStart]
                if (com.kimes.daq.course.distM(home.lat, home.lon, s.lat, s.lon) > 2000) {
                    autoStopping = true
                    event("auto_stop left_course")
                    autoStopNote.value = "Session stopped on its own: the car was over a mile from where it started."
                    android.os.Handler(android.os.Looper.getMainLooper()).post { LoggerService.stop(ctx) }
                }
            }
            autoRec?.feed(prev, s)?.let { on ->
                GoPro.record(on, "auto")
                // Run over: once the camera has finished writing, pull this run's preview over Wi-Fi
                if (!on && s.utc > 0) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ autoFetch() }, 12_000)
                }
            }

            val i = samples.size - 1
            val wasRunning = tracker.isRunning
            val done = tracker.feed(samples, i)
            if (done != null) {
                driverAtStart?.let { runDriver.value = runDriver.value + (done.number to it) }
                runs.value = runs.value + done
                checkCones(done)
                event("run_end ${done.number}")
                saveCourse()
            }
            if (done != null || (!wasRunning && tracker.isRunning)) {
                if (tracker.isRunning) { driverAtStart = if (coDriving) driver.value else null; event("run_start" + (driverAtStart?.let { " $it" } ?: "")) }
                follower = null // updateLive picks the reference fresh for the new run
            }
            updateLive(s, done)
        }

        if (nowMs - lastFlush > 1000) {
            lastFlush = nowMs
            try { gpsOut?.flush(); canOut?.let { synchronized(it) { it.flush() } }; evOut?.flush() } catch (_: Exception) {}
            tick.value = nowMs
        }
    }

    private var followerRef: Run? = null

    private fun updateLive(s: Sample, done: Run?) {
        val brakeIsCan = !s.brake.isNaN()
        if (brakeIsCan) sawCanBrake = true
        // No brake signal from this car at all: show RaceBox braking g instead. A signal that
        // dropped out mid-session stays a fault so the two scales never mix.
        val brakeFromG = !brakeIsCan && !sawCanBrake
        val b = when {
            brakeIsCan -> (s.brake / brakeMax).coerceIn(0.0, 1.0)
            brakeFromG && !s.lonG.isNaN() -> (-s.lonG).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val th = if (s.throttle.isNaN()) 0.0 else (s.throttle / 100).coerceIn(0.0, 1.0)
        bTrace.addLast(b.toFloat()); tTrace.addLast(th.toFloat())
        while (bTrace.size > traceLen) bTrace.removeFirst()
        while (tTrace.size > traceLen) tTrace.removeFirst()

        val running = tracker.isRunning
        val elapsed = if (running) s.t - tracker.currentStartT else 0.0
        if (running && follower == null) {
            followerRef = referenceRun(driverAtStart ?: if (coDriving) driver.value else null)
            follower = followerRef?.let { PathFollower(RefPath(samples, it)) }
        }
        var delta: Double? = null
        if (running) follower?.let { if (it.update(s.x, s.y)) delta = elapsed - it.refT }
        val prev = live.value
        val ref = if (running) followerRef else referenceRun()
        val c = sessionCourse.value
        val nSec = if (c != null && c.timed) c.sectorCount else 1

        // Sector deltas: each finished sector vs the same sector on the reference run
        val refSec = ref?.sectorTimes ?: emptyList()
        val splits = if (running) tracker.currentSplits else emptyList()
        val secDeltas = splits.indices.map { k ->
            val mine = splits[k] - if (k == 0) 0.0 else splits[k - 1]
            refSec.getOrNull(k)?.let { mine - it }
        }
        val d = delta ?: if (running) prev.delta else null
        val atLastSplit = splits.lastOrNull()?.let { sp -> ref?.splits?.getOrNull(splits.size - 1)?.let { sp - it } } ?: 0.0
        live.value = LiveState(
            running = running,
            elapsed = elapsed,
            delta = d,
            brake = b,
            throttle = th,
            brakeIsCan = brakeIsCan,
            brakeFromG = brakeFromG,
            gpsOk = s.fix >= 3,
            runNumber = if (running) runs.value.size + 1 else (done ?: runs.value.lastOrNull())?.number ?: 0,
            refNumber = ref?.number,
            sectorCount = nSec,
            sectorDeltas = secDeltas,
            currentSectorDelta = if (running && nSec > 1) d?.let { it - atLastSplit } else null,
            lineTimed = c != null && c.timed,
            toStartM = if (running) null else toStartM(s),
            brakeTrace = bTrace.toFloatArray(),
            throttleTrace = tTrace.toFloatArray(),
        )
        if (!running) follower = null
    }

    // ---- guided CAN test --------------------------------------------------------------

    fun startCapture() {
        synchronized(capFrames) { capFrames.clear() }
        capPhases.clear()
        logAllCan = true
        capturing = true
        event("capture_start")
    }

    fun phase(label: String, t0: Double, t1: Double) {
        capPhases += CanFinder.Phase(label, t0, t1)
        event("phase $label %.3f %.3f".format(t0, t1))
    }

    fun stopCapture() {
        capturing = false
        event("capture_stop")
    }
}
