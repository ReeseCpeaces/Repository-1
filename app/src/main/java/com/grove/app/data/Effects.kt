package com.grove.app.data

import android.content.Context
import com.grove.app.notify.AlarmScheduler
import com.grove.app.notify.Notifier
import com.grove.app.obsidian.ObsidianSync
import com.grove.app.widget.Widgets

/** Everything that has to follow a data change. */
object Effects {
    fun onChange(context: Context, old: AppData, new: AppData) {
        Notifier.cancelFinishedReminders(context, new)
        Notifier.showPinned(context, new)
        AlarmScheduler.rescheduleAll(context, new)
        Widgets.updateAll(context)
        ObsidianSync.syncAsync(context, old, new)
    }

    /** Full refresh after boot, an app update, or opening the app. */
    fun refreshAll(context: Context) {
        Store.refreshDay(context)
        val data = Store.get(context)
        Notifier.showPinned(context, data)
        AlarmScheduler.rescheduleAll(context, data)
        Widgets.updateAll(context)
    }
}
