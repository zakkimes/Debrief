package com.kimes.daq.run

import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How the car left the start: from standing still to the first upshift. */
data class Launch(
    val launchRpm: Double,          // engine speed as the car started to roll
    val dipRpm: Double,             // lowest engine speed while the clutch came up
    val slipS: Double,              // time from rolling to the clutch fully engaged
    val wheelspinS: Double?,        // time the rear wheels ran >8% faster than the fronts (null without wheel speeds)
    val peakG: Double,              // best forward acceleration in the first 2 s, g
    val sixtyFtS: Double?,          // time to cover 60 ft from rolling
    val shiftRpm: Double?,          // engine speed at the 1-2 shift
    val shiftS: Double?,            // time off the throttle for the 1-2 shift
    val tips: List<String>,
)

/** A moment the rear stepped out: the car rotated more than its path turned. */
data class Slide(
    val t: Double,                  // session time the slide began
    val idx: Int,                   // sample index where it began
    val peakDeg: Double,            // most the car rotated beyond its path, degrees
    val spun: Boolean,
    val cause: String,
    val fix: String,
    val peakIdx: Int = idx,         // sample index at the most rotation
    val kind: Kind = Kind.UNCLEAR,
    val section: String? = null,    // where on the course (filled in by the Analyzer)
    val lost: Double? = null,       // seconds this run lost in that section to the best run there
    val vs: Int? = null,            // the best run through that section
    val compare: String? = null,    // what that run did differently here
) {
    enum class Kind { LIFT, POWER, BRAKE, STEER, UNCLEAR }
    /** 12 degrees and up is a slide; less is the rear moving. */
    val big get() = peakDeg >= 12.0
}

/** A stretch where the engine turned faster than the car's speed allows in its gear: wheelspin (or clutch slip). */
data class SlipEvent(
    val idx: Int,          // session sample index where it began
    val durS: Double,
    val peakPct: Double,   // most the engine ran ahead of the road speed, %
    val gear: Int,
    val wheel: String? = null, // measured from wheel speeds: which rear spun ("inside rear", "left rear", "both rears"); null = inferred from RPM
)

/**
 * Launch and spin coaching from logged channels. Launch needs RPM; slides need the yaw rate from
 * the RaceBox gyro. Steering angle and wheel speeds sharpen both when the car signal setup found them.
 * The tips are rules of thumb for a stock ND on street tires, not certainties.
 */
object Coach {
    private const val MPH = 2.236936

