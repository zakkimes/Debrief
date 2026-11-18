package com.kimes.daq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.Hub
import com.kimes.daq.bt.RaceBox
import com.kimes.daq.bt.RaceBoxScan
import com.kimes.daq.bt.SeenRaceBox
import com.kimes.daq.gopro.GoPro
import kotlinx.coroutines.delay

/**
 * Which RaceBox is yours. With two cars at the grid the app must not grab the other driver's
 * RaceBox, so pick yours here once (and give it a name); after that only it is used.
 */
@Composable
fun DevicesScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val rbStatus by Hub.rbStatus.collectAsState()
    val obd by Hub.obdStatus.collectAsState()
    val goPro by GoPro.status.collectAsState()
    var chosen by remember { mutableStateOf(RaceBox.chosen(ctx)) }
    var nick by remember { mutableStateOf(ctx.getSharedPreferences("devices", 0).getString("raceboxNick", "") ?: "") }
    val seen = remember { mutableStateMapOf<String, SeenRaceBox>() }
    var btOff by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Look for RaceBoxes the whole time this page is open
    val goProSeen = remember { mutableStateMapOf<String, SeenRaceBox>() }
    DisposableEffect(Unit) {
        val scan = RaceBoxScan(ctx) { s -> seen[s.address] = s }
        val gp = com.kimes.daq.bt.GoProScan(ctx) { s -> goProSeen[s.address] = s }
        btOff = !scan.start()
        gp.start()
        onDispose { scan.stop(); gp.stop() }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val connected = Hub.raceBoxAddress().takeIf { rbStatus.startsWith("connected") || rbStatus.startsWith("fix") }

    fun use(address: String, name: String) {
        Hub.chooseRaceBox(address, name)
        chosen = address
        nick = ""
    }

    // Opened from a Home status line: scroll to that device
    val scroll = rememberScrollState()
    val tops = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(Unit) {
        val f = Hub.devicesFocus.value ?: return@LaunchedEffect
        Hub.devicesFocus.value = null
        delay(150)
        tops[f]?.let { scroll.animateScrollTo((it - 40).coerceAtLeast(0)) }
    }
    fun Modifier.mark(key: String) = this.onGloballyPositioned { tops[key] = (it.positionInParent().y).toInt() }
    var pairOther by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackButton("Home") { back() }
        Text("Car & devices", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Hi)
        CarSection()

        Label("RaceBox", Modifier.padding(top = 8.dp).mark("racebox"), Ink)
        if (chosen == null) {
            Text("Not picked yet: the first RaceBox found is used.", color = Amber, fontSize = 15.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Yours: " + (RaceBox.label(ctx) ?: "RaceBox") + "  ·  " + tail(chosen!!), color = Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlineButton("Use any RaceBox", color = Dim) { Hub.chooseRaceBox(null, null); chosen = null; nick = "" }
            }
            OutlinedTextField(
                nick, { v -> nick = v.take(24); RaceBox.nickname(ctx, nick) }, singleLine = true,
                label = { Text("Name it (shown on the pit board), e.g. Zach's RaceBox") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("Now: " + rbStatus + if (connected != null) "  ·  " + tail(connected) else "", color = Dim, fontSize = 14.sp)

        Label("Nearby", Modifier.padding(top = 8.dp), Ink)
        if (btOff) Text("Bluetooth is off.", color = Red, fontSize = 15.sp)
        // The connected RaceBox stops advertising, so it's listed from the link instead
        val rows = seen.values.filter { now - it.at < 15_000 }.sortedByDescending { it.rssi }.toMutableList()
        if (connected != null && rows.none { it.address.equals(connected, true) }) rows.add(0, SeenRaceBox(connected, "RaceBox (connected now)", 0, now))
        if (rows.isEmpty()) Text("Looking… (a RaceBox connected to a phone won't show)", color = Dim, fontSize = 14.sp)
        rows.forEach { r ->
            val mine = r.address.equals(chosen, ignoreCase = true)
            Row(Modifier.fillMaxWidth().background(if (mine) Surface else Paper).bottomRule(RuleFaint).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Hi, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(tail(r.address) + if (r.rssi != 0) "  ·  " + signal(r.rssi) else "", color = Dim, fontSize = 13.sp)
                }
                if (mine) Text("YOURS ✓", color = Green, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                else OutlineButton("This is mine", Modifier.width(150.dp)) { use(r.address, r.name.removeSuffix(" (connected now)")) }
            }
        }

        // GoPro: which camera is yours, and the ones nearby to pair instead
        Label("GoPro", Modifier.padding(top = 12.dp).mark("gopro"), Ink)
        Text((GoPro.name?.let { "Yours: $it" } ?: "No GoPro paired yet.") + "  ·  " + goPro, color = Hi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        GoProControls()
        // Nearby cameras only when pairing one (none paired yet, or asked to pair a different one)
        val listing = GoPro.name == null || pairOther
        if (!listing) TextAction("Pair a different GoPro") { pairOther = true }
        val goPros = if (listing) goProSeen.values.filter { now - it.at < 15_000 && it.name != GoPro.name }.sortedByDescending { it.rssi } else emptyList()
        if (listing && goPros.isEmpty()) Text("Looking…", color = Dim, fontSize = 14.sp)
        goPros.forEach { r ->
            val mine = r.name == GoPro.name
            Row(Modifier.fillMaxWidth().background(if (mine) Surface else Paper).bottomRule(RuleFaint).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Hi, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(tail(r.address) + "  ·  " + signal(r.rssi), color = Dim, fontSize = 13.sp)
                }
                if (mine) Text("YOURS ✓", color = Green, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                else OutlineButton("Pair with this one", Modifier.width(190.dp)) { GoPro.pairWith(r.address) }
            }
        }
        if (listing) Text("To pair: on the GoPro, Connections → Connect Device → GoPro Quik App.", color = Dim, fontSize = 13.sp)

        Label("Car (OBDLink)", Modifier.padding(top = 12.dp).mark("car"), Ink)
        Text("Now: $obd", color = Dim, fontSize = 14.sp)
        val linkMode by Hub.carLinkMode.collectAsState()
        val logging by Hub.logging.collectAsState()
        // Set once: tucked away
        TextAction(if (advanced) "Advanced ▴" else "Advanced ▾") { advanced = !advanced }
        if (advanced) Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Car link", Modifier.width(80.dp), Ink)
            Segmented(
                listOf("Diag 2024+" to com.kimes.daq.bt.ObdLink.Mode.DIAG, "Auto" to com.kimes.daq.bt.ObdLink.Mode.AUTO, "CAN listen" to com.kimes.daq.bt.ObdLink.Mode.CAN), linkMode,
                Modifier.weight(1f), height = 40,
            ) { if (!logging) Hub.setCarLinkMode(it) }
        }
        if (logging) Text("Used from the next session.", color = Dim, fontSize = 13.sp)


    }
}

/** The last part of a Bluetooth address, enough to tell two RaceBoxes apart. */
private fun tail(address: String) = "…" + address.takeLast(5)

private fun signal(rssi: Int) = when {
    rssi > -60 -> "right here"
    rssi > -75 -> "close"
    rssi > -88 -> "nearby"
    else -> "far"
}
