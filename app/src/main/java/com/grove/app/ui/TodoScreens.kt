package com.grove.app.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
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

/** The to-do list on Today: stays until checked off; checked ones are crossed out for the rest of the day. */
@Composable
fun TodoRow(data: AppData, t: Todo, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val done = t.done.isNotEmpty()
    Card(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CheckMark(done, (if (done) "Uncheck " else "Check off ") + t.name, square = true, onToggle = {
                Store.update(context) { Todos.toggle(it, t.id) }
            })
            Column(Modifier.weight(1f).clickable { go(Screen.EditTodo(t.id)) }.padding(vertical = 8.dp)) {
                ItemTitle((if (t.high) "! " else "") + t.name, done)
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
}

@Composable
fun EditTodoScreen(data: AppData, id: String?, back: () -> Unit) {
    val context = LocalContext.current
    val old = data.todos.firstOrNull { it.id == id }
    var name by remember { mutableStateOf(old?.name.orEmpty()) }
    var list by remember { mutableStateOf(old?.list.orEmpty()) }
    var high by remember { mutableStateOf(old?.high ?: false) }
    var due by remember { mutableStateOf(old?.due.orEmpty()) }
    var remind by remember { mutableStateOf(old?.remind.orEmpty()) }

    fun save() {
        if (name.isBlank()) return
        Store.update(context) { d ->
            if (old == null) Todos.add(d, name, list, high, due, remind)
            else d.copy(todos = d.todos.map { if (it.id == old.id) it.copy(name = name.trim(), list = list.trim(), high = high, due = due, remind = remind) else it })
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
            SwitchRow("High priority", "Shown first, marked with !", high) { high = it }
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
