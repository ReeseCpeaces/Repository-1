package com.grove.app.data

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One sleep (a night or a nap), filed under the day it ended (the wake-up day). Minutes are whole numbers.
 * Every sleep Health Connect has is kept on its own, whatever its time or length.
 */
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

    data class Stage(val type: Int, val start: LocalDateTime, val end: LocalDateTime)
    data class Session(val start: LocalDateTime, val end: LocalDateTime, val stages: List<Stage> = emptyList())

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun minutes(a: LocalDateTime, b: LocalDateTime) = Duration.between(a, b).toMinutes().coerceAtLeast(0)

    /** Every sleep on its own (naps included, any time, any length), filed under the day it ended, in time order. */
    fun sleeps(sessions: List<Session>): List<SleepNight> =
        sessions.filter { it.end.isAfter(it.start) }.sortedBy { it.start }.map { night(it.end.toLocalDate().toString(), it) }

    /** The sleeps grouped by wake-up day. */
    fun byDay(sleeps: List<SleepNight>): Map<String, List<SleepNight>> = sleeps.groupBy { it.day }.mapValues { (_, l) -> l.sortedBy { it.start } }

    private fun night(day: String, s: Session): SleepNight {
        fun stage(types: Set<Int>) = s.stages.filter { it.type in types }.sumOf { minutes(it.start, it.end) }.toInt()
        val total = minutes(s.start, s.end).toInt()
        val awake = stage(AWAKE_TYPES)
        return SleepNight(
            day, STAMP.format(s.start), STAMP.format(s.end), asleepMin = (total - awake).coerceAtLeast(0),
            deepMin = stage(setOf(DEEP)), remMin = stage(setOf(REM)), lightMin = stage(setOf(LIGHT)), awakeMin = awake,
        )
    }

    fun hours(n: SleepNight): String = hours(n.asleepMin)
    fun hours(minutes: Int): String = "%.1f".format(java.util.Locale.US, minutes / 60.0)

    /** The day's longest sleep (its "main" sleep). */
    fun main(day: List<SleepNight>): SleepNight? = day.maxByOrNull { Duration.between(LocalDateTime.parse(it.start, STAMP), LocalDateTime.parse(it.end, STAMP)).toMinutes() }

    /**
     * The daily note's frontmatter for a wake-up day: totals over all its sleeps, how many there were, and the
     * longest one's times. Plain numbers so Dataview can chart them.
     */
    fun frontmatter(day: List<SleepNight>): Map<String, String?> {
        val m = main(day) ?: return emptyMap()
        val total = day.sumOf { it.asleepMin }
        return linkedMapOf(
            "sleep_hours" to hours(total),
            "sleep_count" to day.size.toString(),
            "sleep_main_hours" to hours(m),
            "sleep_nap_min" to (total - m.asleepMin).toString(),
            "sleep_start" to m.start,
            "sleep_end" to m.end,
            "sleep_deep_min" to day.sumOf { it.deepMin }.toString(),
            "sleep_rem_min" to day.sumOf { it.remMin }.toString(),
            "sleep_light_min" to day.sumOf { it.lightMin }.toString(),
            "sleep_awake_min" to day.sumOf { it.awakeMin }.toString(),
        )
    }

    private fun clock(stamp: String) = CLOCK.format(LocalDateTime.parse(stamp, STAMP))

    /** "- 2026-11-08 — 01:42 → 09:15 · 7.3 h asleep · deep 62 · REM 95 · light 240 · awake 31" */
    fun logLine(n: SleepNight): String =
        "- ${n.day} — ${clock(n.start)} → ${clock(n.end)} · ${hours(n)} h asleep · deep ${n.deepMin} · REM ${n.remMin} · light ${n.lightMin} · awake ${n.awakeMin}"

    private val LOG_LINE = Regex("^- (\\d{4}-\\d{2}-\\d{2}) — (\\d{2}:\\d{2}) → ")

    /**
     * Put each given day's sleeps in the log, one line per sleep: that day's old lines are replaced by the
     * new set (so a re-import never duplicates), and days stay in date and time order.
     */
    fun applyLog(text: String, days: Map<String, List<SleepNight>>): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = (if (text.isBlank()) "# Sleep log\n\nFrom the Galaxy Watch via Health Connect, written by Grove. One line per sleep (naps included), under the day it ended.\n" else text)
            .split(nl).toMutableList()
        for ((day, sleeps) in days.toSortedMap()) {
            val old = lines.withIndex().filter { LOG_LINE.find(it.value)?.groupValues?.get(1) == day }.map { it.index }
            var pos = old.firstOrNull()
            for (i in old.reversed()) lines.removeAt(i)
            if (pos == null) {
                val log = lines.withIndex().mapNotNull { (i, l) -> LOG_LINE.find(l)?.let { i to it.groupValues[1] } }
                val before = log.lastOrNull { it.second < day }
                pos = when {
                    before != null -> before.first + 1
                    log.isNotEmpty() -> log.first().first
                    else -> { while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex); lines += ""; lines.size }
                }
            }
            lines.addAll(pos, sleeps.sortedBy { it.start }.map { logLine(it) })
        }
        val out = lines.joinToString(nl)
        return if (out.endsWith(nl)) out else out + nl
    }

    /** Sleeps that ended on Grove's day (or the calendar day after it, before Morning practice), for the quiet line on Today. */
    fun latestDay(data: AppData): List<SleepNight> {
        val day = data.sleepNights.lastOrNull { it.day >= data.currentDay }?.day ?: return emptyList()
        return data.sleepNights.filter { it.day == day }
    }

    /** "Slept 7.0 h · 3 sleeps" or "Slept 7.0 h · 01:42 → 09:15". */
    fun summary(day: List<SleepNight>): String? {
        val m = main(day) ?: return null
        val total = hours(day.sumOf { it.asleepMin })
        return if (day.size == 1) "Slept $total h · ${clock(m.start)} → ${clock(m.end)}" else "Slept $total h · ${day.size} sleeps (longest ${hours(m)} h)"
    }
}
