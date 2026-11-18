package com.kimes.daq.run

import com.kimes.daq.data.Run

/**
 * Who's best, given each run's penalty. Penalty map: run number -> cones hit, or DNF.
 * Runs with cones or a DNF never become the reference for the live delta.
 */
object Standings {
    const val DNF = -1
    const val CONE_S = 2.0

    fun clean(r: Run, pen: Map<Int, Int>) = r.finished && (pen[r.number] ?: 0) == 0

    /** Fastest clean finished run, optionally ignoring one run (to compare a new run with the previous best). */
    fun best(runs: List<Run>, pen: Map<Int, Int>, exclude: Int? = null, time: (Run) -> Double = { it.time }): Run? =
        runs.filter { clean(it, pen) && it.number != exclude }.minByOrNull(time)

    /** Official-style time: raw + 2 s per cone, null for DNF. */
    fun scored(r: Run, pen: Map<Int, Int>): Double? = when (val p = pen[r.number] ?: 0) {
        DNF -> null
        else -> r.time + p * CONE_S
    }

    data class SectorBest(val time: Double, val run: Int)

    /** Best time in each sector over clean runs that recorded every split. */
    fun sectorBests(runs: List<Run>, pen: Map<Int, Int>, sectors: Int, exclude: Int? = null): List<SectorBest?> {
        val ok = runs.filter { clean(it, pen) && it.number != exclude && it.sectorTimes.size == sectors }
        return (0 until sectors).map { k -> ok.minByOrNull { it.sectorTimes[k] }?.let { SectorBest(it.sectorTimes[k], it.number) } }
    }

    /** Sum of the best sectors: what a run stitched from your best pieces would be. */
    fun theoreticalBest(runs: List<Run>, pen: Map<Int, Int>, sectors: Int): Double? {
        if (sectors < 2) return best(runs, pen)?.time
        val b = sectorBests(runs, pen, sectors)
        return if (b.any { it == null }) null else b.sumOf { it!!.time }
    }
}
