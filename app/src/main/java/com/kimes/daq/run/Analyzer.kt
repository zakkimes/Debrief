package com.kimes.daq.run

import com.kimes.daq.course.CornerMark
import com.kimes.daq.data.LocalProjection
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A corner on the reference run: from the speed peak before it to the speed peak after it. */
data class Zone(val name: String, val d0: Double, val dMin: Double, val d1: Double)

data class ZoneStats(
    val minSpeed: Double,
    val brakeOnD: Double?,     // reference distance where braking began
    val throttleOnD: Double?,  // reference distance where throttle was picked up after the apex
    val coastS: Double,
    val peakBrake: Double,
    val peakLatG: Double,
    val entrySpeed: Double = Double.NaN,  // m/s entering the corner
    val exitSpeed: Double = Double.NaN,   // m/s leaving it
    val pathM: Double = Double.NaN,       // length of the line actually driven through it (GPS), m
    val apexD: Double = Double.NaN,       // where the slowest point was, along the shared distance
    val fullThrottleS: Double = Double.NaN, // seconds at 90%+ throttle
    val peakDecelG: Double = Double.NaN,  // hardest braking measured by the RaceBox, g
    val oneTurn: Boolean = true,          // not a slalom: there is one apex to talk about
)

data class Analysis(
    val run: Run,
    val ref: Run?,
    val dist: DoubleArray,        // distance along the reference (or own path) per run sample
    val delta: DoubleArray,       // seconds vs reference per run sample (NaN if unknown)
    val refDist: DoubleArray,     // distance per reference sample
    val zones: List<Zone>,
    val insights: List<String>,
    val brakeIsCan: Boolean,
    val opportunities: List<Opportunity> = emptyList(), // biggest losses first
    val gain: Opportunity? = null,                       // where this run gained the most
    val sectorD: List<Double> = emptyList(),             // reference distance of each sector line
    val launch: Launch? = null,
    val slides: List<Slide> = emptyList(),
    val slips: List<Pair<String, SlipEvent>> = emptyList(), // where (e.g. "Corner 3 exit") and the event
)

/** One place to find time: a corner where this run lost (or gained) against the reference. */
data class Opportunity(
    val zone: Zone, val lost: Double, val text: String, val sector: Int?, val coastS: Double = 0.0,
    val vs: Int? = null,   // the run this corner is compared with (the best run there)
    val at: Int? = null,   // session sample index of this run's slowest point in the corner
)

object Analyzer {
    private const val MPH = 2.236936
    private const val FT = 3.28084