    fun launch(samples: List<Sample>, run: Run): Launch? {
        // The last standstill before the run started (cars launch a few lengths behind the start line)
        var i = run.startIdx
        val earliest = samples.indexOfFirst { it.t >= run.startT - 25 }.coerceAtLeast(0)
        while (i > earliest && samples[i].speed > 0.3) i--
        if (samples[i].speed > 0.3) return null
        while (i < run.endIdx && samples[i + 1].speed <= 0.3) i++
        val move = (i until min(run.endIdx, i + 400)).firstOrNull { samples[it].speed > 0.45 } ?: return null
        if (samples[move].rpm.isNaN()) return gpsLaunch(samples, move)
        val t0 = samples[move].t

        // First upshift = the first throttle lift once the car is moving properly. The car's gear
        // reading arrives up to ~1.5 s late, so "gear == 2" marks a point well after the shift,
        // when the revs have already dropped; it's only the fallback when there's no throttle signal.
        val hasThr = (move until min(samples.size, move + 100)).any { !samples[it].throttle.isNaN() }
        // A lift to catch wheelspin isn't a shift: in a real upshift the revs fall while road speed
        // holds, so check ~0.6 s later (when the clutch is still locking up, speed climbs instead).
        fun isUpshift(k: Int): Boolean {
            val j = (k until min(samples.size, k + 40)).firstOrNull { samples[it].t - samples[k].t >= 0.6 } ?: return false
            val peak = (max(move, k - 5)..min(samples.size - 1, k + 5)).maxOf { samples[it].rpm.takeIf { r -> !r.isNaN() } ?: 0.0 }
            val r1 = samples[j].rpm
            return !r1.isNaN() && peak > 0 && r1 < peak * 0.85 && samples[j].speed < samples[k].speed * 1.12
        }
        val shiftIdx = (move until min(samples.size - 1, move + 300)).firstOrNull { k ->
            val s = samples[k]
            s.speed > 3 && s.t - t0 > 0.8 && if (hasThr) (!s.throttle.isNaN() && s.throttle < 10 && (s.rpm.isNaN() || s.rpm > 3500) && isUpshift(k)) else s.gear == 2
        }
        val end = shiftIdx ?: min(samples.size - 1, move + 100)

        // Engine RPM per m/s with the clutch fully up in 1st, learned from this launch
        val ratios = (move..end).map { samples[it] }.filter { it.speed > 2.5 && !it.rpm.isNaN() && (it.gear == 1 || it.gear < 0) }.map { it.rpm / it.speed }.sorted()
        val k1 = if (ratios.size >= 4) ratios[ratios.size / 10] else Double.NaN
        val lockIdx = if (k1.isNaN()) null else (move..end).firstOrNull { samples[it].speed > 1.0 && samples[it].rpm <= k1 * samples[it].speed * 1.06 }
        val slipS = lockIdx?.let { samples[it].t - t0 } ?: (samples[end].t - t0)
        // Peak revs while still standing (up to 3 s): by the time the car is seen moving the
        // clutch has already pulled them down, and RPM only updates about 5 times a second.
        val launchRpm = (max(0, move - 75)..move).maxOf { samples[it].rpm.takeIf { r -> !r.isNaN() } ?: 0.0 }
        val dip = (move..(lockIdx ?: end)).minOf { samples[it].rpm.takeIf { r -> !r.isNaN() } ?: Double.MAX_VALUE }.let { if (it == Double.MAX_VALUE) launchRpm else it }

        val spin = if ((move..end).any { !samples[it].wRL.isNaN() && !samples[it].wFL.isNaN() }) {
            var s = 0.0
            for (k in move + 1..end) {
                val a = samples[k]
                val front = (a.wFL + a.wFR) / 2; val rear = (a.wRL + a.wRR) / 2
                // Wheel speeds come in 1 km/h steps: ask for two steps beyond 8% so rounding can't trigger it
                if (!front.isNaN() && !rear.isNaN() && rear > front * 1.08 + 0.6) s += a.t - samples[k - 1].t
            }
            s
        } else null

        val peakG = (move..min(samples.size - 1, move + 50)).maxOf { samples[it].lonG }
        var dist = 0.0
        var sixty: Double? = null
        for (k in move + 1 until min(samples.size, move + 250)) {
            val a = samples[k - 1]; val b = samples[k]
            dist += (a.speed + b.speed) / 2 * (b.t - a.t)
            if (dist >= 18.288) { sixty = b.t - t0; break }
        }
        var shiftRpm: Double? = null; var shiftS: Double? = null
        shiftIdx?.let { si ->
            // Peak of the whole first-gear pull; a few samples past the lift for RPM's reporting lag
            shiftRpm = (move..min(samples.size - 1, si + 5)).maxOf { samples[it].rpm.takeIf { r -> !r.isNaN() } ?: 0.0 }
            val off = (max(move, si - 6)..min(samples.size - 1, si + 50)).firstOrNull { samples[it].throttle < 10 }
            val on = off?.let { o -> (o..min(samples.size - 1, o + 75)).firstOrNull { samples[it].throttle >= 10 } }
            if (off != null && on != null) shiftS = samples[on].t - samples[off].t
        }

        val tips = ArrayList<String>()
        if (dip < 1700 && slipS > 0.3) tips += "Bogged: the engine fell to %,.0f rpm as the clutch came up. Hold about 3,000–3,500 rpm before you release.".format(dip)
        else if (launchRpm < 2500) tips += "Launched at %,.0f rpm; try about 3,000–3,500 for more drive off the line.".format(launchRpm)
        if (spin != null && spin > 0.3) tips += "Rear wheels spun for %.1f s: launch a little lower or feed the throttle in.".format(spin)
        if (slipS > 1.2 && spin == null) tips += "The engine ran ahead of the car for %.1f s: clutch slip or wheelspin. Launch a little lower, or let the clutch out fully once rolling.".format(slipS)
        else if (slipS > 1.2) tips += "Clutch slipped for %.1f s. Once the car is rolling, let it out fully; aim for under 1 s.".format(slipS)
        else if (slipS < 0.25 && dip < launchRpm - 1200) tips += "Clutch came up very fast (%.2f s): ease it out over about half a second.".format(slipS)
        shiftS?.let { if (it > 0.6) tips += "The 1-2 shift took %.1f s off the throttle; quick shifts are about 0.4 s.".format(it) }
        shiftRpm?.let { if (it < 4500) tips += "Shifted to 2nd at %,.0f rpm. The engine pulls hardest above 5,000: hold 1st longer when the first element is far enough away.".format(it) }
        if (tips.isEmpty()) tips += "Clean launch."
        return Launch(launchRpm, dip, slipS, spin, peakG, sixty, shiftRpm, shiftS, tips)
    }

