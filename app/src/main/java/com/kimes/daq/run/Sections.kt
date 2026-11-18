package com.kimes.daq.run

import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import kotlin.math.max

/**
 * One element of the course, identical for every run. Distances are metres along the base path.
 * Apex is where the base run was slowest inside the section (for map labels).
 */
data class Section(
    val index: Int,
    val name: String,
    val d0: Double,
    val d1: Double,
    val apexX: Double,
    val apexY: Double,
) {
    val length get() = d1 - d0
}

/** How one run drove one section. NaN where unknown (no channel, or the run didn't cover it). */
data class SectionStats(
    val minSpeed: Double,     // m/s
    val brakeOnD: Double,     // distance along the base path where braking began (before the apex)
    val throttleOnD: Double,  // distance along the base path where throttle > 25% after the apex
)

/** The best piece of the optimal lap: [run] drove [section] in [time]. */
data class OptimalPiece(val section: Section, val run: Int, val time: Double)

class SectionTable(
    val base: Run?,
    val sections: List<Section>,
    /** Run numbers in the order given. */
    val runNumbers: List<Int>,
    /** times[runNumber][sectionIndex] seconds, NaN if the run didn't cover the section. */
    val times: Map<Int, DoubleArray>,
    /** stats[runNumber][sectionIndex]. */
    val stats: Map<Int, List<SectionStats>>,
    /** Per section: best finished run number, or -1 if none. */
    val bestRun: IntArray,
    /** Per section: best finished time, NaN if none. */
    val bestTime: DoubleArray,
    /** Finished run numbers. */
    val finished: Set<Int>,
    /** Whole-run times (seconds) by run number. */
    val runTimes: Map<Int, Double>,
    /** Stitched x,y of the best run through each section, in driving order. */
    val optimalPath: List<Pair<Double, Double>>,
    /** The same stitched path as lat, lon, for drawing on the satellite map. */
    val optimalLatLon: List<Pair<Double, Double>> = emptyList(),
    /** Per run number: matched distance along the base path for each of the run's samples. */
    val dists: Map<Int, DoubleArray> = emptyMap(),
) {
    /** Sum of section bests; NaN if some section has no finished run. */
    val optimalTotal: Double =
        if (sections.isEmpty() || bestTime.any { it.isNaN() }) Double.NaN else bestTime.sum()

    /** Fastest finished run's actual time, NaN if none. */
    val bestActualTime: Double = finished.mapNotNull { runTimes[it] }.minOrNull() ?: Double.NaN
    val bestActualRun: Int = finished.minByOrNull { runTimes[it] ?: Double.MAX_VALUE } ?: -1

    /** What stitching the best sections gains over the best real run (>= 0), NaN if unknown. */
    val optimalGain: Double get() = bestActualTime - optimalTotal

    val optimalPieces: List<OptimalPiece>
        get() = sections.indices.filter { bestRun[it] >= 0 }.map { OptimalPiece(sections[it], bestRun[it], bestTime[it]) }

    /** Seconds this run lost in this section vs the best, NaN if unknown. 0 for the best run. */
    fun loss(run: Int, section: Int): Double {
        val t = times[run]?.getOrNull(section) ?: return Double.NaN
        return if (t.isNaN() || bestTime[section].isNaN()) Double.NaN else max(0.0, t - bestTime[section])
    }

    /** Sections where this finished run holds the best time. */
    fun sectionsWon(run: Int): List<Section> = sections.filter { bestRun[it.index] == run }

    /** Total time this run leaves on the table vs the optimal lap (sections it covers only). */
    fun totalLoss(run: Int): Double = sections.indices.sumOf { loss(run, it).let { l -> if (l.isNaN()) 0.0 else l } }
}

/**
 * Per-section timing across runs ("optimal lap").
 *
 * Section rule: sections are defined once on the base path (fastest finished run by default) from
 * [Analyzer.findZones], the same corner definition behind the "Corner N" opportunities: speed
 * valleys with >= ~3.8 mph of drop on each side, the launch valley ignored. Each corner section
 * runs from the speed peak before the corner to the speed peak after it, so adjacent corners share
 * a boundary (a straight between two corners is split at its speed maximum, each half going to a
 * neighbouring corner). Before the first corner there is a "Launch" section (start to first peak);
 * a "Finish" section is added if the course continues past the last corner's peak. A section
 * shorter than [MIN_LEN] m is merged into the following one (the last into the preceding one).
 * Corner names keep Analyzer's numbering when nothing merges.
 *
 * Every run is projected onto the base path with [PathFollower]; the time at each boundary is
 * interpolated where that run's matched distance first reaches it, so a run's section times sum to
 * its run time. Only finished runs compete for best/optimal. Cost is O(n) per run.
 */
