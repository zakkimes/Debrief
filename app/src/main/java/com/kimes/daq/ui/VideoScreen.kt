package com.kimes.daq.ui

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kimes.daq.data.Sample
import com.kimes.daq.gopro.VideoLink
import kotlinx.coroutines.delay

/** A place in the run to jump to: a label and the run sample index where it starts. */
class JumpPoint(val label: String, val idx: Int)

/**
 * The GoPro video for one run, with the map, brake and throttle following along.
 * The video is the clock: its position maps to GPS time, which finds the matching data sample.
 */
@Composable
fun RunVideo(link: VideoLink, runNumber: Int, runS: List<Sample>, jumps: List<JumpPoint>, back: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    var posMs by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val runStartUtc = runS.firstOrNull { it.utc > 0 }?.utc ?: 0L
    fun seekUtc(utc: Long) {
        val t = link.videoTime(utc) ?: return
        val ms = (t * 1000).toInt()
        player?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) ?: view?.seekTo(ms)
        posMs = ms
    }
    // Follow the video
    LaunchedEffect(view) {
        while (true) { view?.let { if (it.isPlaying) posMs = it.currentPosition }; delay(50) }
    }
    LaunchedEffect(player, videoSound) { runCatching { player?.let { val v = if (videoSound) 1f else 0f; it.setVolume(v, v) } } }
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }

    val utcNow = link.startUtc + posMs
    val i = nearestByUtc(runS, utcNow)
    val s = i?.let { runS[it] }
    val bMax = remember(runS) { runS.maxOf { if (it.brake.isNaN()) 0.0 else it.brake }.coerceAtLeast(1.0) }
    val map = rememberMapState()
    LaunchedEffect(map.size) { if (map.size.width > 0) map.fit(runS.map { it.lat to it.lon }, 30f) }

    Panes(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.share(1.7f, 1.1f)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                AndroidView(factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnPreparedListener { mp -> player = mp; seekUtc(runStartUtc) }
                        setOnErrorListener { _, _, _ -> error = "This tablet can't play this video. Try the GoPro's lower-resolution .LRV copy or record at 1080p."; true }
                        setVideoURI(Uri.parse(link.uri))
                        view = this
                    }
                }, modifier = Modifier.fillMaxSize())
                error?.let { Text(it, color = Amber, fontSize = 15.sp, modifier = Modifier.background(Color.Black).padding(12.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Primary(if (playing) "Pause" else "Play", Modifier.width(110.dp), height = 48) {
                    view?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } }
                }
                Secondary("−5 s", Modifier.width(70.dp), height = 48) { seekUtc(utcNow - 5000) }
                Secondary("+5 s", Modifier.width(70.dp), height = 48) { seekUtc(utcNow + 5000) }
                Secondary("Run start", Modifier.width(110.dp), height = 48) { seekUtc(runStartUtc) }
                Text(link.note, color = Lo, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
            }
        }
        Column(Modifier.side(260.dp, 1f).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SatMap(map, Modifier.fillMaxWidth().height(170.dp)) { pr ->
                val p = Path()
                runS.forEachIndexed { k, q -> val o = pr.pt(q.lat, q.lon); if (k == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
                drawPath(p, Hi.copy(alpha = 0.7f), style = Stroke(3f))
                s?.let { val o = pr.pt(it.lat, it.lon); drawCircle(Color.Black, 9f, o); drawCircle(Yellow, 7f, o) }
            }
            if (s != null) {
                KV("Run time", "%.2f s".format(s.t - runS.first().t))
                KV("Speed", "%.0f mph".format(s.speed * MS_TO_MPH))
                KV("Brake", if (s.brake.isNaN()) "no signal" else "%.0f%%".format(s.brake / bMax * 100), if (s.brake.isNaN()) Dim else Red)
                KV("Throttle", if (s.throttle.isNaN()) "no signal" else "%.0f%%".format(s.throttle), if (s.throttle.isNaN()) Dim else Green, last = true)
            } else Text("The video is outside this run.", color = Dim, fontSize = 14.sp)
            if (jumps.isNotEmpty()) {
                Label("Jump to", Modifier.padding(top = 6.dp))
                jumps.forEach { j -> NavRow(j.label) { runS.getOrNull(j.idx)?.utc?.takeIf { it > 0 }?.let { seekUtc(it - 2000) } } }
            }
        }
    }
}

private fun nearestByUtc(ss: List<Sample>, utc: Long): Int? {
    if (ss.isEmpty() || ss.first().utc <= 0) return null
    if (utc < ss.first().utc - 1000 || utc > ss.last().utc + 1000) return null
    var lo = 0; var hi = ss.size - 1
    while (hi - lo > 1) { val m = (lo + hi) / 2; if (ss[m].utc <= utc) lo = m else hi = m }
    return if (utc - ss[lo].utc <= ss[hi].utc - utc) lo else hi
}

/**
 * A small player inside Review: starts at [fromUtc] and, when [toUtc] is given, loops back there
 * (a corner on repeat). Tap to play or pause. [atUtc] reports the GPS time on screen.
 * [seekUtc]: a GPS time to show (a chart being scrubbed); the video pauses on that exact frame.
 */
