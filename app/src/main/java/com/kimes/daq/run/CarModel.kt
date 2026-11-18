package com.kimes.daq.run

import com.kimes.daq.data.Sample
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Tire type: the biggest single factor in grip. [grip] is a typical flat-ground cornering limit, g. */
enum class TireType(val label: String, val example: String, val grip: Double) {
    ALL_SEASON("All-season", "most new-car tires", 0.85),
    SUMMER("Performance summer", "Pilot Sport 4S, Potenza Sport", 0.97),
    EXTREME("200-treadwear extreme summer", "RE-71RS / RZ, A052, RS4, Rival S", 1.12),
    R_COMP("R-compound (DOT race)", "A7, RA1, Hoosier SM7 / A7", 1.28),
    SLICK("Slicks", "", 1.45),
}

enum class Drive { RWD, FWD, AWD }

/** A car from the built-in list: published curb weight and power, approximate. */
data class CarSpec(val make: String, val model: String, val years: String, val weightLb: Int, val hp: Int, val drive: Drive) {
    val label get() = "$make $model ($years)"
}

/** What the driver told us about the car and its tires. */
data class CarProfile(
    val label: String = "",
    val weightLb: Int = 0,
    val hp: Int = 0,
    val drive: Drive = Drive.RWD,
    val tire: TireType = TireType.SUMMER,
    val tireName: String = "",
    val tireSize: String = "",
    val wheelSize: String = "",
) {
    val complete get() = weightLb > 500 && hp > 30

    /** A first guess at the car before it has run on course: tires set grip and braking, power-to-weight sets acceleration. */
    fun toCar(): OptimalLine.Car {
        if (!complete) return OptimalLine.Car()
        val kg = weightLb * 0.4536 + 80 // with the driver
        val traction = when (drive) { Drive.RWD -> 0.45; Drive.FWD -> 0.40; Drive.AWD -> 0.65 } * (tire.grip / 1.0)
        return OptimalLine.Car(
            latG = tire.grip, brakeG = tire.grip, accelG = traction.coerceAtMost(tire.grip),
            powerPerKg = hp * 745.7 * 0.85 / kg, // about 15% lost on the way to the wheels
            source = "your car profile",
        )
    }

    fun json(): JSONObject = JSONObject().put("label", label).put("weightLb", weightLb).put("hp", hp).put("drive", drive.name)
        .put("tire", tire.name).put("tireName", tireName).put("tireSize", tireSize).put("wheelSize", wheelSize)

    companion object {
        fun from(o: JSONObject) = CarProfile(
            o.optString("label"), o.optInt("weightLb"), o.optInt("hp"),
            runCatching { Drive.valueOf(o.optString("drive")) }.getOrDefault(Drive.RWD),
            runCatching { TireType.valueOf(o.optString("tire")) }.getOrDefault(TireType.SUMMER),
            o.optString("tireName"), o.optString("tireSize"), o.optString("wheelSize"),
        )
    }
}

object CarModel {
    private const val G = 9.81

    fun carJson(c: OptimalLine.Car): JSONObject = JSONObject().put("latG", c.latG).put("brakeG", c.brakeG).put("accelG", c.accelG)
        .put("powerPerKg", c.powerPerKg).put("source", c.source)

    fun carFrom(o: JSONObject) = OptimalLine.Car(
        latG = o.getDouble("latG"), brakeG = o.getDouble("brakeG"), accelG = o.getDouble("accelG"),
        powerPerKg = o.optDouble("powerPerKg", 0.0), source = o.optString("source", "earlier runs"),
    )

    private fun pct(v: List<Double>, p: Double) = v.sorted().let { if (it.isEmpty()) Double.NaN else it[((it.size - 1) * p).toInt()] }