    /** 60 ft and pull from the RaceBox alone, for cars with no engine data (RPM fields are NaN). */
    private fun gpsLaunch(samples: List<Sample>, move: Int): Launch {
        val t0 = samples[move].t
        val peakG = (move..min(samples.size - 1, move + 50)).maxOf { samples[it].lonG }
        var dist = 0.0
        var sixty: Double? = null
        for (k in move + 1 until min(samples.size, move + 250)) {
            val a = samples[k - 1]; val b = samples[k]
            dist += (a.speed + b.speed) / 2 * (b.t - a.t)
            if (dist >= 18.288) { sixty = b.t - t0; break }
        }
        return Launch(Double.NaN, Double.NaN, Double.NaN, null, peakG, sixty, null, null, emptyList())
    }

    /**
     * Engine speed per m/s for each gear with the tires gripping, learned from the whole session:
     * the median RPM/speed while that gear has been reported steadily. Wheelspin is rare enough not
     * to move the median, and RPM's reporting lag (low while speeding up, high while slowing) cancels.
     */
    fun gearRatios(samples: List<Sample>): Map<Int, Double> {
        val by = HashMap<Int, ArrayList<Double>>()
        for (k in 40 until samples.size - 40) {
            val s = samples[k]
            if (s.gear < 1 || s.speed < 5 || s.rpm.isNaN() || s.rpm < 1500) continue
            if (samples[k - 40].gear != s.gear || samples[k + 40].gear != s.gear) continue // steady for ±1.6 s
            by.getOrPut(s.gear) { ArrayList() } += s.rpm / s.speed
        }
        return by.filterValues { it.size >= 50 }.mapValues { (_, v) -> v.sorted()[v.size / 2] }
    }

    /**
     * Wheelspin on power: the engine more than 12% ahead of the road speed in a steadily reported
     * gear, on throttle, for at least 0.3 s. Without wheel speeds this can't tell spin from clutch
     * slip, so shift moments (gear not steady) are skipped.
     */
    fun wheelSlip(samples: List<Sample>, run: Run, ratios: Map<Int, Double> = gearRatios(samples)): List<SlipEvent> {
        if (hasWheels(samples, run)) return measuredSlip(samples, run)
        if (ratios.isEmpty()) return emptyList()
        val out = ArrayList<SlipEvent>()
        var start = -1; var peak = 0.0; var gear = 0
        fun close(k: Int) {
            if (start >= 0 && samples[k].t - samples[start].t >= 0.3) out += SlipEvent(start, samples[k].t - samples[start].t, peak * 100, gear)
            start = -1; peak = 0.0
        }
        for (k in run.startIdx..run.endIdx) {
            val s = samples[k]
            val r = ratios[s.gear]
            val steady = k >= 25 && k + 25 < samples.size && samples[k - 25].gear == s.gear && samples[k + 25].gear == s.gear
            val slip = if (r != null && steady && s.speed > 4 && !s.rpm.isNaN() && (s.throttle.isNaN() || s.throttle > 30)) s.rpm / (r * s.speed) - 1 else Double.NaN
            if (!slip.isNaN() && slip > 0.12) {
                if (start < 0) { start = k; gear = s.gear }
                peak = max(peak, slip)
            } else close(k)
        }
        close(run.endIdx)
        return out
    }

    /** The car's wheel speeds were logged for most of this run. */
    fun hasWheels(samples: List<Sample>, run: Run): Boolean {
        val n = run.endIdx - run.startIdx + 1
        return n > 0 && (run.startIdx..run.endIdx).count { k -> val s = samples[k]; !s.wRL.isNaN() && !s.wRR.isNaN() && !s.wFL.isNaN() && !s.wFR.isNaN() } > n / 2
    }

