package com.grove.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import java.time.LocalDateTime

private val WEEKDAYS = listOf("Sundays", "Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays")

@Composable
fun TodayScreen(data: AppData, go: (Screen) -> Unit, tab: (Screen) -> Unit) {
    val context = LocalContext.current
    val day = Logic.date(data.currentDay)
    val habits = data.habits.filter { Logic.scheduledOn(it, day) }
    val tasks = Logic.tasksOn(data, day)
    var menu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 8.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Title("Today", "${Logic.weekdayName(day)}, ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${day.dayOfMonth}")
                    }
                    IconButton(onClick = { go(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Settings") }
                }
                Tabs(forest = false, onToday = {}, onForest = { tab(Screen.Forest) })
                // Tap a day to open its hour-by-hour view.
                WeekStrip(data, day) { go(Screen.Day(it.toString())) }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel("Habits  ${Logic.doneCount(data)}/${data.habits.size} done", GroveColors.Green)
                }
            }
            items(habits, key = { "h" + it.id }) { h ->
                val done = Logic.isDoneToday(data, h)
                val streak = Logic.streak(data, h)
                val n = Logic.stepsDone(data, data.currentDay, h).size
                Card(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CheckMark(done, (if (done) "Uncheck " else "Check off ") + h.name, square = false, onToggle = {
                            Store.update(context) { Logic.toggleHabit(it, h.id, LocalDateTime.now()) }
                        })
                        Column(Modifier.weight(1f).clickable { go(Screen.HabitDetail(h.id)) }.padding(vertical = 8.dp)) {
                            ItemTitle(h.name, done)
                            val whenText = if (h.reminders.isNotEmpty()) h.reminders.sorted().joinToString(", ") { Logic.fmtTime(it) } else h.whenText
                            Text("$n/${h.steps.size} steps · $whenText", fontSize = 12.sp, color = GroveColors.Muted, maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 4.dp)) {
                            Text("$streak", fontWeight = FontWeight.Bold, color = GroveColors.Green)
                            Text(if (streak == 1) "day" else "days", fontSize = 10.sp, color = GroveColors.Muted)
                        }
                    }
                }
            }
            // Forgot to check something off before the day rolled over? Mark it here.
            val yesterday = day.minusDays(1).toString()
            val missed = Logic.missedOn(data, yesterday)
            if (missed.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionLabel("Yesterday · not checked off", GroveColors.Muted) }
                            TextButton(onClick = { go(Screen.Day(yesterday)) }) { Text("Earlier days", fontSize = 13.sp) }
                        }
                    }
                }
                items(missed, key = { "y" + it.id }) { h ->
                    Card(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CheckMark(false, "Mark ${h.name} done yesterday", square = false, onToggle = {
                                Store.update(context) { Logic.toggleHabit(it, h.id, LocalDateTime.now(), yesterday) }
                            })
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                ItemTitle(h.name, false)
                                Text("Did it but forgot to tap? Check it off for yesterday.", fontSize = 12.sp, color = GroveColors.Muted)
                            }
                        }
                    }
                }
            }
            if (data.goals.isNotEmpty()) {
                item { Column(Modifier.padding(horizontal = 16.dp)) { SectionLabel("Weekly goals · any day counts", GroveColors.Green) } }
                item { Column { GoalCards(data, go) } }
            }
            item { Column(Modifier.padding(horizontal = 16.dp)) { SectionLabel("Tasks today", GroveColors.Amber) } }
            if (tasks.isEmpty()) item { Text("Nothing scheduled.", Modifier.padding(horizontal = 16.dp), color = GroveColors.Muted) }
            items(tasks, key = { "t" + it.id }) { t ->
                val done = Logic.isTaskDone(data, data.currentDay, t.id)
                Card(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CheckMark(done, (if (done) "Uncheck " else "Check off ") + t.name, square = true, onToggle = {
                            Store.update(context) { Logic.toggleTask(it, t.id, LocalDateTime.now()) }
                        })
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            ItemTitle((if (t.task?.high == true) "! " else "") + t.name, done)
                            Text("${Logic.fmtTime(t.time)} · ${t.list}", fontSize = 12.sp, color = GroveColors.Muted)
                        }
                        if (t.task != null) IconButton(onClick = { go(Screen.EditTask(t.id)) }) { Icon(Icons.Filled.Edit, "Edit ${t.name}") }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SectionLabel("Wheel of the Year", GroveColors.Muted)
                    Logic.upcomingHolyDays(data, day).take(3).forEach { (h, n) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(h.name, fontWeight = FontWeight.SemiBold)
                                Text(h.detail, fontSize = 12.sp, color = GroveColors.Muted)
                            }
                            Text(Logic.countdown(n), color = GroveColors.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    SectionLabel("All tasks", GroveColors.Muted)
                }
            }
            items(data.tasks, key = { "a" + it.id }) { t ->
                val whenText = when {
                    t.monthDay > 0 -> "Monthly on the ${ordinal(t.monthDay)}"
                    t.firstOfMonth -> "First ${WEEKDAYS[t.days.indexOf(true)].removeSuffix("s")} of the month"
                    t.repeating -> t.days.mapIndexedNotNull { i, on -> if (on) WEEKDAYS[i] else null }.joinToString(", ")
                    else -> t.date
                }
                Row(
                    Modifier.fillMaxWidth().clickable { go(Screen.EditTask(t.id)) }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontSize = 14.sp)
                        Text("$whenText · ${Logic.fmtTime(t.time)}", fontSize = 12.sp, color = GroveColors.Muted)
                    }
                    Text("${t.reminders.size} reminder${if (t.reminders.size == 1) "" else "s"}", fontSize = 12.sp, color = GroveColors.Muted)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            ExtendedFloatingActionButton(
                onClick = { menu = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New") },
                containerColor = GroveColors.Green,
                contentColor = GroveColors.Bg,
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("New habit") }, onClick = { menu = false; go(Screen.EditHabit(null)) })
                DropdownMenuItem(text = { Text("New task") }, onClick = { menu = false; go(Screen.EditTask(null)) })
            }
        }
    }
}

fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"
    n % 10 == 2 -> "nd"
    n % 10 == 3 -> "rd"
    else -> "th"
}
