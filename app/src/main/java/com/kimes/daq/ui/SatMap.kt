package com.kimes.daq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.geo.Merc
import com.kimes.daq.geo.Tiles
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Screen <-> lat/lon for one frame of the map. */
class MapProj(val cLat: Double, val cLon: Double, val zoom: Double, val w: Float, val h: Float) {
    private val scale = 2.0.pow(zoom)
    private val cx = Merc.x(cLon) * scale
    private val cy = Merc.y(cLat) * scale
    fun pt(lat: Double, lon: Double) = Offset((Merc.x(lon) * scale - cx + w / 2).toFloat(), (Merc.y(lat) * scale - cy + h / 2).toFloat())
    fun lat(o: Offset) = Merc.lat((o.y - h / 2 + cy) / scale)
    fun lon(o: Offset) = Merc.lon((o.x - w / 2 + cx) / scale)
    /** Screen pixels per meter here. */
    val pxPerM get() = (scale / Merc.mPerPx0(cLat)).toFloat()
}

@Stable
class MapState(lat: Double, lon: Double, zoom: Double) {
    var lat by mutableDoubleStateOf(lat)
    var lon by mutableDoubleStateOf(lon)
    var zoom by mutableDoubleStateOf(zoom)
    var size by mutableStateOf(IntSize.Zero)
    var fitted = false
    /** Keep the map centered on you as fixes arrive; a one-finger pan turns it off. */
    var follow by mutableStateOf(false)

    fun proj() = MapProj(lat, lon, zoom, size.width.toFloat(), size.height.toFloat())

    /**
     * Pan just enough that [lat]/[lon] sits inside the middle of the map ([margin] of each side
     * kept clear), so a zoomed-in map follows the car as a chart is scrubbed. No-op when it already does.
     */
    fun keepInView(lat: Double, lon: Double, margin: Float = 0.22f) {
        if (size.width == 0) return
        val pr = proj(); val p = pr.pt(lat, lon)
        // Zoomed in: keep the car in the middle part. At the fitted zoom: only when the car is off the
        // map altogether (panned away by a stray finger), so a whole-run view never drifts by itself.
        val zoomed = zoom >= fitZoom + 0.25
        if (!zoomed && p.x in 0f..pr.w && p.y in 0f..pr.h) return
        // Zoomed in: keep the car in the middle of the map
        if (zoomed) { this.lat = lat; this.lon = lon; return }
        val mx = pr.w * margin; val my = pr.h * margin
        val dx = when { p.x < mx -> p.x - mx; p.x > pr.w - mx -> p.x - (pr.w - mx); else -> 0f }
        val dy = when { p.y < my -> p.y - my; p.y > pr.h - my -> p.y - (pr.h - my); else -> 0f }
        if (dx == 0f && dy == 0f) return
        val c = Offset(pr.w / 2 + dx, pr.h / 2 + dy)
        this.lat = pr.lat(c); this.lon = pr.lon(c)
    }

    /** The zoom [fit] last chose. */
    var fitZoom = 99.0

    /** Center and zoom so all points fit, with a margin, and [bottomPx] more kept clear at the bottom (for a legend). */
    fun fit(pts: List<Pair<Double, Double>>, marginPx: Float = 40f, bottomPx: Float = 0f) {
        if (pts.isEmpty() || size.width == 0) return
        val xs = pts.map { Merc.x(it.second) }; val ys = pts.map { Merc.y(it.first) }
        val wx = max(xs.max() - xs.min(), 1e-7); val wy = max(ys.max() - ys.min(), 1e-7)
        val z = min(ln((size.width - 2 * marginPx) / wx) / ln(2.0), ln((size.height - 2 * marginPx - bottomPx) / wy) / ln(2.0))
        zoom = z.coerceIn(3.0, 21.0)
        fitZoom = zoom
        lon = Merc.lon((xs.max() + xs.min()) / 2)
        // Shift the center down by half the reserved strip, so the points sit above it
        lat = Merc.lat((ys.max() + ys.min()) / 2 + bottomPx / 2 / 2.0.pow(zoom))
        fitted = true
    }
}

@Composable
fun rememberMapState(lat: Double = 33.5, lon: Double = -86.8, zoom: Double = 17.0) = remember { MapState(lat, lon, zoom) }

/**
 * Satellite map. One finger pans (or, when [onDrag] claims the touch, drags an item),
 * two fingers pan and zoom, a tap calls [onTap].
 */