    /**
     * [table] (every run's time in every section) turns on per-corner coaching: each corner is
     * compared with whichever run was quickest there, not with one reference run. [runs] are the
     * runs the table covers.
     */
    fun analyze(samples: List<Sample>, run: Run, ref: Run?, corners: List<CornerMark> = emptyList(), table: SectionTable? = null, runs: List<Run> = emptyList()): Analysis {
        val runS = samples.subList(run.startIdx, run.endIdx + 1)
        val brakeIsCan = samples.any { !it.brake.isNaN() && it.brake > 0 }
        val brakeMax = if (brakeIsCan) samples.maxOf { if (it.brake.isNaN()) 0.0 else it.brake } else 1.0
        val hasThrottle = runS.any { !it.throttle.isNaN() }
        // Without the car's pedals (RaceBox only), "back to power" and coasting come from the RaceBox
        // speed trace: the car speeding up again, or neither speeding up nor slowing down
        val coastWhat = if (hasThrottle) "off the brake but not on the throttle" else "not braking or speeding up"

        val useRef = ref != null && ref.number != run.number
        val refRun = if (useRef) ref!! else run
        val refS = samples.subList(refRun.startIdx, refRun.endIdx + 1)
        val path = RefPath(samples, refRun)

        val dist = DoubleArray(runS.size)
        val delta = DoubleArray(runS.size) { Double.NaN }
        val follower = PathFollower(path)
        var lastD = 0.0
        for ((k, s) in runS.withIndex()) {
            if (follower.update(s.x, s.y)) {
                lastD = follower.refD
                if (useRef) delta[k] = (s.t - run.startT) - follower.refT
            }
            dist[k] = lastD
        }

        val zones = findZones(refS, path.ds, corners)
        val gyroBias = Coach.gyroBias(samples)
        fun brakeOn(s: Sample) = if (brakeIsCan) !s.brake.isNaN() && s.brake > 0.12 * brakeMax else s.lonG < -0.25
        fun brakeVal(s: Sample) = if (brakeIsCan) (if (s.brake.isNaN()) 0.0 else s.brake / brakeMax * 100) else max(0.0, -s.lonG)

        fun stats(ss: List<Sample>, ds: DoubleArray, z: Zone): ZoneStats? {
            val idx = ds.indices.filter { ds[it] >= z.d0 && ds[it] <= z.d1 }
            if (idx.size < 5) return null
            val apex = idx.minBy { ss[it].speed }
            val brakeOnD = idx.firstOrNull { it <= apex && brakeOn(ss[it]) }?.let { ds[it] }
            val throttleOnD = if (hasThrottle) idx.firstOrNull { it >= apex && ss[it].throttle > 25 }?.let { ds[it] }
                else idx.firstOrNull { it >= apex && (it until kotlin.math.min(ss.size, it + 5)).all { j -> ss[j].lonG > 0.06 } }?.let { ds[it] }
            var coast = 0.0
            if (hasThrottle) for (j in idx) {
                val s = ss[j]
                if (j == 0) continue
                val thr = if (s.throttle.isNaN()) 0.0 else s.throttle
                // Off both pedals and not leaning on the tires: time that's doing nothing for you
                if (!brakeOn(s) && thr < 10 && s.speed > 3 && abs(s.lonG) < 0.2 && abs(s.latG) < 0.5) coast += s.t - ss[j - 1].t
            } else {
                // RaceBox only: stretches of at least 0.3 s with the speed flat and the tires not loaded
                var streak = 0.0
                for (j in idx) {
                    if (j == 0) continue
                    val s = ss[j]
                    if (s.speed > 3 && s.lonG > -0.12 && s.lonG < 0.04 && abs(s.latG) < 0.5) streak += s.t - ss[j - 1].t
                    else { if (streak >= 0.3) coast += streak; streak = 0.0 }
                }
                if (streak >= 0.3) coast += streak
            }
            var path = 0.0
            for (q in 1 until idx.size) { val a = ss[idx[q - 1]]; val c = ss[idx[q]]; path += kotlin.math.hypot(c.x - a.x, c.y - a.y) }
            var full = 0.0
            if (hasThrottle) for (q in 1 until idx.size) if (ss[idx[q]].throttle >= 90) full += ss[idx[q]].t - ss[idx[q - 1]].t
            return ZoneStats(
                entrySpeed = ss[idx.first()].speed,
                exitSpeed = ss[idx.last()].speed,
                pathM = path,
                apexD = ds[apex],
                oneTurn = !LineCoach.zigzag(ss, idx),
                fullThrottleS = if (hasThrottle) full else Double.NaN,
                peakDecelG = idx.maxOf { -ss[it].lonG }.coerceAtLeast(0.0),
                minSpeed = ss[apex].speed,
                brakeOnD = brakeOnD,
                throttleOnD = throttleOnD,
                coastS = coast,
                peakBrake = idx.maxOf { brakeVal(ss[it]) },
                // From the RaceBox gyro (speed x turn rate) where there is one: GPS heading is too
                // jittery at autocross speeds. 90th percentile, so one noisy sample can't set it.
                peakLatG = idx.map { val s = ss[it]; if (s.yaw.isNaN()) abs(s.latG) else abs(s.speed * Math.toRadians(s.yaw - gyroBias)) / 9.81 }
                    .sorted().let { g -> g[(g.size * 9 / 10).coerceAtMost(g.size - 1)] },
            )
        }

        val insights = ArrayList<String>()
        val opps = ArrayList<Opportunity>()
        var gain: Opportunity? = null
        // Where the sector lines sit along the reference path
        val sectorD = refRun.splits.map { sp ->
            val k = path.ts.indices.minByOrNull { abs(path.ts[it] - sp) } ?: 0
            path.ds[k]
        }
        fun sectorOf(z: Zone) = if (sectorD.isEmpty()) null else sectorD.count { it < z.dMin } + 1
        val refStats = zones.map { stats(refS, path.ds, it) }
        val runStats = zones.map { stats(runS, dist, it) }

        if (useRef) {
            val total = run.time - refRun.time
            insights += if (total >= 0) "%.2fs slower than run %d. Biggest losses below.".format(total, refRun.number)
            else "%.2fs faster than run %d. New reference.".format(-total, refRun.number)

            data class ZoneDelta(val z: Zone, val lost: Double, val r: ZoneStats, val b: ZoneStats)
            val zd = zones.indices.mapNotNull { i ->
                val r = runStats[i] ?: return@mapNotNull null
                val b = refStats[i] ?: return@mapNotNull null
                val d0 = deltaAt(dist, delta, zones[i].d0) ?: return@mapNotNull null
                val d1 = deltaAt(dist, delta, zones[i].d1) ?: return@mapNotNull null
                ZoneDelta(zones[i], d1 - d0, r, b)
            }
            for (x in zd.filter { it.lost > 0.04 }.sortedByDescending { it.lost }.take(4)) {
                val why = reasons(x.r, x.b, brakeIsCan, hasThrottle = hasThrottle)
                if (why.isEmpty()) continue // nothing concrete to say: don't show it
                insights += "${x.z.name}: lost %.2fs. ".format(x.lost) + why
                opps += Opportunity(x.z, x.lost, why + advice(x.r, x.b), sectorOf(x.z), x.r.coastS)
            }
            zd.filter { it.lost < -0.04 }.minByOrNull { it.lost }?.let { x ->
                val why = reasons(x.r, x.b, brakeIsCan, gain = true, hasThrottle = hasThrottle)
                insights += "${x.z.name}: gained %.2fs. ".format(-x.lost) + why
                gain = Opportunity(x.z, x.lost, why, sectorOf(x.z))
            }
            run {
                val c = runStats.sumOf { it?.coastS ?: 0.0 }
                val cr = refStats.sumOf { it?.coastS ?: 0.0 }
                if (c - cr > 0.3) insights += "Coasting %.1fs total vs %.1fs on the reference: $coastWhat.".format(c, cr)
            }
        } else {
            insights += "First run: it's the reference now. Insights below are from this run alone."
            zones.indices.mapNotNull { i -> runStats[i]?.let { zones[i] to it } }
                .filter { it.second.coastS > 0.3 }
                .sortedByDescending { it.second.coastS }
                .take(3)
                .forEach { (z, s) ->
                    insights += "${z.name}: %.1fs coasting between brake and throttle. Overlap them or brake later.".format(s.coastS)
                    opps += Opportunity(z, 0.0, "%.1f s coasting: $coastWhat. Overlap them or brake later.".format(s.coastS), sectorOf(z), s.coastS)
                }
        }

        val peakLat = runS.maxOf { abs(it.latG) }
        val peakDecel = runS.maxOf { -it.lonG }
        insights += "Peak lateral %.2fg, peak braking %.2fg, top speed %.0f mph.".format(peakLat, peakDecel, runS.maxOf { it.speed } * MPH)
        if (hasThrottle) {
            val full = runS.count { it.throttle > 90 }.toDouble() / runS.size * 100
            insights += "Full throttle %.0f%% of the run.".format(full)
        }

        // Each corner against the run that was quickest there (the section-by-section table)
        val runD = table?.dists?.get(run.number)
        if (table != null && runD != null && runD.size == runS.size) {
            val perCorner = ArrayList<Opportunity>()
            val inTable = runs.map { it.number }.filter { it in table.finished }
            for (sec in table.sections) {
                val si = sec.index
                // A corner is judged with the stretch after it: the quickest pair sets the target, so a
                // line that gives a little up here to leave faster is the one coached
                val nxt = table.sections.getOrNull(si + 1)?.takeIf { sec.name != "Launch" }
                fun pair(rn: Int): Double { val t = table.times[rn] ?: return Double.NaN; return t[si] + (nxt?.let { t[it.index] } ?: 0.0) }
                val b = if (nxt == null) table.bestRun[si]
                    else inTable.filter { pair(it).let { p -> !p.isNaN() && p > 0 } }.minByOrNull { pair(it) } ?: -1
                val lost = if (nxt == null) table.loss(run.number, si) else pair(run.number) - pair(b)
                if (b < 0 || b == run.number || lost.isNaN() || lost <= (if (nxt == null) 0.04 else 0.06)) continue
                val bRun = runs.firstOrNull { it.number == b } ?: continue
                val bD = table.dists[b] ?: continue
                val bS = samples.subList(bRun.startIdx, bRun.endIdx + 1)
                if (bD.size != bS.size) continue
                val inSec = runD.indices.filter { runD[it] >= sec.d0 && runD[it] <= sec.d1 }
                val apex = inSec.minByOrNull { runS[it].speed }
                val z = Zone(sec.name, sec.d0, apex?.let { runD[it] } ?: (sec.d0 + sec.d1) / 2, sec.d1)
                val text = (if (sec.name == "Launch") {
                    val mine = try { Coach.launch(samples, run)?.sixtyFtS } catch (e: Exception) { null }
                    val theirs = try { Coach.launch(samples, bRun)?.sixtyFtS } catch (e: Exception) { null }
                    if (mine != null && theirs != null) "60 ft %.2f s vs %.2f s on run %d. A cleaner launch: smooth clutch, no wheelspin.".format(mine, theirs, b)
                    else null
                } else {
                    val tm = table.times[run.number]; val tb = table.times[b]
                    val tHere = if (tm != null && tb != null) tm[si] - tb[si] else lost
                    val tAfter = nxt?.let { n -> if (tm != null && tb != null) tm[n.index] - tb[n.index] else null }
                    val line = try { LineCoach.advise(runS, runD, bS, bD, sec.name, sec.d0, sec.d1, tHere, tAfter, b) } catch (e: Exception) { null }
                    // How the brake was carried into the turn and how the car rotated: with the car's brake
                    // pressure where it's logged, from the RaceBox's slowing otherwise
                    val trail = if (LineCoach.zigzag(runS, inSec)) null else try {
                        val bApex = bD.indices.filter { bD[it] >= sec.d0 && bD[it] <= sec.d1 }.minByOrNull { bS[it].speed }
                        val bm = if (brakeIsCan) brakeMax else Double.NaN
                        val re = apex?.let { TrailCoach.entry(runS, runD, it, bm, gyroBias) }
                        val te = bApex?.let { TrailCoach.entry(bS, bD, it, bm, gyroBias) }
                        if (re != null && te != null) TrailCoach.advise(re, te, brakeIsCan) else null
                    } catch (e: Exception) { null }
                    if (trail != null) {
                        val worth = when {
                            tAfter != null && tHere < -0.02 && tAfter > 0.02 -> "You were %.2f s quicker here but lost %.2f s after it.".format(-tHere, tAfter)
                            tAfter != null -> "Worth %.2f s here and after it.".format(tHere + tAfter)
                            else -> "Worth %.2f s.".format(tHere)
                        }
                        // Line first (where to put the car), then the brake (how to get it there)
                        val lineCore = line?.substringBefore(" That line left at")?.substringBefore(" You were ")?.substringBefore(" Worth ")?.trim()
                        listOfNotNull(lineCore, trail, worth).joinToString(" ") + " (Run $b.)"
                    } else line ?: run {
                        // Same line: what the pedals and speeds did differently, the thing to do first
                        val r = stats(runS, runD, z); val bs = stats(bS, bD, z)
                        val why = if (r != null && bs != null) reasons(r, bs, brakeIsCan, hasThrottle = hasThrottle) else ""
                        if (why.isEmpty()) null else {
                            val todo = advice(r!!, bs!!).removePrefix(". ").removeSuffix(".")
                            (if (todo.isEmpty() || todo == ".") "$why." else "$todo. $why.") + " (Run $b.)"
                        }
                    }
                }) ?: continue // nothing concrete to say: don't show it
                perCorner += Opportunity(z, lost, text, sectorOf(z), stats(runS, runD, z)?.coastS ?: 0.0, vs = b, at = apex?.let { run.startIdx + it })
            }
            if (perCorner.isNotEmpty()) { opps.clear(); opps += perCorner.sortedByDescending { it.lost }.take(4) }
        }

        // Fill up to three with coasting, which costs time even where the corner wasn't a loss
        if (useRef && opps.size < 3) {
            zones.indices.mapNotNull { i -> runStats[i]?.let { zones[i] to it } }
                .filter { (z, st) -> st.coastS > 0.4 && opps.none { it.zone == z } }
                .sortedByDescending { it.second.coastS }
                .take(3 - opps.size)
                .forEach { (z, st) -> opps += Opportunity(z, 0.0, "%.1f s coasting: $coastWhat. Overlap them.".format(st.coastS), sectorOf(z), st.coastS) }
        }
        val launch = try {
            Coach.launch(samples, run)?.let { l ->
                // RaceBox only: the launch is judged against the other run's 60 ft
                if (!l.launchRpm.isNaN() || !useRef) return@let l
                val rs = Coach.launch(samples, refRun)?.sixtyFtS
                val ls = l.sixtyFtS
                val d = if (ls != null && rs != null) ls - rs else null
                when {
                    d == null -> l
                    d > 0.04 -> l.copy(tips = listOf("60 ft %.2f s slower than run %d (%.2f s). Smoother clutch or a little less wheelspin off the line.".format(d, refRun.number, rs)))
                    d < -0.04 -> l.copy(tips = listOf("60 ft %.2f s quicker than run %d (%.2f s).".format(-d, refRun.number, rs)))
                    else -> l.copy(tips = listOf("60 ft the same as run %d.".format(refRun.number)))
                }
            }
        } catch (e: Exception) { null }
        val slides = try { placeSlides(Coach.slides(samples, run, brakeMax, gyroBias), samples, run, table, runs, brakeMax) } catch (e: Exception) { emptyList() }
        val slips = try {
            Coach.wheelSlip(samples, run).map { ev ->
                val d = dist.getOrNull(ev.idx - run.startIdx) ?: 0.0
                val z = zones.firstOrNull { d >= it.d0 && d <= it.d1 }
                val where = when {
                    z == null -> if (zones.isNotEmpty() && d < zones.first().d0) "Launch" else "Straight"
                    d >= z.dMin -> "${z.name} exit"
                    else -> "Into ${z.name}"
                }
                where to ev
            }
        } catch (e: Exception) { emptyList() }
        slips.filter { it.first != "Launch" }.forEach { (w, ev) ->
            insights += if (ev.wheel != null) "$w: ${ev.wheel} spun %.1f s, up to %.0f%% faster than the fronts. Unwind the steering before full throttle.".format(ev.durS, ev.peakPct)
            else "$w: wheelspin %.1f s in gear %d, engine up to %.0f%% ahead of the road speed. Unwind the steering before full throttle.".format(ev.durS, ev.gear, ev.peakPct)
        }
        // Biggest losses chosen above, but shown in the order you drive them
        return Analysis(run, if (useRef) refRun else null, dist, delta, path.ds, zones, insights, brakeIsCan, opps.take(4).sortedBy { it.zone.dMin }, gain, sectorD, launch, slides, slips)
    }

