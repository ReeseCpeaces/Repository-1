package com.grove.app.obsidian

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.grove.app.data.AppData
import com.grove.app.data.DayRecord
import com.grove.app.data.Logic
import com.grove.app.data.Role
import com.grove.app.data.Store
import java.util.concurrent.Executors

/**
 * Writes the day into the Obsidian daily note inside the vault folder the user picked
 * (kept in sync to the server by Syncthing): ticks checkbox properties and keeps a short
 * summary under "## Daily Log".
 */
object ObsidianSync {
    private const val TAG = "ObsidianSync"
    private val worker = Executors.newSingleThreadExecutor()

    fun syncAsync(context: Context, old: AppData?, new: AppData) {
        if (new.settings.vaultUri.isEmpty()) return
        // Earlier days that changed (a check-in filled in later) get their own note updated too.
        val earlier = if (old == null) emptySet() else (old.days.keys + new.days.keys)
            .filter { it != new.currentDay && old.days[it] != new.days[it] }.toSet()
        if (old != null && earlier.isEmpty() && old.days[new.currentDay] == new.days[new.currentDay] && old.habits == new.habits &&
            old.tasks == new.tasks && old.goals == new.goals && old.books == new.books &&
            com.grove.app.data.Todos.doneOn(old, new.currentDay) == com.grove.app.data.Todos.doneOn(new, new.currentDay)) return
        // Read the latest data when the job runs, so queued jobs never write the same lines twice.
        worker.execute { runCatching { sync(context, Store.get(context), earlier) }.onFailure { Log.w(TAG, "Daily note sync failed", it) } }
    }

    /** The properties Grove manages, with [day]'s values. */
    fun properties(data: AppData, day: String = data.currentDay): Map<String, Boolean> {
        val rec = data.days[day] ?: DayRecord()
        val props = linkedMapOf<String, Boolean>()
        val bedtime = data.habits.firstOrNull { it.role == Role.BEDTIME }
        props["Daily Note"] = bedtime != null && Logic.isDone(data, day, bedtime)
        for (h in data.habits) {
            val done = Logic.stepsDone(data, day, h).toSet()
            h.steps.forEachIndexed { i, s -> if (s.prop.isNotBlank()) props[s.prop] = (props[s.prop] ?: false) || i in done }
        }
        for (t in data.tasks) if (t.prop.isNotBlank()) props[t.prop] = (props[t.prop] ?: false) || t.id in rec.tasks
        for (g in data.goals) {
            if (g.prop.isBlank()) continue
            val did = if (g.kind == com.grove.app.data.GoalKind.PAGES) Logic.pagesOn(data, day) > 0 else Logic.sessionLogged(data, day, g)
            props[g.prop] = (props[g.prop] ?: false) || did
        }
        props["lifestyle changes"] = rec.lifestyle
        return props
    }

    fun summary(data: AppData, day: String = data.currentDay): String {
        val d = Logic.date(day)
        val rec = data.days[day] ?: DayRecord()
        val lines = mutableListOf("### Grove · ${Logic.weekdayName(d)}, ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth}")
        for (h in data.habits.filter { Logic.scheduledOn(it, d) }) {
            val done = Logic.isDone(data, day, h)
            val n = Logic.stepsDone(data, day, h).size
            val tail = when {
                done -> rec.habitTimes[h.id]?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                n > 0 -> " ($n/${h.steps.size} steps)"
                else -> ""
            }
            lines += "- [${if (done) "x" else " "}] ${h.name}$tail"
        }
        for (t in Logic.tasksOn(data, d)) {
            val at = rec.tasks[t.id]
            lines += "- [${if (at != null) "x" else " "}] ${t.name}${at?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        }
        for (t in com.grove.app.data.Todos.doneOn(data, day)) lines += "- [x] To-do: ${t.name}"
        for (g in data.goals) {
            if (g.kind == com.grove.app.data.GoalKind.PROMPT) {
                lines += "${g.name}: " + com.grove.app.data.Prompts.status(g.copy(title = ""), d).substringBefore(" · aim")
                continue
            }
            val today = if (g.kind == com.grove.app.data.GoalKind.PAGES) Logic.pagesOn(data, day).let { if (it > 0) " (+$it pages today)" else "" }
            else if (Logic.sessionLogged(data, day, g)) " (session today)" else ""
            val unit = if (g.kind == com.grove.app.data.GoalKind.PAGES) " pages" else ""
            lines += "${g.name}: ${Logic.progress(data, g, d)} / ${Logic.target(data, g, d)}$unit this week$today"
        }
        if (day == data.currentDay) lines += "Streaks: " + data.habits.joinToString(" · ") { "${it.name} ${Logic.streak(data, it)}" }
        Logic.upcomingHolyDays(data, d).firstOrNull()?.let { (h, n) -> lines += "Next holy day: ${h.name} ${Logic.countdown(n)}" }
        return lines.joinToString("\n")
    }

    private fun sync(context: Context, data: AppData, earlier: Set<String> = emptySet()) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return
        syncDay(context, root, data, data.currentDay, create = true)
        // Earlier days: ones just changed, and any with lines not written yet. Only notes that exist.
        val pending = data.days.filter { (k, r) -> k != data.currentDay && r.lines.size > r.linesWritten }.keys
        for (day in (earlier + pending).filter { Logic.canMark(data, it) && it != data.currentDay }.sorted()) {
            syncDay(context, root, Store.get(context), day, create = false)
        }
    }