    /**
     * Wheelspin measured directly: a driven (rear) wheel more than 10% faster than the front axle
     * for at least 0.3 s, above 6 m/s (wheel speeds come in 1 km/h steps, so slower is too coarse).
     * With an open differential the inside rear usually goes first; the event says which one.
     */
    fun measuredSlip(samples: List<Sample>, run: Run): List<SlipEvent> {
        val out = ArrayList<SlipEvent>()
        var start = -1; var peak = 0.0; var gear = 0; var lSum = 0.0; var rSum = 0.0; var both = 0; var n = 0
        fun close(k: Int) {
            if (start >= 0 && samples[k].t - samples[start].t >= 0.3) {
                val l = lSum / n; val r = rSum / n
                val side = when {
                    both * 2 > n -> "both rears"
                    // Inside rear: the one on the inside of the turn (yaw + = turning left)
                    else -> {
                        val left = l > r
                        val yaw = (start..k).mapNotNull { samples[it].yaw.takeIf { y -> !y.isNaN() } }.average()
                        val turning = if (yaw.isNaN() || kotlin.math.abs(yaw) < 3) null else yaw > 0
                        (if (left) "left rear" else "right rear") + if (turning != null && turning == left) " (inside)" else ""
                    }
                }
                out += SlipEvent(start, samples[k].t - samples[start].t, peak * 100, gear, side)
            }
            start = -1; peak = 0.0; lSum = 0.0; rSum = 0.0; both = 0; n = 0
        }
        for (k in run.startIdx..run.endIdx) {
            val s = samples[k]
            val front = (s.wFL + s.wFR) / 2
            val ok = !front.isNaN() && !s.wRL.isNaN() && !s.wRR.isNaN() && front > 6 && (s.throttle.isNaN() || s.throttle > 20)
            val sl = if (ok) (s.wRL - front) / front else Double.NaN
            val sr = if (ok) (s.wRR - front) / front else Double.NaN
            val slip = if (ok) max(sl, sr) else Double.NaN
            if (!slip.isNaN() && slip > 0.10) {
                if (start < 0) { start = k; gear = s.gear }
                peak = max(peak, slip); lSum += sl; rSum += sr; n++
                if (min(sl, sr) > 0.10) both++
            } else close(k)
        }
        close(run.endIdx)
        return out
    }

    /**
     * The RaceBox gyro's zero offset: what it reads with the car stopped (median, deg/s). On 2026-11-14
     * and -15 it read -1.08 deg/s parked, all session, which over a 48 s run adds ~50 degrees of
     * rotation that never happened and hid slides. 0 when the car never sat still long enough.
     */
    fun gyroBias(samples: List<Sample>): Double {
        val still = samples.filter { it.speed < 0.2 && !it.yaw.isNaN() }.map { it.yaw }
        if (still.size < 50) return 0.0
        return still.sorted()[still.size / 2].coerceIn(-5.0, 5.0)
    }

    /**
     * Slides: the car's rotation (gyro, less its zero offset [bias]) beyond the turn of its path (GPS course),
     * kept in a leaky sum (~1.5 s memory). A slide is that rotation building by 8 degrees or more
     * within 0.8 s; slow drift through a long corner is ignored. 60 degrees, or the
     * car nearly stopping, is a spin. The cause is read from what the driver did in the second before.
     */
    fun slides(samples: List<Sample>, run: Run, maxBrake: Double, bias: Double = 0.0): List<Slide> {
        val s = samples.subList(run.startIdx, run.endIdx + 1)
        if (s.count { !it.yaw.isNaN() } < s.size / 2) return emptyList()
        val n = s.size
        val ex = DoubleArray(n)
        for (k in 1 until n) {
            val a = s[k - 1]; val b = s[k]
            val dt = (b.t - a.t).coerceIn(0.0, 0.2)
            var dc = b.heading - a.heading
            if (dc > 180) dc -= 360
            if (dc < -180) dc += 360
            val pathLeft = if (b.speed > 2.5) -dc else 0.0
            val yawDeg = if (b.yaw.isNaN()) 0.0 else (b.yaw - bias) * dt
            ex[k] = ex[k - 1] * (1 - dt / 1.5) + (yawDeg - pathLeft)
        }
        val out = ArrayList<Slide>()
        var j = 0
        var k = 1
        while (k < n) {
            while (j < k && s[k].t - s[j].t > 0.8) j++
            val rise = ex[k] - ex[j]
            if (abs(rise) < 8 || s[k].speed < 4) { k++; continue }
            val sg = if (rise > 0) 1.0 else -1.0
            // Peak: keep going while the rotation still builds (allowing a little noise), up to 2 s
            var p = k
            while (p + 1 < n && s[p + 1].t - s[k].t < 2.0 && sg * (ex[p + 1] - ex[p]) > -0.3) p++
            while (p > k && sg * (ex[p] - ex[p - 1]) <= 0) p--
            // Onset: back from the trigger to where it started building (up to 2.3 s)
            var o = k
            while (o > 0 && s[k].t - s[o - 1].t < 2.3 && sg * (ex[o] - ex[o - 1]) > 0.05) o--
            val peak = sg * (ex[p] - ex[o])
            val minSpeed = (p until min(n, p + 25)).minOf { s[it].speed }
            val spun = peak > 60 || minSpeed < 2.0
            val c = cause(s, o, p, maxBrake)
            if (peak >= 8) out += Slide(s[o].t, run.startIdx + o, peak, spun, c.first, c.second, run.startIdx + p, c.third)
            k = p + 1
            while (k < n && s[k].t - s[p].t < 0.5) k++
            j = k - 1
        }
        return out
    }

