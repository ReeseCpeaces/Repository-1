package com.grove.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.AppData
import com.grove.app.data.Explore
import com.grove.app.data.ExploreSession
import com.grove.app.data.Goal
import com.grove.app.data.Logic
import com.grove.app.data.Plant
import com.grove.app.data.Store
import com.grove.app.obsidian.ExploreNote
import java.time.LocalDate

private fun shortDate(d: String) = Logic.date(d).let { "${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)} ${it.dayOfMonth}" }

/** Druid exploration on Today: this week's minutes, hours so far, and + Session. Tap the title for the Plant Study screen. */
@Composable
fun ExploreCard(data: AppData, g: Goal, go: (Screen) -> Unit) {
    var logging by remember { mutableStateOf(false) }
    val done = Logic.progress(data, g)
    val target = Logic.target(data, g)
    val total = Explore.totalMinutes(data)
    Card(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { go(Screen.PlantStudy) }.padding(vertical = 10.dp)) {
                Text(g.name, fontSize = 16.sp)
                Text("$done / $target min this week · ${Explore.hours(total)} of ${g.total / 60} h", fontSize = 12.sp, color = GroveColors.Muted)
                LinearProgressIndicator(
                    progress = { if (target == 0) 1f else (done.toFloat() / target).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = GroveColors.Green, trackColor = GroveColors.Line,
                )
            }
            FilledTonalButton(onClick = { logging = true }, modifier = Modifier.padding(start = 10.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text(" Session")
            }
        }
    }
    if (logging) SessionDialog(data, null, null) { logging = false }
}

