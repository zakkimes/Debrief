package com.kimes.daq.data

import com.kimes.daq.bt.RaceBoxPacket
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Yaw rate from the RaceBox gyro, whichever way the box is mounted: learns which gyro axis (and sign)
 * follows the GPS heading while driving, and reports that axis in deg/s with + = turning left.
 * Until it has seen enough turning it assumes the box lies flat (Z up).
 * The gyro's zero offset (it read -1.08 deg/s parked on 2026-11-14/15) is learned whenever the car
 * sits still and taken off, so slow drift doesn't pile up into rotation that never happened.
 */
class YawAxis {
    private val sxy = DoubleArray(3)
    private val sxx = DoubleArray(3)
    private var syy = 0.0
    private var n = 0
    var axis = 2; private set
    var sign = 1.0; private set
    var bias = 0.0; private set
    private var stillN = 0

    fun rate(p: RaceBoxPacket, prev: Sample?, t: Double): Double {
        val g = doubleArrayOf(p.rX, p.rY, p.rZ)
        if (prev != null && p.speed > 4 && prev.speed > 4 && t - prev.t in 0.01..0.2) {
            var dh = p.heading - prev.heading
            if (dh > 180) dh -= 360
            if (dh < -180) dh += 360
            val left = -dh / (t - prev.t) // compass heading grows turning right
            if (abs(left) < 120) {
                for (k in 0..2) { sxy[k] += g[k] * left; sxx[k] += g[k] * g[k] }
                syy += left * left; n++
                if (n >= 100 && syy > 100 * 25.0) learn()
            }
        }
        val raw = g[axis] * sign
        if (p.speed < 0.2) {
            stillN++
            bias += (raw - bias) / minOf(stillN, 250) // running mean, then a slow moving average
        }
        return raw - (if (stillN >= 25) bias else 0.0)
    }

    private fun learn() {
        var best = axis; var bestR = 0.0
        for (k in 0..2) {
            val r = sxy[k] / sqrt(sxx[k] * syy + 1e-9)
            if (abs(r) > abs(bestR)) { bestR = r; best = k }
        }
        if (abs(bestR) > 0.6) {
            val sg = if (bestR > 0) 1.0 else -1.0
            if (best != axis || sg != sign) { axis = best; sign = sg; bias = 0.0; stillN = 0 } // offset belongs to the old axis
        }
    }
}
