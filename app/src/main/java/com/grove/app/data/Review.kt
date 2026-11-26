package com.grove.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields

/** Sunday's weekly review: which week, the counts, and the note text. Pure, so it can be unit tested. */
object Review {
    /** "2026-W48" (ISO week of the Monday). */
    fun label(monday: LocalDate): String =
        "%d-W%02d".format(monday.get(IsoFields.WEEK_BASED_YEAR), monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    /** The week to review: this week, or last week early on Monday/Tuesday (a review opened late from Sunday). */
    fun weekOf(ref: LocalDate): LocalDate {
        val monday = Logic.weekStart(ref)
        return if (ref.dayOfWeek == DayOfWeek.MONDAY || ref.dayOfWeek == DayOfWeek.TUESDAY) monday.minusDays(7) else monday
    }

    fun path(pattern: String, monday: LocalDate): String = pattern
        .replace("{GGGG}", monday.get(IsoFields.WEEK_BASED_YEAR).toString())
        .replace("{WW}", "%02d".format(monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)))

    /** One line per weekly goal, counted at the end of that week (or today, if the week isn't over). */
    fun counts(data: AppData, monday: LocalDate): List<String> {
        val end = minOf(monday.plusDays(6), Logic.groveToday(data))
        return data.goals.map { g ->
            val done = Logic.progress(data, g, end)
            val target = Logic.target(data, g, end)
            when (g.kind) {
                GoalKind.PAGES -> "${g.name}: $done / $target pages"
                GoalKind.MINUTES -> "${g.name}: $done / $target min"
                GoalKind.PROMPT -> "${g.name}: " + Prompts.status(g, end).substringBefore(" · aim")
                else -> "${g.name}: ${Logic.sessionLabel(done, target).removeSuffix(" this week")}"
            }
        }
    }

    val QUESTIONS = listOf("What went well?", "What got in the way? (What came right before?)", "One small change for next week")

    private fun body(data: AppData, monday: LocalDate, answers: List<String>, level: String): String {
        val out = StringBuilder()
        out.append("$level This week\n")
        counts(data, monday).forEach { out.append("- ").append(it).append('\n') }
        QUESTIONS.forEachIndexed { i, q ->
            out.append("\n$level ").append(q).append('\n')
            answers.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it).append('\n') }
        }
        return out.toString()
    }

    /** A new review note: the counts as a list, then the three answers (blank ones left empty). */
    fun note(data: AppData, monday: LocalDate, answers: List<String>): String {
        val sunday = monday.plusDays(6)
        val range = "${monday.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${monday.dayOfMonth} – " +
            "${sunday.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${sunday.dayOfMonth}, ${sunday.year}"
        return "# Weekly review · ${label(monday)}\n$range\n\n" + body(data, monday, answers, "##")
    }

    /** Add (or replace) a "## Weekly review" section in a note that's already there; nothing else changes. */
    fun mergeInto(text: String, data: AppData, monday: LocalDate, answers: List<String>): String {
        val section = "## Weekly review\n" + body(data, monday, answers, "###")
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).toMutableList()
        val head = lines.indexOfFirst { it.trim() == "## Weekly review" }
        if (head >= 0) {
            val end = (head + 1 until lines.size).firstOrNull { Regex("^#{1,2}\\s").containsMatchIn(lines[it]) } ?: lines.size
            for (i in end - 1 downTo head) lines.removeAt(i)
            lines.addAll(head, section.trimEnd('\n').split("\n"))
            return lines.joinToString(nl).let { if (it.endsWith(nl)) it else it + nl }
        }
        val base = text.trimEnd()
        return (if (base.isEmpty()) "" else base + "\n\n") + section
    }
}
