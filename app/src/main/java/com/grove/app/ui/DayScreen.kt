package com.grove.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.data.Store
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One day laid out by hour, like a calendar day view. Tap an empty hour (or "Add task")
 * to add a task on this day at that time.
 */
@Composable
fun DayScreen(data: AppData, dateKey: String, go: (Screen) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var date by remember(dateKey) { mutableStateOf(LocalDate.parse(dateKey)) }
    val today = Logic.groveToday(data)
    val isToday = date == today
    val dayKey = date.toString()
    // Today and any earlier day can be checked off; the future is just a plan.
    val markable = !date.isAfter(today)
    val tasks = Logic.tasksOn(data, date)
    val byHour = tasks.groupBy { it.time.substringBefore(':').toInt() }
    val firstHour = minOf(8, byHour.keys.minOrNull() ?: 8)
    val hours = (firstHour..23).toList()
    val habits = if (!markable) emptyList()
        else data.habits.filter { Logic.scheduledOn(it, date) || Logic.isDone(data, dayKey, it) }
    val holy = data.holyDays.firstOrNull { it.date == date.toString() }
    val list = rememberLazyListState()
    LaunchedEffect(date) { list.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { Icon(Icons.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isToday) "Today" else if (date == today.plusDays(1)) "Tomorrow" else if (date == today.minusDays(1)) "Yesterday" else Logic.weekdayName(date),
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp,
                        )
                        Text("${Logic.weekdayName(date)}, ${date.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}, ${date.year}",
                            fontSize = 12.sp, color = GroveColors.Muted)
                    }
                    IconButton(onClick = { date = date.minusDays(1) }) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous day") }
                    IconButton(onClick = { date = date.plusDays(1) }) { Icon(Icons.Filled.KeyboardArrowRight, "Next day") }
                }
            }
            item { WeekStrip(data, date) { date = it } }
            if (date == LocalDate.now() && date.isAfter(today)) {
                item {
                    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(vertical = 10.dp)) {
                            Text("Grove is still on ${Logic.weekdayName(today)} ${today.dayOfMonth}. Start this day to check things off.",
                                fontSize = 13.sp, color = GroveColors.Muted)
                            androidx.compose.material3.TextButton(onClick = { Store.update(context) { Logic.startNewDay(it, LocalDateTime.now()) } }) {
                                Text("Start ${Logic.weekdayName(date)} ${date.dayOfMonth} now")
                            }
                        }
                    }
                }
            }
            if (holy != null) {
                item {
                    Text(
                        "${holy.name} · ${holy.detail}", fontWeight = FontWeight.SemiBold, color = GroveColors.Warn,
                        modifier = Modifier.padding(16.dp, 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GroveColors.WarnBg).padding(12.dp),
                    )
                }
            }
            if (habits.isNotEmpty()) {
                item { Column(Modifier.padding(horizontal = 16.dp)) { SectionLabel(if (isToday) "Habits (any time today)" else "Habits · tap to mark or undo", GroveColors.Green) } }
                items(habits, key = { "h" + it.id }) { h ->
                    val done = Logic.isDone(data, dayKey, h)
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CheckMark(done, (if (done) "Uncheck " else "Check off ") + h.name, square = false, onToggle = {
                            Store.update(context) { Logic.toggleHabit(it, h.id, LocalDateTime.now(), dayKey) }
                        })
                        ItemTitle(h.name, done, Modifier.weight(1f).clickable { go(Screen.HabitDetail(h.id)) }, size = 15)
                    }
                }
            }
            item { Column(Modifier.padding(horizontal = 16.dp)) { SectionLabel("Schedule", GroveColors.Amber) } }
            items(hours, key = { "hour$it" }) { hour ->
                val hh = "%02d:00".format(hour)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { go(Screen.EditTask(null, date.toString(), hh)) }.padding(horizontal = 16.dp),
                ) {
                    Text(Logic.fmtTime(hh).replace(":00", ""), Modifier.width(56.dp).padding(top = 6.dp), fontSize = 12.sp, color = GroveColors.Muted)
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(GroveColors.Line))
                        byHour[hour].orEmpty().forEach { t ->
                            val done = markable && Logic.isTaskDone(data, dayKey, t.id)
                            Row(
                                Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GroveColors.Surface)
                                    .clickable(enabled = t.task != null) { go(Screen.EditTask(t.id)) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (markable) {
                                    CheckMark(done, (if (done) "Uncheck " else "Check off ") + t.name, square = true, onToggle = {
                                        Store.update(context) { Logic.toggleTask(it, t.id, LocalDateTime.now(), dayKey) }
                                    }, size = 24)
                                } else {
                                    Box(Modifier.width(14.dp))
                                }
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    ItemTitle((if (t.task?.high == true) "! " else "") + t.name, done, size = 15)
                                    Text("${Logic.fmtTime(t.time)} · ${t.list}", fontSize = 12.sp, color = GroveColors.Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { go(Screen.EditTask(null, date.toString(), null)) },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Add task") },
            containerColor = GroveColors.Green,
            contentColor = GroveColors.Bg,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
}

/** Seven days starting from the week's Sunday, with a dot on days that have tasks. */
@Composable
fun WeekStrip(data: AppData, selected: LocalDate, onPick: (LocalDate) -> Unit) {
    val start = selected.minusDays((selected.dayOfWeek.value % 7).toLong())
    val today = Logic.groveToday(data)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        for (i in 0 until 7) {
            val d = start.plusDays(i.toLong())
            val sel = d == selected
            val has = Logic.tasksOn(data, d).isNotEmpty()
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) GroveColors.Green else GroveColors.Bg)
                    .clickable { onPick(d) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("SMTWTFS"[i].toString(), fontSize = 11.sp, color = if (sel) GroveColors.Bg else GroveColors.Muted)
                Text("${d.dayOfMonth}", fontWeight = FontWeight.Bold,
                    color = if (sel) GroveColors.Bg else if (d == today) GroveColors.Green else GroveColors.Text)
                Box(Modifier.padding(top = 3.dp).width(5.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (has) (if (sel) GroveColors.Bg else GroveColors.Amber) else androidx.compose.ui.graphics.Color.Transparent))
            }
        }
    }
}
