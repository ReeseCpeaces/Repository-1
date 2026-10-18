package com.grove.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.grove.app.R
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.ui.MainActivity

object Notifier {
    const val CHANNEL_PINNED = "pinned"
    const val CHANNEL_REMINDERS = "reminders"
    private const val PINNED_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PINNED, "Today's habits", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The pinned checklist of today's habits"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Habit and task reminders, repeated until done"
            }
        )
    }

    private fun allowed(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33

    private fun openApp(context: Context, code: Int, habitId: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (habitId != null) i.putExtra(MainActivity.EXTRA_HABIT, habitId)
        return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun receiver(context: Context, code: Int, action: String, build: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(action).apply(build)
        return PendingIntent.getBroadcast(context, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The pinned checklist. Android 14+ lets any notification be swiped away, so dismissing it
     * triggers [ReminderReceiver.ACTION_PIN_DISMISSED], which posts it again.
     */
    fun showPinned(context: Context, data: AppData) {
        if (!allowed(context)) return
        val pinned = data.habits.filter { it.pinned && Logic.scheduledOn(it, Logic.date(data.currentDay)) }
        val done = pinned.count { Logic.isDoneToday(data, it) }
        val next = Logic.nextHabit(data)?.takeIf { it.pinned }
        val title = if (pinned.isNotEmpty() && done == pinned.size) "All habits done today" else "$done of ${pinned.size} habits done"
        val subtitle = next?.let { "Next: ${it.name}" + (it.reminders.minOrNull()?.let { t -> " · " + Logic.fmtTime(t) } ?: "") } ?: "Nice work."
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        pinned.forEach { h ->
            val mark = if (Logic.isDoneToday(data, h)) "✓" else "○"
            style.addLine("$mark  ${h.name}  ·  ${Logic.streak(data, h)}d")
        }
        data.goals.forEach { g ->
            val unit = if (g.kind == com.grove.app.data.GoalKind.PAGES) " pages" else ""
            style.addLine("☆  ${g.name}  ·  ${Logic.progress(data, g)}/${Logic.target(data, g)}$unit this week")
        }
        val b = NotificationCompat.Builder(context, CHANNEL_PINNED)
            .setSmallIcon(R.drawable.ic_stat_grove)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context, 100))
            .setDeleteIntent(receiver(context, 101, ReminderReceiver.ACTION_PIN_DISMISSED))
        if (next != null) {
            b.addAction(0, "Check off: ${next.name}", receiver(context, 102, ReminderReceiver.ACTION_DONE) {
                putExtra(ReminderReceiver.EXTRA_KIND, ReminderReceiver.KIND_HABIT)
                putExtra(ReminderReceiver.EXTRA_ID, next.id)
            })
        }
        b.addAction(0, "Open Grove", openApp(context, 103))
        NotificationManagerCompat.from(context).notify(PINNED_ID, b.build())
    }

    fun reminderId(kind: String, id: String) = ("rem|$kind|$id").hashCode()

    /** A reminder with Done and Snooze buttons. "Snooze…" opens a picker for other lengths. */
    fun showReminder(context: Context, kind: String, id: String, title: String, text: String, defaultSnooze: Int) {
        if (!allowed(context)) return
        val nid = reminderId(kind, id)
        fun extras(i: Intent) {
            i.putExtra(ReminderReceiver.EXTRA_KIND, kind); i.putExtra(ReminderReceiver.EXTRA_ID, id)
        }
        val snoozePicker = Intent(context, SnoozeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
            .also(::extras)
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_grove)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(false)
            .setContentIntent(openApp(context, nid, if (kind == ReminderReceiver.KIND_HABIT) id else null))
            .addAction(0, "Done", receiver(context, nid + 1, ReminderReceiver.ACTION_DONE) { extras(this) })
            .addAction(0, "Snooze $defaultSnooze min", receiver(context, nid + 2, ReminderReceiver.ACTION_SNOOZE) {
                extras(this); putExtra(ReminderReceiver.EXTRA_MINUTES, defaultSnooze)
            })
            .addAction(0, "Snooze…", PendingIntent.getActivity(context, nid + 3, snoozePicker, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        NotificationManagerCompat.from(context).notify(nid, n)
    }

    /** A gentle weekly-goal nudge: no repeats; "Log it" opens the app, or snooze it. */
    fun showNudge(context: Context, goalId: String, title: String, text: String, defaultSnooze: Int) {
        if (!allowed(context)) return
        val kind = ReminderReceiver.KIND_GOAL
        val nid = reminderId(kind, goalId)
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_grove)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, nid))
            .addAction(0, "Log it", openApp(context, nid + 1))
            .addAction(0, "Snooze $defaultSnooze min", receiver(context, nid + 2, ReminderReceiver.ACTION_SNOOZE) {
                putExtra(ReminderReceiver.EXTRA_KIND, kind); putExtra(ReminderReceiver.EXTRA_ID, goalId)
                putExtra(ReminderReceiver.EXTRA_MINUTES, defaultSnooze)
            })
            .build()
        NotificationManagerCompat.from(context).notify(nid, n)
    }

    fun cancelReminder(context: Context, kind: String, id: String) =
        NotificationManagerCompat.from(context).cancel(reminderId(kind, id))

    /** Clear reminder notifications for anything already checked off. */
    fun cancelFinishedReminders(context: Context, data: AppData) {
        data.habits.filter { Logic.isDoneToday(data, it) }.forEach { cancelReminder(context, ReminderReceiver.KIND_HABIT, it.id) }
        data.today.tasks.keys.forEach { cancelReminder(context, ReminderReceiver.KIND_TASK, it) }
    }
}