    /**
     * Which section each slide was in, what it cost there against the quickest run through that
     * section, and what that run did differently. Small rotations (under 12 degrees) are kept only
     * where the section was slower than the best; big slides are always kept.
     */
    private fun placeSlides(found: List<Slide>, samples: List<Sample>, run: Run, table: SectionTable?, runs: List<Run>, maxBrake: Double): List<Slide> {
        val runD = table?.dists?.get(run.number)
        if (table == null || runD == null) return found.filter { it.big }
        fun thr(s: Sample) = if (s.throttle.isNaN()) 0.0 else s.throttle
        return found.mapNotNull { sl ->
            val k = sl.idx - run.startIdx
            val kp = sl.peakIdx - run.startIdx
            val d = runD.getOrNull(k) ?: return@mapNotNull if (sl.big) sl else null
            val sec = table.sections.firstOrNull { d >= it.d0 && d <= it.d1 } ?: table.sections.lastOrNull { d >= it.d0 }
                ?: return@mapNotNull if (sl.big) sl else null
            val b = table.bestRun[sec.index]
            val lost = table.loss(run.number, sec.index).takeIf { !it.isNaN() }
            val costly = lost != null && lost > 0.04 && b != run.number
            if (!sl.big && !costly) return@mapNotNull null
            val bRun = runs.firstOrNull { it.number == b && it.number != run.number }
            val bD = bRun?.let { table.dists[it.number] }
            var compare: String? = null
            var fix = sl.fix
            if (bRun != null && bD != null) {
                // The same stretch of course: from a second before the slide to its peak
                val k0 = (0..k).lastOrNull { samples[run.startIdx + k].t - samples[run.startIdx + it].t >= 1.0 } ?: 0
                val dA = runD[k0]; val dB = runD.getOrNull(kp) ?: d
                val mine = (k0..kp.coerceAtMost(runD.size - 1)).map { samples[run.startIdx + it] }
                val theirs = bD.indices.filter { bD[it] in dA..dB }.map { samples[bRun.startIdx + it] }
                val hasThr = mine.any { !it.throttle.isNaN() }
                // No clear input: if the quickest run was as fast or faster through the same stretch
                // without sliding, too much speed wasn't the cause, so don't say "carry less speed"
                if (sl.kind == Slide.Kind.UNCLEAR && theirs.size >= 3 && mine.isNotEmpty()) {
                    val vM = mine.map { it.speed }.average() * 2.23694; val vB = theirs.map { it.speed }.average() * 2.23694
                    if (vB >= vM - 0.5) {
                        compare = "Run ${bRun.number}, the quickest here, went through this stretch at %.0f mph against your %.0f mph without sliding.".format(vB, vM)
                        fix = "Not too much speed, then: likely the line or a sudden steering or pedal input. Watch the video for where the car turned in."
                    }
                }
                if (theirs.size >= 3 && hasThr) {
                    val offS = run { var t = 0.0; for (q in 1 until mine.size) if (thr(mine[q]) < 5 && (mine[q].brake.isNaN() || mine[q].brake < 0.05 * maxBrake)) t += mine[q].t - mine[q - 1].t; t }
                    val avgB = theirs.map(::thr).average(); val maxB = theirs.maxOf(::thr); val maxM = mine.maxOf(::thr)
                    val brB = theirs.maxOf { if (it.brake.isNaN()) 0.0 else it.brake / maxBrake * 100 }
                    val brM = mine.maxOf { if (it.brake.isNaN()) 0.0 else it.brake / maxBrake * 100 }
                    if (sl.kind != Slide.Kind.UNCLEAR) compare = when (sl.kind) {
                        Slide.Kind.LIFT -> if (avgB >= 10) "On run ${bRun.number}, the quickest here, you held about %.0f%% throttle through this stretch; on run ${run.number} you were off both pedals for %.1f s.".format(avgB, offS) else null
                        Slide.Kind.POWER -> if (maxB < maxM - 10) "On run ${bRun.number}, the quickest here, you used at most %.0f%% throttle through this stretch; on run ${run.number} you reached %.0f%%.".format(maxB, maxM) else null
                        Slide.Kind.BRAKE -> if (brB < brM - 10) "On run ${bRun.number}, the quickest here, the brake peaked at %.0f%% through this stretch; on run ${run.number} it reached %.0f%%.".format(brB, brM) else null
                        else -> null
                    }
                }
            }
            sl.copy(section = sec.name, lost = if (costly) lost else null, vs = if (costly) b else null, compare = compare, fix = fix)
        }
    }

