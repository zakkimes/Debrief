package com.kimes.daq

import com.kimes.daq.data.CanDecoders
import com.kimes.daq.data.CanFinder
import org.junit.Assert.assertEquals
import org.junit.Test

class BrakeSignalTest {
    @Test
    fun defaultBrakeDecodesDscReply() {
        val brake = CanDecoders.defaults.first { it.name == "brake" }
        assertEquals(0x760, brake.header)
        assertEquals(0x2B0D, brake.id)
        // Reply "62 2B 0D 00 17" -> payload 00 17 -> 23 / 2.3
        assertEquals(10.0, brake.decode(byteArrayOf(0x00, 0x17))!!, 1e-9)
        // Signed: FF FE at rest reads slightly negative (the app clamps it to 0)
        assertEquals(-2 / 2.3, brake.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))!!, 1e-9)
    }

    @Test
    fun guidedTestFindsSignedPressureAmongDscSignals() {
        val key = (0x760 shl 16) or 0x2B0D
        val other = (0x760 shl 16) or 0x2B01
        val phases = listOf(
            CanFinder.Phase("REST", 0.0, 5.0), CanFinder.Phase("BRAKE", 5.0, 10.0), CanFinder.Phase("REST", 10.0, 15.0),
            CanFinder.Phase("BRAKE", 15.0, 20.0), CanFinder.Phase("REST", 20.0, 25.0),
            CanFinder.Phase("BRAKE_LIGHT", 25.0, 30.0), CanFinder.Phase("REST", 30.0, 35.0),
        )
        val frames = ArrayList<CanFinder.Frame>()
        var t = 0.0
        var k = 0
        while (t < 35) {
            val label = phases.first { t >= it.t0 && t < it.t1 }.label
            // Pressure hovers around 0 (occasionally -1 = FFFF) at rest, 400 hard, 60 light
            val v = when (label) { "BRAKE" -> 400 + (k % 7); "BRAKE_LIGHT" -> 60 + (k % 3); else -> if (k % 4 == 0) -1 else 0 }
            frames += CanFinder.Frame(t, key, byteArrayOf((v shr 8).toByte(), v.toByte()))
            frames += CanFinder.Frame(t, other, byteArrayOf(0x12, (k % 256).toByte())) // unrelated counter
            t += 0.1; k++
        }
        val best = CanFinder.rank(frames, phases, "BRAKE", lightLabel = "BRAKE_LIGHT").first()
        println(best)
        assertEquals(key, best.id)
        assertEquals(true, best.signed)
        val d = best.decoder("brake")
        assertEquals(0x760, d.header)
        assertEquals(0x2B0D, d.id)
    }

    /** Real guided test from the car (skipped when not pulled): pressure must beat the brake light switch (2B00). */
    @Test
    fun realGuidedTestRanksPressureFirst() {
        val dir = java.io.File("../pulled/files/sessions/20261111_144421")
        if (!java.io.File(dir, "can.csv").exists()) return
        val frames = java.io.File(dir, "can.csv").readLines().mapNotNull { l ->
            val p = l.split(",")
            if (p.size < 3 || !p[1].contains(":")) return@mapNotNull null
            val (h, did) = p[1].split(":").map { it.toInt(16) }
            CanFinder.Frame(p[0].toDouble(), (h shl 16) or did, p[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        val phases = java.io.File(dir, "events.csv").readLines().filter { it.contains(",phase ") }.map {
            val (label, t0, t1) = it.substringAfter("phase ").split(" ")
            CanFinder.Phase(label, t0.toDouble(), t1.toDouble())
        }
        val r = CanFinder.rank(frames, phases, "BRAKE", lightLabel = "BRAKE_LIGHT")
        r.forEach { println(it) }
        val best = r.first()
        assertEquals(true, (best.id and 0xFFFF) in setOf(0x2B0D, 0x2034))
        assertEquals(true, best.bigEndian && best.signed && best.len == 2 && best.start == 0)
    }
}
