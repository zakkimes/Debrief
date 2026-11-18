package com.kimes.daq.run

import com.kimes.daq.data.Gate
import com.kimes.daq.data.Run
import com.kimes.daq.data.Sample

/**
 * Timing from timing lines, like a real timing system.
 *
 * Autocross: the clock starts when the car crosses the start line in the course direction and
 * stops at the finish line. Sector lines in between record splits in order. Crossing the start
 * line again before finishing restarts the run (a false start or a re-grid). A run is abandoned
 * if the car sits stopped for 8 s or runs past 5 minutes (off course, red flag).
 *
 * Laps: each crossing of the start/finish line ends one lap and starts the next.
 */
class LineTimer(
    private val start: Gate,
    private val finish: Gate,
    private val sectors: List<Gate>,
    private val laps: Boolean,
) : RunTiming {
    private var running = false
    private var startIdx = 0
    private var startT = 0.0
    private val splits = ArrayList<Double>()
    private var stoppedSince = -1.0
    private var nextNumber = 1

    override val isRunning get() = running
    override val currentStartT get() = startT
    override val currentSplits: List<Double> get() = splits.toList()
    override fun setNextNumber(n: Int) { nextNumber = n }

    override fun feed(samples: List<Sample>, i: Int): Run? {
        if (i == 0) return null
        val s = samples[i]
        val p = samples[i - 1]
        if (s.t - p.t > 1.0) return null // gap in data: don't interpolate across it
        fun at(f: Double) = p.t + (s.t - p.t) * f

        if (running) {
            // Next sector line, in order
            if (splits.size < sectors.size) {
                sectors[splits.size].crossing(p.x, p.y, s.x, s.y)?.let { splits += at(it) - startT }
            }
            val fin = finish.crossing(p.x, p.y, s.x, s.y)
            if (fin != null && at(fin) - startT > 3.0) {
                val endT = at(fin)
                val run = Run(nextNumber++, startIdx, i, startT, endT, true, splits.toList())
                running = false
                if (laps) begin(i, endT)
                return run
            }
            if (!laps) {
                start.crossing(p.x, p.y, s.x, s.y)?.let { begin(i - 1, at(it)); return null }
            }
            if (s.speed < 0.8) {
                if (stoppedSince < 0) stoppedSince = s.t
                if (s.t - stoppedSince > 8.0) running = false
            } else stoppedSince = -1.0
            if (s.t - startT > 300) running = false
            return null
        }
        start.crossing(p.x, p.y, s.x, s.y)?.let { begin(i - 1, at(it)) }
        return null
    }

    private fun begin(idx: Int, t: Double) {
        running = true
        startIdx = idx
        startT = t
        splits.clear()
        stoppedSince = -1.0
    }
}
