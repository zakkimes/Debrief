package com.kimes.daq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kimes.daq.Hub
import com.kimes.daq.data.Run
import kotlin.math.abs

/**
 * The official time for one run: what was keyed in from the timing slip, or, once any run has
 * one, this run's time corrected by the timer offset. Tap to enter or change it.
 */
@Composable
fun OfficialTime(run: Run, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val off by Hub.official.collectAsState()
    val runs by Hub.runs.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val mine = off[run.number]
    val offset = Hub.timingOffset(runs, off)
    val n = runs.count { off.containsKey(it.number) }
    val fg = if (onDark) Paper else Ink
    Row(modifier.clickable { editing = true }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            mine != null -> {
                Text("OFFICIAL", color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Num("%.3f".format(mine), 20.sp, fg, Modifier.padding(start = 8.dp))
                Text("  timer read %.3f (%s)".format(run.time, signed(mine - run.time, 3)), color = if (onDark) Paper else Dim, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                Text("   Edit", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            offset != null -> {
                Text("ABOUT", color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                Num("%.2f".format(run.time + offset), 20.sp, fg, Modifier.padding(start = 8.dp))
                Text("  official (timer offset %s from %d run%s)".format(signed(offset, 2), n, if (n > 1) "s" else ""), color = if (onDark) Paper else Dim, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                Text("   Enter", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            else -> Text("+ Enter the official time", color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (editing) OfficialDialog(run, mine) { editing = false }
}

@Composable
private fun OfficialDialog(run: Run, current: Double?, close: () -> Unit) {
    var text by remember { mutableStateOf(current?.let { "%.3f".format(it) } ?: "") }
    val v = text.toDoubleOrNull()
    // A slip for another run, or a typo, is the usual mistake: it would land entirely in the Launch
    // and Finish sections. Warn when it's over a second from this tablet's time, or (with 3+ official
    // times already) more than 10% away from the usual correction.
    val far = v != null && abs(v - run.time) > 1.0
    val others = Hub.official.value.filterKeys { it != run.number }
    val usual = if (others.size >= 3) Hub.timingOffset(Hub.runs.value, others) else null
    val odd = v != null && !far && usual != null && abs((v - run.time) - usual) > 0.1 * abs(usual)
    Dialog(onDismissRequest = close) {
        Column(Modifier.width(420.dp).background(Paper).border(4.dp, Ink, RectangleShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Official time, run ${run.number}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("The raw time from the timing slip, before cones. This tablet's timer read %.3f.".format(run.time), color = Dim, fontSize = 15.sp, lineHeight = 20.sp)
            OutlinedTextField(
                value = text, onValueChange = { s -> text = s.filter { it.isDigit() || it == '.' }.take(7) },
                label = { Text("Seconds, e.g. %.3f".format(run.time)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            if (far) Text("That's %.2f s from this tablet's time. Is it the slip for run ${run.number}?".format(abs(v!! - run.time)), color = Red, fontSize = 14.sp)
            if (odd) Text("Expected about %.3f: the usual correction is %s, this one is %s. Check the slip.".format(run.time + usual!!, signed(usual, 2), signed(v!! - run.time, 2)), color = Amber, fontSize = 14.sp, lineHeight = 19.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Primary("Save", Modifier.weight(1f), height = 48, enabled = v != null && v > 1.0) { Hub.setOfficial(run.number, v); close() }
                if (current != null) Secondary("Clear", Modifier.weight(1f), height = 48, color = Red) { Hub.setOfficial(run.number, null); close() }
                Secondary("Cancel", Modifier.weight(1f), height = 48) { close() }
            }
        }
    }
}

/**
 * The app made this run a DNF (wrong side of a pointer cone). Said plainly, with the way out right
 * there: the cone may have been moved since the course walk.
 */
@Composable
fun AutoDnfBlock(run: Int, what: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Yellow).border(3.dp, Ink, RectangleShape).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("DNF · AUTOMATIC", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            Text("$what. The car never passed it on its marked side.", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
        }
        Primary("Not a DNF", Modifier.padding(start = 12.dp).width(150.dp), height = 52, fill = Ink) { Hub.notDnf(run) }
    }
}
