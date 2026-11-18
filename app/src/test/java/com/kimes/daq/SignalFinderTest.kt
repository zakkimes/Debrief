package com.kimes.daq

import com.kimes.daq.data.CanFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** The guided clutch and motion-sensor tests find the right fields in synthetic captures. */
class SignalFinderTest {
    private val pcm = 0x7E0 shl 16

    @Test
    fun clutchBitIsFound() {
        val phases = ArrayList<CanFinder.Phase>()
        val frames = ArrayList<CanFinder.Frame>()
        var t = 0.0
        val labels = listOf("REST", "CLUTCH", "REST", "CLUTCH", "REST", "CLUTCH", "REST")
        for (l in labels) {
            phases += CanFinder.Phase(l, t, t + 8)
            var k = 0.0
            while (k < 8) {
                val pressed = l == "CLUTCH"
                // DID 0x1234: byte 1 bit 3 is the clutch switch; byte 0 is a counter that toggles every reading
                val counter = ((t + k) * 10).toInt() and 0xFF
                frames += CanFinder.Frame(t + k, pcm or 0x1234, byteArrayOf(counter.toByte(), (if (pressed) 0x08 or 0x01 else 0x01).toByte()))
                // DID 0x0100: constant
                frames += CanFinder.Frame(t + k + 0.01, pcm or 0x0100, byteArrayOf(0x55))
                k += 0.5
            }
            t += 8
        }
        val bits = CanFinder.bits(frames, phases, "CLUTCH")
        assertEquals(1, bits.size)
        val b = bits.first()
        assertEquals(0x1234, b.id and 0xFFFF); assertEquals(1, b.byte); assertEquals(3, b.bit); assertEquals(1, b.onValue)
        val dec = b.decoder("clutch")
        assertEquals(1.0, dec.decode(byteArrayOf(0, 0x09))!!, 1e-9)
        assertEquals(0.0, dec.decode(byteArrayOf(0, 0x01))!!, 1e-9)
    }

    @Test
    fun clutchBitActiveLowDecodesAsPressed() {
        val b = CanFinder.Bit(pcm or 0x1234, 0, 2, onValue = 0, frames = 10)
        val dec = b.decoder("clutch")
        assertEquals(1.0, dec.decode(byteArrayOf(0x00))!!, 1e-9)
        assertEquals(0.0, dec.decode(byteArrayOf(0x04))!!, 1e-9)
    }

    @Test
    fun tiresMatchTheDash() {
        // Body module: tire pressures at 2813-2816 as kPa x 1.373 per step, in the order LR, LF, RR, RF;
        // lots of other noise around them
        val dash = listOf(32.0, 31.0, 28.0, 32.0) // LF RF LR RR
        fun raw(psi: Double) = (psi / 0.1450377 / 1.373).let { kotlin.math.round(it).toInt() }
        val body = HashMap<Int, ByteArray>()
        for (d in 0x2800..0x2830) body[d] = byteArrayOf(((d * 37) and 0xFF).toByte(), 0x10)
        body[0x2813] = byteArrayOf(raw(28.0).toByte(), 0)
        body[0x2814] = byteArrayOf(raw(32.0).toByte(), 0)
        body[0x2815] = byteArrayOf(raw(32.0).toByte(), 0)
        body[0x2816] = byteArrayOf(raw(31.0).toByte(), 0)
        val cluster = HashMap<Int, ByteArray>()
        for (d in 0x2A00..0x2A20) cluster[d] = byteArrayOf(((d * 91) and 0xFF).toByte(), ((d * 13) and 0xFF).toByte())
        val m = CanFinder.matchTires(mapOf(0x726 to body, 0x720 to cluster), dash)!!
        assertEquals(listOf(0x2813, 0x2814, 0x2815, 0x2816), m.decoders.map { it.id })
        assertEquals(0x726, m.decoders[0].header)
        assertEquals("LR", m.corners[0]); assertEquals("RF", m.corners[3])
        // LF and RR read the same today, so those two can't be told apart here
        assertTrue(m.corners.subList(1, 3).toSet() == setOf("LF", "RR"))
        assertEquals(28.0, m.decoders[0].decode(body[0x2813]!!)!!, 0.3)
    }

    @Test
    fun insideRearSpinIsMeasured() {
        // 10 s at 12 m/s turning left; from 4 s to 5 s the left (inside) rear runs 25% fast on throttle
        val ss = ArrayList<com.kimes.daq.data.Sample>()
        var t = 0.0
        while (t < 10) {
            val spin = t in 4.0..5.0
            fun q(v: Double) = kotlin.math.floor(v * 3.6) / 3.6 // 1 km/h steps
            ss += com.kimes.daq.data.Sample(t, 0.0, 0.0, 0.0, 0.0, 12.0, 0.0, 0.0, 80.0, 4000.0, Double.NaN, 0.5, 0.0, 12, 3,
                yaw = 20.0, gear = 2, wFL = q(11.8), wFR = q(12.2), wRL = q(if (spin) 15.0 else 11.9), wRR = q(12.1))
            t += 0.04
        }
        val run = com.kimes.daq.data.Run(1, 0, ss.size - 1, 0.0, 10.0, true)
        val ev = com.kimes.daq.run.Coach.wheelSlip(ss, run)
        assertEquals(1, ev.size)
        assertEquals("left rear (inside)", ev[0].wheel)
        assertEquals(1.0, ev[0].durS, 0.1)
        assertTrue(ev[0].peakPct in 20.0..30.0)
    }

    @Test
    fun yawSignalFollowsRaceBox() {
        val dsc = 0x760 shl 16
        val phases = listOf(CanFinder.Phase("REST", 0.0, 5.0), CanFinder.Phase("CIRCLE", 5.0, 30.0), CanFinder.Phase("REST", 30.0, 40.0))
        fun yaw(t: Double) = 25 * sin(t / 6)
        val frames = ArrayList<CanFinder.Frame>()
        var t = 0.0
        while (t < 40) {
            // Car's yaw sensor: signed 16-bit BE, 0.01 deg/s per bit, opposite sign to the RaceBox
            val raw = (-yaw(t) * 100).toInt()
            frames += CanFinder.Frame(t, dsc or 0x2B0C, byteArrayOf((raw shr 8).toByte(), raw.toByte()))
            frames += CanFinder.Frame(t + 0.01, dsc or 0x2B05, byteArrayOf(0x40, 0, 0, 0))
            t += 0.25
        }
        val found = CanFinder.follows(frames, phases, { yaw(it) }, "deg/s")
        assertTrue(found.isNotEmpty())
        val f = found.first()
        assertEquals(0x2B0C, f.c.id and 0xFFFF)
        assertEquals(-0.01, f.scale, 1e-4)
    }
}