    private fun cause(s: List<Sample>, o: Int, p: Int, maxBrake: Double): Triple<String, String, Slide.Kind> {
        val before = (max(0, o - 25)..o).map { s[it] } // the second before
        val during = (o..p).map { s[it] }
        val at = s[o]
        val hasThr = before.any { !it.throttle.isNaN() }
        val hasBrake = before.any { !it.brake.isNaN() }
        fun thr(x: Sample) = if (x.throttle.isNaN()) 0.0 else x.throttle
        val thrMax = before.maxOf(::thr)
        val lift = thrMax - thr(at)
        val brakeFrac = if (at.brake.isNaN() || maxBrake <= 0) 0.0 else at.brake / maxBrake
        val cornering = abs(at.latG) > 0.3 || abs(at.yaw) > 10
        // Off both pedals for most of the second before: coasting through the turn
        val coasting = hasThr && before.count { thr(it) < 5 && (it.brake.isNaN() || it.brake < 0.05 * maxBrake) } > before.size * 0.8
        val steerRate = before.zipWithNext().mapNotNull { (a, b) -> if (a.steer.isNaN() || b.steer.isNaN() || b.t <= a.t) null else abs(b.steer - a.steer) / (b.t - a.t) }.maxOrNull()
        // A rear wheel well ahead of the fronts with the throttle on (wheel speeds update a few times a second)
        val rearSpin = (before.takeLast(8) + during).any { x ->
            val front = (x.wFL + x.wFR) / 2
            val rear = max(x.wRL.takeIf { !it.isNaN() } ?: 0.0, x.wRR.takeIf { !it.isNaN() } ?: 0.0)
            !front.isNaN() && front > 3 && rear > front * 1.15 + 0.5 && (!hasThr || thr(x) > 20)
        }
        val gpsLonG = before.takeLast(12).map { it.lonG }.average()
        return when {
            rearSpin || (thr(at) > 50 && cornering) -> Triple("Too much throttle while turning", "Power overcame the rear tires. Unwind the steering before adding throttle, and squeeze it on progressively.", Slide.Kind.POWER)
            brakeFrac > 0.2 && cornering -> Triple("Braking while turning", "Too much brake with the wheel turned unloads the rear. Finish more of the braking in a straight line, and ease off the brake as you add steering.", Slide.Kind.BRAKE)
            lift > 25 && cornering -> Triple("Lifted off the throttle mid-corner", "Lifting shifts weight forward and the rear lets go. Stay steady on the throttle through the corner; if you're too fast, ease off gently.", Slide.Kind.LIFT)
            coasting && cornering -> Triple("Off the throttle while turning", "Coasting through the turn puts the weight on the front and the rear goes light. Keep a little throttle on to settle the rear; if you're carrying too much speed, brake briefly in a straight line before the turn instead of coasting.", Slide.Kind.LIFT)
            // Off the brake (or nearly) by the time it let go, but still on it while cornering hard in the second before
            hasBrake && maxBrake > 0 && cornering && before.count { !it.brake.isNaN() && it.brake > 0.1 * maxBrake && abs(it.latG) > 0.7 } >= 5 ->
                Triple("Brake held into the turn", "Still on the brake while cornering hard rotated the car past what the corner needed. Ease off the brake progressively as you turn in, so you're off it by the time you're cornering hard.", Slide.Kind.BRAKE)
            steerRate != null && steerRate > 400 -> Triple("Sudden steering input", "A quick flick of the wheel snapped the rear around. Turn in smoothly, especially in transitions like slaloms.", Slide.Kind.STEER)
            !hasThr && !hasBrake && gpsLonG < -0.15 && cornering -> Triple("Slowing while turning", "The car was slowing as it turned, which unloads the rear. Do the slowing in a straight line before the turn, then keep a little throttle on through it.", Slide.Kind.LIFT)
            !hasThr && !hasBrake && gpsLonG > 0.12 && cornering -> Triple("Speeding up while turning", "The car was accelerating as it turned and the rear let go: likely too much throttle with the wheel turned. Unwind the steering before full throttle.", Slide.Kind.POWER)
            else -> Triple("The rear let go without one clear input", "Probably just past the grip limit. Carry slightly less speed into this corner and check the video.", Slide.Kind.UNCLEAR)
        }
    }
}
