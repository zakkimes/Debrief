package com.kimes.daq

import com.kimes.daq.data.Sample
import com.kimes.daq.run.Analyzer
import com.kimes.daq.run.Course
import com.kimes.daq.run.RunTracker
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Replays sessions pulled from the tablet into ./pulled (skipped when none are present). */
class RealSessionReplayTest {
    @Test
    fun replayPulledSessions() {
        val root = File("../pulled/files/sessions")
        assumeTrue(root.isDirectory)
        for (d in root.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            val samples = File(d, "gps.csv").readLines().drop(1).mapNotNull { Sample.parse(it) }
            val runs = RunTracker.detectAll(samples, Course())
            val vmax = samples.maxOfOrNull { it.speed } ?: 0.0
            println("== ${d.name}: ${samples.size} samples, %.0f s, top %.0f mph, ${runs.size} runs".format(
                (samples.lastOrNull()?.t ?: 0.0) - (samples.firstOrNull()?.t ?: 0.0), vmax * 2.237))
            for (r in runs) {
                println("  run ${r.number}: %.2f s".format(r.time))
                Analyzer.analyze(samples, r, runs.first()).insights.forEach { println("    • $it") }
            }
        }
    }
}
