package com.kimes.daq.data

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Guided-test analysis: for each signal source (broadcast CAN id, or polled data identifier) and
 * byte position (8-bit, 16-bit signed/unsigned, both byte orders), compares values while an input
 * is held (e.g. BRAKE) against REST, and ranks the fields that move clearly and consistently.
 */
object CanFinder {
    /** id: broadcast CAN id, or (header shl 16) or data identifier for polled signals. */
    class Frame(val t: Double, val id: Int, val data: ByteArray)
    class Phase(val label: String, val t0: Double, val t1: Double)
    data class Candidate(
        val id: Int, val start: Int, val len: Int, val bigEndian: Boolean, val signed: Boolean,
        val rest: Double, val active: Double, val score: Double,
        val light: Double? = null, // mean during the light-press phases, when the test had them
    ) {
        val name: String get() = if (id > 0xFFF) "%03X:%04X".format(id ushr 16, id and 0xFFFF) else "0x%03X".format(id)

        override fun toString() = "%s b%d%s%s%s  rest %.0f → %.0f%s  (score %.1f)".format(
            name, start, if (len == 2) "-${start + 1}" else "", if (len == 2) (if (bigEndian) " BE" else " LE") else "",
            if (signed) " signed" else "", rest, active,
            light?.let { "  light %.0f%%".format((it - rest) / (active - rest) * 100) } ?: "", score,
        )

        fun decoder(name: String) = CanDecoder(
            name, if (id > 0xFFF) id and 0xFFFF else id,
            header = if (id > 0xFFF) id ushr 16 else 0,
            start = start, len = len, bigEndian = bigEndian, signed = signed,
        )
    }

    private data class Variant(val len: Int, val be: Boolean, val signed: Boolean)
    // Preference order when several readings of the same signal qualify
    private val variants = listOf(Variant(2, true, true), Variant(2, true, false), Variant(2, false, false), Variant(1, true, false))

    private class Scored(val c: Candidate, val continuous: Boolean, val pref: Int)

    /**
     * Ranks signals that move clearly on every press. Continuous signals (pressure) rank above
     * on/off ones (brake light switch); each signal is listed once, in its most likely encoding.
     */
    fun rank(frames: List<Frame>, phases: List<Phase>, label: String, lightLabel: String? = null, top: Int = 6): List<Candidate> {
        val act = phases.filter { it.label == label }
        val rest = phases.filter { it.label == "REST" }
        val light = lightLabel?.let { l -> phases.filter { it.label == l } }.orEmpty()
        if (act.isEmpty() || rest.isEmpty()) return emptyList()
        // Trim the start of each phase: people react to the beep late, so the previous state lingers
        fun phaseOf(t: Double, ps: List<Phase>) = ps.indexOfFirst { t >= it.t0 + 1.5 && t <= it.t1 }

        val byId = frames.groupBy { it.id }
        val out = ArrayList<Scored>()
        for ((id, fs) in byId) {
            val n = fs.maxOf { it.data.size }
            for ((vi, v) in variants.withIndex()) for (start in 0..n - v.len) {
                val actVals = Array(act.size) { ArrayList<Double>() }
                val restVals = ArrayList<Double>()
                val lightVals = ArrayList<Double>()
                for (f in fs) {
                    if (f.data.size < start + v.len) continue
                    val x = value(f.data, start, v)
                    val a = phaseOf(f.t, act)
                    if (a >= 0) actVals[a] += x
                    else if (phaseOf(f.t, rest) >= 0) restVals += x
                    else if (phaseOf(f.t, light) >= 0) lightVals += x
                }
                if (restVals.size < 3 || actVals.any { it.size < 2 }) continue
                val rMed = median(restVals)
                val rNoise = median(restVals.map { abs(it - rMed) })
                val meds = actVals.map { median(it) }
                // Every press must move the value the same direction, well outside rest noise.
                // Press force varies between presses, so only the weakest press counts, not the spread.
                val diffs = meds.map { it - rMed }
                if (!(diffs.all { it > 0 } || diffs.all { it < 0 })) continue
                val score = diffs.minOf { abs(it) } / (rNoise + 1.0)
                if (score <= 2) continue
                val distinct = actVals.flatMap { it }.toSet().size
                val c = Candidate(id, start, v.len, v.be, v.signed, rMed, meds.average(), score, lightVals.takeIf { it.isNotEmpty() }?.let { median(it) })
                out += Scored(c, continuous = distinct >= 4, pref = vi)
            }
        }
        // One entry per signal: continuous first, then the preferred encoding
        val best = out.groupBy { it.c.id }.values.map { g ->
            g.sortedWith(compareByDescending<Scored> { it.continuous }.thenBy { it.pref }.thenByDescending { it.c.score }).first()
        }
        return best.sortedWith(compareByDescending<Scored> { it.continuous }.thenByDescending { it.c.score }).map { it.c }.take(top)
    }