    /** What to do differently, from the biggest difference. */
    private fun advice(r: ZoneStats, b: ZoneStats): String {
        if (r.brakeOnD != null && b.brakeOnD != null && b.brakeOnD - r.brakeOnD > 4) return ". Brake later and harder."
        if (r.throttleOnD != null && b.throttleOnD != null && r.throttleOnD - b.throttleOnD > 4) return ". Get back to throttle sooner."
        if (r.coastS - b.coastS > 0.2) return ". Overlap brake release and throttle."
        if ((b.minSpeed - r.minSpeed) * MPH >= 1.2) return if (r.oneTurn && b.oneTurn) ". Carry more speed to the apex." else ". Carry more speed through it."
        if (r.oneTurn && b.oneTurn && (b.apexD - r.apexD) * FT in 10.0..60.0) return ". Apex later: turn in later so you can straighten out and go to throttle sooner."
        if ((b.exitSpeed - r.exitSpeed) * MPH >= 1.5) return ". Open up the exit: unwind the steering sooner and get to full throttle earlier."
        if ((r.pathM - b.pathM) * FT >= 5) return ". Take a tighter line: compare the two lines on the map."
        if ((b.entrySpeed - r.entrySpeed) * MPH >= 1.5) return ". Carry more speed in: the previous corner's exit sets this one up."
        if (b.fullThrottleS - r.fullThrottleS >= 0.25) return ". More time at full throttle."
        if (b.peakDecelG - r.peakDecelG >= 0.1) return ". Brake harder, for less time."
        return "."
    }

