package com.grove.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.data.Store
import com.grove.app.data.Todo
import com.grove.app.data.Todos
import java.time.LocalDate

/** Priority colors, hot to cool (10–9 red … 2–1 blue-grey); bright enough for the dark theme. */
val PRIORITY_COLORS = listOf(Color(0xFFE5534B), Color(0xFFEA8A3A), Color(0xFFE3C44A), Color(0xFF8FCB8B), Color(0xFF93A6C2))
private val BADGE_TEXT = Color(0xFF0F1713)

fun priorityColor(p: Int): Color = PRIORITY_COLORS[Todos.band(p)]

/** The number in a small colored badge. */
@Composable
fun PriorityBadge(p: Int, faded: Boolean = false, modifier: Modifier = Modifier) {
    Box(
        modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(priorityColor(p).copy(alpha = if (faded) 0.4f else 1f)),
        contentAlignment = Alignment.Center,
    ) { Text("$p", color = BADGE_TEXT, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

/** Ten big colored buttons: 10–6 on top, 5–1 below. */
@Composable
fun PriorityPicker(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in listOf(10 downTo 6, 5 downTo 1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in row) {
                    val on = p == selected
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                            .background(priorityColor(p).copy(alpha = if (on) 1f else 0.35f))
                            .then(if (on) Modifier.border(2.dp, GroveColors.Text, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { onPick(p) },
                        contentAlignment = Alignment.Center,
                    ) { Text("$p", color = if (on) BADGE_TEXT else GroveColors.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                }
            }
        }
        Text("10 = most important, 1 = least", fontSize = 12.sp, color = GroveColors.Muted)
    }
}

/** Quick priority change (tap the badge or long-press the to-do). */
@Composable
fun PriorityDialog(t: Todo, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Priority: ${t.name}", maxLines = 2) },
        text = { PriorityPicker(Todos.priority(t)) { p -> Store.update(context) { Todos.setPriority(it, t.id, p) }; onClose() } },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** The to-do list on Today: highest priority first; checked ones are crossed out at the bottom for the rest of the day. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodoRow(data: AppData, t: Todo, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val done = t.done.isNotEmpty()
    var picking by remember { mutableStateOf(false) }
    Card(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckMark(done, (if (done) "Uncheck " else "Check off ") + t.name, square = true, onToggle = {
                Store.update(context) { Todos.toggle(it, t.id) }
            })
            PriorityBadge(Todos.priority(t), faded = done, modifier = Modifier.padding(end = 10.dp).clickable { picking = true })
            Column(Modifier.weight(1f).combinedClickable(onClick = { go(Screen.EditTodo(t.id)) }, onLongClick = { picking = true }).padding(vertical = 8.dp)) {
                ItemTitle(t.name, done)
                val bits = listOfNotNull(
                    t.list.ifEmpty { null },
                    t.due.takeIf { it.isNotEmpty() }?.let { due ->
                        val late = !done && due < data.currentDay
                        (if (late) "was due " else "by ") + LocalDate.parse(due).let { "${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)} ${it.dayOfMonth}" }
                    },
                    t.remind.takeIf { it.isNotEmpty() }?.let { "reminds " + Logic.fmtTime(it) },
                )
                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), fontSize = 12.sp, color = GroveColors.Muted)
            }
        }
    }
    if (picking) PriorityDialog(t) { picking = false }
}

@Composable
fun EditTodoScreen(data: AppData, id: String?, back: () -> Unit) {
    val context = LocalContext.current
    val old = data.todos.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(old?.name.orEmpty()) }
    var list by remember { mutableStateOf(old?.list.orEmpty()) }
    val high = old?.high ?: false
    var priority by remember { mutableStateOf(old?.let { Todos.priority(it) } ?: Todos.DEFAULT_PRIORITY) }
    var due by remember { mutableStateOf(old?.due.orEmpty()) }
    var remind by remember { mutableStateOf(old?.remind.orEmpty()) }

    fun save() {
        if (name.isBlank()) return
        Store.update(context) { d ->
            if (old == null) Todos.add(d, name, list, high, due, remind, priority)
            else d.copy(todos = d.todos.map { if (it.id == old.id) it.copy(name = name.trim(), list = list.trim(), due = due, remind = remind, priority = priority) else it })
        }
        back()
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("Cancel") }
            Text(if (old == null) "New to-do" else "Edit to-do", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Button(onClick = ::save) { Text("Save") }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("No date: it stays on Today until you check it off." +
                if (data.settings.todoNotePath.isNotBlank()) " Also kept in ${data.settings.todoNotePath} in Obsidian." else "",
                fontSize = 13.sp, color = GroveColors.Muted)
            OutlinedTextField(name, { name = it }, label = { Text("To-do") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(list, { list = it }, label = { Text("List (optional: Home, Druid Path…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Priority", fontWeight = FontWeight.SemiBold)
            PriorityPicker(priority) { priority = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    val d = due.ifEmpty { null }?.let { LocalDate.parse(it) } ?: Logic.groveToday(data)
                    DatePickerDialog(context, { _, y, m, dd -> due = LocalDate.of(y, m + 1, dd).toString() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }, modifier = Modifier.weight(1f)) { Text(if (due.isEmpty()) "Do by (optional)" else "Do by $due") }
                if (due.isNotEmpty()) TextButton(onClick = { due = "" }) { Text("Clear") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickTime(context, remind.ifEmpty { "10:00" }) { remind = it } }, modifier = Modifier.weight(1f)) {
                    Text(if (remind.isEmpty()) "Daily reminder (optional)" else "Reminds daily at ${Logic.fmtTime(remind)} until done")
                }
                if (remind.isNotEmpty()) TextButton(onClick = { remind = "" }) { Text("Clear") }
            }
            if (old != null) {
                OutlinedButton(onClick = { Store.update(context) { Todos.delete(it, old.id) }; back() }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GroveColors.Warn)) { Text("Delete") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** "+ Add a to-do" at the bottom of the list. */
@Composable
fun AddTodoRow(go: (Screen) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { go(Screen.EditTodo(null)) }.heightIn(min = 48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("+ Add a to-do", color = GroveColors.Green, fontSize = 14.sp)
    }
}
