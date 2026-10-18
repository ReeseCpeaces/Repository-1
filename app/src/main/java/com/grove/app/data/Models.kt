package com.grove.app.data

import kotlinx.serialization.Serializable

/** One checkable piece of a habit. Ticking it can also tick a property in the Obsidian daily note. */
@Serializable
data class Step(val label: String, val prop: String = "")

/** Special jobs a habit can have beyond being tracked. */
object Role {
    /** Checking it off starts a new day (and turns the forest to daytime). */
    const val MORNING = "morning"

    /** Checking it off brings night to the forest and writes the daily summary. */
    const val BEDTIME = "bedtime"
}

@Serializable
data class Habit(
    val id: String,
    val name: String,
    /** Shown when the habit has no timed reminder, e.g. "Right after waking". */
    val whenText: String = "",
    /** Sunday-first, one flag per weekday. */
    val days: List<Boolean> = List(7) { true },
    val steps: List<Step>,
    /** Reminder times as "HH:mm". */
    val reminders: List<String> = emptyList(),
    /** Keep re-alerting every [nagMinutes] until done. */
    val nag: Boolean = false,
    val nagMinutes: Int = 15,
    /** Shown in the pinned notification. */
    val pinned: Boolean = true,
    val role: String = "",
    /** Day the habit was added, "yyyy-MM-dd"; streaks never reach back past it. */
    val created: String,
)

@Serializable
data class Task(
    val id: String,
    val name: String,
    val list: String = "Inbox",
    /** Sunday-first weekday flags. All false means a one-time task on [date]. */
    val days: List<Boolean> = List(7) { false },
    val date: String = "",
    val time: String = "16:30",
    /** Only on the first matching weekday of the month. */
    val firstOfMonth: Boolean = false,
    val prop: String = "",
    val high: Boolean = false,
    val reminders: List<String> = emptyList(),
    val nag: Boolean = true,
    val nagMinutes: Int = 30,
    /** 1–31: repeats monthly on this day of the month (overrides [days] and [date]). 0 = not monthly. */
    val monthDay: Int = 0,
) {
    val repeating: Boolean get() = monthDay > 0 || days.any { it }
}

/** A weekly-goal nudge: on [weekday] (Sunday-first, 0–6) at [time], if progress is under [below] × the week's target. */
@Serializable
data class Nudge(val weekday: Int, val time: String, val below: Double)

object GoalKind {
    /** Count sessions (at most one per day) toward [Goal.perWeek]. */
    const val SESSIONS = "sessions"

    /** Count pages across [AppData.books] toward a weekly target that finishes them by [Goal.deadline]. */
    const val PAGES = "pages"
}

/** A weekly goal: any day counts, no streak, a fresh count every Monday. */
@Serializable
data class Goal(
    val id: String,
    val name: String,
    val kind: String,
    val perWeek: Int = 3,
    /** For page goals: finish every book by this date ("yyyy-MM-dd"). */
    val deadline: String = "",
    /** Obsidian property ticked on days with progress. */
    val prop: String = "",
    val nudges: List<Nudge> = emptyList(),
    /** Habit this goal replaced; its old check-offs count as past sessions. */
    val legacyHabit: String = "",
    val created: String,
)

/**
 * A book for a page goal. [piece] names the forest piece it adds when finished. [notePath] is its
 * note in the vault (e.g. "Books/Michigan Snakes.md"), which Grove keeps up to date with progress.
 */
@Serializable
data class Book(val id: String, val title: String, val pages: Int, val startPage: Int = 0, val piece: String = "", val notePath: String = "")

@Serializable
data class HolyDay(val id: String, val name: String, val date: String, val detail: String)

@Serializable
data class Settings(
    /** Storage Access Framework tree URI of the Obsidian vault folder. */
    val vaultUri: String = "",
    /** Daily note path inside the vault. Tokens: {yyyy} {MM} {MMMM} {dd} {yyyy-MM-dd}. */
    val dailyNotePath: String = "Journal/Daily notes/{yyyy}/{MMMM}/{yyyy-MM-dd}.md",
    /** Template used when today's note doesn't exist yet, relative to the vault. */
    val templatePath: String = "",
    /** A new day starts at this hour if Morning practice hasn't been checked off yet. */
    val cutoffHour: Int = 14,
    val defaultSnoozeMinutes: Int = 10,
    val wolfName: String = "",
    /** Vault folder with one note per book (Media DB). Notes with subType "Druid book" join the Books screen. */
    val booksFolder: String = "Books",
    /** Things that shouldn't ask "Add a line?" when checked off: habit/task ids, or "reading". */
    val linePromptOff: List<String> = emptyList(),
)

/** What got done on one logical day. */
@Serializable
data class DayRecord(
    /** Habit id -> indexes of its steps that are checked. */
    val steps: Map<String, List<Int>> = emptyMap(),
    /** Task id -> time it was checked off ("h:mm a"). */
    val tasks: Map<String, String> = emptyMap(),
    /** Habit id -> time it was completed. */
    val habitTimes: Map<String, String> = emptyMap(),
    val lifestyle: Boolean = false,
    val summaryWritten: Boolean = false,
    /** Ids of session goals logged this day (at most once per day each). */
    val sessions: List<String> = emptyList(),
    /** Book id -> pages read this day. */
    val pages: Map<String, Int> = emptyMap(),
    /** Lines for the daily note's Daily Log, like "Nature: saw a heron". */
    val lines: List<String> = emptyList(),
    /** How many of [lines] are already in the note. */
    val linesWritten: Int = 0,
    /** "Add a line?" prompts already answered or skipped this day (habit/task ids, "reading"). */
    val prompted: List<String> = emptyList(),
)

@Serializable
data class AppData(
    val habits: List<Habit> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val holyDays: List<HolyDay> = emptyList(),
    val settings: Settings = Settings(),
    /** The logical day currently being tracked, "yyyy-MM-dd". */
    val currentDay: String = "",
    val days: Map<String, DayRecord> = emptyMap(),
    val goals: List<Goal> = emptyList(),
    val books: List<Book> = emptyList(),
    /** Next number for new ids. Ids are never handed out twice, even after deletes. */
    val nextId: Int = 0,
    /** Book picked last time pages were logged. */
    val lastBook: String = "",
    /** Ids of Grove/inbox entries (logs from the connector) already applied. */
    val appliedInbox: List<String> = emptyList(),
) {
    val today: DayRecord get() = days[currentDay] ?: DayRecord()
}
