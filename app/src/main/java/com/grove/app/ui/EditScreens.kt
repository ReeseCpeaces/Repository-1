package com.grove.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.AppData
import com.grove.app.data.Habit
import com.grove.app.data.Logic
import com.grove.app.data.Step
import com.grove.app.data.Store
import com.grove.app.data.Task
import java.time.LocalDate

private val DAY_LETTERS = listOf("S", "M", "T", "W", "T", "F", "S")
private val NAG_CHOICES = listOf(5, 10, 15, 30, 60)

private fun pickTime(context: Context, initial: String, onPick: (String) -> Unit) {
    val (h, m) = initial.split(":").map { it.toInt() }
    TimePickerDialog(context, { _, hh, mm -> onPick("%02d:%02d".format(hh, mm)) }, h, m, false).show()
}

private fun newId(prefix: String) = prefix + System.currentTimeMillis().toString(36)

@Composable
fun EditHabitScreen(data: AppData, id: String?, back: () -> Unit) {
    val context = LocalContext.current
    val old = data.habits.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(old?.name.orEmpty()) }
    var whenText by remember { mutableStateOf(old?.whenText ?: "Any time today") }
    val steps = remember { mutableStateListOf<Step>().apply { addAll(old?.steps ?: listOf(Step(""))) } }
    val days = remember { mutableStateListOf<Boolean>().apply { addAll(old?.days ?: List(7) { true }) } }
    val reminders = remember { mutableStateListOf<String>().apply { addAll(old?.reminders.orEmpty()) } }
    var nag by remember { mutableStateOf(old?.nag ?: true) }
    var nagMinutes by remember { mutableStateOf(old?.nagMinutes ?: 15) }
    var pinned by remember { mutableStateOf(old?.pinned ?: true) }

    fun save() {
        if (name.isBlank()) return
        val clean = steps.map { Step(it.label.trim(), it.prop.trim()) }.filter { it.label.isNotEmpty() }.ifEmpty { listOf(Step(name.trim())) }
        val h = (old ?: Habit(newId("h"), name, steps = clean, created = data.currentDay)).copy(
            name = name.trim(), whenText = whenText.trim(), steps = clean, days = days.toList(),
            reminders = reminders.sorted(), nag = nag, nagMinutes = nagMinutes, pinned = pinned,
        )
        Store.update(context) { d -> d.copy(habits = if (old == null) d.habits + h else d.habits.map { if (it.id == h.id) h else it }) }
        back()
    }

    Form(if (old == null) "New habit" else "Edit habit", back, ::save, onDelete = old?.let { o ->
        { Store.update(context) { d -> d.copy(habits = d.habits.filter { it.id != o.id }) }; back() }
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(whenText, { whenText = it }, label = { Text("When (shown if there's no reminder)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text("Steps (shown when you tap the habit)", fontWeight = FontWeight.SemiBold)
        steps.forEachIndexed { i, s ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(GroveColors.Surface).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(s.label, { steps[i] = s.copy(label = it) }, label = { Text("Step ${i + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { steps.removeAt(i) }) { Icon(Icons.Filled.Close, "Remove step ${i + 1}") }
                }
                OutlinedTextField(s.prop, { steps[i] = s.copy(prop = it) }, label = { Text("Obsidian property to tick (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedButton(onClick = { steps += Step("") }, modifier = Modifier.fillMaxWidth()) { Text("+ Add step") }

        DayPicker("Which days", days)
        ReminderEditor(reminders, nag, { nag = it }, nagMinutes, { nagMinutes = it })
        SwitchRow("Show in pinned notification", "Check it off right from the notification shade", pinned) { pinned = it }
    }
}

@Composable
fun EditTaskScreen(data: AppData, id: String?, back: () -> Unit) {
    val context = LocalContext.current
    val old = data.tasks.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(old?.name.orEmpty()) }
    var list by remember { mutableStateOf(old?.list ?: "Inbox") }
    val days = remember { mutableStateListOf<Boolean>().apply { addAll(old?.days ?: List(7) { false }) } }
    var date by remember { mutableStateOf(old?.date?.ifEmpty { null } ?: LocalDate.now().toString()) }
    var time by remember { mutableStateOf(old?.time ?: "16:30") }
    var firstOfMonth by remember { mutableStateOf(old?.firstOfMonth ?: false) }
    var prop by remember { mutableStateOf(old?.prop.orEmpty()) }
    var high by remember { mutableStateOf(old?.high ?: false) }
    val reminders = remember { mutableStateListOf<String>().apply { addAll(old?.reminders ?: listOf("16:30")) } }
    var nag by remember { mutableStateOf(old?.nag ?: true) }
    var nagMinutes by remember { mutableStateOf(old?.nagMinutes ?: 30) }

    fun save() {
        if (name.isBlank()) return
        val t = (old ?: Task(newId("t"), name)).copy(
            name = name.trim(), list = list.trim().ifEmpty { "Inbox" }, days = days.toList(), date = if (days.any { it }) "" else date,
            time = time, firstOfMonth = firstOfMonth && days.any { it }, prop = prop.trim(), high = high,
            reminders = reminders.sorted(), nag = nag, nagMinutes = nagMinutes,
        )
        Store.update(context) { d -> d.copy(tasks = if (old == null) d.tasks + t else d.tasks.map { if (it.id == t.id) t else it }) }
        back()
    }

    Form(if (old == null) "New task" else "Edit task", back, ::save, onDelete = old?.let { o ->
        { Store.update(context) { d -> d.copy(tasks = d.tasks.filter { it.id != o.id }) }; back() }
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(list, { list = it }, label = { Text("List (Home, Druid Path…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DayPicker("Repeats on (leave empty for a one-time task)", days)
        if (days.any { it }) {
            SwitchRow("Only the first one of the month", "e.g. first Sunday", firstOfMonth) { firstOfMonth = it }
        } else {
            OutlinedButton(onClick = {
                val d = LocalDate.parse(date)
                DatePickerDialog(context, { _, y, m, dd -> date = LocalDate.of(y, m + 1, dd).toString() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("Date: $date") }
        }
        OutlinedButton(onClick = { pickTime(context, time) { time = it } }, modifier = Modifier.fillMaxWidth()) { Text("Time: ${Logic.fmtTime(time)}") }
        OutlinedTextField(prop, { prop = it }, label = { Text("Obsidian property to tick (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SwitchRow("High priority", "Marked with ! in lists", high) { high = it }
        ReminderEditor(reminders, nag, { nag = it }, nagMinutes, { nagMinutes = it })
    }
}

@Composable
private fun Form(title: String, back: () -> Unit, save: () -> Unit, onDelete: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("Cancel") }
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Button(onClick = save) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            content()
            if (onDelete != null) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = GroveColors.Warn)) {
                    Text("Delete")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(label: String, days: SnapshotStateList<Boolean>) {
    Text(label, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEachIndexed { i, on ->
            FilterChip(selected = on, onClick = { days[i] = !on }, label = { Text(DAY_LETTERS[i]) })
        }
    }
}

/** Reminder times, plus "keep reminding until done" and how often. Snooze length is picked on the notification. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReminderEditor(reminders: SnapshotStateList<String>, nag: Boolean, setNag: (Boolean) -> Unit, nagMinutes: Int, setNagMinutes: (Int) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GroveColors.Surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Notifications", fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reminders.sorted().forEach { t ->
                InputChip(selected = false, onClick = { reminders.remove(t) }, label = { Text(Logic.fmtTime(t)) },
                    trailingIcon = { Icon(Icons.Filled.Close, "Remove ${Logic.fmtTime(t)} reminder") })
            }
            if (reminders.isEmpty()) Text("No timed reminders.", color = GroveColors.Muted, fontSize = 13.sp)
        }
        OutlinedButton(onClick = { pickTime(context, reminders.maxOrNull() ?: "14:00") { if (it !in reminders) reminders += it } }) { Text("+ Add reminder time") }
        SwitchRow("Keep reminding until done", "Re-alerts after each reminder until you check it off", nag, setNag)
        if (nag) {
            Text("Repeat every", fontSize = 12.sp, color = GroveColors.Muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NAG_CHOICES.forEach { n -> FilterChip(selected = nagMinutes == n, onClick = { setNagMinutes(n) }, label = { Text("$n min") }) }
            }
        }
        Text("Every reminder has Done and Snooze buttons. Snooze… lets you pick 5 minutes to 2 hours.", fontSize = 12.sp, color = GroveColors.Muted)
    }
}

@Composable
fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (sub != null) Text(sub, fontSize = 12.sp, color = GroveColors.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
