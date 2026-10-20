package com.grove.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Weekly prompt goals (My Deepest Me): one prompt a week, opened Monday, aimed at Friday, and kept
 * open past Sunday if it slips. Pure rules plus note-text edits; mirrored in connector/grove_core.py.
 * Only Reese's own words (title, thoughts, answer) ever go into Grove, never the book's prompt text.
 */
object Prompts {
    const val THOUGHTS = "Thoughts"
    const val ANSWER = "Answer"

    fun goals(data: AppData): List<Goal> = data.goals.filter { it.kind == GoalKind.PROMPT }

    fun finishedAll(g: Goal): Boolean = g.doneOn.isNotEmpty() && g.current >= g.total

    fun doneThisWeek(g: Goal, ref: LocalDate): Boolean =
        g.doneOn.isNotEmpty() && Logic.weekStart(Logic.date(g.doneOn)) == Logic.weekStart(ref)

    /** The week after a prompt is finished, the next one opens (on that Monday). */
    fun advance(data: AppData): AppData {
        if (data.currentDay.isEmpty()) return data
        val monday = Logic.weekStart(Logic.groveToday(data))
        var changed = false
        val goals = data.goals.map { g ->
            if (g.kind != GoalKind.PROMPT || g.doneOn.isEmpty() || g.current >= g.total) return@map g
            if (!monday.isAfter(Logic.weekStart(Logic.date(g.doneOn)))) return@map g
            changed = true
            g.copy(current = g.current + 1, title = "", opened = monday.toString(), doneOn = "")
        }
        return if (changed) data.copy(goals = goals) else data
    }

    /** Prompts still to do, counting the open one. */
    fun promptsLeft(g: Goal): Int = if (g.doneOn.isEmpty()) g.total - g.current + 1 else g.total - g.current

    /** Weeks from this week (next week if this week's prompt is done) to the deadline, rounded up. */
    fun weeksLeft(g: Goal, ref: LocalDate): Int {
        if (g.deadline.isEmpty()) return promptsLeft(g)
        val from = Logic.weekStart(ref).let { if (g.doneOn.isEmpty()) it else it.plusDays(7) }
        val days = ChronoUnit.DAYS.between(from, Logic.date(g.deadline))
        return if (days <= 0) 0 else ((days + 6) / 7).toInt()
    }

    fun spareWeeks(g: Goal, ref: LocalDate): Int = weeksLeft(g, ref) - promptsLeft(g)

    private fun short(d: String) = Logic.date(d).let { "${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)} ${it.dayOfMonth}" }

    /** "Prompt 3 · aim for Friday", "Prompt 3 done for this week ✓", or "Prompt 3 · open since Oct 19 · aim for Friday". */
    fun status(g: Goal, ref: LocalDate): String = when {
        finishedAll(g) -> "All ${g.total} prompts done ✓"
        doneThisWeek(g, ref) -> "Prompt ${g.current} done for this week ✓"
        g.opened.isNotEmpty() && Logic.date(g.opened).isBefore(Logic.weekStart(ref)) -> "Prompt ${g.current} · open since ${short(g.opened)} · aim for Friday"
        else -> "Prompt ${g.current} · aim for Friday"
    }

    fun addThought(data: AppData, goalId: String, text: String): AppData {
        val g = data.goals.firstOrNull { it.id == goalId } ?: return data
        if (text.isBlank()) return data
        return data.copy(promptQueue = data.promptQueue + PromptEntry(entryId(data), g.id, g.current, THOUGHTS, data.currentDay, text.trim()))
    }

    fun setTitle(data: AppData, goalId: String, title: String): AppData =
        data.copy(goals = data.goals.map { if (it.id == goalId) it.copy(title = title.trim().replace("\n", " ")) else it })

    /** Finish this week's prompt: optional answer for the note, and "✓ My Deepest Me: Prompt N" in the daily note. */
    fun markDone(data: AppData, goalId: String, answer: String): AppData {
        val g = data.goals.firstOrNull { it.id == goalId } ?: return data
        if (g.doneOn.isNotEmpty()) return data
        var d = data.copy(goals = data.goals.map { if (it.id == goalId) it.copy(doneOn = data.currentDay) else it })
        if (answer.isNotBlank()) d = d.copy(promptQueue = d.promptQueue + PromptEntry(entryId(d), g.id, g.current, ANSWER, d.currentDay, answer.trim()))
        return Logic.withDay(d, d.currentDay) { r -> r.copy(lines = r.lines + "✓ ${g.name}: Prompt ${g.current}") }
    }