    /**
     * The car as it really goes, measured from timed runs: braking and acceleration from the GPS
     * speed, power from the best acceleration at speed. Grip is then set so the model, driven along
     * the quickest run's own path, matches that run's time. That makes it the grip this driver
     * actually used, so predictions are times they can reach, not a robot's.
     * [runs]: each run's samples; [bestPath]: the quickest run's path in meters, and [bestTime] its time.
     */
    fun fromRuns(runs: List<List<Sample>>, bestPath: Pair<DoubleArray, DoubleArray>, bestTime: Double, prior: OptimalLine.Car): OptimalLine.Car {
        val acc = ArrayList<Double>(); val accLow = ArrayList<Double>(); val pw = ArrayList<Double>()
        for (ss in runs) {
            // Speed change over about 0.4 s: the GPS speed is clean, its derivative sample to sample isn't
            var j = 0
            for (i in ss.indices) {
                while (j < ss.size - 1 && ss[j].t - ss[i].t < 0.4) j++
                val dt = ss[j].t - ss[i].t
                if (dt < 0.3 || dt > 0.6) continue
                val v = (ss[i].speed + ss[j].speed) / 2
                if (v < 3) continue
                val a = (ss[j].speed - ss[i].speed) / dt
                acc += a
                if (v < 10) accLow += a
                if (v > 12) pw += (a + prior.drag * v * v) * v
            }
        }
        if (acc.size < 200) return prior
        val brake = (-pct(acc, 0.005) / G).coerceIn(0.5, 1.6)
        val traction = (pct(accLow, 0.98) / G).takeIf { !it.isNaN() }?.coerceIn(0.15, 0.9) ?: prior.accelG
        val power = pct(pw, 0.97).takeIf { !it.isNaN() }?.coerceIn(15.0, 500.0) ?: prior.powerPerKg
        var car = prior.copy(brakeG = brake, accelG = traction, powerPerKg = power, source = "your runs")
        // Grip: the model on the quickest run's path should take as long as that run did
        var lo = 0.5; var hi = 2.0
        repeat(30) {
            val mid = (lo + hi) / 2
            val t = OptimalLine.evaluate(bestPath.first, bestPath.second, car.copy(latG = mid)).time
            if (t > bestTime) lo = mid else hi = mid
        }
        car = car.copy(latG = (lo + hi) / 2)
        return car
    }

