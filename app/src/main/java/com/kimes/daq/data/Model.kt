package com.kimes.daq.data

import kotlin.math.PI
import kotlin.math.cos

/** One fused telemetry row, emitted on every RaceBox GNSS packet (25 Hz). */
data class Sample(
    val t: Double,          // seconds since session start
    val lat: Double,
    val lon: Double,
    val x: Double,          // meters east of session origin
    val y: Double,          // meters north of session origin
    val speed: Double,      // m/s (GNSS)
    val heading: Double,    // degrees
    val brake: Double,      // CAN brake channel, NaN if unavailable
    val throttle: Double,   // percent, NaN if unavailable
    val rpm: Double,
    val steer: Double,
    val latG: Double,       // derived from GNSS speed * yaw rate, + = left
    val lonG: Double,       // derived from GNSS dv/dt, + = accel
    val sats: Int,
    val fix: Int,
    val utc: Long = 0,      // GPS UTC time, epoch ms (0 = unknown); used to sync video
    val yaw: Double = Double.NaN,   // deg/s from the RaceBox gyro, + = turning left
    val gear: Int = -1,             // as the car reports it (OBD 01 A4): 0 neutral/stopped, 1-6, 7+ other; -1 unknown
    val gearRatio: Double = Double.NaN, // the car's reported engine/output-shaft ratio
    val wFL: Double = Double.NaN, val wFR: Double = Double.NaN, // wheel speeds, m/s, once found in the car
    val wRL: Double = Double.NaN, val wRR: Double = Double.NaN,
) {
    companion object {
        const val CSV_HEADER = "t,lat,lon,x,y,speed,heading,brake,throttle,rpm,steer,latG,lonG,sats,fix,utc,yaw,gear,gearRatio,wFL,wFR,wRL,wRR"

        fun parse(line: String): Sample? {
            val p = line.split(',')
            if (p.size < 15) return null
            return try {
                Sample(
                    p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(),
                    p[5].toDouble(), p[6].toDouble(), p[7].toDouble(), p[8].toDouble(), p[9].toDouble(),
                    p[10].toDouble(), p[11].toDouble(), p[12].toDouble(), p[13].toInt(), p[14].toInt(),
                    if (p.size > 15) p[15].toLongOrNull() ?: 0 else 0,
                    d(p, 16), p.getOrNull(17)?.toIntOrNull() ?: -1, d(p, 18), d(p, 19), d(p, 20), d(p, 21), d(p, 22),
                )
            } catch (e: NumberFormatException) {
                null
            }
        }

        private fun d(p: List<String>, i: Int) = p.getOrNull(i)?.toDoubleOrNull() ?: Double.NaN
    }

    fun csv(): String = buildString {
        append("%.3f".format(t)).append(',')
        append("%.7f".format(lat)).append(',').append("%.7f".format(lon)).append(',')
        append("%.2f".format(x)).append(',').append("%.2f".format(y)).append(',')
        append("%.3f".format(speed)).append(',').append("%.1f".format(heading)).append(',')
        append(fmt(brake)).append(',').append(fmt(throttle)).append(',').append(fmt(rpm)).append(',')
        append(fmt(steer)).append(',')
        append("%.3f".format(latG)).append(',').append("%.3f".format(lonG)).append(',')
        append(sats).append(',').append(fix).append(',').append(utc).append(',')
        append(fmt(yaw)).append(',').append(gear).append(',')
        append(if (gearRatio.isNaN()) "NaN" else "%.3f".format(gearRatio)).append(',')
        append(fmt(wFL)).append(',').append(fmt(wFR)).append(',').append(fmt(wRL)).append(',').append(fmt(wRR))
    }

    private fun fmt(v: Double) = if (v.isNaN()) "NaN" else "%.2f".format(v)
}

/** Equirectangular projection around a fixed origin; plenty accurate over an autocross lot. */
/** The origin (lat0, lon0) of the [LocalProjection] that put a point at lat/lon to x/y. */
fun projectionOrigin(lat: Double, lon: Double, x: Double, y: Double): Pair<Double, Double> {
    val lat0 = lat - y / 111_320.0
    return lat0 to lon - x / (111_320.0 * cos(lat0 * PI / 180.0))
}

class LocalProjection(private val lat0: Double, private val lon0: Double) {
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(lat0 * PI / 180.0)
    fun x(lon: Double) = (lon - lon0) * mPerDegLon
    fun y(lat: Double) = (lat - lat0) * mPerDegLat
}

/** A timing line: center point, unit direction of travel, half width in meters. */
data class Gate(val x: Double, val y: Double, val hx: Double, val hy: Double, val halfWidth: Double = 15.0) {
    /** Fraction along a->b where the path crosses this gate in the direction of travel, or null. */
    fun crossing(ax: Double, ay: Double, bx: Double, by: Double): Double? {
        val s0 = (ax - x) * hx + (ay - y) * hy
        val s1 = (bx - x) * hx + (by - y) * hy
        if (!(s0 < 0 && s1 >= 0)) return null
        val f = -s0 / (s1 - s0)
        val px = ax + (bx - ax) * f
        val py = ay + (by - ay) * f
        val lateral = (px - x) * (-hy) + (py - y) * hx
        return if (kotlin.math.abs(lateral) <= halfWidth) f else null
    }
}

data class Run(
    val number: Int,
    val startIdx: Int,
    val endIdx: Int,
    val startT: Double,
    val endT: Double,
    val finished: Boolean,   // ended by crossing the finish gate (time is trustworthy)
    val splits: List<Double> = emptyList(), // elapsed time at each sector line, in course order
) {
    /** Time spent in each sector (splits + finish), or empty without sector lines. */
    val sectorTimes: List<Double>
        get() = if (splits.isEmpty()) emptyList() else (splits + time).let { all -> all.indices.map { all[it] - if (it == 0) 0.0 else all[it - 1] } }

    val time get() = endT - startT
}
