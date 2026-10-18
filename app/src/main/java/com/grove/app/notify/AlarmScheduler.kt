package com.grove.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Schedules today's reminder alarms, plus the follow-ups for snoozes and "keep reminding until done". */
object AlarmScheduler {
    private const val PREFS = "alarms"
    private const val KEY_CODES = "codes"

    private fun am(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun fireIntent(context: Context, code: Int, kind: String, id: String): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_KIND, kind)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun setExact(context: Context, at: LocalDateTime, pi: PendingIntent) {
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val am = am(context)
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    /** Schedule every remaining reminder for today and the day-rollover checks; cancel stale ones. */
    fun rescheduleAll(context: Context, data: AppData) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val codes = mutableSetOf<Int>()

        // Each reminder fires at its time on the date the item is due. Both Grove's day (which can
        // still be yesterday after midnight) and the calendar day are covered, so nothing due today
        // is missed and yesterday's items never alarm on the wrong date.
        fun schedule(kind: String, id: String, date: LocalDate, times: List<String>) {
            for (t in times) {
                val (h, m) = t.split(":").map { it.toInt() }
                val at = date.atTime(h, m)
                if (!at.isAfter(now)) continue
                val code = "rem|$kind|$id|$date|$t".hashCode()
                codes += code
                setExact(context, at, fireIntent(context, code, kind, id))
            }
        }

        for (date in linkedSetOf(Logic.groveToday(data), today)) {
            val key = Logic.key(date)
            for (h in data.habits) {
                if (Logic.scheduledOn(h, date) && !Logic.isDone(data, key, h)) schedule(ReminderReceiver.KIND_HABIT, h.id, date, h.reminders)
            }
            for (t in Logic.tasksOn(data, date)) {
                val task = t.task ?: continue
                if (!Logic.isTaskDone(data, key, t.id)) schedule(ReminderReceiver.KIND_TASK, t.id, date, task.reminders)
            }
        }

        // Weekly-goal nudges for the rest of this week, only while still under their threshold.
        val groveDay = Logic.groveToday(data)
        for (g in data.goals) {
            for (n in g.nudges) {
                val date = Logic.dayInWeek(groveDay, n.weekday)
                if (date.isBefore(groveDay)) continue
                if (Logic.progress(data, g, date) >= Logic.target(data, g, date) * n.below) continue
                val (h, m) = n.time.split(":").map { it.toInt() }
                val at = date.atTime(h, m)
                if (!at.isAfter(now)) continue
                val code = "nudge|${g.id}|$date|${n.time}".hashCode()
                codes += code
                setExact(context, at, fireIntent(context, code, ReminderReceiver.KIND_GOAL, g.id))
            }
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(KEY_CODES, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
        for (code in previous - codes) {
            val pi = PendingIntent.getBroadcast(context, code, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) am(context).cancel(pi)
        }
        prefs.edit().putStringSet(KEY_CODES, codes.map { it.toString() }.toSet()).apply()

        scheduleDayTicks(context, data, now)
    }

    /** Wake at the cutoff hour and just after midnight to roll the day and plan new reminders. */
    private fun scheduleDayTicks(context: Context, data: AppData, now: LocalDateTime) {
        val cutoff = now.toLocalDate().atTime(data.settings.cutoffHour, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
        val midnight = now.toLocalDate().plusDays(1).atTime(0, 1)
        for ((code, at) in listOf(9001 to cutoff, 9002 to midnight)) {
            val i = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_DAY_TICK)
            setExact(context, at, PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
    }

    private fun followUpCode(kind: String, id: String) = "follow|$kind|$id".hashCode()

    /** One-off alarm for a snooze or a "keep reminding" repeat. Replaces any earlier follow-up. */
    fun scheduleFollowUp(context: Context, kind: String, id: String, minutes: Int) {
        val code = followUpCode(kind, id)
        setExact(context, LocalDateTime.now().plusMinutes(minutes.toLong()), fireIntent(context, code, kind, id))
    }

    fun cancelFollowUp(context: Context, kind: String, id: String) {
        val code = followUpCode(kind, id)
        val pi = PendingIntent.getBroadcast(context, code, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) am(context).cancel(pi)
    }
}