object Sections {
    const val MIN_LEN = 8.0
    private const val END_TOL = 12.0   // how close to the base path's end a run must get to count as finishing

    /**
     * [corrections]: per run number, official time minus this tablet's time. The timing lights and
     * the tablet's GPS lines trigger at slightly different spots, so the whole difference belongs to
     * the start and the finish: half goes into the first section, half into the last. Every section
     * in between stays as measured, and each run's sections add up to its official time.
     */
    fun build(samples: List<Sample>, runs: List<Run>, base: Run? = null, corners: List<com.kimes.daq.course.CornerMark> = emptyList(), corrections: Map<Int, Double> = emptyMap()): SectionTable {
        val usable = runs.filter { it.endIdx - it.startIdx >= 9 && it.endIdx < samples.size && it.time > 0 }
        val baseRun = base ?: usable.filter { it.finished }.minByOrNull { it.time } ?: usable.maxByOrNull { it.time }
        if (baseRun == null || baseRun.endIdx - baseRun.startIdx < 9 || baseRun.endIdx >= samples.size) return empty(baseRun, runs)

        val path = RefPath(samples, baseRun)
        val total = path.ds[path.size - 1]
        val refS = samples.subList(baseRun.startIdx, baseRun.endIdx + 1)
        val zones = Analyzer.findZones(refS, path.ds, corners)

        class Raw(var name: String, var d0: Double, var d1: Double, val apexD: Double, val corner: Boolean)
        val raw = ArrayList<Raw>()
        if (zones.isEmpty()) {
            raw += Raw("Course", 0.0, total, apexOfSpeed(refS, path.ds, 0.0, total), false)
        } else {
            if (zones.first().d0 > 0.0) raw += Raw("Launch", 0.0, zones.first().d0, 0.0, false)
            for ((k, z) in zones.withIndex()) {
                val start = if (k == 0) z.d0 else raw.last().d1
                raw += Raw(z.name, start, z.d1, z.dMin, true)
            }
            if (zones.last().d1 < total - MIN_LEN) raw += Raw("Finish", zones.last().d1, total, total, false)
        }
        var i = 0
        while (i < raw.size && raw.size > 1) {
            val r = raw[i]
            if (r.d1 - r.d0 < MIN_LEN) {
                if (i + 1 < raw.size) { raw[i + 1].d0 = r.d0; if (!raw[i + 1].corner && r.corner) raw[i + 1].name = r.name }
                else raw[i - 1].d1 = r.d1
                raw.removeAt(i)
            } else i++
        }
        val sections = raw.mapIndexed { k, r ->
            val ai = indexAtDist(path.ds, r.apexD.coerceIn(r.d0, r.d1))
            Section(k, r.name, r.d0, r.d1, path.xs[ai], path.ys[ai])
        }
        val bounds = DoubleArray(sections.size + 1) { if (it == 0) 0.0 else sections[it - 1].d1 }
        bounds[sections.size] = total

        val brakeIsCan = samples.any { !it.brake.isNaN() && it.brake > 0 }
        val brakeMax = if (brakeIsCan) samples.maxOf { if (it.brake.isNaN()) 0.0 else it.brake } else 1.0
        fun brakeOn(s: Sample) = if (brakeIsCan) !s.brake.isNaN() && s.brake > 0.12 * brakeMax else s.lonG < -0.25

        val nan = SectionStats(Double.NaN, Double.NaN, Double.NaN)
        val times = LinkedHashMap<Int, DoubleArray>()
        val stats = LinkedHashMap<Int, List<SectionStats>>()
        val dists = HashMap<Int, DoubleArray>()
        for (run in runs) {
            if (run.endIdx - run.startIdx < 2 || run.endIdx >= samples.size) {
                times[run.number] = DoubleArray(sections.size) { Double.NaN }
                stats[run.number] = sections.map { nan }
                continue
            }
            val n = run.endIdx - run.startIdx + 1
            val dm = DoubleArray(n)          // running-max matched distance (carried over failed matches)
            val follower = PathFollower(path)
            var last = 0.0
            for (k in 0 until n) {
                val s = samples[run.startIdx + k]
                if (follower.update(s.x, s.y)) last = max(last, follower.refD)
                dm[k] = last
            }
            dists[run.number] = dm
            val tb = crossingTimes(samples, run, dm, bounds, total)
            times[run.number] = DoubleArray(sections.size) { tb[it + 1] - tb[it] }.also { t ->
                corrections[run.number]?.takeIf { !it.isNaN() && t.isNotEmpty() }?.let { c ->
                    if (t.size == 1) t[0] += c else { t[0] += c / 2; t[t.size - 1] += c / 2 }
                }
            }

            val hasThrottle = (run.startIdx..run.endIdx).any { !samples[it].throttle.isNaN() }
            var from = 0
            stats[run.number] = sections.map { sec ->
                while (from < n && dm[from] < sec.d0) from++
                var to = from
                while (to < n && (dm[to] < sec.d1 || sec.index == sections.size - 1)) to++
                if (to - from < 3) return@map nan
                var apex = from
                for (k in from until to) if (samples[run.startIdx + k].speed < samples[run.startIdx + apex].speed) apex = k
                var brakeD = Double.NaN
                for (k in from..apex) if (brakeOn(samples[run.startIdx + k])) { brakeD = dm[k]; break }
                var thrD = Double.NaN
                if (hasThrottle) for (k in apex until to) if (samples[run.startIdx + k].throttle > 25) { thrD = dm[k]; break }
                SectionStats(samples[run.startIdx + apex].speed, brakeD, thrD)
            }
        }

        val finished = runs.filter { it.finished }.map { it.number }.toSet()
        val bestRun = IntArray(sections.size) { -1 }
        val bestTime = DoubleArray(sections.size) { Double.NaN }
        for (s in sections.indices) {
            for (r in runs) {
                if (r.number !in finished) continue
                val t = times[r.number]?.get(s) ?: continue
                if (!t.isNaN() && t > 0 && (bestTime[s].isNaN() || t < bestTime[s])) { bestTime[s] = t; bestRun[s] = r.number }
            }
        }

        val opt = ArrayList<Pair<Double, Double>>()
        val optLL = ArrayList<Pair<Double, Double>>()
        for (s in sections.indices) {
            val rn = bestRun[s]
            val run = runs.firstOrNull { it.number == rn } ?: continue
            val dm = dists[rn] ?: continue
            val last = s == sections.size - 1
            for (k in dm.indices) {
                val d = dm[k]
                if (d >= sections[s].d0 && (d < sections[s].d1 || last)) {
                    val sm = samples[run.startIdx + k]
                    opt += sm.x to sm.y
                    optLL += sm.lat to sm.lon
                }
            }
        }

        return SectionTable(
            baseRun, sections, runs.map { it.number }, times, stats, bestRun, bestTime, finished,
            runs.associate { it.number to it.time + (corrections[it.number] ?: 0.0) }, opt, optLL, dists,
        )
    }

