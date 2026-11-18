package com.kimes.daq.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.Hub
import com.kimes.daq.LiveState
import com.kimes.daq.data.Run
import com.kimes.daq.gopro.GoPro
import com.kimes.daq.run.Standings
import kotlin.math.abs

const val MS_TO_MPH = 2.236936
const val M_TO_FT = 3.28084

/** Seconds with a real minus sign: "−0.42", "+0.07". */
fun signed(v: Double, digits: Int = 2) = (if (v < 0) "−" else "+") + "%.${digits}f".format(abs(v))

fun deltaColor(v: Double?, dead: Double = 0.02) = when {
    v == null -> Dim
    v < -dead -> Green
    v > dead -> Red
    else -> Hi
}

/**
 * During a run. The whole field is the delta: poster paper while you're ahead (or level), red when
 * you're behind, readable from the corner of your eye. One big number, the run clock, sector blocks,
 * and the brake and throttle trace. Nothing the car's own dash shows (speed, RPM, gear).
 */
@Composable
fun LiveScreen(back: () -> Unit, review: (Int) -> Unit) {
    val s by Hub.live.collectAsState()
    val runs by Hub.runs.collectAsState()
    val pen by Hub.penalties.collectAsState()
    val camRec by GoPro.recording.collectAsState()
    val camStatus by GoPro.status.collectAsState()
    val last = runs.lastOrNull()
    // After a run: the result stays up until the next run starts
    if (!s.running && last != null && last.number == s.runNumber) {
        RunResult(last, runs, pen, s.sectorCount, back, review)
        return
    }
    val behind = s.running && (s.delta ?: 0.0) > 0.02
    val field = if (behind) RedFill else Paper
    val ink = if (behind) Color.White else Ink
    BoxWithConstraints(Modifier.fillMaxSize().background(field)) {
        val h = maxHeight
        // Portrait: the delta gets the full width on its own line, sized by the width
        val port = maxHeight > maxWidth
        val d0 = LocalDensity.current
        val numSp = with(d0) { (if (port) maxWidth * 0.33f else h * 0.34f).toSp() }
        val clockSp = with(d0) { (if (port) maxWidth * 0.11f else h * 0.105f).toSp() }
        val roundel = if (port) maxWidth * 0.2f else h * 0.15f
        val drv by Hub.driver.collectAsState()
        val who = if (Hub.coDriving) drv?.let { "$it · " } ?: "" else ""
        // Rolling up to the line: how far to go, in big numbers (the same 50 ft the GoPro starts at)
        val toStartFt = if (!s.running) s.toStartM?.let { (it * 3.281).toInt() } else null
        val waitText = who + when {
            toStartFt != null -> "to the start line"
            !s.running -> if (s.lineTimed) "Waiting for start line" else "Waiting for launch"
            s.refNumber == null -> "first run"
            else -> "vs %02d".format(s.refNumber)
        }
        val deltaText = if (toStartFt != null) "$toStartFt FT" else if (!s.running) "READY" else s.delta?.let { signed(it) } ?: "+0.00"
        // Twin stripes down the left edge, like a period hood stripe
        Row(Modifier.fillMaxHeight().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(ink)); Box(Modifier.width(3.dp).fillMaxHeight().background(ink))
        }
        Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 18.dp, top = 8.dp, bottom = 12.dp)) {
            if (port) {
                Row(Modifier.fillMaxWidth().clickable { back() }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(waitText, color = ink)
                        Num(if (s.running) "%.2f".format(s.elapsed) else "0.00", clockSp, ink, Modifier.padding(top = 4.dp))
                    }
                    Roundel("%02d".format(if (s.running) s.runNumber else runs.size + 1), roundel)
                }
                Text(deltaText, fontFamily = Anton, fontSize = numSp, lineHeight = numSp, color = ink, maxLines = 1, softWrap = false,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { back() })
            } else Row(Modifier.fillMaxWidth().height(h * 0.36f).clickable { back() }, verticalAlignment = Alignment.Bottom) {
                Text(
                    deltaText,
                    fontFamily = Anton, fontSize = numSp, lineHeight = numSp, color = ink, maxLines = 1, softWrap = false,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(waitText, Modifier.padding(end = 8.dp), ink)
                        Roundel("%02d".format(if (s.running) s.runNumber else runs.size + 1), roundel)
                    }
                    Num(if (s.running) "%.2f".format(s.elapsed) else "0.00", clockSp, ink, Modifier.padding(top = 4.dp))
                }
            }
            if (s.sectorCount > 1) SectorBlocks(s, Modifier.padding(top = 10.dp).height(if (port) 64.dp else h * 0.085f))
            // Brake and throttle: the things the dash can't show
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp).background(Ink).border(3.dp, Ink, RectangleShape)) {
                Traces(s.brakeTrace, s.throttleTrace, Modifier.fillMaxSize())
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip2(if (s.brakeFromG) "Brake · RaceBox g" else "Brake", BrakeRed, Color.White); Chip2("Throttle", Paper, Ink)
                }
                BackButton("Home", Modifier.align(Alignment.BottomEnd).padding(8.dp), height = 36) { back() }
                // Faults are the only status shown: a block in the middle of the trace
                val noBrake = !s.brakeIsCan && !s.brakeFromG
                val faults = listOfNotNull(if (noBrake) "No brake signal" else null, if (!s.gpsOk) "No GPS fix" else null)
                if (faults.isNotEmpty()) Text(
                    faults.joinToString("  ·  ").uppercase(), color = if (noBrake) Color.White else Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.Center).background(if (noBrake) RedFill else Yellow).border(3.dp, Ink, RectangleShape).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        // GoPro: a REC sticker while it records; a yellow one if the app asked and the camera hasn't started
        if (camRec || (camStatus != "off" && GoPro.want)) Text(
            if (camRec) "● REC" else "GOPRO NOT RECORDING",
            color = if (camRec) Color.White else Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp,
            modifier = Modifier.align(Alignment.TopCenter).offset(x = 60.dp, y = 14.dp).rotate(5f)
                .background(if (!camRec) Yellow else if (behind) Ink else RedFill).border(3.dp, Ink, RectangleShape).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Chip2(text: String, bg: Color, fg: Color) =
    Text(text.uppercase(), color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, modifier = Modifier.background(bg).padding(horizontal = 7.dp, vertical = 1.dp))

/** One block per sector: inked when you gained, white when you lost, striped for the one you're in. */
@Composable
private fun SectorBlocks(s: LiveState, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in 0 until s.sectorCount) {
            val done = k < s.sectorDeltas.size
            val now = s.running && k == s.sectorDeltas.size
            val v = if (done) s.sectorDeltas[k] else if (now) s.currentSectorDelta else null
            val gained = done && v != null && v < 0
            val bg = when { gained -> Ink; done -> Color.White; else -> Paper }
            Box(Modifier.weight(1f).fillMaxHeight().background(bg).border(3.dp, Ink, RectangleShape)) {
                if (now) Canvas(Modifier.fillMaxSize()) {
                    val step = 14.dp.toPx(); var x = -size.height
                    while (x < size.width) { drawLine(Ink, Offset(x, size.height), Offset(x + size.height, 0f), 5.dp.toPx()); x += step }
                }
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("S${k + 1}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                        color = if (gained) Color.White else Ink, modifier = Modifier.background(if (now) Ink else Color.Transparent).padding(horizontal = if (now) 4.dp else 0.dp))
                    Spacer(Modifier.weight(1f))
                    if (done && v != null) Num(signed(v), 22.sp, if (gained) Sage else Red)
                }
            }
        }
    }
}