    private fun reasons(r: ZoneStats, b: ZoneStats, can: Boolean, gain: Boolean = false, hasThrottle: Boolean = true): String {
        val power = if (hasThrottle) "throttle" else "picked up speed"
        val out = ArrayList<String>()
        val dv = (r.minSpeed - b.minSpeed) * MPH
        if (abs(dv) >= 1.2) out += "min speed %.0f vs %.0f mph".format(r.minSpeed * MPH, b.minSpeed * MPH)
        if (r.brakeOnD != null && b.brakeOnD != null) {
            val d = b.brakeOnD - r.brakeOnD
            if (d > 4 && !gain) out += "braked %.0f ft earlier".format(d * FT)
            if (d < -4 && gain) out += "braked %.0f ft later".format(-d * FT)
        }
        if (r.throttleOnD != null && b.throttleOnD != null) {
            val d = r.throttleOnD - b.throttleOnD
            if (d > 4 && !gain) out += "$power %.0f ft later".format(d * FT)
            if (d < -4 && gain) out += "$power %.0f ft sooner".format(-d * FT)
        }
        if (r.coastS - b.coastS > 0.2 && !gain) out += "%.1fs more coasting".format(r.coastS - b.coastS)
        // How hard the tires were working through the corner
        if (!gain && b.peakLatG - r.peakLatG > 0.06) out += "cornering %.2fg vs %.2fg".format(r.peakLatG, b.peakLatG)
        if (gain && r.peakLatG - b.peakLatG > 0.06) out += "cornering %.2fg vs %.2fg".format(r.peakLatG, b.peakLatG)
        if (!gain && b.peakBrake - r.peakBrake > (if (can) 12.0 else 0.12)) {
            out += if (can) "brake pressure peak %.0f%% vs %.0f%%".format(r.peakBrake, b.peakBrake)
            else "peak decel %.2fg vs %.2fg".format(r.peakBrake, b.peakBrake)
        }
        // The three biggest differences are enough to act on
        if (out.size >= 3) return out.take(3).joinToString(", ").replaceFirstChar { it.uppercase() }
        if (!gain) {
            // Line and speed shape, from GPS and the RaceBox: something concrete even without pedal data
            val dExit = (b.exitSpeed - r.exitSpeed) * MPH
            if (dExit >= 1.5) out += "exit speed %.0f vs %.0f mph".format(r.exitSpeed * MPH, b.exitSpeed * MPH)
            val dEntry = (b.entrySpeed - r.entrySpeed) * MPH
            if (dEntry >= 1.5) out += "entry speed %.0f vs %.0f mph".format(r.entrySpeed * MPH, b.entrySpeed * MPH)
            val dPath = (r.pathM - b.pathM) * FT
            if (dPath >= 5) out += "line %.0f ft longer".format(dPath)
            val dApex = (b.apexD - r.apexD) * FT
            // Only where the corner has one clear slowest point (a long sweeper or slalom doesn't)
            if (r.oneTurn && b.oneTurn && dApex in 10.0..60.0) out += "apex %.0f ft earlier".format(dApex)
            if (r.oneTurn && b.oneTurn && dApex in -60.0..-10.0) out += "apex %.0f ft later".format(-dApex)
            val dFull = b.fullThrottleS - r.fullThrottleS
            if (!dFull.isNaN() && dFull >= 0.25) out += "%.1f s less at full throttle".format(dFull)
            if (!can && b.peakDecelG - r.peakDecelG >= 0.1) out += "braking %.2fg vs %.2fg".format(r.peakDecelG, b.peakDecelG)
        }
        return out.take(3).joinToString(", ").replaceFirstChar { it.uppercase() }
    }

