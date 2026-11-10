package com.grove.app.data

import android.content.Context
import com.grove.app.notify.AlarmScheduler
import com.grove.app.notify.Notifier
import com.grove.app.obsidian.BookNotes
import com.grove.app.obsidian.ExploreNote
import com.grove.app.obsidian.ObsidianSync
import com.grove.app.obsidian.PromptNotes
import com.grove.app.obsidian.TodoNote
import com.grove.app.obsidian.VaultSync
import com.grove.app.widget.Widgets

/** Everything that has to follow a data change. */
object Effects {
    private val healthWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun onChange(context: Context, old: AppData, new: AppData) {
        Notifier.cancelFinishedReminders(context, new)
        Notifier.showPinned(context, new)
        AlarmScheduler.rescheduleAll(context, new)
        Widgets.updateAll(context)
        ObsidianSync.syncAsync(context, old, new)
        VaultSync.exportAsync(context, old, new)
        BookNotes.syncAsync(context, old, new)
        TodoNote.syncAsync(context, old, new)
        PromptNotes.syncAsync(context, old, new)
        ExploreNote.syncAsync(context, old, new)
    }

    /** Full refresh after boot, an app update, or opening the app. Call off the main thread. */
    fun refreshAll(context: Context) {
        // Pick up setup edits made to Grove/grove.json in the vault (by hand or by Claude).
        runCatching { VaultSync.importIfChanged(context) }
        // Apply pages and sessions logged through the connector.
        runCatching { VaultSync.applyInbox(context) }
        // Link Druid book notes in the vault to the Books screen and update their progress.
        runCatching { BookNotes.scan(context) }
        // Pick up to-dos added or ticked in the Obsidian to-do note.
        TodoNote.syncAsync(context, null, Store.get(context))
        // Create this week's prompt note and write any waiting thoughts.
        PromptNotes.syncAsync(context, null, Store.get(context))
        // Pick up to-dos ticked in today's note.
        ObsidianSync.syncAsync(context, null, Store.get(context))
        // Pick up plant-study sessions added in the note by hand or by Claude.
        ExploreNote.syncAsync(context, null, Store.get(context))
        // Sleep and weigh-ins from Health Connect, if turned on (no notifications). On their own thread:
        // the first weight import can bring months of history.
        healthWorker.execute {
            runCatching { com.grove.app.obsidian.SleepImport.importIfOn(context) }
            runCatching { com.grove.app.obsidian.BodyImport.importIfOn(context) }
        }
        Store.refreshDay(context)
        val data = Store.get(context)
        Notifier.showPinned(context, data)
        AlarmScheduler.rescheduleAll(context, data)
        Widgets.updateAll(context)
    }
}
