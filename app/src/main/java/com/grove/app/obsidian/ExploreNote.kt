package com.grove.app.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.grove.app.data.AppData
import com.grove.app.data.Explore
import com.grove.app.data.Store
import java.util.concurrent.Executors

/**
 * Keeps plant-study sessions in step with the "## Session log" of the plant-study note. The note is
 * never created (a deleted one stays deleted) and is never written unless it could be read first.
 */
object ExploreNote {
    private const val TAG = "ExploreNote"
    private val worker = Executors.newSingleThreadExecutor()

    fun syncAsync(context: Context, old: AppData?, new: AppData) {
        if (new.settings.vaultUri.isEmpty() || new.settings.plantStudyNotePath.isBlank()) return
        if (old != null && old.explore == new.explore && old.plants == new.plants && old.exploreDeleted == new.exploreDeleted &&
            old.settings.plantStudyNotePath == new.settings.plantStudyNotePath) return
        // One worker thread: each sync reads the note only after the previous one wrote it.
        worker.execute { runCatching { sync(context) }.onFailure { Log.w(TAG, "Plant study note sync failed", it) } }
    }

    private fun sync(context: Context) {
        val data = Store.get(context)
        val path = data.settings.plantStudyNotePath.trim().trim('/').let { if (it.endsWith(".md")) it else "$it.md" }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return
        val f = ObsidianSync.find(root, path)?.takeIf { it.isFile } ?: return
        val before = context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } ?: return
        var after = before
        Store.update(context) { d -> Explore.syncNote(d, before).also { after = it.second }.first }
        if (after != before) context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(after.encodeToByteArray()) }
    }

    fun open(context: Context, data: AppData) = ObsidianSync.openNote(context, data, data.settings.plantStudyNotePath)
}
