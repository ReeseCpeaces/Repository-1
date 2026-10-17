package com.grove.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.grove.app.obsidian.ObsidianSync
import java.time.LocalDateTime

@Composable
fun HabitScreen(data: AppData, id: String, go: (Screen) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val h = data.habits.firstOrNull { it.id == id }
    if (h == null) {
        LaunchedEffect(id) { back() }
        return
    }
    val done = Logic.isDoneToday(data, h)
    val stepsDone = Logic.stepsDone(data, data.currentDay, h).toSet()
    val status = when (Logic.streakState(data, h)) {
        Logic.StreakState.NEW -> (if (done) "First day done. Streak started." else "New habit: check in today to start a streak.") to false
        Logic.StreakState.RESET -> "Missed the last two days, so the streak reset. Start fresh today." to true
        Logic.StreakState.PAUSED -> "Missed yesterday, so the streak is paused. Check in today to keep it." to true
        Logic.StreakState.OK -> (if (done) "Done for today." else "Not done yet today.") to false
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        BackBar(back) {
            TextButton(onClick = { go(Screen.EditHabit(h.id)) }) {
                Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Text("  Edit habit & reminders")
            }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(h.whenText, color = GroveColors.Muted, fontSize = 13.sp)
                Text(h.name, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("${Logic.streak(data, h)}", "day streak", GroveColors.Green, Modifier.weight(1f))
                Stat("${Logic.totalDays(data, h)}", "total days", GroveColors.Text, Modifier.weight(1f))
            }
            Text(
                status.first, fontSize = 14.sp,
                color = if (status.second) GroveColors.Warn else GroveColors.Text,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (status.second) GroveColors.WarnBg else GroveColors.Surface).padding(12.dp),
            )
            WeekStrip(data, h)
            Text("Miss one day and the streak pauses. Miss two in a row and it resets.", fontSize = 12.sp, color = GroveColors.Muted)

            SectionLabel("Today's steps", GroveColors.Green)
            h.steps.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckMark(i in stepsDone, s.label, square = false, onToggle = {
                        Store.update(context) { Logic.toggleStep(it, h.id, i, LocalDateTime.now()) }
                    })
                    Column {
                        Text(s.label, fontSize = 15.sp, color = if (i in stepsDone) GroveColors.Muted else GroveColors.Text)
                        if (s.prop.isNotBlank()) Text("Ticks \"${s.prop}\" in today's note", fontSize = 12.sp, color = GroveColors.Muted)
                    }
                }
            }

            Card {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Reminders", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (h.reminders.isEmpty()) "No timed reminder. ${h.whenText}." else "At " + h.reminders.sorted().joinToString(" and ") { Logic.fmtTime(it) },
                        fontSize = 13.sp, color = GroveColors.Muted,
                    )
                    if (h.reminders.isNotEmpty()) {
                        Text(if (h.nag) "Keeps reminding every ${h.nagMinutes} min until done." else "Reminds once.", fontSize = 13.sp, color = GroveColors.Muted)
                    }
                    Text(if (h.pinned) "Shown in the pinned notification." else "Not in the pinned notification.", fontSize = 13.sp, color = GroveColors.Muted)
                }
            }
            if (data.settings.vaultUri.isNotEmpty()) {
                OutlinedButton(onClick = { ObsidianSync.openInObsidian(context, data) }, modifier = Modifier.fillMaxWidth()) { Text("Open today's note") }
            }
            Box(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(GroveColors.Surface).padding(14.dp)) {
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, color = GroveColors.Muted)
    }
}

/** The past six days plus today: filled = done, dashed = missed, ring = today. */
@Composable
private fun WeekStrip(data: AppData, h: com.grove.app.data.Habit) {
    val today = Logic.date(data.currentDay)
    val created = Logic.date(h.created)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 6 downTo 0) {
            val d = today.minusDays(i.toLong())
            val done = Logic.isDone(data, d.toString(), h)
            val before = d.isBefore(created) || !Logic.scheduledOn(h, d)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SMTWTFS"[Logic.weekdayIndex(d)].toString(), fontSize = 11.sp, color = GroveColors.Muted)
                val mod = Modifier.size(36.dp).clip(CircleShape)
                Box(
                    when {
                        done -> mod.background(GroveColors.Green)
                        i == 0 -> mod.border(2.dp, GroveColors.Green, CircleShape)
                        before -> mod
                        else -> mod.border(1.dp, GroveColors.Line, CircleShape)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${d.dayOfMonth}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (done) GroveColors.Bg else if (before) GroveColors.Line else GroveColors.Text)
                }
            }
        }
    }
}