/** Video sound on or off, the same for every clip until changed (on: the GoPro's audio). */
private var videoSound by androidx.compose.runtime.mutableStateOf(true)

@Composable
fun InlineVideo(link: VideoLink, fromUtc: Long, toUtc: Long?, modifier: Modifier, seekUtc: Long? = null, onFullScreen: (() -> Unit)? = null, atUtc: (Long) -> Unit = {}) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    // The video's own shape, so there are no black bars (GoPro previews are 16:9 or 4:3 by lens mode)
    var aspect by remember(link.uri) { mutableStateOf(16f / 9f) }
    var playing by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    fun applySpeed(mp: MediaPlayer?) { runCatching { mp?.let { it.playbackParams = it.playbackParams.setSpeed(speed) } } }
    var error by remember { mutableStateOf<String?>(null) }
    val fromMs = ((link.videoTime(fromUtc) ?: 0.0) * 1000).toInt()
    val toMs = toUtc?.let { u -> link.videoTime(u)?.let { (it * 1000).toInt() } }
    LaunchedEffect(view, fromMs, toMs) {
        val v = view ?: return@LaunchedEffect
        v.seekTo(fromMs)
        // The player reports its position in coarse steps; run a smooth clock between reports
        // (eased toward each new report), so the car on the map glides instead of jumping
        var anchorPos = -1.0; var anchorWall = 0L; var lastMeas = -1
        while (true) {
            androidx.compose.runtime.withFrameMillis { }
            if (v.isPlaying) {
                val now = android.os.SystemClock.uptimeMillis()
                val meas = v.currentPosition
                if (toMs != null && meas >= toMs) { v.seekTo(fromMs); anchorPos = -1.0; continue }
                if (anchorPos < 0) { anchorPos = meas.toDouble(); anchorWall = now; lastMeas = meas }
                var est = anchorPos + (now - anchorWall) * speed
                if (meas != lastMeas) {
                    lastMeas = meas
                    val err = meas - est
                    if (kotlin.math.abs(err) > 500) { anchorPos = meas.toDouble(); anchorWall = now; est = anchorPos }
                    else anchorPos += err * 0.2
                }
                atUtc(link.startUtc + est.toLong())
            } else { anchorPos = -1.0; delay(80) }
        }
    }
    // Follow the chart: the frame at the same moment on track (frame-exact, not the nearest keyframe)
    val seekMs = seekUtc?.let { u -> link.videoTime(u)?.let { (it * 1000).toInt() } }
    LaunchedEffect(player, seekMs) {
        val mp = player ?: return@LaunchedEffect
        val ms = seekMs ?: return@LaunchedEffect
        runCatching {
            view?.let { if (it.isPlaying) { it.pause(); playing = false } }
            mp.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
        }
    }
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }
    Box(modifier.aspectRatio(aspect).background(Color.Black).clickable {
        view?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true; started = true; applySpeed(player) } }
    }, contentAlignment = Alignment.Center) {
        AndroidView(factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    player = mp
                    if (mp.videoWidth > 0 && mp.videoHeight > 0) aspect = mp.videoWidth.toFloat() / mp.videoHeight
                    seekTo(fromMs)
                }
                setOnErrorListener { _, _, _ -> error = "This tablet can't play this video."; true }
                setVideoURI(Uri.parse(link.uri))
                view = this
            }
        }, modifier = Modifier.fillMaxSize())
        // While a chart drives the video, keep the label out of the middle of the frame
        if (!playing) Text(if (seekMs != null) "▶  Tap to play from here" else if (toMs != null) "▶  Tap to play this corner" else "▶  Tap to play", color = Color.White, fontSize = if (seekMs != null) 13.sp else 16.sp,
            modifier = Modifier.align(if (seekMs != null) Alignment.BottomCenter else Alignment.Center).padding(bottom = if (seekMs != null) 6.dp else 0.dp)
                .background(Color(0xA6000000)).padding(horizontal = 12.dp, vertical = 6.dp))
        // Full screen, with the course and a chart over the video
        if (onFullScreen != null) Text("⛶", color = Color.White, fontSize = 20.sp,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xA6000000)).clickable { onFullScreen() }.padding(horizontal = 10.dp, vertical = 2.dp))
        // Slow it down or speed it up, once it has been played
        Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Sound on or off
            Text(if (videoSound) "🔊" else "🔇", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.background(Color(0xA6000000)).clickable { videoSound = !videoSound }.padding(horizontal = 8.dp, vertical = 4.dp))
            if (started) for (x in listOf(0.5f, 1f, 2f)) Text(if (x == 1f) "1x" else if (x == 0.5f) "0.5x" else "2x",
                color = if (speed == x) Color.Black else Color.White, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.background(if (speed == x) Color.White else Color(0xA6000000)).clickable {
                    speed = x; if (view?.isPlaying == true) applySpeed(player)
                }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        error?.let { Text(it, color = Amber, fontSize = 14.sp, modifier = Modifier.background(Color.Black).padding(10.dp)) }
    }
}
