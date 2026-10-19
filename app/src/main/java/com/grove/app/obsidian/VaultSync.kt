package com.grove.app.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.grove.app.data.AppData
import com.grove.app.data.Book
import com.grove.app.data.DayRecord
import com.grove.app.data.Goal
import com.grove.app.data.Habit
import com.grove.app.data.Logic
import com.grove.app.data.HolyDay
import com.grove.app.data.Settings
import com.grove.app.data.Store
import com.grove.app.data.Task
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

/** The part of Grove's data that can be edited from outside the app (by hand or by Claude). */
@Serializable
data class SharedSetup(
    val version: Int = 1,
    val habits: List<Habit>,
    val tasks: List<Task>,
    val holyDays: List<HolyDay>,
    val settings: Settings,
    // Added Oct 2026. Null when an older grove.json lacks them, so the app's own copy is kept.
    val goals: List<Goal>? = null,
    val books: List<Book>? = null,
    val nextId: Int? = null,
)

/** A log written by the connector into Grove/inbox/, applied by the app and then deleted. */
@Serializable
data class InboxEntry(
    val id: String,
    val type: String,
    val day: String = "",
    val book: String = "",
    val pages: Int = 0,
    val goal: String = "",
)

/** Check-in history, written for the Grove connector to read. The app never reads it back. */
@Serializable
data class SharedHistory(
    val version: Int = 1,
    val currentDay: String,
    val days: Map<String, DayRecord>,
    val appliedInbox: List<String> = emptyList(),
)

/**
 * Keeps Grove's setup in the vault at Grove/grove.json, which Syncthing carries to the server.
 * The app writes the file when its setup changes, and applies the file when someone else edited it.
 * Check-in history stays in the app; only the setup is shared.
 */
object VaultSync {
    private const val TAG = "VaultSync"
    private const val DIR = "Grove"
    private const val FILE = "grove.json"
    private const val README = "README.md"
    private const val HISTORY = "history.json"
    private const val PREFS = "vault_sync"
    private const val KEY_LAST = "last_text_hash"

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val worker = Executors.newSingleThreadExecutor()

    fun setupOf(data: AppData) = SharedSetup(
        habits = data.habits, tasks = data.tasks, holyDays = data.holyDays,
        // The vault location is specific to this phone, so it's never shared.
        settings = data.settings.copy(vaultUri = ""),
        goals = data.goals, books = data.books, nextId = data.nextId,
    )

    private fun folder(context: Context, data: AppData): DocumentFile? {
        if (data.settings.vaultUri.isEmpty()) return null
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return null
        return root.findFile(DIR) ?: root.createDirectory(DIR)
    }

