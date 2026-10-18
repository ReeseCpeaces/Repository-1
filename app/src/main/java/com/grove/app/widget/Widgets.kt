package com.grove.app.widget

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
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
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private val BG = Color(0xF21B231E)
private val TEXT = Color(0xFFECEFE9)
private val MUTED = Color(0xFFA3AEA6)
private val GREEN = Color(0xFF8FCB8B)
private val AMBER = Color(0xFFE9A23B)
private val DARK = Color(0xFF0F1713)

private fun style(color: Color, size: Int, bold: Boolean = false, strike: Boolean = false, center: Boolean = false) = TextStyle(
    color = ColorProvider(color), fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    textDecoration = if (strike) TextDecoration.LineThrough else TextDecoration.None,
    textAlign = if (center) TextAlign.Center else TextAlign.Start,
)

private fun short(d: LocalDate) =
    "${d.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.US)}, ${d.month.getDisplayName(JTextStyle.SHORT, Locale.US)} ${d.dayOfMonth}"

object Widgets {
    val KIND = ActionParameters.Key<String>("kind")
    val ID = ActionParameters.Key<String>("id")
    val DATE = ActionParameters.Key<String>("date")
    val DELTA = ActionParameters.Key<Int>("delta")
    val HABIT = ActionParameters.Key<String>(MainActivity.EXTRA_HABIT)
    val DAY = ActionParameters.Key<String>(MainActivity.EXTRA_DAY)

    /** Per-widget state: the day picked on the calendar and how many months away from now it shows. */
    val SELECTED = stringPreferencesKey("selected")
    val MONTH = intPreferencesKey("month")

    /**
     * Redraw every widget. Off the main thread this waits for the redraw, so a broadcast receiver
     * (the midnight tick, a notification button) can't be torn down halfway through.
     */
    fun updateAll(context: Context) {
        val work: suspend () -> Unit = {
            TodayWidget().updateAll(context)
            HolyDaysWidget().updateAll(context)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) CoroutineScope(Dispatchers.Default).launch { work() } else runBlocking { work() }
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

/** Tapping a day shows that day's tasks beside the calendar. */
class SelectDayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val date = parameters[Widgets.DATE] ?: return
        updateAppWidgetState(context, glanceId) { it[Widgets.SELECTED] = date }
        TodayWidget().update(context, glanceId)
    }
}

/** The month arrows. */
class MonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[Widgets.DELTA] ?: return
        updateAppWidgetState(context, glanceId) { it[Widgets.MONTH] = (it[Widgets.MONTH] ?: 0) + delta }
        TodayWidget().update(context, glanceId)
    }
}

/** Tapping the month name jumps back to today. */
class ResetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it.remove(Widgets.MONTH); it.remove(Widgets.SELECTED) }
        TodayWidget().update(context, glanceId)
    }
}

