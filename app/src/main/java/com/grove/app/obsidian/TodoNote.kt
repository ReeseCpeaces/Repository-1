package com.grove.app.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.grove.app.data.AppData
import com.grove.app.data.Store
import com.grove.app.data.Todos
import java.util.concurrent.Executors

/** Keeps Grove's to-dos in step with the checklist in the to-do note picked in Settings. */
object TodoNote {
    private const val TAG = "TodoNote"
    private val worker = Executors.newSingleThreadExecutor()

    /** After a change to the to-dos (or the note setting). */
    fun syncAsync(context: Context, old: AppData?, new: AppData) {
        if (new.settings.vaultUri.isEmpty() || new.settings.todoNotePath.isBlank()) return
        if (old != null && old.todos == new.todos && old.todoDeleted == new.todoDeleted && old.settings.todoNotePath == new.settings.todoNotePath) return
        // Always on the one worker thread: each sync reads the note only after the last one wrote it.
        worker.execute { runCatching { sync(context) }.onFailure { Log.w(TAG, "To-do note sync failed", it) } }
    }

    /** Read the note, merge, and write back what changed. A note that can't be read is never written. */
    private fun sync(context: Context) {
        val data = Store.get(context)
        val path = data.settings.todoNotePath.trim().trim('/').let { if (it.endsWith(".md")) it else "$it.md" }
        if (path == ".md" || data.settings.vaultUri.isEmpty()) return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return
        val f = ObsidianSync.find(root, path)?.takeIf { it.isFile } ?: return
        val before = context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } ?: return
        var after = before
        Store.update(context) { d -> Todos.syncNote(d, before).also { after = it.second }.first }
        if (after != before) context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(after.encodeToByteArray()) }
    }
}