    /** Ticks properties, refreshes Grove's summary and adds pending lines in [day]'s note. */
    private fun syncDay(context: Context, root: DocumentFile, data: AppData, day: String, create: Boolean) {
        val d = Logic.date(day)
        val rel = NoteText.path(data.settings.dailyNotePath, d)
        val note = (if (create) findOrCreate(context, root, rel, data, d) else find(root, rel)) ?: return
        val resolver = context.contentResolver
        // If the note can't be read, leave it alone rather than risk writing over it.
        val before = resolver.openInputStream(note.uri)?.use { it.readBytes().decodeToString() } ?: return
        val rec = data.days[day] ?: DayRecord()
        val pending = rec.lines.drop(rec.linesWritten)
        val after = NoteText.appendLogLines(NoteText.applySummary(NoteText.applyProperties(before, properties(data, day)), summary(data, day)), pending)
        if (after != before) resolver.openOutputStream(note.uri, "wt")?.use { it.write(after.encodeToByteArray()) } ?: return
        if (pending.isNotEmpty()) Store.update(context) { Logic.markLinesWritten(it, day, rec.lines.size) }
    }

    fun find(root: DocumentFile, rel: String): DocumentFile? =
        rel.split('/').filter { it.isNotEmpty() }.fold(root as DocumentFile?) { dir, part -> dir?.findFile(part) }

    private fun findOrCreate(context: Context, root: DocumentFile, rel: String, data: AppData, d: java.time.LocalDate): DocumentFile? {
        find(root, rel)?.let { return it }
        val parts = rel.split('/').filter { it.isNotEmpty() }
        var dir = root
        for (p in parts.dropLast(1)) dir = dir.findFile(p) ?: dir.createDirectory(p) ?: return null
        val name = parts.last()
        // "text/markdown" would let some providers append ".txt"; octet-stream keeps the name as given.
        val file = dir.createFile("application/octet-stream", name) ?: return null
        val template = data.settings.templatePath.takeIf { it.isNotBlank() }?.let { find(root, it) }
            ?.let { t -> context.contentResolver.openInputStream(t.uri)?.use { it.readBytes().decodeToString() } }
        val body = template?.let { NoteText.fillTemplate(it, d) } ?: "---\n---\n## Week [[Weekly/${Logic.isoWeek(d)}]]\n\n## Daily Log\n"
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(body.encodeToByteArray()) }
        return file
    }

    /** Opens today's note in the Obsidian app. */
    fun openInObsidian(context: Context, data: AppData) =
        openNote(context, data, NoteText.path(data.settings.dailyNotePath, Logic.date(data.currentDay)))

    /** Opens a note (path inside the vault) in the Obsidian app. */
    fun openNote(context: Context, data: AppData, rel: String) {
        val vault = data.settings.vaultUri.takeIf { it.isNotEmpty() }
            ?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() } ?: "Obsidian"
        val uri = Uri.parse("obsidian://open?vault=" + Uri.encode(vault) + "&file=" + Uri.encode(rel.removeSuffix(".md")))
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