    /** A prompt finished elsewhere (by Claude, in grove.json) still gets its line in that day's note. */
    fun noteNewlyDone(old: AppData, new: AppData): AppData {
        var d = new
        for (g in goals(new)) {
            val o = old.goals.firstOrNull { it.id == g.id }
            if (g.doneOn.isEmpty() || (o != null && o.doneOn.isNotEmpty() && o.current == g.current)) continue
            d = Logic.withDay(d, g.doneOn) { r -> r.copy(lines = r.lines + "✓ ${g.name}: Prompt ${g.current}") }
        }
        return d
    }

    /** Reopen this week's prompt (a mistaken Done). */
    fun undoDone(data: AppData, goalId: String): AppData =
        data.copy(goals = data.goals.map { if (it.id == goalId && it.current > 0) it.copy(doneOn = "") else it })

    private fun entryId(data: AppData) = "${data.currentDay}-${data.promptQueue.size}-${System.nanoTime() % 1_000_000}"

    // ----- note text -----

    fun notePath(g: Goal, n: Int): String = "${g.noteFolder.trim('/')}/Prompt $n.md"

    fun heading(g: Goal, n: Int, title: String) = "# Prompt $n" + if (title.isNotBlank()) ": $title" else ""

    fun template(g: Goal, n: Int, title: String, opened: String): String = listOf(
        "---", "tags: [GCC, my-deepest-me]", "prompt: $n", "title: $title", "opened: $opened", "finished:", "---",
        heading(g, n, title), "## Thoughts", "## Answer", "",
    ).joinToString("\n")

    /** Keep the note's title (frontmatter and "# Prompt N" heading) and `finished` in step. Nothing else changes. */
    fun applyState(text: String, g: Goal, n: Int, title: String, finished: String): String {
        val front = com.grove.app.obsidian.NoteText.frontmatter(text)
        val values = linkedMapOf<String, String?>()
        if (front["title"].orEmpty() != title) values["title"] = title
        if (front["finished"].orEmpty() != finished) values["finished"] = finished
        var out = if (values.isEmpty()) text else com.grove.app.obsidian.NoteText.setFrontmatter(text, values).replace("finished: \n", "finished:\n")
        val nl = if (out.contains("\r\n")) "\r\n" else "\n"
        val lines = out.split(nl).toMutableList()
        val i = lines.indexOfFirst { Regex("^# Prompt $n(:.*)?\\s*$").matches(it) }
        if (i >= 0 && lines[i] != heading(g, n, title)) { lines[i] = heading(g, n, title); out = lines.joinToString(nl) }
        return out
    }

    /** Add list items at the end of a "## [section]" (before the next level 1–2 heading); add the heading if missing. */
    fun appendToSection(text: String, section: String, items: List<String>): String {
        if (items.isEmpty()) return text
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).toMutableList()
        val head = lines.indexOfFirst { Regex("^##\\s+${Regex.escape(section)}\\s*$").matches(it) }
        if (head < 0) {
            while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
            return (lines + "## $section" + items + "").joinToString(nl)
        }
        var end = lines.size
        for (j in head + 1 until lines.size) if (Regex("^#{1,2}\\s").containsMatchIn(lines[j])) { end = j; break }
        var at = end
        while (at > head + 1 && lines[at - 1].isBlank()) at--
        lines.addAll(at, items)
        return lines.joinToString(nl)
    }

    private val TRACKER_LINE = Regex("""^(\s*[-*+] \[)([ xX])(\] \*\*Prompt (\d+)\*\*.*)$""")

    /**
     * Tick the tracker's "- [ ] **Prompt N**: week of …" lines for prompts that are done (and untick the
     * current one if Done was undone), adding a link to the prompt's note. Later prompts are left alone.
     */
    fun applyTracker(text: String, g: Goal): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).map { line ->
            val m = TRACKER_LINE.matchEntire(line) ?: return@map line
            val n = m.groupValues[4].toInt()
            if (n > g.current) return@map line
            val done = n < g.current || g.doneOn.isNotEmpty()
            val ticked = m.groupValues[2] != " "
            if (done == ticked) return@map line
            val rest = m.groupValues[3]
            val link = if (done && !rest.contains("[[")) " · [[${notePath(g, n).removeSuffix(".md")}|note]]" else ""
            m.groupValues[1] + (if (done) "x" else " ") + rest + link
        }
        return lines.joinToString(nl)
    }
}