    private fun empty(base: Run?, runs: List<Run>) = SectionTable(
        base, emptyList(), runs.map { it.number },
        runs.associate { it.number to DoubleArray(0) }, runs.associate { it.number to emptyList() },
        IntArray(0), DoubleArray(0), runs.filter { it.finished }.map { it.number }.toSet(),
        runs.associate { it.number to it.time }, emptyList(),
    )

    /** Elapsed time (since run start) when the run reaches each boundary; NaN if it never does. */
    private fun crossingTimes(samples: List<Sample>, run: Run, dm: DoubleArray, bounds: DoubleArray, total: Double): DoubleArray {
        val out = DoubleArray(bounds.size) { Double.NaN }
        out[0] = 0.0
        var k = 0
        for (b in 1 until bounds.size - 1) {
            while (k < dm.size && dm[k] < bounds[b]) k++
            if (k >= dm.size) break
            if (k == 0) { if (dm[0] - bounds[b] < 15) out[b] = samples[run.startIdx].t - run.startT; continue }
            val s1 = samples[run.startIdx + k]; val s0 = samples[run.startIdx + k - 1]
            val span = dm[k] - dm[k - 1]
            val f = if (span < 1e-9) 1.0 else ((bounds[b] - dm[k - 1]) / span).coerceIn(0.0, 1.0)
            out[b] = (s0.t + (s1.t - s0.t) * f) - run.startT
        }
        val reached = dm.isNotEmpty() && dm.last() >= total - END_TOL
        out[bounds.size - 1] = if (reached) run.time else Double.NaN
        return out
    }

    private fun indexAtDist(ds: DoubleArray, d: Double): Int {
        var lo = 0; var hi = ds.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (ds[m] < d) lo = m + 1 else hi = m }
        return lo
    }

    private fun apexOfSpeed(ss: List<Sample>, ds: DoubleArray, d0: Double, d1: Double): Double {
        var best = d0; var v = Double.MAX_VALUE
        for (i in ss.indices) if (ds[i] in d0..d1 && ss[i].speed < v) { v = ss[i].speed; best = ds[i] }
        return best
    }
}
