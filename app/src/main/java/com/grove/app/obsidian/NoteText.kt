package com.grove.app.obsidian

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/** Text edits on an Obsidian daily note. Pure functions, so they can be unit tested. */
object NoteText {
    const val START = "<!-- grove:start -->"
    const val END = "<!-- grove:end -->"

    /** "Journal/Daily notes/{yyyy}/{MMMM}/{yyyy-MM-dd}.md" -> concrete path for [d]. */
    fun path(pattern: String, d: LocalDate): String = pattern
        .replace("{yyyy-MM-dd}", d.toString())
        .replace("{yyyy}", d.year.toString())
        .replace("{MMMM}", d.month.getDisplayName(TextStyle.FULL, Locale.US))
        .replace("{MM}", "%02d".format(d.monthValue))
        .replace("{dd}", "%02d".format(d.dayOfMonth))

    /** Format a date with a moment.js pattern, as Templater's tp.date.now does. */
    fun moment(fmt: String, d: LocalDate): String {
        val out = StringBuilder()
        var i = 0
        val tokens = listOf("GGGG", "YYYY", "MMMM", "dddd", "MMM", "ddd", "YY", "MM", "DD", "WW", "M", "D", "W")
        while (i < fmt.length) {
            if (fmt[i] == '[') {
                val end = fmt.indexOf(']', i)
                if (end > i) { out.append(fmt, i + 1, end); i = end + 1; continue }
            }
            val tok = tokens.firstOrNull { fmt.startsWith(it, i) }
            if (tok == null) { out.append(fmt[i]); i++; continue }
            out.append(
                when (tok) {
                    "GGGG" -> d.get(IsoFields.WEEK_BASED_YEAR).toString()
                    "YYYY" -> d.year.toString()
                    "YY" -> "%02d".format(d.year % 100)
                    "MMMM" -> d.month.getDisplayName(TextStyle.FULL, Locale.US)
                    "MMM" -> d.month.getDisplayName(TextStyle.SHORT, Locale.US)
                    "MM" -> "%02d".format(d.monthValue)
                    "M" -> d.monthValue.toString()
                    "DD" -> "%02d".format(d.dayOfMonth)
                    "D" -> d.dayOfMonth.toString()
                    "dddd" -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
                    "ddd" -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
                    "WW" -> "%02d".format(d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
                    else -> d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString()
                }
            )
            i += tok.length
        }
        return out.toString()
    }

    private val DATE_NOW = Regex("""<%\s*tp\.date\.now\(\s*"([^"]*)"\s*(?:,\s*(-?\d+)\s*)?\)\s*%>""")
    private val FILE_TITLE = Regex("""<%\s*tp\.file\.title\s*%>""")

    /** Fill the Templater date expressions a daily-note template uses. */
    fun fillTemplate(template: String, d: LocalDate): String = template
        .replace(DATE_NOW) { m -> moment(m.groupValues[1], d.plusDays(m.groupValues[2].ifEmpty { "0" }.toLong())) }
        .replace(FILE_TITLE, d.toString())

    /** Set checkbox properties in the frontmatter, adding any that are missing. Other lines stay untouched. */
    fun applyProperties(text: String, props: Map<String, Boolean>): String {
        if (props.isEmpty()) return text
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).toMutableList()
        val hasFront = lines.firstOrNull()?.trim() == "---" && lines.drop(1).any { it.trim() == "---" }
        if (!hasFront) {
            val front = listOf("---") + props.map { (k, v) -> "$k: $v" } + listOf("---")
            return (front + lines).joinToString(nl)
        }
        val close = lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
        val remaining = props.toMutableMap()
        for (i in 1 until close) {
            val key = lines[i].substringBefore(':', "").trim()
            if (key.isNotEmpty() && key in remaining && lines[i].contains(':')) {
                lines[i] = "$key: ${remaining.remove(key)}"
            }
        }
        lines.addAll(close, remaining.map { (k, v) -> "$k: $v" })
        return lines.joinToString(nl)
    }

    /** Replace Grove's section under "## Daily Log" (or append it), leaving everything else as written. */
    fun applySummary(text: String, summary: String): String {
        val block = "$START\n$summary\n$END"
        val s = text.indexOf(START)
        val e = text.indexOf(END)
        if (s >= 0 && e > s) return text.substring(0, s) + block + text.substring(e + END.length)
        val heading = Regex("(?m)^##\\s+Daily Log\\s*$").find(text)
        if (heading != null) {
            val at = heading.range.last + 1
            return text.substring(0, at) + "\n" + block + "\n" + text.substring(at)
        }
        val sep = if (text.endsWith("\n") || text.isEmpty()) "" else "\n"
        return text + sep + "\n## Daily Log\n" + block + "\n"
    }
}