    /** A found signal with its scaling to real units (degrees, m/s). */
    data class Scaled(val c: Candidate, val scale: Double, val offset: Double, val note: String) {
        fun decoder(name: String) = c.decoder(name).copy(scale = scale, offset = offset)
        override fun toString() = "%s b%d%s%s  %s".format(c.name, c.start, if (c.len == 2) "-${c.start + 1}" else "", if (c.len == 2) (if (c.bigEndian) " BE" else " LE") else "", note)
    }

    private fun fields(fs: List<Frame>, vs: List<Variant>) = sequence {
        val n = fs.maxOf { it.data.size }
        for ((vi, v) in vs.withIndex()) for (start in 0..n - v.len) yield(Triple(vi, v, start))
    }

    /**
     * Steering angle: with the wheel held right, then left, then centered (REST), the signal moves
     * one way for every RIGHT and the other way for every LEFT. Scaled to degrees, + = left.
     */
    fun steering(frames: List<Frame>, phases: List<Phase>, top: Int = 4): List<Scaled> {
        fun ph(l: String) = phases.filter { it.label == l }
        val right = ph("RIGHT"); val left = ph("LEFT"); val rest = ph("REST")
        if (right.isEmpty() || left.isEmpty() || rest.isEmpty()) return emptyList()
        fun idx(t: Double, ps: List<Phase>) = ps.indexOfFirst { t >= it.t0 + 2.0 && t <= it.t1 }
        val out = ArrayList<Pair<Double, Scaled>>()
        val signed = listOf(Variant(2, true, true), Variant(2, false, true))
        for ((id, fs) in frames.groupBy { it.id }) for ((_, v, start) in fields(fs, signed)) {
            val r = Array(right.size) { ArrayList<Double>() }; val l = Array(left.size) { ArrayList<Double>() }; val z = ArrayList<Double>()
            for (f in fs) {
                if (f.data.size < start + v.len) continue
                val x = value(f.data, start, v)
                idx(f.t, right).let { if (it >= 0) r[it] += x }
                idx(f.t, left).let { if (it >= 0) l[it] += x }
                if (idx(f.t, rest) >= 0) z += x
            }
            if (z.size < 3 || r.any { it.isEmpty() } || l.any { it.isEmpty() }) continue
            val z0 = median(z); val noise = median(z.map { abs(it - z0) })
            val dr = r.map { median(it) - z0 }; val dl = l.map { median(it) - z0 }
            val opposite = (dr.all { it > 0 } && dl.all { it < 0 }) || (dr.all { it < 0 } && dl.all { it > 0 })
            if (!opposite) continue
            val score = minOf(dr.minOf { abs(it) }, dl.minOf { abs(it) }) / (noise + 1.0)
            if (score <= 3) continue
            val lock = dl.average()
            // Full lock on an ND is a bit under 1.5 turns each way: pick the 0.1 or 1 deg/bit that fits, else fit it
            val per = when (abs(lock)) { in 2500.0..8000.0 -> 0.1; in 250.0..800.0 -> 1.0; else -> 490.0 / abs(lock) }
            val scale = if (lock > 0) per else -per
            val c = Candidate(id, start, v.len, v.be, v.signed, z0, z0 + lock, score)
            out += score to Scaled(c, scale, -z0 * scale, "center %.0f, full left %+.0f°, full right %+.0f°".format(z0, lock * scale, dr.average() * scale))
        }
        return out.groupBy { it.second.c.id }.values.map { g -> g.maxBy { it.first } }.sortedByDescending { it.first }.take(top).map { it.second }
    }

