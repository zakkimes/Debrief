package com.kimes.daq.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Extracts a numeric channel from a raw CAN frame: bytes [start, start+len), optional mask/shift,
 * then value * scale + offset.
 *
 * header == 0: [id] is a broadcast CAN id. Otherwise the channel is polled: [header] is the
 * module's diagnostic address and [id] the 0x22 data identifier; bytes start after "62 id id".
 */
data class CanDecoder(
    val name: String,
    val id: Int,
    val header: Int = 0,
    val start: Int,
    val len: Int,
    val bigEndian: Boolean = true,
    val signed: Boolean = false,
    val mask: Long = -1L,
    val shift: Int = 0,
    val scale: Double = 1.0,
    val offset: Double = 0.0,
) {
    fun decode(data: ByteArray): Double? {
        if (start + len > data.size) return null
        var raw = 0L
        for (i in 0 until len) {
            val b = data[if (bigEndian) start + i else start + len - 1 - i].toLong() and 0xFF
            raw = (raw shl 8) or b
        }
        raw = (raw ushr shift) and mask
        if (signed) {
            val bits = len * 8 - shift
            if (bits in 1..63 && raw and (1L shl (bits - 1)) != 0L) raw -= (1L shl bits)
        }
        return raw * scale + offset
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("id", Integer.toHexString(id).uppercase()).put("header", Integer.toHexString(header).uppercase())
        .put("start", start).put("len", len).put("bigEndian", bigEndian).put("signed", signed)
        .put("mask", java.lang.Long.toHexString(mask)).put("shift", shift)
        .put("scale", scale).put("offset", offset)

    companion object {
        fun fromJson(o: JSONObject) = CanDecoder(
            name = o.getString("name"),
            id = o.getString("id").toInt(16),
            header = o.optString("header", "0").toInt(16),
            start = o.getInt("start"),
            len = o.getInt("len"),
            bigEndian = o.optBoolean("bigEndian", true),
            signed = o.optBoolean("signed", false),
            mask = java.lang.Long.parseUnsignedLong(o.optString("mask", "ffffffffffffffff"), 16),
            shift = o.optInt("shift", 0),
            scale = o.optDouble("scale", 1.0),
            offset = o.optDouble("offset", 0.0),
        )
    }
}

object CanDecoders {
    /**
     * 2024+ ND3: the OBD port is diagnostic-only, so brake comes from the DSC module (0x760),
     * data id 0x2B0D, signed 16-bit / 2.3 (community-verified, github.com/shifty35/2024-nd3-mazda-obdii).
     * Pedal and RPM come from standard OBD-II PIDs. The guided DSC test can replace the brake id.
     */
    val defaults = listOf(
        CanDecoder("brake", 0x2B0D, header = 0x760, start = 0, len = 2, signed = true, scale = 1 / 2.3),
    )

    /**
     * Found on this car with the guided tests (2026-11-14): the four wheel speeds from the DSC,
     * 1 km/h per step (left front 2B06, right front 2B07, left rear 2B08, right rear 2B09; the slow
     * left and right circles confirmed the corners), and the clutch pedal switch from the engine
     * computer (1E04 byte 2, top bit = pressed). Always polled; added to older saved setups.
     */
    val known = listOf(
        CanDecoder("wFL", 0x2B06, header = 0x760, start = 0, len = 1, scale = 1 / 3.6),
        CanDecoder("wFR", 0x2B07, header = 0x760, start = 0, len = 1, scale = 1 / 3.6),
        CanDecoder("wRL", 0x2B08, header = 0x760, start = 0, len = 1, scale = 1 / 3.6),
        CanDecoder("wRR", 0x2B09, header = 0x760, start = 0, len = 1, scale = 1 / 3.6),
        CanDecoder("clutch", 0x1E04, header = 0x7E0, start = 2, len = 1, shift = 7, mask = 1),
    )

    /**
     * Polled slowly in the background, never shown live. Tire pressure and temperature from the
     * instrument cluster (0x720, 2A05-2A08 / 2A0A-2A0D; kPa = A x 1.373, degC = A - 50) are from
     * the community and did NOT answer on this car; the module scan can replace them. Engine oil
     * temperature from the PCM (1310, degC = AB/100 - 40) works. Units here: psi and degF.
     * (DSC 2033, once listed as steering angle, turned out to be a yaw rate on the ND3.)
     */
    val slow = (1..4).map { CanDecoder("tire$it", 0x2A04 + it, header = 0x720, start = 0, len = 1, scale = 1.373 * 0.1450377) } +
        (1..4).map { CanDecoder("tiretemp$it", 0x2A09 + it, header = 0x720, start = 0, len = 1, scale = 1.8, offset = -58.0) } +
        CanDecoder("oiltemp", 0x1310, header = 0x7E0, start = 0, len = 2, scale = 0.018, offset = -40.0) +
        // Outside air as the dash shows it: body module DD05, degC = A - 40 (read 42.8°F with 42°F on the dash)
        CanDecoder("ambient", 0xDD05, header = 0x726, start = 0, len = 1, scale = 1.8, offset = -40.0)

    fun load(file: File): List<CanDecoder> {
        if (!file.exists()) {
            save(file, defaults + known)
            return defaults + known
        }
        return try {
            val arr = JSONArray(file.readText())
            val list = (0 until arr.length()).map { CanDecoder.fromJson(arr.getJSONObject(it)) }
            // Files from the first build only held ND1/ND2 broadcast guesses that the ND3 can't see
            if (list.none { it.header != 0 }) (defaults + known).also { save(file, it) }
            else (list.filter { it.name != "steer" || it.id != 0x2033 } + known.filter { k -> list.none { it.name == k.name } }).also { if (it != list) save(file, it) }
        } catch (e: Exception) {
            defaults + known
        }
    }

    /** A saved list as-is, with none of [load]'s defaults. */
    fun read(file: File): List<CanDecoder> = runCatching {
        val arr = JSONArray(file.readText()); (0 until arr.length()).map { CanDecoder.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(file: File, list: List<CanDecoder>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString(2))
    }
}
