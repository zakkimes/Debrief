package com.kimes.daq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.kimes.daq.Hub
import com.kimes.daq.course.CourseDef
import com.kimes.daq.data.LocalProjection
import com.kimes.daq.run.OptimalLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The optimized line in lat/lon, with its estimated time and sector times. */
class OptimalPath(
    val lats: DoubleArray,
    val lons: DoubleArray,
    val time: Double,
    val sectorTimes: List<Double>,
    val worstClearance: Double,
    /** Where the time comes from, for the screen: "your best sections", "your car profile", ... */
    val basis: String = "",
)

/** Why there's no line yet, for the editor. */
fun optimalLineMissing(c: CourseDef): String? = when {
    c.start == null || c.finish == null -> "Optimized line needs start and finish lines."
    c.cones.count { it.passBearing != null } < 2 -> "Optimized line needs at least 2 cones with a pass side."
    else -> null
}

/** The last line worked out, and what it depended on: the search takes a few seconds, so screens share it. */
private var cached: Pair<List<Any?>, OptimalPath?>? = null

fun computeOptimal(c: CourseDef): OptimalPath? {
    if (optimalLineMissing(c) != null) return null
    val key = listOf(c, Hub.sessionCourse.value?.id == c.id, Hub.runs.value.size, Hub.penalties.value, Hub.carProfile.value, Hub.learnedCar.value?.source)
    cached?.let { (k, v) -> if (k == key) return v }
    return computeFresh(c).also { cached = key to it }
}

private fun computeFresh(c: CourseDef): OptimalPath? {
    val (lat0, lon0) = c.center() ?: return null
    val proj = LocalProjection(lat0, lon0)
    val cones = c.cones.filter { it.passBearing != null }.map {
        val r = it.passBearing!! * PI / 180
        OptimalLine.ConeSide(proj.x(it.lon), proj.y(it.lat), sin(r), cos(r))
    }
    // The car: learned from earlier runs, else the car profile, else a stock sports car
    val prior = Hub.carPrior()
    val start = c.start!!.gate(proj)
    // With a course map placed, the line stays on its paved area (and can use more of the lot)
    val mPerLat0 = 111_320.0; val mPerLon0 = 111_320.0 * cos(lat0 * PI / 180)
    val drivable = c.overlay?.let { o ->
        com.kimes.daq.course.MapImport.loadPicture(o.file)?.let { pic ->
            { x: Double, y: Double ->
                val (u, v) = o.toPixel(lat0 + y / mPerLat0, lon0 + x / mPerLon0)
                val iu = u.toInt(); val iv = v.toInt()
                pic.isLot(iu, iv) || pic.isLot(iu + 2, iv) || pic.isLot(iu - 2, iv) || pic.isLot(iu, iv + 2) || pic.isLot(iu, iv - 2)
            }
        }
    }
    val mPerLat = 111_320.0; val mPerLon = 111_320.0 * cos(lat0 * PI / 180)
    // Runs on this course: the line is your best sections stitched together, and so is the time
    val driven = if (Hub.sessionCourse.value?.id == c.id) runCatching { stitchedBest(c, proj) }.getOrNull() else null
    val r: OptimalLine.Result
    val time: Double
    val basis: String
    if (driven != null) {
        val (path, car, total) = driven
        Hub.learnCar(car)
        // Only smoothing out the joins between sections: the shape stays the one you drove
        r = OptimalLine.refine(path.first, path.second, cones, car, iterations = 3000, maxMove = 1.0)
        time = total; basis = "your best sections"
    } else {
        // No runs yet: start from the geometric line, then search for the fastest line near it with this car
        val geo = OptimalLine.solve(start, c.finish!!.gate(proj), cones, prior, iterations = 2500)
        // The course map's paved area isn't used: read from a PDF it's rough, and as a limit it cost
        // Saturday's course 3 s of perfectly drivable line. The cones bound the line.
        r = OptimalLine.refine(geo.xs, geo.ys, cones, prior, iterations = 9000)
        time = r.time
        basis = when (prior.source) { "default" -> "a typical sports car: set yours under Devices"; "your runs" -> "your earlier runs"; else -> prior.source }
    }
    val scale = time / r.time
    // In the order the line crosses them (lines are stored in the order they were placed)
    val splits = c.sectors.mapNotNull { r.crossingTime(it.gate(proj)) }.sorted()
    val all = if (splits.size == c.sectors.size && c.sectors.isNotEmpty()) splits + r.time else emptyList()
    return OptimalPath(
        DoubleArray(r.xs.size) { lat0 + r.ys[it] / mPerLat },
        DoubleArray(r.xs.size) { lon0 + r.xs[it] / mPerLon },
        time,
        all.indices.map { (all[it] - if (it == 0) 0.0 else all[it - 1]) * scale },
        r.worstClearance,
        basis,
    )
}

/**
 * This session's best sections stitched into one line (in the course's meters, smoothed), the car
 * measured from the runs, and the stitched time. Null with fewer than two clean runs.
 */
private fun stitchedBest(c: CourseDef, proj: LocalProjection): Triple<Pair<DoubleArray, DoubleArray>, OptimalLine.Car, Double>? {
    val snap = synchronized(Hub) { ArrayList(Hub.samples) }
    val pens = Hub.penalties.value
    val runs = Hub.runs.value.filter { it.finished && pens[it.number] != com.kimes.daq.run.Standings.DNF && it.endIdx < snap.size }
    if (runs.size < 2) return null
    val sec = com.kimes.daq.run.Sections.build(snap, runs, corners = c.corners, corrections = Hub.corrections(Hub.runs.value))
    if (sec.optimalTotal.isNaN() || sec.optimalLatLon.size < 20) return null
    fun xy(ll: List<Pair<Double, Double>>) = ll.map { proj.x(it.second) }.toDoubleArray() to ll.map { proj.y(it.first) }.toDoubleArray()
    val path = xy(sec.optimalLatLon).let { OptimalLine.smoothPath(it.first, it.second, 50) }
    val best = runs.minBy { it.time }
    val bestPath = xy(snap.subList(best.startIdx, best.endIdx + 1).map { it.lat to it.lon }).let { OptimalLine.smoothPath(it.first, it.second, 50) }
    val car = com.kimes.daq.run.CarModel.fromRuns(runs.map { snap.subList(it.startIdx, it.endIdx + 1) }, bestPath, best.time, Hub.carPrior())
    return Triple(path, car, sec.optimalTotal)
}

/** Computes the line in the background whenever the course changes. */
@Composable
fun rememberOptimal(c: CourseDef?): OptimalPath? {
    var out by remember { mutableStateOf<OptimalPath?>(null) }
    LaunchedEffect(c) {
        kotlinx.coroutines.delay(400) // wait for drags and typing to settle
        out = if (c == null) null else withContext(Dispatchers.Default) { runCatching { computeOptimal(c) }.getOrNull() }
    }
    return out
}

fun DrawScope.drawOptimal(pr: MapProj, p: OptimalPath, width: Float = 4f, color: Color = Magenta) {
    val path = Path()
    for (k in p.lats.indices) {
        val o = pr.pt(p.lats[k], p.lons[k])
        if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    drawPath(path, color, style = Stroke(width))
}