    /**
     * Wheel speeds: signals that follow GPS speed closely while rolling. Each is scaled to m/s. The
     * CIRCLE phase (slow left circle) then tells the corners apart: right-front fastest, then
     * right-rear, left-front, left-rear slowest.
     */
    fun wheelSpeeds(frames: List<Frame>, phases: List<Phase>, speedAt: (Double) -> Double?, exclude: Set<Int> = emptySet()): List<Pair<String, Scaled>> {
        val t0 = phases.minOfOrNull { it.t0 } ?: return emptyList(); val t1 = phases.maxOf { it.t1 }
        val circle = phases.filter { it.label == "CIRCLE" }
        val unsigned = listOf(Variant(2, true, false), Variant(2, false, false), Variant(1, true, false))
        val found = ArrayList<Triple<Double, Scaled, Double>>() // r, signal, mean speed in the circle
        for ((id, fs) in frames.groupBy { it.id }) {
            if (id in exclude) continue
            for ((_, v, start) in fields(fs, unsigned)) {
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val circ = ArrayList<Double>()
                for (f in fs) {
                    if (f.t < t0 || f.t > t1 || f.data.size < start + v.len) continue
                    val sp = speedAt(f.t) ?: continue
                    val x = value(f.data, start, v)
                    xs += x; ys += sp
                    if (circle.any { f.t >= it.t0 + 2 && f.t <= it.t1 }) circ += x
                }
                if (xs.size < 12 || (ys.maxOrNull() ?: 0.0) < 2.0) continue
                val mx = xs.average(); val my = ys.average()
                var sxy = 0.0; var sxx = 0.0; var syy = 0.0
                for (k in xs.indices) { val a = xs[k] - mx; val b = ys[k] - my; sxy += a * b; sxx += a * a; syy += b * b }
                if (sxx == 0.0 || syy == 0.0) continue
                val r = sxy / sqrt(sxx * syy)
                if (r < 0.93) continue
                val slope = sxy / sxx; val icpt = my - slope * mx
                val c = Candidate(id, start, v.len, v.be, v.signed, mx, mx, r)
                val sc = Scaled(c, slope, icpt, "follows speed (r %.3f), %.4f m/s per bit".format(r, slope))
                found += Triple(r, sc, if (circ.isEmpty()) Double.NaN else circ.average() * slope + icpt)
            }
        }
        // Keep the best encoding per byte position, and drop overlapping positions of the same signal
        val best = found.sortedByDescending { it.first }.fold(ArrayList<Triple<Double, Scaled, Double>>()) { acc, f ->
            val c = f.second.c
            if (acc.none { it.second.c.id == c.id && it.second.c.start < c.start + c.len && c.start < it.second.c.start + it.second.c.len }) acc += f
            acc
        }.take(4)
        if (best.size == 4 && best.all { !it.third.isNaN() }) {
            val names = listOf("wFR", "wRR", "wFL", "wRL")
            return best.sortedByDescending { it.third }.mapIndexed { i, f -> names[i] to f.second }
        }
        return best.mapIndexed { i, f -> "wheel${i + 1}" to f.second }
    }

    /** An on/off signal: one bit that is [onValue] in every press phase and the opposite in every rest. */
    data class Bit(val id: Int, val byte: Int, val bit: Int, val onValue: Int, val frames: Int) {
        val name: String get() = "%03X:%04X".format(id ushr 16, id and 0xFFFF)
        fun decoder(name: String) = CanDecoder(name, id and 0xFFFF, header = id ushr 16, start = byte, len = 1, shift = bit, mask = 1, offset = 0.0,
            scale = if (onValue == 1) 1.0 else -1.0).let { if (onValue == 1) it else it.copy(offset = 1.0) }
        override fun toString() = "%s byte %d bit %d = %d when pressed (%d readings)".format(name, byte, bit, onValue, frames)
    }

