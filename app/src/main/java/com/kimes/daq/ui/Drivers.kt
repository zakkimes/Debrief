package com.kimes.daq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kimes.daq.Hub

/**
 * Who drives next. With co-drivers each run belongs to whoever was picked here when it started,
 * and each driver is compared with their own runs. One driver (or none): a link to add a co-driver.
 */
@Composable
fun DriverPicker(modifier: Modifier = Modifier, label: String = "Driver", height: Int = 40) {
    val drivers by Hub.drivers.collectAsState()
    val driver by Hub.driver.collectAsState()
    var editing by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.width(64.dp), Ink)
        if (drivers.size >= 2) {
            Segmented(drivers.map { it to it }, driver, Modifier.weight(1f), height = height) { Hub.selectDriver(it) }
            Text("Edit", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { editing = true }.padding(start = 12.dp))
        } else {
            Text((drivers.firstOrNull()?.let { "$it · " } ?: "") + "add a co-driver", color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).clickable { editing = true }.padding(vertical = 8.dp))
        }
    }
    if (editing) DriversDialog { editing = false }
}

/**
 * Home's DRIVER line: who's driving. One name: tap to change or add a co-driver. Two or more:
 * pick who drives next right on the line.
 */
@Composable
fun DriverRow() {
    val drivers by Hub.drivers.collectAsState()
    val driver by Hub.driver.collectAsState()
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().bottomRule(Ink, 2.dp).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("DRIVER", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(end = 10.dp))
        if (drivers.size >= 2) {
            Segmented(drivers.map { it to it }, driver, Modifier.weight(1f), height = 34) { Hub.selectDriver(it) }
            Text("Edit", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { editing = true }.padding(start = 10.dp))
        } else {
            Spacer(Modifier.weight(1f))
            Text(drivers.firstOrNull() ?: "tap to add your name", color = if (drivers.isEmpty()) Ink2 else Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { editing = true })
        }
    }
    if (editing) DriversDialog { editing = false }
}

@Composable
private fun DriversDialog(close: () -> Unit) {
    val names = remember { mutableStateListOf<String>().apply { addAll(Hub.drivers.value); while (size < 2) add("") } }
    Dialog(onDismissRequest = close) {
        Column(Modifier.width(440.dp).background(Paper).border(4.dp, Ink, RectangleShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Drivers", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("Add a second name if you share the car; each driver is compared with their own best.", color = Dim, fontSize = 15.sp, lineHeight = 20.sp)
            names.forEachIndexed { i, n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(n, { names[i] = it.take(16) }, singleLine = true, label = { Text("Driver ${i + 1}") }, modifier = Modifier.weight(1f))
                    if (names.size > 1) Text("✕", color = Dim, fontSize = 20.sp, modifier = Modifier.clickable { names.removeAt(i) }.padding(start = 12.dp, end = 4.dp))
                }
            }
            if (names.size < 4) TextAction("+ Another driver") { names.add("") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Primary("Save", Modifier.weight(1f), height = 48) { Hub.setDrivers(names); close() }
                Secondary("Cancel", Modifier.weight(1f), height = 48) { close() }
            }
        }
    }
}
