package com.kimes.daq.run

import com.kimes.daq.data.Sample
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Trail braking and rotation into a corner, in the driver's terms: how much brake was still on as the
 * car turned in, where it came off before the slowest point, and how much the car rotated (pointed in
 * further than its path, from the RaceBox gyro against the GPS course) on the way to the slowest point.
 *
 * With the car's brake pressure (OBD) the brake is a percentage of the session's hardest stop; without
 * it (RaceBox only) it's how hard the car was still slowing, from the GPS speed, in g.
 */
object TrailCoach {
    private const val FT = 3.28084
    private const val MPH = 2.236936
    /** Cornering (g, smoothed) at or above this is "turned in". */
    private const val TURNED_G = 0.35
    /** A corner needs at least this much braking (g) on the way in to coach trail braking at all. */
    const val BRAKING_G = 0.35

    class Entry(
        val brakeAtTurnIn: Double,  // % of max pressure (CAN) or g of slowing (RaceBox only)
        val releaseFt: Double,      // ft before the slowest point the brake came off (0 = held to it)
        val rotationDeg: Double,    // the car pointed in beyond its path by the slowest point, degrees (NaN without a gyro)
        val minSpeed: Double,       // m/s
        val apexIdx: Int,
        val turnInIdx: Int,
    )

    /**
     * The entry to the corner whose slowest point is [apex] (index into [ss], with base-path distances
     * [ds]), or null when there was no real braking for it. [brakeMax] is the session's peak brake
     * pressure, or NaN when the car's brake isn't logged. [bias] is the gyro's zero offset.
     */
    fun entry(ss: List<Sample>, ds: DoubleArray, apex: Int, brakeMax: Double, bias: Double): Entry? {
        if (apex < 5 || apex >= ss.size) return null
        val can = !brakeMax.isNaN() && brakeMax > 0
        fun sm(i: Int, f: (Sample) -> Double): Double {
            var sum = 0.0; var n = 0
            for (k in maxOf(0, i - 3)..minOf(ss.size - 1, i + 3)) { val v = f(ss[k]); if (!v.isNaN()) { sum += v; n++ } }
            return if (n == 0) Double.NaN else sum / n
        }
        fun decel(i: Int) = -sm(i) { it.lonG }
        fun brake(i: Int) = if (can) (if (ss[i].brake.isNaN()) 0.0 else ss[i].brake / brakeMax * 100) else decel(i).coerceAtLeast(0.0)
        // The 4 s before the slowest point
        var i0 = apex; while (i0 > 0 && ss[apex].t - ss[i0 - 1].t <= 4.0) i0--
        val pk = (i0..apex).maxByOrNull { decel(it) } ?: return null
        if (decel(pk) < BRAKING_G) return null
        // Which way it turns: the cornering at the slowest point
        val dir = if (sm(apex) { it.latG } >= 0) 1.0 else -1.0
        if (abs(sm(apex) { it.latG }) < TURNED_G * 0.7) return null
        // Turn-in: back from the slowest point while it's cornering (in a curving braking zone, that can
        // be before the hardest braking)
        var ti = apex; while (ti > i0 && dir * sm(ti - 1) { it.latG } >= TURNED_G) ti--
        // Off the brake: the last sample with it on (CAN: over 5%; RaceBox: still slowing at 0.2 g, as
        // the tires scrub off 0.1 g or so just from cornering)
        val on = (pk..apex).lastOrNull { if (can) brake(it) > 5 else decel(it) > 0.2 } ?: pk
        val releaseFt = ((ds[apex] - ds[on]) * FT).coerceAtLeast(0.0)
        // Rotation: the car's heading (gyro) turning beyond its direction of travel (GPS course), from
        // the peak braking to the slowest point
        val rot = if (ss.subList(pk, apex + 1).count { !it.yaw.isNaN() } < (apex - pk + 1) / 2) Double.NaN else {
            var beta = 0.0; var peak = 0.0
            for (k in pk + 1..apex) {
                val a = ss[k - 1]; val b = ss[k]
                val dt = (b.t - a.t).coerceIn(0.0, 0.2)
                var dc = b.heading - a.heading; if (dc > 180) dc -= 360; if (dc < -180) dc += 360
                if (b.speed < 2.5 || b.yaw.isNaN()) continue
                beta += dir * ((b.yaw - bias) * dt + dc)
                peak = maxOf(peak, beta)
            }
            peak
        }
        return Entry(brake(ti), releaseFt, rot, ss[apex].speed, apex, ti)
    }

    /**
     * What to change in how the run braked into the corner to brake like the target did, or null when
     * they were alike (or the corner has no braking). [can] says the brake figures are pressure (%).
     */
    fun advise(r: Entry, t: Entry, can: Boolean): String? {
        val dB = t.brakeAtTurnIn - r.brakeAtTurnIn
        val bigB = if (can) 15.0 else 0.12
        val dRel = r.releaseFt - t.releaseFt  // + = the target held it closer to the slowest point
        val dRot = if (r.rotationDeg.isNaN() || t.rotationDeg.isNaN()) Double.NaN else t.rotationDeg - r.rotationDeg
        fun amt(v: Double) = if (can) "%.0f%%".format(v) else "%.2f g".format(v)
        fun ft(v: Double) = ((v / 5).roundToInt() * 5).coerceAtLeast(5)
        val slower = t.minSpeed - r.minSpeed  // + = the target was quicker at the slowest point
        val sb = StringBuilder()
        when {
            // This run's car rotated a lot more into the turn with as much brake on or more, and was
            // slower for it: too much brake (or held too long) with the wheel turned
            !dRot.isNaN() && dRot <= -4.0 && dB <= 0 && slower > 0 -> {
                sb.append("Come off the brake sooner as you turn in")
                if (r.releaseFt < t.releaseFt - 10) sb.append(": off it ${ft(t.releaseFt)} ft before the apex, you held it to ${ft(r.releaseFt)} ft")
                else if (-dB >= bigB / 2) sb.append(": about ${amt(t.brakeAtTurnIn)} as you turn in, not ${amt(r.brakeAtTurnIn)}")
                sb.append(". The car rotated %.0f° more than it needed, which scrubs speed. ".format(-dRot))
            }
            // The target carried more brake into the turn, it rotated the car (where there's a gyro to
            // tell) and the slowest point was no slower
            dB >= bigB && dRel >= 10 && (dRot.isNaN() || dRot >= 1.0) && slower > -0.1 -> {
                sb.append(if (can) "Trail the brake in: about ${amt(t.brakeAtTurnIn)} as you turn in, easing off " else "Trail the brake in: keep slowing gently as you turn in (${amt(t.brakeAtTurnIn)}), easing off ")
                sb.append(if (t.releaseFt < 8) "right to the apex. " else "${ft(t.releaseFt)} ft before the apex. ")
                sb.append("You had ${amt(r.brakeAtTurnIn)} there and were off it ${ft(r.releaseFt)} ft before. ")
                if (!dRot.isNaN() && dRot >= 1.5) sb.append("That rotated the car %.0f° more toward the exit. ".format(dRot))
            }
            else -> return null
        }
        val dv = (t.minSpeed - r.minSpeed) * MPH
        if (abs(dv) >= 1.0) sb.append("Apex speed %.0f mph vs your %.0f. ".format(t.minSpeed * MPH, r.minSpeed * MPH))
        return sb.toString().trim()
    }
}
