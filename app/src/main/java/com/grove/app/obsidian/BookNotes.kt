package com.grove.app.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.grove.app.data.AppData
import com.grove.app.data.Book
import com.grove.app.data.Logic
import com.grove.app.data.Store
import java.util.concurrent.Executors

/**
 * Links the Books screen to the book notes in the vault (one Media DB note per book in Books/):
 * finds notes with `subType: Druid book`, and writes reading progress into their frontmatter
 * (`pagesRead`, `lastReadOn`, `pages` if missing, and `read`/`finished` when a book is done).
 */
object BookNotes {
    private const val TAG = "BookNotes"
    private const val SUBTYPE = "druid book"
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var lastScan = 0L

    private fun root(context: Context, data: AppData): DocumentFile? =
        data.settings.vaultUri.takeIf { it.isNotEmpty() }?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }

    private fun read(context: Context, f: DocumentFile): String? =
        runCatching { context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()

    /**
     * Look for Druid book notes and link them (at most every few minutes unless [force]).
     * Then bring every linked note up to date. Call off the main thread.
     */
    fun scan(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScan < 5 * 60_000) return
        lastScan = now
        val data = Store.get(context)
        val root = root(context, data) ?: return
        val folder = data.settings.booksFolder.trim('/').ifEmpty { "Books" }
        val dir = ObsidianSync.find(root, folder) ?: return
        val notes = dir.listFiles().filter { it.isFile && it.name?.endsWith(".md") == true }.mapNotNull { f ->
            val front = read(context, f)?.let { NoteText.frontmatter(it) } ?: return@mapNotNull null
            val sub = (front["subType"] ?: front["subtype"]).orEmpty()
            if (!sub.equals(SUBTYPE, ignoreCase = true)) return@mapNotNull null
            Logic.BookNote("$folder/${f.name}", front["title"].orEmpty().ifEmpty { f.name!!.removeSuffix(".md") }, front["pages"]?.toIntOrNull() ?: 0)
        }
        Store.update(context) { Logic.linkBookNotes(it, notes) }
        val fresh = Store.get(context)
        fresh.books.filter { it.notePath.isNotEmpty() }.forEach { write(context, root, fresh, it) }
    }

    /** After a change: update the notes of books whose progress or link changed. */
    fun syncAsync(context: Context, old: AppData, new: AppData) {
        if (new.settings.vaultUri.isEmpty()) return
        val changed = new.books.filter { b ->
            b.notePath.isNotEmpty() && (old.books.firstOrNull { it.id == b.id }?.let { o -> o != b || signature(old, o) != signature(new, b) } ?: true)
        }.map { it.id }
        if (changed.isEmpty()) return
        worker.execute {
            runCatching {
                val data = Store.get(context)
                val root = root(context, data) ?: return@runCatching
                data.books.filter { it.id in changed }.forEach { write(context, root, data, it) }
            }.onFailure { Log.w(TAG, "Book note sync failed", it) }
        }
    }

    private fun signature(data: AppData, b: Book) = Triple(Logic.bookPage(data, b), Logic.lastReadOn(data, b), Logic.bookFinished(data, b))

    /** Update one note's frontmatter. A note that can't be read is never written. */
    private fun write(context: Context, root: DocumentFile, data: AppData, b: Book) {
        val f = ObsidianSync.find(root, b.notePath) ?: return
        val before = read(context, f) ?: return
        val after = NoteText.setFrontmatter(before, Logic.bookNoteValues(data, b, NoteText.frontmatter(before)))
        if (after != before) context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(after.encodeToByteArray()) }
    }
}
