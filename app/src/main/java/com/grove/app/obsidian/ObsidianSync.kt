package com.grove.app.obsidian

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.data.Role
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
        if (old != null && old.days[new.currentDay] == new.days[new.currentDay] && old.habits == new.habits && old.tasks == new.tasks) return
        worker.execute { runCatching { sync(context, new) }.onFailure { Log.w(TAG, "Daily note sync failed", it) } }
    }

    /** The properties Grove manages, with today's values. */
    fun properties(data: AppData): Map<String, Boolean> {
        val day = data.currentDay
        val rec = data.today
        val props = linkedMapOf<String, Boolean>()
        val bedtime = data.habits.firstOrNull { it.role == Role.BEDTIME }
        props["Daily Note"] = bedtime != null && Logic.isDoneToday(data, bedtime)
        for (h in data.habits) {
            val done = Logic.stepsDone(data, day, h).toSet()
            h.steps.forEachIndexed { i, s -> if (s.prop.isNotBlank()) props[s.prop] = (props[s.prop] ?: false) || i in done }
        }
        for (t in data.tasks) if (t.prop.isNotBlank()) props[t.prop] = (props[t.prop] ?: false) || t.id in rec.tasks
        props["lifestyle changes"] = rec.lifestyle
        return props
    }

    fun summary(data: AppData): String {
        val d = Logic.date(data.currentDay)
        val lines = mutableListOf("### Grove · ${Logic.weekdayName(d)}, ${d.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth}")
        for (h in data.habits.filter { Logic.scheduledOn(it, d) }) {
            val done = Logic.isDoneToday(data, h)
            val n = Logic.stepsDone(data, data.currentDay, h).size
            val tail = when {
                done -> data.today.habitTimes[h.id]?.let { " · $it" }.orEmpty()
                n > 0 -> " ($n/${h.steps.size} steps)"
                else -> ""
            }
            lines += "- [${if (done) "x" else " "}] ${h.name}$tail"
        }
        for (t in Logic.tasksOn(data, d)) {
            val at = data.today.tasks[t.id]
            lines += "- [${if (at != null) "x" else " "}] ${t.name}${at?.let { " · $it" }.orEmpty()}"
        }
        lines += "Streaks: " + data.habits.joinToString(" · ") { "${it.name} ${Logic.streak(data, it)}" }
        Logic.upcomingHolyDays(data, d).firstOrNull()?.let { (h, n) -> lines += "Next holy day: ${h.name} ${Logic.countdown(n)}" }
        return lines.joinToString("\n")
    }

    private fun sync(context: Context, data: AppData) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return
        val d = Logic.date(data.currentDay)
        val rel = NoteText.path(data.settings.dailyNotePath, d)
        val note = findOrCreate(context, root, rel, data, d) ?: return
        val resolver = context.contentResolver
        val before = resolver.openInputStream(note.uri)?.use { it.readBytes().decodeToString() } ?: ""
        val after = NoteText.applySummary(NoteText.applyProperties(before, properties(data)), summary(data))
        if (after != before) resolver.openOutputStream(note.uri, "wt")?.use { it.write(after.encodeToByteArray()) }
    }

    private fun find(root: DocumentFile, rel: String): DocumentFile? =
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
    fun openInObsidian(context: Context, data: AppData) {
        val rel = NoteText.path(data.settings.dailyNotePath, Logic.date(data.currentDay)).removeSuffix(".md")
        val uri = Uri.parse("obsidian://open?file=" + Uri.encode(rel))
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
