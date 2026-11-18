package com.kimes.daq.run

import com.kimes.daq.data.Gate
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample
import kotlin.math.hypot

class Course {
    var startX: Double? = null
    var startY: Double? = null
    var finish: Gate? = null
}

/**
 * Autocross run detection. A run starts at launch (stopped >= 1 s, then moving), ends when the
 * car crosses the finish gate (if set) or slows to a crawl. Runs that are too short or slow are
 * discarded, so paddock and grid driving doesn't produce runs.
 */
/** Turns a stream of samples into runs. */
interface RunTiming {
    val isRunning: Boolean
    val currentStartT: Double
    val currentSplits: List<Double> get() = emptyList()
    fun setNextNumber(n: Int)
    fun feed(samples: List<Sample>, i: Int): Run?
}

class RunTracker(private val course: Course) : RunTiming {
    private var running = false
    private var slowStartIdx = -1
    private var slowEndIdx = -1
    private var startIdx = 0
    private var startT = 0.0
    private var dist = 0.0
    private var maxSpeed = 0.0
    private var crawlSince = -1
    private var nextNumber = 1

    override val isRunning get() = running
    override val currentStartT get() = startT
    val currentStartIdx get() = startIdx

    override fun setNextNumber(n: Int) { nextNumber = n }

    /** Feed sample i (samples[i] must exist). Returns a completed valid run, if one just ended. */
    override fun feed(samples: List<Sample>, i: Int): Run? {
        val s = samples[i]
        if (!running) {
            if (s.speed < 0.6) {
                if (slowEndIdx < 0 || slowEndIdx != i - 1) slowStartIdx = i
                slowEndIdx = i
            } else if (s.speed > 1.5 && slowEndIdx >= 0 && slowStartIdx >= 0) {
                val stoppedFor = samples[slowEndIdx].t - samples[slowStartIdx].t
                val since = s.t - samples[slowEndIdx].t
                if (stoppedFor >= 1.0 && since < 2.0 && nearStart(samples[slowEndIdx])) {
                    running = true
                    startIdx = slowEndIdx
                    startT = samples[slowEndIdx].t
                    dist = 0.0
                    maxSpeed = 0.0
                    crawlSince = -1
                }
                slowEndIdx = -1
            }
            return null
        }

        val p = samples[i - 1]
        dist += hypot(s.x - p.x, s.y - p.y)
        if (s.speed > maxSpeed) maxSpeed = s.speed
        val elapsed = s.t - startT

        val fin = course.finish
        if (fin != null && elapsed > 8 && dist > 80) {
            val f = fin.crossing(p.x, p.y, s.x, s.y)
            if (f != null) return end(samples, i, p.t + (s.t - p.t) * f, true)
        }
        if (s.speed < 2.5) {
            if (crawlSince < 0) crawlSince = i
            if (s.t - samples[crawlSince].t >= 1.5) return end(samples, crawlSince, samples[crawlSince].t, false)
        } else {
            crawlSince = -1
        }
        if (elapsed > 300) return end(samples, i, s.t, false)
        return null
    }

    private fun nearStart(s: Sample): Boolean {
        val x = course.startX ?: return true
        val y = course.startY ?: return true
        return hypot(s.x - x, s.y - y) < 40
    }

    private fun end(samples: List<Sample>, endIdx: Int, endT: Double, finished: Boolean): Run? {
        running = false
        slowStartIdx = -1
        slowEndIdx = -1
        val valid = dist > 150 && maxSpeed > 11 && endT - startT > 12
        if (!valid) return null
        if (course.startX == null) {
            course.startX = samples[startIdx].x
            course.startY = samples[startIdx].y
        }
        return Run(nextNumber++, startIdx, endIdx, startT, endT, finished)
    }

    companion object {
        fun detectAll(samples: List<Sample>, course: Course): List<Run> {
            val t = RunTracker(course)
            val out = ArrayList<Run>()
            for (i in samples.indices) t.feed(samples, i)?.let { out += it }
            return out
        }
    }
}

/** Reference lap path with elapsed time and distance at each point. */
class RefPath(samples: List<Sample>, run: Run) {
    val xs: DoubleArray
    val ys: DoubleArray
    val ts: DoubleArray
    val ds: DoubleArray
    val size: Int

    init {
        val n = run.endIdx - run.startIdx + 1
        size = n
        xs = DoubleArray(n); ys = DoubleArray(n); ts = DoubleArray(n); ds = DoubleArray(n)
        for (k in 0 until n) {
            val s = samples[run.startIdx + k]
            xs[k] = s.x; ys[k] = s.y; ts[k] = s.t - run.startT
            ds[k] = if (k == 0) 0.0 else ds[k - 1] + hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
        }
    }
}

/**
 * Finds where the car is along a reference path, searching forward from the last match so
 * that courses which pass near themselves don't confuse it.
 */
class PathFollower(private val ref: RefPath) {
    private var idx = 0
    var refT = 0.0; private set
    var refD = 0.0; private set

    fun update(x: Double, y: Double): Boolean {
        var best = Double.MAX_VALUE
        var bestJ = -1
        var bestF = 0.0
        val lo = (idx - 10).coerceAtLeast(0)
        val hi = (idx + 100).coerceAtMost(ref.size - 2)
        for (j in lo..hi) {
            val ax = ref.xs[j]; val ay = ref.ys[j]
            val dx = ref.xs[j + 1] - ax; val dy = ref.ys[j + 1] - ay
            val l2 = dx * dx + dy * dy
            val f = if (l2 < 1e-9) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(ax + dx * f - x, ay + dy * f - y)
            if (d < best) { best = d; bestJ = j; bestF = f }
        }
        if (bestJ < 0 || best > 20) return false
        idx = bestJ
        refT = ref.ts[bestJ] + (ref.ts[bestJ + 1] - ref.ts[bestJ]) * bestF
        refD = ref.ds[bestJ] + (ref.ds[bestJ + 1] - ref.ds[bestJ]) * bestF
        return true
    }
}
