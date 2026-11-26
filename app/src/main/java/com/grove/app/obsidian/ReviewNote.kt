package com.grove.app.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.grove.app.data.AppData
import com.grove.app.data.Review
import java.time.LocalDate
import java.util.concurrent.Executors

/** Saves a weekly review into the vault: a new note, or a "## Weekly review" section in that week's note if it exists. */
object ReviewNote {
    private val worker = Executors.newSingleThreadExecutor()

    fun saveAsync(context: Context, data: AppData, monday: LocalDate, answers: List<String>, done: (Boolean) -> Unit) {
        worker.execute {
            val ok = runCatching { save(context, data, monday, answers) }.onFailure { Log.w("ReviewNote", "Saving the review failed", it) }.getOrDefault(false)
            done(ok)
        }
    }

    private fun save(context: Context, data: AppData, monday: LocalDate, answers: List<String>): Boolean {
        if (data.settings.vaultUri.isEmpty()) return false
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return false
        val rel = Review.path(data.settings.weeklyReviewPath, monday).trim('/')
        val resolver = context.contentResolver
        val existing = ObsidianSync.find(root, rel)?.takeIf { it.isFile }
        if (existing != null) {
            // Never write a note that couldn't be read first.
            val before = resolver.openInputStream(existing.uri)?.use { it.readBytes().decodeToString() } ?: return false
            val after = Review.mergeInto(before, data, monday, answers)
            if (after != before) resolver.openOutputStream(existing.uri, "wt")?.use { it.write(after.encodeToByteArray()) } ?: return false
            return true
        }
        val parts = rel.split('/')
        var dir = root
        for (p in parts.dropLast(1)) dir = dir.findFile(p) ?: dir.createDirectory(p) ?: return false
        val f = dir.createFile("application/octet-stream", parts.last()) ?: return false
        resolver.openOutputStream(f.uri, "wt")?.use { it.write(Review.note(data, monday, answers).encodeToByteArray()) } ?: return false
        return true
    }
}