@Composable
fun SatMap(
    state: MapState,
    modifier: Modifier = Modifier,
    onTap: ((lat: Double, lon: Double, at: Offset) -> Unit)? = null,
    onDrag: ((phase: Int, lat: Double, lon: Double, at: Offset) -> Boolean)? = null, // phase 0 start (return true to claim), 1 move, 2 end
    controls: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    overlay: DrawScope.(MapProj) -> Unit = {},
) {
    val v by Tiles.version.collectAsState()
    Box(modifier.clipToBounds().background(Color(0xFF1B1F1A))) {
        Canvas(
            Modifier.matchParentSize()
                .pointerInput(onTap, onDrag) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p0 = state.proj()
                        var claimed = onDrag?.invoke(0, p0.lat(down.position), p0.lon(down.position), down.position) == true
                        var moved = false
                        var last = down.position
                        var lastSpan = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (claimed) { onDrag?.invoke(2, 0.0, 0.0, last); claimed = false }
                                val c = Offset(pressed.map { it.position.x }.average().toFloat(), pressed.map { it.position.y }.average().toFloat())
                                val span = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                                if (lastSpan > 0f && span > 0f) {
                                    // The ground point that was under the fingers stays under them
                                    val pr = state.proj()
                                    val aLat = pr.lat(last); val aLon = pr.lon(last)
                                    state.zoom = (state.zoom + ln((span / lastSpan).toDouble()) / ln(2.0)).coerceIn(3.0, 21.0)
                                    val p2 = state.proj()
                                    val newCenter = Offset(p2.w / 2, p2.h / 2) + (p2.pt(aLat, aLon) - c)
                                    state.lat = p2.lat(newCenter); state.lon = p2.lon(newCenter)
                                }
                                lastSpan = span; last = c; moved = true
                            } else {
                                val pos = pressed[0].position
                                lastSpan = 0f
                                if (!moved && (pos - down.position).getDistance() < viewConfiguration.touchSlop) continue
                                moved = true
                                if (claimed) {
                                    val pr = state.proj()
                                    onDrag?.invoke(1, pr.lat(pos), pr.lon(pos), pos)
                                } else {
                                    state.follow = false
                                    val pr = state.proj()
                                    val newCenter = Offset(pr.w / 2, pr.h / 2) - (pos - last)
                                    state.lat = pr.lat(newCenter); state.lon = pr.lon(newCenter)
                                }
                                last = pos
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                        if (claimed) onDrag?.invoke(2, 0.0, 0.0, last)
                        if (!moved) { val pr = state.proj(); onTap?.invoke(pr.lat(down.position), pr.lon(down.position), down.position) }
                    }
                }
        ) {
            if (state.size != IntSize(size.width.toInt(), size.height.toInt())) state.size = IntSize(size.width.toInt(), size.height.toInt())
            v.hashCode()
            val pr = state.proj()
            drawTiles(pr)
            overlay(pr)
        }
        controls()
        Text(
            Tiles.ATTRIBUTION, fontSize = 9.sp, color = Color(0xCCFFFFFF),
            modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x66000000)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private fun DrawScope.drawTiles(pr: MapProj) {
    val z = floor(pr.zoom).toInt().coerceIn(0, Tiles.MAX_Z)
    val tileScale = 2.0.pow(pr.zoom - z) // screen px per tile px
    val tilePx = 256 * tileScale
    val n = 1 shl z
    val topLeftX = Merc.x(pr.lon(Offset.Zero)) * n / 256
    val topLeftY = Merc.y(pr.lat(Offset.Zero)) * n / 256
    val tx0 = floor(topLeftX).toInt(); val ty0 = floor(topLeftY).toInt()
    val cols = (size.width / tilePx).toInt() + 2; val rows = (size.height / tilePx).toInt() + 2
    for (dx in 0..cols) for (dy in 0..rows) {
        val tx = tx0 + dx; val ty = ty0 + dy
        if (ty < 0 || ty >= n) continue
        val x = ((tx - topLeftX) * tilePx).toFloat(); val y = ((ty - topLeftY) * tilePx).toFloat()
        val img = Tiles.get(z, ((tx % n) + n) % n, ty)
        if (img != null) {
            drawImage(img, dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize((tilePx + 1).toInt(), (tilePx + 1).toInt()))
        } else {
            // Fall back to a lower-zoom parent tile, scaled up
            var pz = z - 1; var drawn = false
            while (pz >= z - 4 && pz >= 0 && !drawn) {
                val k = 1 shl (z - pz)
                val ptx = Math.floorDiv(tx, k); val pty = Math.floorDiv(ty, k)
                val pimg = Tiles.get(pz, ptx, pty, fetch = false)
                if (pimg != null) {
                    val sub = 256 / k
                    drawImage(
                        pimg, srcOffset = IntOffset(Math.floorMod(tx, k) * sub, Math.floorMod(ty, k) * sub), srcSize = IntSize(sub, sub),
                        dstOffset = IntOffset(x.toInt(), y.toInt()), dstSize = IntSize((tilePx + 1).toInt(), (tilePx + 1).toInt()),
                    )
                    drawn = true
                }
                pz--
            }
        }
    }
}

/** Small button that sits on top of the map. */
@Composable
fun MapButton(text: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.background(if (on) Cyan else Color.White).border(3.dp, Ink).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), color = if (on) Color.White else Cyan, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = 0.6.sp) }
}
