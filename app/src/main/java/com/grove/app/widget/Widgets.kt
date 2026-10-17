package com.grove.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.data.Store
import com.grove.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private val BG = Color(0xF21B231E)
private val TEXT = Color(0xFFECEFE9)
private val MUTED = Color(0xFFA3AEA6)
private val GREEN = Color(0xFF8FCB8B)
private val AMBER = Color(0xFFE9A23B)

private fun style(color: Color, size: Int, bold: Boolean = false, strike: Boolean = false, center: Boolean = false) = TextStyle(
    color = ColorProvider(color), fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
    textAlign = if (center) TextAlign.Center else TextAlign.Start,
)

object Widgets {
    val KIND = ActionParameters.Key<String>("kind")
    val ID = ActionParameters.Key<String>("id")
    val HABIT = ActionParameters.Key<String>(MainActivity.EXTRA_HABIT)
    val SCREEN = ActionParameters.Key<String>(MainActivity.EXTRA_SCREEN)

    fun updateAll(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            TodayWidget().updateAll(context)
            HolyDaysWidget().updateAll(context)
        }
    }
}

/** Checkbox taps on a widget: check the item off without opening the app. */
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[Widgets.ID] ?: return
        val now = java.time.LocalDateTime.now()
        Store.update(context) { d ->
            if (parameters[Widgets.KIND] == "habit") Logic.toggleHabit(d, id, now) else Logic.toggleTask(d, id, now)
        }
    }
}

/** Month calendar beside today's habits and tasks, kept in separate sections. */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Store.refreshDay(context)
        provideContent { Content(Store.get(context)) }
    }

    @Composable
    private fun Content(data: AppData) {
        val today = LocalDate.now()
        Row(GlanceModifier.fillMaxSize().background(BG).cornerRadius(24.dp).padding(10.dp)) {
            Calendar(data, today)
            Spacer(GlanceModifier.width(10.dp))
            Lists(data)
        }
    }

    @Composable
    private fun Calendar(data: AppData, today: LocalDate) {
        val first = today.withDayOfMonth(1)
        val blanks = first.dayOfWeek.value % 7
        val cells: List<Int?> = List(blanks) { null } + (1..today.lengthOfMonth()).toList()
        Column(GlanceModifier.width(154.dp).clickable(actionStartActivity<MainActivity>())) {
            Text(today.month.getDisplayName(JTextStyle.FULL, Locale.US), GlanceModifier.fillMaxWidth(), style(GREEN, 15, bold = true, center = true))
            Row(GlanceModifier.fillMaxWidth()) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach {
                    Text(it, GlanceModifier.width(22.dp), style(MUTED, 10, bold = true, center = true))
                }
            }
            cells.chunked(7).forEach { week ->
                Row(GlanceModifier.fillMaxWidth().height(20.dp)) {
                    week.forEach { day ->
                        val label = day?.toString().orEmpty()
                        val date = day?.let { first.withDayOfMonth(it) }
                        val holy = date != null && data.holyDays.any { it.date == date.toString() }
                        val isToday = date == today
                        val color = when {
                            isToday -> Color(0xFF0F1713)
                            holy -> AMBER
                            date != null && (date.dayOfWeek.value % 7 == 0 || date.dayOfWeek.value == 6) -> MUTED
                            else -> TEXT
                        }
                        Box(
                            GlanceModifier.width(22.dp).height(20.dp).then(if (isToday) GlanceModifier.background(GREEN).cornerRadius(10.dp) else GlanceModifier),
                            contentAlignment = Alignment.Center,
                        ) { Text(label, style = style(color, 11, bold = isToday || holy, center = true)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Lists(data: AppData) {
        val day = Logic.date(data.currentDay)
        val habits = data.habits.filter { Logic.scheduledOn(it, day) }
        val tasks = Logic.tasksOn(data, LocalDate.now())
        val done = habits.count { Logic.isDoneToday(data, it) }
        LazyColumn(GlanceModifier.fillMaxSize()) {
            item { Text("HABITS  $done/${habits.size}", style = style(GREEN, 10, bold = true)) }
            items(habits.size) { i ->
                val h = habits[i]
                val checked = Logic.isDoneToday(data, h)
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (checked) "●" else "○",
                        GlanceModifier.width(26.dp).clickable(actionRunCallback<ToggleAction>(actionParametersOf(Widgets.KIND to "habit", Widgets.ID to h.id))),
                        style(GREEN, 18, center = true),
                    )
                    Text(
                        h.name,
                        GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>(actionParametersOf(Widgets.HABIT to h.id))),
                        style(if (checked) MUTED else TEXT, 13, strike = checked),
                        maxLines = 1,
                    )
                    Text("${Logic.streak(data, h)}d", style = style(GREEN, 10, bold = true))
                }
            }
            item { Spacer(GlanceModifier.height(6.dp)) }
            item { Text("TASKS", style = style(AMBER, 10, bold = true)) }
            if (tasks.isEmpty()) item { Text("Nothing scheduled.", style = style(MUTED, 12)) }
            items(tasks.size) { i ->
                val t = tasks[i]
                val checked = Logic.isTaskDone(data, data.currentDay, t.id)
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (checked) "■" else "□",
                        GlanceModifier.width(26.dp).clickable(actionRunCallback<ToggleAction>(actionParametersOf(Widgets.KIND to "task", Widgets.ID to t.id))),
                        style(AMBER, 18, center = true),
                    )
                    Text(t.name, GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()), style(if (checked) MUTED else TEXT, 13, strike = checked), maxLines = 1)
                    Text(Logic.fmtTime(t.time), style = style(MUTED, 10))
                }
            }
        }
    }
}

/** Upcoming holy days with countdowns. */
class HolyDaysWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content(Store.get(context)) }
    }

    @Composable
    private fun Content(data: AppData) {
        val upcoming = Logic.upcomingHolyDays(data, LocalDate.now())
        Column(GlanceModifier.fillMaxSize().background(BG).cornerRadius(24.dp).padding(12.dp)
            .clickable(actionStartActivity<MainActivity>(actionParametersOf(Widgets.SCREEN to "today")))) {
            Text("Wheel of the Year", style = style(TEXT, 15, bold = true))
            Spacer(GlanceModifier.height(4.dp))
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(upcoming.size) { i ->
                    val (h, n) = upcoming[i]
                    val d = Logic.date(h.date)
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(GlanceModifier.defaultWeight()) {
                            Text(h.name, style = style(TEXT, 13, bold = true))
                            Text("${d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.US)}, ${d.month.getDisplayName(JTextStyle.SHORT, Locale.US)} ${d.dayOfMonth} · ${h.detail}", style = style(MUTED, 11), maxLines = 1)
                        }
                        Text(Logic.countdown(n), style = style(GREEN, 11, bold = true))
                    }
                }
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class HolyDaysWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HolyDaysWidget()
}
