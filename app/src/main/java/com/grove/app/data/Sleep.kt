package com.grove.app.data

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** One night's (or day's) main sleep, filed under the day it ended (the wake-up day). Minutes are whole numbers. */
@Serializable
data class SleepNight(
    val day: String,
    val start: String,
    val end: String,
    val asleepMin: Int,
    val deepMin: Int = 0,
    val remMin: Int = 0,
    val lightMin: Int = 0,
    val awakeMin: Int = 0,
)

/**
 * Sleep from Health Connect (Galaxy Watch → Samsung Health), turned into one main sleep per wake-up day.
 * Pure, so it can be unit tested; the Health Connect reading is in obsidian/SleepImport.kt.
 */
object Sleep {
    // Health Connect's SleepSessionRecord stage types.
    const val AWAKE = 1
    const val SLEEPING = 2
    const val OUT_OF_BED = 3
    const val LIGHT = 4
    const val DEEP = 5
    const val REM = 6
    const val AWAKE_IN_BED = 7
    private val AWAKE_TYPES = setOf(AWAKE, OUT_OF_BED, AWAKE_IN_BED)

    /** Pieces of a sleep closer than this are one sleep (the watch sometimes splits a night). */
    const val MERGE_GAP_MIN = 60L
    /** Shorter than this isn't counted as a main sleep. */
    const val MIN_SLEEP_MIN = 60L

    data class Stage(val type: Int, val start: LocalDateTime, val end: LocalDateTime)
    data class Session(val start: LocalDateTime, val end: LocalDateTime, val stages: List<Stage> = emptyList())

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun minutes(a: LocalDateTime, b: LocalDateTime) = Duration.between(a, b).toMinutes().coerceAtLeast(0)

    /**
     * One main sleep per wake-up day: pieces less than an hour apart are joined, then each day keeps its
     * longest sleep (naps that day are left out). Days are calendar days of the end time, since a "night"
     * can end at any hour.
     */
    fun nights(sessions: List<Session>): List<SleepNight> {
        val merged = mutableListOf<Session>()
        for (s in sessions.filter { it.end.isAfter(it.start) }.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && minutes(last.end, s.start) <= MERGE_GAP_MIN) {
                merged[merged.lastIndex] = Session(last.start, maxOf(last.end, s.end), last.stages + s.stages)
            } else merged += s
        }
        return merged.filter { minutes(it.start, it.end) >= MIN_SLEEP_MIN }
            .groupBy { it.end.toLocalDate() }
            .map { (day, list) -> night(day.toString(), list.maxBy { minutes(it.start, it.end) }) }
            .sortedBy { it.day }
    }

    private fun night(day: String, s: Session): SleepNight {
        fun stage(types: Set<Int>) = s.stages.filter { it.type in types }.sumOf { minutes(it.start, it.end) }.toInt()
        val total = minutes(s.start, s.end).toInt()
        val awake = stage(AWAKE_TYPES)
        return SleepNight(
            day, STAMP.format(s.start), STAMP.format(s.end), asleepMin = (total - awake).coerceAtLeast(0),
            deepMin = stage(setOf(DEEP)), remMin = stage(setOf(REM)), lightMin = stage(setOf(LIGHT)), awakeMin = awake,
        )
    }

    fun hours(n: SleepNight): String = "%.1f".format(java.util.Locale.US, n.asleepMin / 60.0)

    /** The daily note's frontmatter: plain numbers so Dataview can chart them. */
    fun frontmatter(n: SleepNight): Map<String, String?> = linkedMapOf(
        "sleep_start" to n.start,
        "sleep_end" to n.end,
        "sleep_hours" to hours(n),
        "sleep_deep_min" to n.deepMin.toString(),
        "sleep_rem_min" to n.remMin.toString(),
        "sleep_light_min" to n.lightMin.toString(),
        "sleep_awake_min" to n.awakeMin.toString(),
    )

    private fun clock(stamp: String) = CLOCK.format(LocalDateTime.parse(stamp, STAMP))

    /** "- 2026-11-08 — 01:42 → 09:15 · 7.3 h asleep · deep 62 · REM 95 · light 240 · awake 31" */
    fun logLine(n: SleepNight): String =
        "- ${n.day} — ${clock(n.start)} → ${clock(n.end)} · ${hours(n)} h asleep · deep ${n.deepMin} · REM ${n.remMin} · light ${n.lightMin} · awake ${n.awakeMin}"

    /** Put each night's line in the log: a night already there is replaced, a new one is added in date order. */
    fun applyLog(text: String, nights: List<SleepNight>): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = (if (text.isBlank()) "# Sleep log\n\nFrom the Galaxy Watch via Health Connect, written by Grove. One line per wake-up day.\n" else text)
            .split(nl).toMutableList()
        for (n in nights) {
            val line = logLine(n)
            val at = lines.indexOfFirst { it.startsWith("- ${n.day} — ") }
            if (at >= 0) { lines[at] = line; continue }
            // After the last earlier day, or after the last log line.
            val logLines = lines.withIndex().filter { Regex("^- \\d{4}-\\d{2}-\\d{2} — ").containsMatchIn(it.value) }
            val before = logLines.lastOrNull { it.value.substring(2, 12) < n.day }
            val pos = when {
                before != null -> before.index + 1
                logLines.isNotEmpty() -> logLines.first().index
                else -> { while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex); lines += ""; lines.size }
            }
            lines.add(pos, line)
        }
        val out = lines.joinToString(nl)
        return if (out.endsWith(nl)) out else out + nl
    }

    /** The sleep that ended on Grove's day (or the calendar day after it), for the quiet line on Today. */
    fun latest(data: AppData): SleepNight? = data.sleepNights.lastOrNull { it.day >= data.currentDay }
}