    private fun read(context: Context, f: DocumentFile): String? =
        runCatching { context.contentResolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()

    private fun write(context: Context, dir: DocumentFile, name: String, text: String) {
        val f = dir.findFile(name) ?: dir.createFile("application/octet-stream", name) ?: return
        context.contentResolver.openOutputStream(f.uri, "wt")?.use { it.write(text.encodeToByteArray()) }
    }

    private fun lastHash(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST, 0)
    private fun remember(context: Context, text: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LAST, text.hashCode()).apply()

    /** Write the setup out after it changes in the app. */
    fun exportAsync(context: Context, old: AppData?, new: AppData) {
        if (new.settings.vaultUri.isEmpty()) return
        if (old == null || old.settings.vaultUri != new.settings.vaultUri) {
            worker.execute { runCatching { adoptOrExport(context) }.onFailure { Log.w(TAG, "Vault setup failed", it) } }
            return
        }
        if (setupOf(old) != setupOf(new)) {
            worker.execute { runCatching { export(context, new) }.onFailure { Log.w(TAG, "Export failed", it) } }
        }
        if (old.days != new.days || old.currentDay != new.currentDay || old.appliedInbox != new.appliedInbox) {
            worker.execute { runCatching { exportHistory(context, new) }.onFailure { Log.w(TAG, "History export failed", it) } }
        }
    }

    /**
     * Just after the vault folder is picked. If the vault already has a Grove setup (say, after
     * reinstalling the app), restore from it instead of overwriting it with the fresh install's.
     */
    private fun adoptOrExport(context: Context) {
        val data = Store.get(context)
        val dir = folder(context, data) ?: return
        if (dir.findFile(FILE) == null) {
            export(context, data)
            exportHistory(context, data)
            return
        }
        restoreHistoryIfEmpty(context, dir)
        importIfChanged(context, force = true)
    }

    /** Bring back check-ins from history.json when this install has none of its own. */
    private fun restoreHistoryIfEmpty(context: Context, dir: DocumentFile) {
        val hasOwn = Store.get(context).days.values.any { r -> r.tasks.isNotEmpty() || r.steps.values.any { it.isNotEmpty() } }
        if (hasOwn) return
        val text = dir.findFile(HISTORY)?.let { read(context, it) } ?: return
        val history = runCatching { json.decodeFromString(SharedHistory.serializer(), text) }.getOrNull() ?: return
        Store.update(context) { it.copy(days = history.days + it.days.filterKeys { k -> k !in history.days }) }
    }

    private fun exportHistory(context: Context, data: AppData) {
        val dir = folder(context, data) ?: return
        write(context, dir, HISTORY, json.encodeToString(SharedHistory.serializer(), SharedHistory(currentDay = data.currentDay, days = data.days, appliedInbox = data.appliedInbox)) + "\n")
    }

    private fun export(context: Context, data: AppData) {
        val dir = folder(context, data) ?: return
        val text = json.encodeToString(SharedSetup.serializer(), setupOf(data)) + "\n"
        val current = dir.findFile(FILE)?.let { read(context, it) }
        if (current != text) write(context, dir, FILE, text)
        remember(context, text)
        if (dir.findFile(README) == null) write(context, dir, README, GUIDE)
    }

    /**
     * Apply grove.json if it was edited since Grove last wrote or read it. Runs on a background
     * thread (app resume, boot, the day tick). Returns true if anything changed.
     */
    fun importIfChanged(context: Context, force: Boolean = false): Boolean {
        val data = Store.get(context)
        val dir = folder(context, data) ?: return false
        val f = dir.findFile(FILE) ?: run { export(context, data); return false }
        val text = read(context, f) ?: return false
        if (!force && text.hashCode() == lastHash(context)) return false
        val setup = runCatching { json.decodeFromString(SharedSetup.serializer(), text) }
            .onFailure { Log.w(TAG, "grove.json couldn't be read; keeping the app's setup", it) }
            .getOrNull() ?: return false
        remember(context, text)
        Store.update(context) { d ->
            d.copy(
                habits = setup.habits, tasks = setup.tasks, holyDays = setup.holyDays,
                settings = setup.settings.copy(vaultUri = d.settings.vaultUri),
                goals = setup.goals ?: d.goals, books = setup.books ?: d.books,
                nextId = maxOf(d.nextId, setup.nextId ?: 0),
            )
        }
        return true
    }

    /**
     * Apply logs the connector left in Grove/inbox/ (pages read, writing sessions), then delete
     * them. Applied ids are remembered, so a file that couldn't be deleted is never counted twice.
     */
    fun applyInbox(context: Context): Boolean {
        val data = Store.get(context)
        val inbox = folder(context, data)?.findFile("inbox") ?: return false
        // Names start with the time they were logged, so a correction lands after what it corrects.
        val files = inbox.listFiles().filter { f -> f.name.let { it != null && it.endsWith(".json") && !Logic.isSyncJunk(it) } }.sortedBy { it.name }
        if (files.isEmpty()) return false
        val entries = files.mapNotNull { f ->
            read(context, f)?.let { text -> runCatching { json.decodeFromString(InboxEntry.serializer(), text) }.getOrNull() }?.let { f to it }
        }
        Store.update(context) { d ->
            var x = d
            for ((_, e) in entries) {
                if (e.id in x.appliedInbox) continue
                val day = e.day.ifEmpty { x.currentDay }
                x = Logic.withDay(x, day) { r ->
                    when (e.type) {
                        "pages" -> r.copy(pages = r.pages + (e.book to ((r.pages[e.book] ?: 0) + e.pages).coerceAtLeast(0)))
                        "session" -> if (e.goal in r.sessions) r else r.copy(sessions = r.sessions + e.goal)
                        else -> r
                    }
                }
                x = x.copy(appliedInbox = x.appliedInbox + e.id, lastBook = if (e.type == "pages") e.book else x.lastBook)
            }
            x
        }
        entries.forEach { (f, _) -> runCatching { f.delete() } }
        return entries.isNotEmpty()
    }

    private val GUIDE = """
        # Grove setup file

        `grove.json` in this folder is the setup for the Grove app on the phone: habits, tasks, reminders,
        holy days and settings. Edit it (by hand or ask Claude), and Syncthing carries it to the phone;
        Grove applies it the next time the app opens. The app writes its own changes back here.
        Check-in history is in `history.json` (written by the app, read-only for everyone else).

        Rules for editing:
        - Keep it valid JSON. If Grove can't read it, it ignores the file and keeps its current setup.
        - Every habit and task needs a unique `id` (any short text, e.g. "h6", "t12"). Don't reuse or change
          existing ids: streak history is keyed by them.
        - Times are 24-hour "HH:mm" strings, e.g. "21:00". Dates are "yyyy-MM-dd".
        - `days` is 7 true/false values, Sunday first: [Sun, Mon, Tue, Wed, Thu, Fri, Sat].

        Habit fields: `id`, `name`, `whenText` (shown when there's no reminder), `days`, `steps` (list of
        `{ "label": ..., "prop": ... }`; `prop` is an Obsidian daily-note checkbox property to tick, or ""),
        `reminders` (list of times), `nag` (keep reminding until done), `nagMinutes`, `pinned` (show in the pinned
        notification), `role` ("morning" starts the day, "bedtime" ends it, else ""), `created` (date added; use today).

        Task fields: `id`, `name`, `list`, `days` (all false = one-time task on `date`), `date`, `time` (due time),
        `firstOfMonth` (only the first matching weekday of the month), `monthDay` (1–31 = repeats monthly on that
        day of the month, 0 = not monthly; overrides `days` and `date`), `prop`, `high` (high priority),
        `reminders`, `nag`, `nagMinutes`.

        Goal fields (weekly goals, no streak, weeks start Monday): `id`, `name`, `kind` ("sessions" counts days with a
        session toward `perWeek`; "pages" counts pages across `books` toward finishing them by `deadline`), `prop`,
        `nudges` (list of `{ "weekday": 0-6 Sunday-first, "time": "HH:mm", "below": fraction of the week's target }`),
        `legacyHabit`, `created`.

        Book fields: `id`, `title`, `pages`, `startPage` (pages already read before logging began), `piece` (forest
        piece added when finished: trees, wildflowers, weeds, mushrooms, birds, mammals, rocks, frogs, snakes, or ""),
        `notePath` (the book's note in the vault, e.g. "Books/Michigan Snakes.md"; Grove links notes with
        `subType: Druid book` by title and writes `pagesRead`, `lastReadOn`, `read` and `finished` into them).

        `nextId`: the next number for new ids. Take ids from it (e.g. "t31", then set nextId to 32); never reuse an id.

        Logging pages or sessions isn't done in this file: the Grove connector writes small files to `Grove/inbox/`,
        which the app applies and deletes.

        Holy day fields: `id`, `name`, `date`, `detail`.

        Settings: `dailyNotePath`, `templatePath`, `cutoffHour` (backup hour a new day starts),
        `defaultSnoozeMinutes`, `wolfName`, `booksFolder` (where the book notes are, default "Books"),
        `linePromptOff` (habit/task ids, or "reading", that shouldn't ask "Add a line?"). Leave `vaultUri` empty;
        it's set on the phone.
    """.trimIndent() + "\n"
}
