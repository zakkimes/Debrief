package com.kimes.daq.run

import com.kimes.daq.data.Sample
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Where to put the car through a corner, in the driver's terms: feet left or right at turn-in, apex
 * and track-out, and turning in or apexing earlier or later. Compared with the run that was quickest
 * through the corner and the stretch after it together, so a line that gives a little up in the
 * corner to leave it faster (an early or late apex that sets up what comes next) is the one coached.
 */
object LineCoach {
    private const val FT = 3.28084
    private const val MPH = 2.236936
    /** Sideways differences under this are within the GPS's run-to-run wander: not coached. */
    const val MIN_SIDE_FT = 2.5
    /** Turn-in, apex or track-out moved by less than this along the course: not coached. */
    const val MIN_ALONG_FT = 10.0
    /** Cornering above this (g, smoothed) is "in the corner": below it before the apex is turn-in, after it track-out. */
    private const val TURNING_G = 0.35
    private const val WINDOW_M = 3.0

    /**
     * Signed sideways distance (m, + = left of travel) of each of [aS]'s samples from [bS]'s line,
     * matched by distance along the shared base path ([aD], [bD], both non-decreasing). NaN where
     * nothing matched.
     */
    fun offsets(aS: List<Sample>, aD: DoubleArray, bS: List<Sample>, bD: DoubleArray): DoubleArray {
        val out = DoubleArray(aS.size) { Double.NaN }
        var j0 = 0
        for (k in aS.indices) {
            val d = aD[k]
            while (j0 < bD.size - 2 && bD[j0 + 1] < d - 15) j0++
            var best = Double.MAX_VALUE; var sign = 1.0
            var j = j0
            while (j < bS.size - 1 && bD[j] <= d + 15) {
                val p = bS[j]; val q = bS[j + 1]
                val dx = q.x - p.x; val dy = q.y - p.y
                val len2 = dx * dx + dy * dy
                if (len2 > 1e-6) {
                    val u = (((aS[k].x - p.x) * dx + (aS[k].y - p.y) * dy) / len2).coerceIn(0.0, 1.0)
                    val g = hypot(aS[k].x - (p.x + u * dx), aS[k].y - (p.y + u * dy))
                    if (g < best) { best = g; sign = if (dx * (aS[k].y - p.y) - dy * (aS[k].x - p.x) >= 0) 1.0 else -1.0 }
                }
                j++
            }
            if (best < 15.0) out[k] = sign * best
        }
        return out
    }