    private fun deltaAt(dist: DoubleArray, delta: DoubleArray, d: Double): Double? {
        var best = -1
        var bestErr = Double.MAX_VALUE
        for (k in dist.indices) {
            if (delta[k].isNaN()) continue
            val e = abs(dist[k] - d)
            if (e < bestErr) { bestErr = e; best = k }
        }
        return if (best >= 0 && bestErr < 15) delta[best] else null
    }

    /**
     * The course's corners along this run. With corners named on the course walk, each one becomes a
     * zone under its own name (same corners on every run, whichever run is the reference); without
     * them, speed valleys with at least ~4 mph of drop on each side, numbered in course order.
     */
    fun findZones(ss: List<Sample>, ds: DoubleArray, corners: List<CornerMark> = emptyList()): List<Zone> {
        if (ss.size < 10) return emptyList()
        if (corners.isNotEmpty()) namedZones(ss, ds, corners)?.let { return it }
        return speedZones(ss, ds)
    }

    /** Zones from walked corners: apex at the slowest point within 25 m of the mark, edges halfway to the neighbours. */
    private fun namedZones(ss: List<Sample>, ds: DoubleArray, corners: List<CornerMark>): List<Zone>? {
        val proj = LocalProjection(ss[0].lat, ss[0].lon)
        val px = DoubleArray(ss.size) { proj.x(ss[it].lon) }; val py = DoubleArray(ss.size) { proj.y(ss[it].lat) }
        fun gap(i: Int, c: CornerMark) = hypot(px[i] - proj.x(c.lon), py[i] - proj.y(c.lat))
        // In walk order, searching forward so a course that crosses itself still matches the right pass
        var from = 0
        val hits = ArrayList<Pair<Double, String>>()
        for (c in corners) {
            val fwd = (from until ss.size).minByOrNull { gap(it, c) }
            val any = ss.indices.minBy { gap(it, c) }
            val k = if (fwd != null && gap(fwd, c) < 40.0) fwd else any
            if (gap(k, c) > 60.0) continue // not on this run's path
            if (k >= from) from = k
            val near = ss.indices.filter { abs(ds[it] - ds[k]) <= 25.0 }
            val apex = near.minByOrNull { ss[it].speed } ?: k
            hits += ds[apex] to c.name
        }
        if (hits.isEmpty()) return null
        // Corners the driver didn't name still count: a speed dip well away from every named corner
        // keeps its own zone, called after the named corner before it
        val named = hits.sortedBy { it.first }
        for (z in speedZones(ss, ds)) {
            if (named.any { abs(it.first - z.dMin) < 40.0 }) continue
            val before = named.lastOrNull { it.first < z.dMin }?.second
            hits += z.dMin to (if (before != null) "After $before" else "Before ${named.first().second}")
        }
        val sorted = hits.sortedBy { it.first }
        // Several unnamed dips after the same corner: number them
        val dup = sorted.groupingBy { it.second }.eachCount()
        val seen = HashMap<String, Int>()
        val labelled = sorted.map { (d, n) -> if ((dup[n] ?: 0) > 1 && (n.startsWith("After ") || n.startsWith("Before "))) d to "$n (${seen.merge(n, 1, Int::plus)})" else d to n }
        val total = ds.last()
        return labelled.mapIndexed { i, (d, name) ->
            val d0 = if (i == 0) max(0.0, d - (if (sorted.size > 1) (sorted[1].first - d) / 2 else 40.0)) else (sorted[i - 1].first + d) / 2
            val d1 = if (i == sorted.size - 1) kotlin.math.min(total, d + (if (i > 0) (d - sorted[i - 1].first) / 2 else 40.0)) else (d + sorted[i + 1].first) / 2
            Zone(name, d0, d, d1)
        }
    }