    /** Built-in cars: published curb weight (lb) and power (hp), rounded. Edit the numbers to match yours. */
    val cars: List<CarSpec> = listOf(
        CarSpec("Mazda", "MX-5 Miata NA 1.6", "1990-93", 2150, 116, Drive.RWD),
        CarSpec("Mazda", "MX-5 Miata NA 1.8", "1994-97", 2290, 133, Drive.RWD),
        CarSpec("Mazda", "MX-5 Miata NB", "1999-2005", 2350, 142, Drive.RWD),
        CarSpec("Mazda", "MX-5 Miata NC", "2006-15", 2480, 167, Drive.RWD),
        CarSpec("Mazda", "MX-5 Miata ND1", "2016-18", 2330, 155, Drive.RWD),
        CarSpec("Mazda", "MX-5 Miata ND2", "2019-23", 2340, 181, Drive.RWD),
        CarSpec("Mazda", "MX-5 Miata ND3", "2024-", 2340, 181, Drive.RWD),
        CarSpec("Mazda", "MX-5 Miata RF", "2019-", 2450, 181, Drive.RWD),
        CarSpec("Fiat", "124 Spider Abarth", "2017-20", 2480, 164, Drive.RWD),
        CarSpec("Toyota", "GR86", "2022-", 2810, 228, Drive.RWD),
        CarSpec("Subaru", "BRZ", "2022-", 2815, 228, Drive.RWD),
        CarSpec("Toyota", "86 / Scion FR-S", "2013-20", 2760, 205, Drive.RWD),
        CarSpec("Subaru", "BRZ", "2013-20", 2780, 205, Drive.RWD),
        CarSpec("Toyota", "GR Corolla", "2023-", 3260, 300, Drive.AWD),
        CarSpec("Toyota", "GR Supra 2.0", "2021-", 3180, 255, Drive.RWD),
        CarSpec("Toyota", "GR Supra 3.0", "2020-", 3400, 382, Drive.RWD),
        CarSpec("Honda", "S2000 AP1", "2000-03", 2810, 240, Drive.RWD),
        CarSpec("Honda", "S2000 AP2", "2004-09", 2860, 237, Drive.RWD),
        CarSpec("Honda", "Civic Si", "2017-20", 2890, 205, Drive.FWD),
        CarSpec("Honda", "Civic Si", "2022-", 2950, 200, Drive.FWD),
        CarSpec("Honda", "Civic Type R FK8", "2017-21", 3120, 306, Drive.FWD),
        CarSpec("Honda", "Civic Type R FL5", "2023-", 3190, 315, Drive.FWD),
        CarSpec("Honda", "Fit", "2015-20", 2520, 130, Drive.FWD),
        CarSpec("Acura", "Integra Type S", "2024-", 3220, 320, Drive.FWD),
        CarSpec("Volkswagen", "Golf GTI Mk7", "2015-21", 3030, 220, Drive.FWD),
        CarSpec("Volkswagen", "Golf GTI Mk8", "2022-", 3210, 241, Drive.FWD),
        CarSpec("Volkswagen", "Golf R Mk7", "2015-19", 3340, 292, Drive.AWD),
        CarSpec("Volkswagen", "Golf R Mk8", "2022-", 3430, 315, Drive.AWD),
        CarSpec("Hyundai", "Veloster N", "2019-22", 3110, 275, Drive.FWD),
        CarSpec("Hyundai", "Elantra N", "2022-", 3190, 276, Drive.FWD),
        CarSpec("Mini", "Cooper S (F56)", "2014-", 2830, 189, Drive.FWD),
        CarSpec("Mini", "JCW (F56)", "2015-", 2900, 228, Drive.FWD),
        CarSpec("Ford", "Fiesta ST", "2014-19", 2720, 197, Drive.FWD),
        CarSpec("Ford", "Focus ST", "2013-18", 3220, 252, Drive.FWD),
        CarSpec("Ford", "Focus RS", "2016-18", 3460, 350, Drive.AWD),
        CarSpec("Ford", "Mustang EcoBoost", "2015-23", 3530, 310, Drive.RWD),
        CarSpec("Ford", "Mustang GT (S550)", "2018-23", 3710, 460, Drive.RWD),
        CarSpec("Ford", "Mustang GT (S650)", "2024-", 3830, 480, Drive.RWD),
        CarSpec("Chevrolet", "Corvette C5", "1997-2004", 3250, 345, Drive.RWD),
        CarSpec("Chevrolet", "Corvette C6", "2005-13", 3180, 400, Drive.RWD),
        CarSpec("Chevrolet", "Corvette C7 Stingray", "2014-19", 3300, 455, Drive.RWD),
        CarSpec("Chevrolet", "Corvette C8 Stingray", "2020-", 3540, 490, Drive.RWD),
        CarSpec("Chevrolet", "Camaro 1LE 2.0T / V6", "2016-24", 3350, 275, Drive.RWD),
        CarSpec("Chevrolet", "Camaro SS", "2016-24", 3690, 455, Drive.RWD),
        CarSpec("Subaru", "WRX", "2015-21", 3270, 268, Drive.AWD),
        CarSpec("Subaru", "WRX", "2022-", 3320, 271, Drive.AWD),
        CarSpec("Subaru", "WRX STI", "2015-21", 3390, 305, Drive.AWD),
        CarSpec("Nissan", "350Z", "2003-08", 3190, 300, Drive.RWD),
        CarSpec("Nissan", "370Z", "2009-20", 3230, 332, Drive.RWD),
        CarSpec("Nissan", "Z", "2023-", 3490, 400, Drive.RWD),
        CarSpec("Porsche", "Boxster 986", "1997-2004", 2780, 217, Drive.RWD),
        CarSpec("Porsche", "Cayman S 987", "2006-12", 2980, 295, Drive.RWD),
        CarSpec("Porsche", "Cayman 981", "2013-16", 2890, 275, Drive.RWD),
        CarSpec("Porsche", "718 Cayman", "2017-", 2970, 300, Drive.RWD),
        CarSpec("BMW", "325i E30", "1987-91", 2700, 168, Drive.RWD),
        CarSpec("BMW", "M3 E36", "1995-99", 3180, 240, Drive.RWD),
        CarSpec("BMW", "M3 E46", "2001-06", 3420, 333, Drive.RWD),
        CarSpec("BMW", "Z4 3.0 E85", "2003-08", 2930, 225, Drive.RWD),
        CarSpec("BMW", "M2 F87", "2016-20", 3450, 365, Drive.RWD),
        CarSpec("Lotus", "Elise S2", "2005-11", 1980, 190, Drive.RWD),
        CarSpec("Lotus", "Exige S", "2006-11", 2080, 257, Drive.RWD),
        CarSpec("Tesla", "Model 3 Performance", "2018-23", 4050, 455, Drive.AWD),
    )
}