/** Shown when a run finishes: time, comparison, sectors, and cones/DNF entry. */
@Composable
private fun RunResult(run: Run, allRuns: List<Run>, pen: Map<Int, Int>, sectors: Int, back: () -> Unit, review: (Int) -> Unit) {
    // With co-drivers: this driver's runs only
    val runDrivers by Hub.runDriver.collectAsState()
    val runs = remember(allRuns, runDrivers) { Hub.sameDriver(allRuns, run.number) }
    val drvName = runDrivers[run.number]
    val prevBest = Standings.best(runs, pen, exclude = run.number)
    val myPen = pen[run.number] ?: 0
    val d = prevBest?.let { run.time - it.time }
    val isBest = run.finished && myPen == 0 && (prevBest == null || run.time < prevBest.time)
    val secBest = Standings.sectorBests(runs, pen, sectors)
    val theo = Standings.theoreticalBest(runs, pen, sectors)
    BoxWithConstraints(Modifier.fillMaxSize().background(Paper)) {
        // Portrait sizes from the width, so the time still fits on one line
        val port = maxHeight > maxWidth
        val h = if (port) maxWidth * 0.62f else maxHeight
        val d0 = LocalDensity.current
        val timeSp = with(d0) { (h * 0.25f).toSp() }
        val cellSp = with(d0) { (h * 0.06f).toSp() }
        val coneH = (h.value * 0.15f).toInt().coerceAtLeast(52)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 10.dp)) {
                @Composable fun Ribbon() {
                    // Ribbon: violet for a new best, else green ahead / red behind
                    val rib = when { myPen == Standings.DNF || myPen > 0 -> Yellow; isBest -> Violet; d == null -> Ink; d < 0 -> GreenFill; else -> RedFill }
                    val ribInk = if (rib == Yellow) Ink else Color.White
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp).rotate(-3f).background(rib).border(3.dp, Ink, RectangleShape).padding(horizontal = 14.dp, vertical = 4.dp)) {
                        if (d != null) Num(signed(d), with(d0) { (h * 0.11f).toSp() }, ribInk)
                        Label(
                            when {
                                myPen == Standings.DNF -> "DNF · not counted"
                                myPen > 0 -> "+$myPen cone${if (myPen > 1) "s" else ""} · %.3f scored".format(run.time + myPen * Standings.CONE_S)
                                prevBest == null -> (drvName?.let { "$it's first clean run" } ?: "First clean run · your reference")
                                isBest -> "New best · was %.3f, run %d".format(prevBest.time, prevBest.number)
                                else -> "vs best %.3f, run %d".format(prevBest.time, prevBest.number)
                            }, color = ribInk,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Roundel("%02d".format(run.number), h * 0.22f, Modifier.clickable { back() }, if (run.finished) "finished" else "no finish")
                    Num("%.3f".format(run.time), timeSp, Ink, Modifier.padding(start = 14.dp).clickable { back() })
                    Spacer(Modifier.weight(1f))
                    if (!port) Ribbon()
                }
                if (port) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) { Ribbon() }
                if (run.finished) OfficialTime(run, Modifier.padding(top = 6.dp))
                val autoDnf by Hub.autoDnf.collectAsState()
                autoDnf[run.number]?.let { a -> AutoDnfBlock(run.number, a.what, Modifier.padding(top = 10.dp)) }
                if (sectors > 1 && run.sectorTimes.size == sectors) {
                    Column(Modifier.padding(top = 12.dp).border(3.dp, Ink, RectangleShape)) {
                        @Composable fun row(label: String, header: Boolean = false, bg: (Int) -> Color = { Color.Transparent }, cell: @Composable (Int) -> Unit) {
                            Row(Modifier.fillMaxWidth().background(if (header) Ink else Color.Transparent).bottomRule(Ink, 2.dp)) {
                                Box(Modifier.width(96.dp).rightRule(Ink, 2.dp).padding(horizontal = 8.dp, vertical = 4.dp)) { Label(label, color = if (header) Paper else Ink) }
                                for (k in 0 until sectors) {
                                    Box(Modifier.weight(1f).background(bg(k)).rightRule(Ink, 2.dp).padding(horizontal = 8.dp, vertical = 3.dp)) { cell(k) }
                                }
                            }
                        }
                        fun bestHere(k: Int) = secBest.getOrNull(k)?.run == run.number
                        fun vsRef(k: Int) = prevBest?.sectorTimes?.getOrNull(k)?.let { run.sectorTimes[k] - it }
                        fun vsBg(k: Int) = vsRef(k)?.let { when { it < -0.02 -> GreenFill; it > 0.02 -> RedFill; else -> Color.Transparent } } ?: Color.Transparent
                        row("", header = true) { k -> Label("Sector ${k + 1}", color = Paper) }
                        row("This run", bg = { if (bestHere(it)) Violet else Color.Transparent }) { k ->
                            Num("%.2f".format(run.sectorTimes[k]), cellSp, if (bestHere(k)) Color.White else Ink)
                        }
                        if (prevBest != null && prevBest.sectorTimes.size == sectors) row("vs run ${prevBest.number}", bg = ::vsBg) { k ->
                            vsRef(k)?.let { sd -> Num(signed(sd), cellSp, if (vsBg(k) == Color.Transparent) Ink else Color.White) }
                        }
                        row("Best today") { k ->
                            secBest.getOrNull(k)?.let { b ->
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Num("%.2f".format(b.time), (cellSp.value * 0.75f).sp, if (b.run == run.number) Violet else Ink)
                                    Text("  R${b.run}", color = Ink2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                theo?.let { t ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Label("Optimal", Modifier.padding(end = 10.dp, bottom = 3.dp), Ink)
                        Num("%.3f".format(t), 22.sp)
                        Text("   violet = best of the day", color = Ink2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                // Co-drivers swap here, between runs
                if (Hub.coDriving) DriverPicker(Modifier.fillMaxWidth().padding(bottom = 10.dp), label = "Next", height = 44)
                @Composable fun cones(m: Modifier) = Segmented(
                    listOf("0" to 0, "+1" to 1, "+2" to 2, "+3" to 3, "DNF" to Standings.DNF), myPen,
                    m, height = coneH, cellColor = { if (it == Standings.DNF) Red else Ink },
                ) { Hub.setPenalty(run.number, it) }
                if (port) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Cones", Modifier.width(56.dp), Ink)
                        cones(Modifier.weight(1f))
                    }
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        BackButton("Home", Modifier.padding(end = 14.dp), height = coneH) { back() }
                        Primary("Review run ${run.number}", Modifier.weight(1f), height = coneH) { review(run.number) }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    BackButton("Home", Modifier.padding(end = 14.dp), height = coneH) { back() }
                    Label("Cones", Modifier.width(56.dp), Ink)
                    cones(Modifier.weight(1.4f))
                    Spacer(Modifier.width(16.dp))
                    Primary("Review run ${run.number}", Modifier.weight(1f), height = coneH) { review(run.number) }
                }
            }
            Checker(Modifier.fillMaxWidth().height(10.dp).topRule(Ink, 3.dp), 10.dp)
        }
    }
}

/** The last 8 s on an ink panel: brake as a red shape, throttle as a paper-colored line, newest at the right. */
@Composable
fun Traces(brake: FloatArray, throttle: FloatArray, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (q in 1..3) drawLine(Color(0xFF2E2A24), Offset(w * q / 4, 0f), Offset(w * q / 4, h), 1.dp.toPx())
        val n = 200
        fun xAt(i: Int, len: Int) = w - (len - 1 - i) * (w / (n - 1))
        if (brake.size > 1) {
            val p = Path().apply {
                moveTo(xAt(0, brake.size), h)
                for (i in brake.indices) lineTo(xAt(i, brake.size), h - brake[i] * h)
                lineTo(xAt(brake.size - 1, brake.size), h)
                close()
            }
            drawPath(p, BrakeRed)
        }
        if (throttle.size > 1) {
            val l = Path().apply {
                for (i in throttle.indices) if (i == 0) moveTo(xAt(i, throttle.size), h - throttle[i] * h) else lineTo(xAt(i, throttle.size), h - throttle[i] * h)
            }
            drawPath(l, Paper, style = Stroke(4.dp.toPx()))
        }
    }
}