    /**
     * Switches (clutch pedal): bits that flip with every press and hold steady through every rest.
     * Only bits that changed at least once per press count, so constant bits and counters drop out.
     */
    fun bits(frames: List<Frame>, phases: List<Phase>, label: String, top: Int = 6): List<Bit> {
        val act = phases.filter { it.label == label }; val rest = phases.filter { it.label == "REST" }
        if (act.isEmpty() || rest.isEmpty()) return emptyList()
        // Skip the reaction time at the start and the anticipation at the end of each phase
        fun idx(t: Double, ps: List<Phase>) = ps.indexOfFirst { t >= it.t0 + 1.5 && t <= it.t1 - 0.3 }
        val out = ArrayList<Bit>()
        for ((id, fs) in frames.groupBy { it.id }) {
            val n = fs.maxOf { it.data.size }
            for (b in 0 until n) for (bit in 0 until 8) {
                val a = Array(act.size) { ArrayList<Int>() }; val r = Array(rest.size) { ArrayList<Int>() }
                for (f in fs) {
                    if (f.data.size <= b) continue
                    val v = (f.data[b].toInt() ushr bit) and 1
                    idx(f.t, act).let { if (it >= 0) a[it] += v }
                    idx(f.t, rest).let { if (it >= 0) r[it] += v }
                }
                if (a.any { it.isEmpty() } || r.count { it.isNotEmpty() } < 2) continue
                // Each press mostly reads one value and each rest mostly the other (one stray reading is tolerated)
                val on = if (a[0].count { it == 1 } * 2 > a[0].size) 1 else 0
                fun share(p: List<Int>, v: Int) = p.count { it == v }.toDouble() / p.size
                if (a.all { share(it, on) >= 0.85 } && r.filter { it.isNotEmpty() }.all { share(it, 1 - on) >= 0.85 }) out += Bit(id, b, bit, on, a.sumOf { it.size })
            }
        }
        // A bit seen in more readings is more trustworthy
        return out.sortedByDescending { it.frames }.take(top)
    }

    /**
     * Signals that follow a reference channel through the whole capture (e.g. the RaceBox yaw rate
     * during the circles): signed 16-bit and 8-bit fields, ranked by correlation, scaled to the
     * reference's units. Negative correlation counts too (the car may use the opposite sign).
     */
    fun follows(frames: List<Frame>, phases: List<Phase>, ref: (Double) -> Double?, units: String, minR: Double = 0.85, exclude: Set<Int> = emptySet(), top: Int = 3, unsigned16: Boolean = false): List<Scaled> {
        val t0 = phases.minOfOrNull { it.t0 } ?: return emptyList(); val t1 = phases.maxOf { it.t1 }
        val vs = listOf(Variant(2, true, true), Variant(2, false, true), Variant(1, true, false)) +
            if (unsigned16) listOf(Variant(2, true, false), Variant(2, false, false)) else emptyList()
        val found = ArrayList<Pair<Double, Scaled>>()
        for ((id, fs) in frames.groupBy { it.id }) {
            if (id in exclude) continue
            for ((_, v, start) in fields(fs, vs)) {
                val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
                for (f in fs) {
                    if (f.t < t0 || f.t > t1 || f.data.size < start + v.len) continue
                    val y = ref(f.t) ?: continue
                    if (y.isNaN()) continue
                    xs += value(f.data, start, v); ys += y
                }
                if (xs.size < 12) continue
                val mx = xs.average(); val my = ys.average()
                var sxy = 0.0; var sxx = 0.0; var syy = 0.0
                for (k in xs.indices) { val a = xs[k] - mx; val b = ys[k] - my; sxy += a * b; sxx += a * a; syy += b * b }
                if (sxx == 0.0 || syy == 0.0) continue
                val r = sxy / sqrt(sxx * syy)
                if (abs(r) < minR) continue
                val slope = sxy / sxx
                val c = Candidate(id, start, v.len, v.be, v.signed, mx, mx, abs(r))
                found += abs(r) to Scaled(c, slope, my - slope * mx, "follows the RaceBox (r %.2f), %.4g %s per bit".format(r, slope, units))
            }
        }
        return found.sortedByDescending { it.first }.fold(ArrayList<Pair<Double, Scaled>>()) { acc, f ->
            val c = f.second.c
            if (acc.none { it.second.c.id == c.id && it.second.c.start < c.start + c.len && c.start < it.second.c.start + it.second.c.len }) acc += f
            acc
        }.take(top).map { it.second }
    }

    /** Tire pressure signals matched to what the dash showed; corners[i] is the corner of decoders[i] (tire i+1). */
    data class TireMatch(val decoders: List<CanDecoder>, val corners: List<String>, val maxErrPsi: Double, val note: String)