/** Month calendar beside the picked day's habits and tasks, kept in separate sections. */
class TodayWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Store.refreshDay(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val today = Logic.groveToday(Store.get(context))
            val selected = prefs[Widgets.SELECTED]?.let { LocalDate.parse(it) }?.takeIf { !it.isBefore(today) } ?: today
            Content(Store.get(context), today, selected, prefs[Widgets.MONTH] ?: 0)
        }
    }

    @Composable
    private fun Content(data: AppData, today: LocalDate, selected: LocalDate, monthOffset: Int) {
        Row(GlanceModifier.fillMaxSize().background(BG).cornerRadius(24.dp).padding(10.dp)) {
            Calendar(data, today, selected, today.withDayOfMonth(1).plusMonths(monthOffset.toLong()))
            Spacer(GlanceModifier.width(10.dp))
            Lists(data, today, selected)
        }
    }

    @Composable
    private fun Calendar(data: AppData, today: LocalDate, selected: LocalDate, first: LocalDate) {
        val blanks = first.dayOfWeek.value % 7
        val cells: List<Int?> = List(blanks) { null } + (1..first.lengthOfMonth()).toList()
        val label = first.month.getDisplayName(JTextStyle.FULL, Locale.US) + if (first.year != today.year) " ${first.year}" else ""
        Column(GlanceModifier.width(154.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", GlanceModifier.width(28.dp).clickable(actionRunCallback<MonthAction>(actionParametersOf(Widgets.DELTA to -1))), style(MUTED, 20, center = true))
                Text(label, GlanceModifier.defaultWeight().clickable(actionRunCallback<ResetAction>()), style(GREEN, 14, bold = true, center = true), maxLines = 1)
                Text("›", GlanceModifier.width(28.dp).clickable(actionRunCallback<MonthAction>(actionParametersOf(Widgets.DELTA to 1))), style(MUTED, 20, center = true))
            }
            Row(GlanceModifier.fillMaxWidth()) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach { Text(it, GlanceModifier.width(22.dp), style(MUTED, 10, bold = true, center = true)) }
            }
            cells.chunked(7).forEach { week ->
                Row(GlanceModifier.fillMaxWidth()) {
                    week.forEach { day ->
                        if (day == null) {
                            Spacer(GlanceModifier.width(22.dp).height(24.dp))
                        } else {
                            DayCell(data, first.withDayOfMonth(day), today, selected)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DayCell(data: AppData, date: LocalDate, today: LocalDate, selected: LocalDate) {
        val holy = data.holyDays.any { it.date == date.toString() }
        val hasTasks = !date.isBefore(today) && Logic.tasksOn(data, date).isNotEmpty()
        val isSel = date == selected
        val weekend = date.dayOfWeek.value % 7 == 0 || date.dayOfWeek.value == 6
        val color = when {
            isSel -> DARK
            date == today -> GREEN
            holy -> AMBER
            date.isBefore(today) || weekend -> MUTED
            else -> TEXT
        }
        Column(
            GlanceModifier.width(22.dp).height(24.dp).clickable(actionRunCallback<SelectDayAction>(actionParametersOf(Widgets.DATE to date.toString()))),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                GlanceModifier.size(19.dp).then(if (isSel) GlanceModifier.background(GREEN).cornerRadius(10.dp) else GlanceModifier),
                contentAlignment = Alignment.Center,
            ) { Text(date.dayOfMonth.toString(), style = style(color, 11, bold = isSel || date == today || holy, center = true)) }
            // The dot marks days with something due, like TickTick's calendar.
            Box(
                GlanceModifier.size(4.dp).cornerRadius(2.dp).background(
                    when {
                        holy -> AMBER
                        hasTasks -> Color(0xFF6F8EA8)
                        else -> Color.Transparent
                    }
                )
            ) {}
        }
    }

    @Composable
    private fun Lists(data: AppData, today: LocalDate, selected: LocalDate) {
        val isToday = selected == today
        val habits = if (isToday) data.habits.filter { Logic.scheduledOn(it, Logic.date(data.currentDay)) } else emptyList()
        val tasks = Logic.tasksOn(data, selected)
        val done = habits.count { Logic.isDoneToday(data, it) }
        val openDay = actionStartActivity<MainActivity>(actionParametersOf(Widgets.DAY to selected.toString()))
        LazyColumn(GlanceModifier.fillMaxSize()) {
            item {
                Text((if (isToday) "Today" else short(selected)) + "  ›", GlanceModifier.fillMaxWidth().clickable(openDay), style(TEXT, 13, bold = true))
            }
            if (isToday) {
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
                // Weekly goals: progress only; logging happens in the app.
                items(data.goals.size) { i ->
                    val g = data.goals[i]
                    val done = Logic.progress(data, g)
                    val target = Logic.target(data, g)
                    val label = if (g.kind == com.grove.app.data.GoalKind.PAGES) "$done/$target p" else if (done > target) "$done/$target ★" else "$done/$target"
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(actionStartActivity<MainActivity>()),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("☆", GlanceModifier.width(26.dp), style(GREEN, 15, center = true))
                        Text(g.name, GlanceModifier.defaultWeight(), style(TEXT, 13), maxLines = 1)
                        Text(label, style = style(GREEN, 10, bold = true))
                    }
                }
                item { Spacer(GlanceModifier.height(6.dp)) }
            }
            item { Text("TASKS", style = style(AMBER, 10, bold = true)) }
            if (tasks.isEmpty()) item { Text("Nothing scheduled.", style = style(MUTED, 12)) }
            items(tasks.size) { i ->
                val t = tasks[i]
                val checked = isToday && Logic.isTaskDone(data, data.currentDay, t.id)
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isToday) {
                        Text(
                            if (checked) "■" else "□",
                            GlanceModifier.width(26.dp).clickable(actionRunCallback<ToggleAction>(actionParametersOf(Widgets.KIND to "task", Widgets.ID to t.id))),
                            style(AMBER, 18, center = true),
                        )
                    } else {
                        Text("·", GlanceModifier.width(14.dp), style(AMBER, 16, center = true))
                    }
                    Text(t.name, GlanceModifier.defaultWeight().clickable(openDay), style(if (checked) MUTED else TEXT, 13, strike = checked), maxLines = 1)
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
        val upcoming = Logic.upcomingHolyDays(data, Logic.groveToday(data))
        Column(GlanceModifier.fillMaxSize().background(BG).cornerRadius(24.dp).padding(12.dp)) {
            Text("Wheel of the Year", style = style(TEXT, 15, bold = true))
            Spacer(GlanceModifier.height(4.dp))
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(upcoming.size) { i ->
                    val (h, n) = upcoming[i]
                    val d = Logic.date(h.date)
                    Row(
                        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable(actionStartActivity<MainActivity>(actionParametersOf(Widgets.DAY to h.date))),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(GlanceModifier.defaultWeight()) {
                            Text(h.name, style = style(TEXT, 13, bold = true))
                            Text("${short(d)} · ${h.detail}", style = style(MUTED, 11), maxLines = 1)
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
