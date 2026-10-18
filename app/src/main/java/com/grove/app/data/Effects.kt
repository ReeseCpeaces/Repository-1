package com.grove.app.data

import android.content.Context
import com.grove.app.notify.AlarmScheduler
import com.grove.app.notify.Notifier
import com.grove.app.obsidian.BookNotes
import com.grove.app.obsidian.ObsidianSync
import com.grove.app.obsidian.VaultSync
import com.grove.app.widget.Widgets

/** Everything that has to follow a data change. */
object Effects {
    fun onChange(context: Context, old: AppData, new: AppData) {
        Notifier.cancelFinishedReminders(context, new)
        Notifier.showPinned(context, new)
        AlarmScheduler.rescheduleAll(context, new)
        Widgets.updateAll(context)
        ObsidianSync.syncAsync(context, old, new)
        VaultSync.exportAsync(context, old, new)
        BookNotes.syncAsync(context, old, new)
    }

    /** Full refresh after boot, an app update, or opening the app. Call off the main thread. */
    fun refreshAll(context: Context) {
        // Pick up setup edits made to Grove/grove.json in the vault (by hand or by Claude).
        runCatching { VaultSync.importIfChanged(context) }
        // Apply pages and sessions logged through the connector.
        runCatching { VaultSync.applyInbox(context) }
        // Link Druid book notes in the vault to the Books screen and update their progress.
        runCatching { BookNotes.scan(context) }
        Store.refreshDay(context)
        val data = Store.get(context)
        Notifier.showPinned(context, data)
        AlarmScheduler.rescheduleAll(context, data)
        Widgets.updateAll(context)
    }
}
