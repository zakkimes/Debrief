package com.kimes.daq.course

import com.kimes.daq.data.Gate
import com.kimes.daq.data.LocalProjection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class LineKind { START, SECTOR, FINISH, START_FINISH }
enum class CourseMode { AUTOX, LAPS }

/** A timing line. bearing = direction of travel across it (degrees, 0 = north). */
data class GeoLine(
    val kind: LineKind,
    val lat: Double,
    val lon: Double,
    val bearing: Double,
    val width: Double = 50.0,
) {
    fun gate(p: LocalProjection): Gate {
        val r = bearing * PI / 180
        return Gate(p.x(lon), p.y(lat), sin(r), cos(r), width / 2)
    }

    fun json(): JSONObject = JSONObject().put("kind", kind.name).put("lat", lat).put("lon", lon)
        .put("bearing", bearing).put("width", width)

    companion object {
        fun from(o: JSONObject) = GeoLine(
            LineKind.valueOf(o.getString("kind")), o.getDouble("lat"), o.getDouble("lon"),
            o.getDouble("bearing"), o.optDouble("width", 50.0),
        )
    }
}

/** A cone. passBearing points from the cone toward the side the car passes on (null = not set). */
data class Cone(val lat: Double, val lon: Double, val passBearing: Double? = null) {
    fun json(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).apply { passBearing?.let { put("pass", it) } }

    companion object {
        fun from(o: JSONObject) = Cone(o.getDouble("lat"), o.getDouble("lon"), if (o.has("pass")) o.getDouble("pass") else null)
    }
}

/** A corner the driver named on the course walk (placed in driving order). */
data class CornerMark(val lat: Double, val lon: Double, val name: String) {
    fun json(): JSONObject = JSONObject().put("lat", lat).put("lon", lon).put("name", name)

    companion object {
        fun from(o: JSONObject) = CornerMark(o.getDouble("lat"), o.getDouble("lon"), o.optString("name", "Corner"))
    }
}