    private fun speedZones(ss: List<Sample>, ds: DoubleArray): List<Zone> {
        val v = DoubleArray(ss.size) { i ->
            var sum = 0.0; var n = 0
            for (k in (i - 4).coerceAtLeast(0)..(i + 4).coerceAtMost(ss.size - 1)) { sum += ss[k].speed; n++ }
            sum / n
        }
        val h = 1.7
        // Alternating extrema with hysteresis
        val ext = ArrayList<Pair<Int, Boolean>>() // index, isMax
        var lookingForMin = true
        var cand = 0
        for (i in 1 until v.size) {
            if (lookingForMin) {
                if (v[i] < v[cand]) cand = i
                else if (v[i] > v[cand] + h) { ext += cand to false; lookingForMin = false; cand = i }
            } else {
                if (v[i] > v[cand]) cand = i
                else if (v[i] < v[cand] - h) { ext += cand to true; lookingForMin = true; cand = i }
            }
        }
        val zones = ArrayList<Zone>()
        var n = 1
        for ((k, e) in ext.withIndex()) {
            if (e.second || k == 0) continue // skip maxima and the launch
            val prevMax = ext.subList(0, k).lastOrNull { it.second }?.first ?: 0
            val nextMax = ext.subList(k + 1, ext.size).firstOrNull { it.second }?.first ?: (v.size - 1)
            zones += Zone("Corner ${n++}", ds[prevMax], ds[e.first], ds[nextMax])
        }
        return zones
    }

    fun pathLength(ss: List<Sample>): Double {
        var d = 0.0
        for (i in 1 until ss.size) d += hypot(ss[i].x - ss[i - 1].x, ss[i].y - ss[i - 1].y)
        return d
    }
}