/** Add or edit a plant-study session: plant (defaults to the last one), minutes, optional note, and day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionDialog(data: AppData, editing: ExploreSession?, plantId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    var plant by remember { mutableStateOf(editing?.plant ?: plantId ?: Explore.lastPlant(data)?.id.orEmpty()) }
    var minutes by remember { mutableStateOf(editing?.minutes?.toString().orEmpty()) }
    var note by remember { mutableStateOf(editing?.note.orEmpty()) }
    var day by remember { mutableStateOf(editing?.day ?: data.currentDay) }
    var confirmDelete by remember { mutableStateOf(false) }
    val n = minutes.toIntOrNull() ?: 0
    val month = Logic.groveToday(data).monthValue
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (editing == null) "Plant study session" else "Edit session") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Plant", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.plants.forEach { p ->
                        FilterChip(selected = p.id == plant, onClick = { plant = p.id },
                            label = { Text(p.name + if (Explore.inSeason(p, month)) " ·" else "", maxLines = 1) })
                    }
                }
                Text("Minutes", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60).forEach { q -> FilterChip(selected = n == q, onClick = { minutes = q.toString() }, label = { Text("$q") }) }
                }
                OutlinedTextField(minutes, { v -> minutes = v.filter { it.isDigit() }.take(4) }, label = { Text("Minutes") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") },
                    supportingText = { Text("What you noticed. The keyboard's mic works.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp))
                OutlinedButton(onClick = {
                    val d = LocalDate.parse(day)
                    DatePickerDialog(context, { _, y, m, dd ->
                        val picked = LocalDate.of(y, m + 1, dd)
                        if (!picked.isAfter(Logic.groveToday(data))) day = picked.toString()
                    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth()) { Text(if (day == data.currentDay) "Today" else "Day: ${shortDate(day)}") }
                if (editing != null) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete session", color = GroveColors.Warn) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = n > 0 && plant.isNotEmpty(), onClick = {
                Store.update(context) { d ->
                    if (editing == null) Explore.add(d, plant, n, note, day) else Explore.edit(d, editing.id, plant, n, note, day)
                }
                onClose()
            }) { Text(if (editing == null) "Add $n min" else "Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
    if (confirmDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("${shortDate(editing.day)} · ${Explore.plantName(data, editing.plant)} · ${editing.minutes} min. Its line in the plant-study note is removed too.") },
            confirmButton = { TextButton(onClick = { Store.update(context) { Explore.delete(it, editing.id) }; confirmDelete = false; onClose() }) { Text("Delete", color = GroveColors.Warn) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SessionRow(data: AppData, s: ExploreSession, showPlant: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(GroveColors.Surface).clickable(onClick = onClick).padding(12.dp)) {
        Row {
            Text(shortDate(s.day) + if (showPlant) " · ${Explore.plantName(data, s.plant)}" else "", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${s.minutes} min", fontSize = 13.sp, color = GroveColors.Green)
        }
        if (s.note.isNotBlank()) Text(s.note, fontSize = 13.sp, color = GroveColors.Muted)
    }
}

/** The Plant Study screen: totals, the plant list (in season highlighted), and the session log. */
@Composable
fun PlantStudyScreen(data: AppData, go: (Screen) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val g = Explore.goal(data)
    var tab by remember { mutableStateOf(0) }
    var editingSession by remember { mutableStateOf<ExploreSession?>(null) }
    var logging by remember { mutableStateOf(false) }
    var editingPlant by remember { mutableStateOf<Plant?>(null) }
    var addingPlant by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }
    val today = Logic.groveToday(data)
    val total = Explore.totalMinutes(data)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        BackBar(back) { TextButton(onClick = { logging = true }) { Icon(Icons.Filled.Add, null); Text(" Session") } }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("Plant Study", "Druid exploration")
            if (g != null) {
                Text("${Explore.hours(total)} of ${g.total / 60} hours · ${Logic.progress(data, g)} / ${Logic.target(data, g)} min this week · ${Explore.weeksLeft(g, today)} weeks left",
                    fontSize = 13.sp, color = GroveColors.Muted)
                LinearProgressIndicator(
                    progress = { (total.toFloat() / g.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = GroveColors.Green, trackColor = GroveColors.Line,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Plants") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Session log") })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { ExploreNote.open(context, data) }) { Text("Open in Obsidian", fontSize = 13.sp) }
            }
            if (tab == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("In season this month is outlined.", Modifier.weight(1f), fontSize = 12.sp, color = GroveColors.Muted)
                    TextButton(onClick = { arranging = !arranging }) { Text(if (arranging) "Done" else "Edit list") }
                }
                data.plants.forEachIndexed { i, p ->
                    val seasonal = Explore.inSeason(p, today.monthValue)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(GroveColors.Surface)
                            .then(if (seasonal) Modifier.border(1.5.dp, GroveColors.Green, RoundedCornerShape(14.dp)) else Modifier)
                            .clickable { if (arranging) editingPlant = p else go(Screen.Plant(p.id)) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${p.name}", fontSize = 15.sp)
                            if (p.latin.isNotBlank()) Text(p.latin, fontSize = 12.sp, fontStyle = FontStyle.Italic, color = GroveColors.Muted)
                            Text(listOfNotNull(p.season.ifBlank { null }, p.backup.takeIf { it.isNotBlank() }?.let { "backup: $it" }).joinToString(" · ") +
                                if (seasonal) " · in season" else "", fontSize = 12.sp, color = if (seasonal) GroveColors.Green else GroveColors.Muted)
                        }
                        if (arranging) {
                            IconButton(onClick = { Store.update(context) { Explore.movePlant(it, p.id, -1) } }, enabled = i > 0) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
                            IconButton(onClick = { Store.update(context) { Explore.movePlant(it, p.id, 1) } }, enabled = i < data.plants.lastIndex) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
                        } else {
                            Text("${Explore.plantMinutes(data, p.id)} min", fontSize = 13.sp, color = GroveColors.Green)
                        }
                    }
                }
                if (arranging) OutlinedButton(onClick = { addingPlant = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add a plant") }
            } else {
                val sessions = Explore.sessions(data)
                if (sessions.isEmpty()) Text("No sessions yet.", color = GroveColors.Muted)
                sessions.forEach { s -> SessionRow(data, s, showPlant = true) { editingSession = s } }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (logging) SessionDialog(data, null, null) { logging = false }
    editingSession?.let { s -> SessionDialog(data, s, null) { editingSession = null } }
    if (addingPlant || editingPlant != null) PlantDialog(data, editingPlant) { addingPlant = false; editingPlant = null }
}

/** One plant: its minutes, every session for it (newest first), + Session, and Open in Obsidian. */
@Composable
fun PlantScreen(data: AppData, id: String, back: () -> Unit) {
    val context = LocalContext.current
    val p = Explore.plant(data, id)
    if (p == null) { androidx.compose.runtime.LaunchedEffect(id) { back() }; return }
    var logging by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ExploreSession?>(null) }
    val minutes = Explore.plantMinutes(data, p.id)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        BackBar(back) { TextButton(onClick = { logging = true }) { Icon(Icons.Filled.Add, null); Text(" Session") } }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Title(p.name, p.latin.ifBlank { null })
            Text("$minutes min (${Explore.hours(minutes)} h)" + (if (p.season.isNotBlank()) " · best: ${p.season}" else "") +
                (if (p.backup.isNotBlank()) " · backup: ${p.backup}" else ""), fontSize = 13.sp, color = GroveColors.Muted)
            OutlinedButton(onClick = { ExploreNote.open(context, data) }, modifier = Modifier.fillMaxWidth()) { Text("Open in Obsidian") }
            val sessions = Explore.sessions(data, p.id)
            if (sessions.isEmpty()) Text("No sessions with ${p.name} yet.", color = GroveColors.Muted)
            sessions.forEach { s -> SessionRow(data, s, showPlant = false) { editing = s } }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (logging) SessionDialog(data, null, p.id) { logging = false }
    editing?.let { s -> SessionDialog(data, s, null) { editing = null } }
}

/** Add or edit a plant (name, Latin name, best time, backup). A plant with sessions can't be removed. */
@Composable
private fun PlantDialog(data: AppData, plant: Plant?, onClose: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(plant?.name.orEmpty()) }
    var latin by remember { mutableStateOf(plant?.latin.orEmpty()) }
    var season by remember { mutableStateOf(plant?.season.orEmpty()) }
    var backup by remember { mutableStateOf(plant?.backup.orEmpty()) }
    val used = plant != null && data.explore.any { it.plant == plant.id }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (plant == null) "Add a plant" else "Edit plant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(latin, { latin = it }, label = { Text("Latin name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(season, { season = it }, label = { Text("Best time (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(backup, { backup = it }, label = { Text("Backup plant (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (plant != null) {
                    if (used) Text("It has sessions, so it can't be removed (renaming also updates its lines in the note).", fontSize = 12.sp, color = GroveColors.Muted,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    else TextButton(onClick = { Store.update(context) { Explore.removePlant(it, plant.id) }; onClose() }) { Text("Remove plant", color = GroveColors.Warn) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                Store.update(context) { d ->
                    if (plant == null) Explore.addPlant(d, name, latin, season).let { x -> Explore.updatePlant(x, x.plants.last().copy(backup = backup.trim())) }
                    else Explore.updatePlant(d, plant.copy(name = name.trim(), latin = latin.trim(), season = season.trim(), backup = backup.trim()))
                }
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