    private fun median(v: List<Double>): Double = v.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }

    /** Where a run turned in, apexed (slowest) and tracked out in a section, as base-path distances. */
    class Shape(val turnIn: Double, val apex: Double, val trackOut: Double, val apexIdx: Int, val minSpeed: Double, val exitSpeed: Double)

    /**
     * The car turned one way, then the other, then back again (each for over 0.6 s) over samples [idx]:
     * a slalom, which has no single turn-in or apex to coach. A section often starts at the end of the
     * turn before it, so two turns isn't enough to tell.
     */
    fun zigzag(ss: List<Sample>, idx: List<Int>): Boolean {
        var turns = 0; var cur = 0; var t0 = 0.0
        fun close(t: Double) { if (cur != 0 && t - t0 >= 0.6) turns++ }
        for (i in idx) {
            val g = (maxOf(0, i - 3)..minOf(ss.size - 1, i + 3)).map { ss[it].latG }.filter { !it.isNaN() }.average()
            val d = if (g >= TURNING_G) 1 else if (g <= -TURNING_G) -1 else 0
            if (d != cur) { close(ss[i].t); cur = d; t0 = ss[i].t }
        }
        idx.lastOrNull()?.let { close(ss[it].t) }
        return turns >= 3
    }

    fun shape(ss: List<Sample>, ds: DoubleArray, d0: Double, d1: Double): Shape? {
        val idx = ds.indices.filter { ds[it] >= d0 && ds[it] <= d1 }
        if (idx.size < 5) return null
        val g = idx.map { i -> (maxOf(0, i - 3)..minOf(ss.size - 1, i + 3)).map { abs(ss[it].latG) }.filter { !it.isNaN() }.average() }
        val a = idx.indices.minBy { ss[idx[it]].speed }
        var ti = a; while (ti > 0 && g[ti - 1] >= TURNING_G) ti--
        var to = a; while (to < idx.size - 1 && g[to + 1] >= TURNING_G) to++
        return Shape(ds[idx[ti]], ds[idx[a]], ds[idx[to]], idx[a], ss[idx[a]].speed, ss[idx.last()].speed)
    }

    /**
     * What to change through [name] (base-path [d0]..[d1]) to drive it like the target run, or null when
     * the lines are the same within what GPS can tell. [tHere] and [tAfter] are the seconds the run lost
     * to the target in this section and the next; [target] is the target run's number.
     */
    fun advise(
        runS: List<Sample>, runD: DoubleArray, tgtS: List<Sample>, tgtD: DoubleArray,
        name: String, d0: Double, d1: Double, tHere: Double, tAfter: Double?, target: Int,
    ): String? {
        // A slalom or chicane has no one turn-in or apex to move
        if (zigzag(runS, runD.indices.filter { runD[it] in d0..d1 }) || zigzag(tgtS, tgtD.indices.filter { tgtD[it] in d0..d1 })) return null
        val r = shape(runS, runD, d0, d1) ?: return null
        val t = shape(tgtS, tgtD, d0, d1) ?: return null
        val off = offsets(runS, runD, tgtS, tgtD)
        // GPS wanders a foot or two between runs: take the run's typical offset over the whole course as that
        val bias = median(off.toList()).let { if (it.isNaN()) 0.0 else it }
        fun sideAt(d: Double): Double? = median(runD.indices.filter { abs(runD[it] - d) <= WINDOW_M }.map { off[it] - bias }).takeIf { !it.isNaN() }
        /** "3 ft further right" to move from where the run was to the target's line, or null if too small to trust. */
        fun say(o: Double): String? {
            val ft = (abs(o) * FT).roundToInt()
            if (ft < MIN_SIDE_FT) return null
            return "$ft ft further " + if (o > 0) "right" else "left"
        }
        fun move(d: Double): String? = sideAt(d)?.let { say(it) }
        fun along(mine: Double, theirs: Double, sooner: String, later: String): String? {
            val ft = ((theirs - mine) * FT / 5).roundToInt() * 5
            // Over 100 ft is a gate mismatched, not the same corner
            return if (abs(ft) < MIN_ALONG_FT || abs(ft) > 100) null else "${abs(ft)} ft " + if (ft > 0) later else sooner
        }
        // Turn-in and apex points only mean something in a corner with one clear turn: the cornering
        // starts after the section does and doesn't fill it (a chicane, slalom or long sweeper never lets up)
        val len = d1 - d0
        fun clear(s: Shape) = s.turnIn > d0 + 1 && s.trackOut - s.turnIn < 0.7 * len
        val clearCorner = clear(r) && clear(t)
        // The slowest points can be compared wherever both fall well inside the section (at its edge the
        // car was still slowing from, or for, the next corner)
        fun inside(d: Double) = d > d0 + 3 && d < d1 - 3
        val sameApex = inside(r.apex) && inside(t.apex)
        val items = ArrayList<String>()
        // The whole corner shifted one way: one instruction instead of three
        val sides = listOfNotNull(sideAt(t.turnIn), sideAt(t.apex), sideAt(t.trackOut).takeIf { t.trackOut > t.apex + 3 && t.trackOut < d1 - 1 })
        val whole = sides.size == 3 && (sides.all { it > 0 } || sides.all { it < 0 }) && (sides.max() - sides.min()) * FT < 2.0 && say(sides.average()) != null
        if (whole) items += "drive the whole corner " + say(sides.average())
        val tiSide = if (whole) null else move(t.turnIn); val tiAlong = if (clearCorner) along(r.turnIn, t.turnIn, "earlier", "later") else null
        listOfNotNull(tiSide, tiAlong).takeIf { it.isNotEmpty() }?.let { items += "turn in " + it.joinToString(" and ") }
        val apSide = if (whole) null else move(t.apex); val apAlong = if (sameApex) along(r.apex, t.apex, "earlier", "later") else null
        when {
            apAlong != null && apSide != null -> items += "apex $apAlong, $apSide"
            apAlong != null -> items += "apex $apAlong"
            apSide != null -> items += "apex $apSide"
        }
        if (!whole && t.trackOut > t.apex + 3 && t.trackOut < d1 - 1) move(t.trackOut)?.let { items += "track out $it" }
        if (items.isEmpty()) return null
        val sb = StringBuilder(items.take(3).joinToString(", ").replaceFirstChar { it.uppercase() }).append(". ")
        val dv = (t.exitSpeed - r.exitSpeed) * MPH
        if (dv >= 1.5) sb.append("That line left at %.0f mph, you %.0f. ".format(t.exitSpeed * MPH, r.exitSpeed * MPH))
        when {
            tAfter != null && tHere < -0.02 && tAfter > 0.02 ->
                sb.append("You were %.2f s quicker here but lost %.2f s after it.".format(-tHere, tAfter))
            tAfter != null -> sb.append("Worth %.2f s here and after it.".format(tHere + tAfter))
            else -> sb.append("Worth %.2f s.".format(tHere))
        }
        sb.append(" (Run $target's line.)")
        return sb.toString()
    }
}