    /**
     * Finds the four tire pressures in a module scan, given what the dash showed (psi, in the order
     * LF, RF, LR, RR). Looks at groups of four neighbouring data ids, or four fields inside one id,
     * and keeps the group whose values are a straight-line rescaling of the dash numbers (gauge
     * psi, kPa, or absolute pressure) to within 0.7 psi. The best fit also says which is which corner.
     */
    fun matchTires(hits: Map<Int, Map<Int, ByteArray>>, dash: List<Double>): TireMatch? {
        val codes = listOf("LF", "RF", "LR", "RR")
        data class F(val header: Int, val did: Int, val start: Int, val len: Int, val x: Double)
        fun fv(b: ByteArray, s: Int, len: Int) = if (len == 1) (b[s].toInt() and 0xFF).toDouble() else ((b[s].toInt() and 0xFF) * 256 + (b[s + 1].toInt() and 0xFF)).toDouble()
        val groups = ArrayList<List<F>>()
        for ((h, m) in hits) {
            val ids = m.keys.sorted()
            for (len in listOf(1, 2)) {
                // Four neighbouring ids (1 or 2 apart), same field
                for (step in listOf(1, 2)) for (d in ids) {
                    val four = (0 until 4).map { m[d + it * step] }
                    if (four.any { it == null }) continue
                    val n = four.minOf { it!!.size }
                    for (s in 0..n - len) groups += (0 until 4).map { F(h, d + it * step, s, len, fv(four[it]!!, s, len)) }
                }
                // Four fields inside one id
                for (d in ids) {
                    val b = m[d]!!
                    for (s in 0..b.size - 4 * len) groups += (0 until 4).map { F(h, d, s + it * len, len, fv(b, s + it * len, len)) }
                }
            }
        }
        fun perms(l: List<Int>): List<List<Int>> = if (l.size <= 1) listOf(l) else l.flatMap { a -> perms(l - a).map { listOf(a) + it } }
        val all = perms(listOf(0, 1, 2, 3)).sortedBy { p -> (0 until 4).sumOf { i -> (i + 1 until 4).count { j -> p[i] > p[j] } } } // in-order first
        var best: Triple<Double, List<F>, List<Int>>? = null
        var bestAB = 0.0 to 0.0
        for (g in groups) {
            val xs = g.map { it.x }
            if (xs.toSet().size < 2 || xs.any { it == 0.0 }) continue
            for (p in all) {
                val ys = p.map { dash[it] }
                val mx = xs.average(); val my = ys.average()
                var sxy = 0.0; var sxx = 0.0
                for (k in 0 until 4) { sxy += (xs[k] - mx) * (ys[k] - my); sxx += (xs[k] - mx) * (xs[k] - mx) }
                if (sxx == 0.0) continue
                val a = sxy / sxx; val b = my - a * mx
                if (a <= 0) continue
                // Gauge pressure has little offset (some cars add a few kPa, and the tires may have warmed
                // since the dash numbers were typed); absolute pressure is off by one atmosphere
                if (abs(b) > 8 && abs(b + 14.7) > 4) continue
                // A scale that maps the field onto plausible units: psi/count between 0.01 and 2
                if (a < 0.01 || a > 2) continue
                val err = (0 until 4).maxOf { abs(a * xs[it] + b - ys[it]) }
                if (err > 0.8) continue
                if (best == null || err < best.first - 0.05) { best = Triple(err, g, p); bestAB = a to b }
                break // perms are in preference order; the first that fits this group is enough
            }
        }
        val (err, g, p) = best ?: return null
        val (a, b) = bestAB
        val decs = g.mapIndexed { i, f -> CanDecoder("tire${i + 1}", f.did, header = f.header, start = f.start, len = f.len, scale = a, offset = b) }
        return TireMatch(decs, p.map { codes[it] }, err, "%03X:%04X… %.4g psi per step%s, within %.1f psi".format(g[0].header, g[0].did, a, if (b < -8) " (absolute)" else "", err))
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun value(d: ByteArray, start: Int, v: Variant): Double {
        if (v.len == 1) return (d[start].toInt() and 0xFF).toDouble()
        val a = d[start].toInt() and 0xFF
        val b = d[start + 1].toInt() and 0xFF
        val u = if (v.be) a * 256 + b else b * 256 + a
        return (if (v.signed && u >= 0x8000) u - 0x10000 else u).toDouble()
    }

}