data class CourseDef(
    val id: String,
    val name: String,
    val mode: CourseMode = CourseMode.AUTOX,
    val lines: List<GeoLine> = emptyList(),
    val cones: List<Cone> = emptyList(),
    val overlay: MapOverlay? = null,   // organizer's course map laid over the satellite view
    val corners: List<CornerMark> = emptyList(),
) {
    val start get() = lines.firstOrNull { it.kind == LineKind.START || it.kind == LineKind.START_FINISH }
    val finish get() = if (mode == CourseMode.LAPS) start else lines.firstOrNull { it.kind == LineKind.FINISH }
    val sectors get() = lines.filter { it.kind == LineKind.SECTOR }

    /** Line-based timing needs a start and a finish (or a start/finish line for laps). */
    val timed get() = start != null && finish != null

    /** Number of sectors a run is split into. */
    val sectorCount get() = sectors.size + 1

    /** Nothing placed and no course map: a course that was started and abandoned. */
    val isEmpty get() = lines.isEmpty() && cones.isEmpty() && corners.isEmpty() && overlay == null

    fun center(): Pair<Double, Double>? {
        val pts = lines.map { it.lat to it.lon } + cones.map { it.lat to it.lon }
        if (pts.isEmpty()) return null
        return pts.sumOf { it.first } / pts.size to pts.sumOf { it.second } / pts.size
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("mode", mode.name)
        .put("lines", JSONArray().apply { lines.forEach { put(it.json()) } })
        .put("cones", JSONArray().apply { cones.forEach { put(it.json()) } })
        .apply { overlay?.let { put("overlay", it.json()) } }
        .put("corners", JSONArray().apply { corners.forEach { put(it.json()) } })

    companion object {
        fun from(o: JSONObject): CourseDef {
            val ls = o.optJSONArray("lines") ?: JSONArray()
            val cs = o.optJSONArray("cones") ?: JSONArray()
            return CourseDef(
                o.getString("id"), o.optString("name", "Course"),
                runCatching { CourseMode.valueOf(o.optString("mode", "AUTOX")) }.getOrDefault(CourseMode.AUTOX),
                (0 until ls.length()).map { GeoLine.from(ls.getJSONObject(it)) },
                (0 until cs.length()).map { Cone.from(cs.getJSONObject(it)) },
                o.optJSONObject("overlay")?.let { runCatching { MapOverlay.from(it) }.getOrNull() },
                o.optJSONArray("corners")?.let { a -> (0 until a.length()).mapNotNull { runCatching { CornerMark.from(a.getJSONObject(it)) }.getOrNull() } } ?: emptyList(),
            )
        }

        /**
         * A course to send to another tablet: the course itself, with its map picture (if any) packed
         * inside, since the picture's file path means nothing on another device.
         */
        fun exportJson(c: CourseDef, overlayPng: ByteArray?): String {
            val o = c.json().put("format", "debrief-course").put("version", 1)
            if (overlayPng != null) o.put("overlayPng", java.util.Base64.getEncoder().encodeToString(overlayPng))
            else o.remove("overlay")
            return o.toString(2)
        }

        /**
         * Read a course another tablet exported. It gets a new [id] so it never replaces one of this
         * tablet's courses; [saveOverlay] stores the packed map picture and returns its file path.
         */
        fun importJson(text: String, id: String, saveOverlay: (ByteArray) -> String?): CourseDef? {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            if (!o.has("lines") && !o.has("cones")) return null
            o.put("id", id)
            val png = o.optString("overlayPng", "").takeIf { it.isNotEmpty() }?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
            val path = png?.let(saveOverlay)
            o.optJSONObject("overlay")?.let { ov -> if (path != null) ov.put("file", path) else o.remove("overlay") }
            return runCatching { from(o) }.getOrNull()
        }

        /** RaceChrono .rcz: a zip holding trackId.json with track.traps[] (coords in degrees x 6e6, bearing x 1000, width in mm). */
        fun fromRcz(input: InputStream, id: String): CourseDef? {
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.name.endsWith(".json")) continue
                    val o = runCatching { JSONObject(z.readBytes().toString(Charsets.UTF_8)) }.getOrNull() ?: continue
                    val t = o.optJSONObject("track") ?: continue
                    return fromRaceChronoTrack(t, id)
                }
            }
            return null
        }

        fun fromRaceChronoTrack(t: JSONObject, id: String): CourseDef {
            val traps = t.optJSONArray("traps") ?: JSONArray()
            val lines = (0 until traps.length()).map { traps.getJSONObject(it) }
                .sortedBy { it.optInt("orderValue", 0) }
                .map { tr ->
                    val kind = when (tr.optInt("type")) {
                        1 -> LineKind.START
                        2 -> LineKind.FINISH
                        0 -> LineKind.START_FINISH
                        else -> LineKind.SECTOR
                    }
                    GeoLine(
                        kind, tr.getLong("centerLatitude") / 6e6, tr.getLong("centerLongitude") / 6e6,
                        tr.optLong("bearing") / 1000.0, (tr.optLong("width", 50000) / 1000.0).coerceIn(5.0, 200.0),
                    )
                }
            val laps = lines.any { it.kind == LineKind.START_FINISH } && lines.none { it.kind == LineKind.FINISH }
            return CourseDef(id, t.optString("name", "Imported course"), if (laps) CourseMode.LAPS else CourseMode.AUTOX, lines)
        }
    }
}

/** Saved courses, one JSON file each. */
class CourseStore(private val dir: File) {
    init { dir.mkdirs() }

    fun all(): List<CourseDef> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".json") }
        .mapNotNull { f -> runCatching { CourseDef.from(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.id }

    fun get(id: String): CourseDef? = File(dir, "$id.json").takeIf { it.exists() }
        ?.let { runCatching { CourseDef.from(JSONObject(it.readText())) }.getOrNull() }

    fun save(c: CourseDef) = File(dir, "${c.id}.json").writeText(c.json().toString(2))

    fun delete(id: String) { File(dir, "$id.json").delete() }

    /** Removes courses that never had anything placed, except [keep] (the one in use). Returns how many. */
    fun dropEmpty(keep: String?): Int = all().filter { it.isEmpty && it.id != keep }.onEach { delete(it.id) }.size

    /** The saved course whose lines/cones are closest to a position, if within maxM. */
    fun nearest(lat: Double, lon: Double, maxM: Double = 1500.0): CourseDef? =
        all().mapNotNull { c -> c.center()?.let { c to distM(lat, lon, it.first, it.second) } }
            .filter { it.second <= maxM }.minByOrNull { it.second }?.first
}

fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val k = 111_320.0
    return hypot((lon2 - lon1) * k * cos(lat1 * PI / 180), (lat2 - lat1) * k)
}

/** Bearing (deg, 0 = north) from point 1 to point 2. */
fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dx = (lon2 - lon1) * cos(lat1 * PI / 180)
    val dy = lat2 - lat1
    return (atan2(dx, dy) * 180 / PI + 360) % 360
}
