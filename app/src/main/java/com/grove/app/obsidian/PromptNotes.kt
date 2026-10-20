package com.grove.app.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.grove.app.data.AppData
import com.grove.app.data.Goal
import com.grove.app.data.Prompts
import com.grove.app.data.Store
import java.util.concurrent.Executors

/**
 * Writes prompt goals into the vault: creates "Prompt N.md" when a prompt opens, adds thoughts and the
 * answer under their headings, keeps title/finished in step, and ticks the tracker note. A note that
 * can't be read is never written, and a note that was deleted is never recreated.
 */
object PromptNotes {
    private const val TAG = "PromptNotes"
    private val worker = Executors.newSingleThreadExecutor()

    fun syncAsync(context: Context, old: AppData?, new: AppData) {
        if (new.settings.vaultUri.isEmpty() || Prompts.goals(new).isEmpty()) return
        if (old != null && Prompts.goals(old) == Prompts.goals(new) && old.promptQueue == new.promptQueue) return
        worker.execute { runCatching { sync(context) }.onFailure { Log.w(TAG, "Prompt note sync failed", it) } }
    }

    private fun read(context: Context, f: DocumentFile): String? =
        runCatching { context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()

    private fun write(context: Context, f: DocumentFile, text: String) =
        context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(text.encodeToByteArray()) } != null

    private fun sync(context: Context) {
        val data = Store.get(context)
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return
        for (g in Prompts.goals(data)) syncGoal(context, root, data, g)
    }

    private fun syncGoal(context: Context, root: DocumentFile, data: AppData, g: Goal) {
        if (g.current <= 0 || g.noteFolder.isBlank()) return
        val n = g.current
        val path = Prompts.notePath(g, n)
        var note = ObsidianSync.find(root, path)?.takeIf { it.isFile }
        var made = g.notesMade
        if (note == null && n !in made) {
            // Create it when the prompt opens. (If it was made before and is gone now, it was deleted: leave it.)
            val parts = path.split('/')
            var dir = root
            for (p in parts.dropLast(1)) dir = dir.findFile(p) ?: dir.createDirectory(p) ?: return
            note = dir.createFile("application/octet-stream", parts.last()) ?: return
            if (!write(context, note, Prompts.template(g, n, g.title, g.opened.ifEmpty { data.currentDay }))) return
        }
        if (note != null && n !in made) made = made + n
        val written = mutableSetOf<String>()
        if (note != null) {
            val before = read(context, note)
            if (before != null) {
                val entries = data.promptQueue.filter { it.goal == g.id && it.prompt == n }
                var after = Prompts.applyState(before, g, n, g.title, g.doneOn)
                for (section in listOf(Prompts.THOUGHTS, Prompts.ANSWER)) {
                    val items = entries.filter { it.section == section }
                    after = Prompts.appendToSection(after, section, items.map { if (section == Prompts.THOUGHTS) "- ${it.day}: ${it.text}" else it.text })
                    written += items.map { it.id }
                }
                if (after != before && !write(context, note, after)) written.clear()
            }
        }
        // If the note was deleted, what's waiting for it stays queued (never lost, never forcing the note back).
        // Entries for earlier prompts (written after the week moved on) go to their own notes if those exist.
        for ((p, entries) in data.promptQueue.filter { it.goal == g.id && it.prompt != n }.groupBy { it.prompt }) {
            val f = ObsidianSync.find(root, Prompts.notePath(g, p))?.takeIf { it.isFile }
            val before = f?.let { read(context, it) }
            if (f != null && before != null) {
                var after = before
                for (section in listOf(Prompts.THOUGHTS, Prompts.ANSWER)) {
                    val items = entries.filter { it.section == section }
                    after = Prompts.appendToSection(after, section, items.map { if (section == Prompts.THOUGHTS) "- ${it.day}: ${it.text}" else it.text })
                }
                if (after == before || write(context, f, after)) written += entries.map { it.id }
            }
        }
        // The tracker: tick finished prompts.
        if (g.tracker.isNotBlank()) {
            ObsidianSync.find(root, g.tracker)?.takeIf { it.isFile }?.let { t ->
                val before = read(context, t) ?: return@let
                val after = Prompts.applyTracker(before, g)
                if (after != before) write(context, t, after)
            }
        }
        if (written.isNotEmpty() || made != g.notesMade) {
            Store.update(context) { d ->
                d.copy(
                    promptQueue = d.promptQueue.filter { it.id !in written },
                    goals = d.goals.map { if (it.id == g.id) it.copy(notesMade = (it.notesMade + made).distinct()) else it },
                )
            }
        }
    }
}
