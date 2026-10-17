package com.grove.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.grove.app.data.Effects
import com.grove.app.data.Logic
import com.grove.app.data.Store
import java.time.LocalDate
import java.time.LocalDateTime

/** Handles alarms, notification buttons and system events (boot, time change, app update). */
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.grove.app.FIRE"
        const val ACTION_DONE = "com.grove.app.DONE"
        const val ACTION_SNOOZE = "com.grove.app.SNOOZE"
        const val ACTION_PIN_DISMISSED = "com.grove.app.PIN_DISMISSED"
        const val ACTION_DAY_TICK = "com.grove.app.DAY_TICK"
        const val EXTRA_KIND = "kind"
        const val EXTRA_ID = "id"
        const val EXTRA_MINUTES = "minutes"
        const val KIND_HABIT = "habit"
        const val KIND_TASK = "task"

        /** Shared by the snooze picker. */
        fun snooze(context: Context, kind: String, id: String, minutes: Int) {
            Notifier.cancelReminder(context, kind, id)
            AlarmScheduler.scheduleFollowUp(context, kind, id, minutes)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                handle(context.applicationContext, intent)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        when (intent.action) {
            ACTION_FIRE -> fire(context, kind, id)
            ACTION_DONE -> {
                AlarmScheduler.cancelFollowUp(context, kind, id)
                Notifier.cancelReminder(context, kind, id)
                Store.update(context) { d ->
                    val now = LocalDateTime.now()
                    if (kind == KIND_HABIT) {
                        val h = d.habits.firstOrNull { it.id == id }
                        if (h == null || Logic.isDoneToday(d, h)) d else Logic.toggleHabit(d, id, now)
                    } else {
                        if (Logic.isTaskDone(d, d.currentDay, id)) d else Logic.toggleTask(d, id, now)
                    }
                }
            }
            ACTION_SNOOZE -> snooze(context, kind, id, intent.getIntExtra(EXTRA_MINUTES, Store.get(context).settings.defaultSnoozeMinutes))
            ACTION_PIN_DISMISSED -> Notifier.showPinned(context, Store.get(context))
            ACTION_DAY_TICK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Effects.refreshAll(context)
        }
    }

    /** Post the reminder unless it's already done; if it nags, plan the next re-alert. */
    private fun fire(context: Context, kind: String, id: String) {
        Store.refreshDay(context)
        val data = Store.get(context)
        val snooze = data.settings.defaultSnoozeMinutes
        if (kind == KIND_HABIT) {
            val h = data.habits.firstOrNull { it.id == id } ?: return
            if (Logic.isDoneToday(data, h)) return
            val streak = Logic.streak(data, h)
            val text = h.steps.joinToString(" · ") { it.label } + if (streak > 0) "  ·  $streak-day streak" else ""
            Notifier.showReminder(context, kind, id, h.name, text, snooze)
            if (h.nag) AlarmScheduler.scheduleFollowUp(context, kind, id, h.nagMinutes)
        } else {
            val t = Logic.tasksOn(data, LocalDate.now()).firstOrNull { it.id == id }?.task
                ?: data.tasks.firstOrNull { it.id == id } ?: return
            if (Logic.isTaskDone(data, data.currentDay, id)) return
            Notifier.showReminder(context, kind, id, t.name, "Due ${Logic.fmtTime(t.time)} · ${t.list}", snooze)
            if (t.nag) AlarmScheduler.scheduleFollowUp(context, kind, id, t.nagMinutes)
        }
    }
}
