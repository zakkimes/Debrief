package com.kimes.daq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kimes.daq.Hub
import com.kimes.daq.run.CarModel
import com.kimes.daq.run.Drive
import com.kimes.daq.run.TireType

/**
 * The car and its tires: a first guess for predicted times on a new course. After runs, what the
 * car really did (measured) takes over, and is shown here.
 */
@Composable
fun CarSection() {
    val p by Hub.carProfile.collectAsState()
    val learned by Hub.learnedCar.collectAsState()
    var search by remember { mutableStateOf("") }
    fun set(f: (com.kimes.daq.run.CarProfile) -> com.kimes.daq.run.CarProfile) = Hub.setCarProfile(f(Hub.carProfile.value))

    var editing by remember { mutableStateOf(false) }
    val set0 = p.label.isNotEmpty() && p.complete
    // Set already: one line, and the one thing worth acting on
    if (set0 && !editing) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Your car", Modifier.padding(top = 4.dp), Ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label + listOf(p.tireName, p.tireSize).filter { it.isNotBlank() }.joinToString(" ").let { if (it.isEmpty()) "" else " · $it" },
                    color = Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextAction("Edit") { editing = true }
            }
            learned?.takeIf { it.source == "your runs" && it.brakeG < p.tire.grip - 0.2 }?.let { c ->
                Text("Hardest braking so far %.2f g; these tires can do about %.1f g.".format(c.brakeG, p.tire.grip), color = Amber, fontSize = 15.sp)
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Your car", Modifier.padding(top = 4.dp).weight(1f), Ink)
            if (set0) TextAction("Done") { editing = false }
        }
        Text(if (p.label.isEmpty()) "Not set: find your car below" else p.label,
            color = if (p.label.isEmpty()) Amber else Hi, fontSize = if (p.label.isEmpty()) 15.sp else 19.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("Find a car: make or model, e.g. Miata, GR86, Civic") }, modifier = Modifier.fillMaxWidth())
        if (search.length >= 2) {
            val words = search.lowercase().split(' ').filter { it.isNotBlank() }
            val hits = CarModel.cars.filter { c -> words.all { w -> c.label.lowercase().contains(w) } }.take(8)
            if (hits.isEmpty()) Text("Not in the list: type the name, weight and power below.", color = Dim, fontSize = 14.sp)
            hits.forEach { c ->
                Row(Modifier.fillMaxWidth().background(Paper).bottomRule(RuleFaint).clickable {
                    set { it.copy(label = c.label, weightLb = c.weightLb, hp = c.hp, drive = c.drive) }; search = ""
                }.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(c.label, color = Hi, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("${c.weightLb} lb · ${c.hp} hp · ${c.drive}", color = Dim, fontSize = 14.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(p.label, { v -> set { it.copy(label = v.take(40)) } }, singleLine = true, label = { Text("Car") }, modifier = Modifier.weight(2f))
            OutlinedTextField(if (p.weightLb > 0) "${p.weightLb}" else "", { v -> set { it.copy(weightLb = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } },
                singleLine = true, label = { Text("Weight, lb") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(if (p.hp > 0) "${p.hp}" else "", { v -> set { it.copy(hp = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) } },
                singleLine = true, label = { Text("Power, hp") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        Segmented(Drive.entries.map { it.name to it }, p.drive, Modifier.fillMaxWidth(), height = 40) { d -> set { it.copy(drive = d) } }
        Label("Tires", Modifier.padding(top = 4.dp))
        TireType.entries.forEach { t ->
            val sel = p.tire == t
            Row(Modifier.fillMaxWidth().background(if (sel) Ink else Paper).bottomRule(RuleFaint).clickable { set { it.copy(tire = t) } }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(t.label, color = if (sel) Paper else Hi, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (t.example.isNotEmpty()) Text(t.example, color = if (sel) Paper else Dim, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(p.tireName, { v -> set { it.copy(tireName = v.take(40)) } }, singleLine = true, label = { Text("Tire, e.g. Bridgestone RE-71RZ") }, modifier = Modifier.weight(2f))
            OutlinedTextField(p.tireSize, { v -> set { it.copy(tireSize = v.take(16)) } }, singleLine = true, label = { Text("Size, e.g. 225/50R16") }, modifier = Modifier.weight(1.2f))
            OutlinedTextField(p.wheelSize, { v -> set { it.copy(wheelSize = v.take(12)) } }, singleLine = true, label = { Text("Wheel, e.g. 16x8") }, modifier = Modifier.weight(1f))
        }
        // What the runs showed, and the one number worth a coaching note
        learned?.takeIf { it.source == "your runs" }?.let { c ->
            Text("Measured: cornering %.2f g · braking %.2f g · launch %.2f g · power %.0f W/kg".format(c.latG, c.brakeG, c.accelG, c.powerPerKg),
                color = Hi, fontSize = 15.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
            if (c.brakeG < p.tire.grip - 0.2) Text(
                "Your hardest braking was about %.2f g; ${p.tire.label.lowercase()} tires can do about %.1f g. Braking harder and later is time on the table.".format(c.brakeG, p.tire.grip),
                color = Amber, fontSize = 15.sp, lineHeight = 20.sp,
            )
        }
    }
}
