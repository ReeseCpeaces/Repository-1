package com.grove.app.data

/**
 * A log note in the vault with one line per item, grouped by day ("- yyyy-MM-dd …"), like Health/Sleep log.md
 * and Health/Weight log.md. Pure, so it can be unit tested.
 */
object DayLog {
    private val LINE = Regex("^- (\\d{4}-\\d{2}-\\d{2})[ ]")

    /**
     * Replace each given day's lines with its new lines (a re-import never duplicates), keeping days in date
     * order. A blank file starts with [header]. Every other line in the note stays as it is.
     */
    fun apply(text: String, header: String, days: Map<String, List<String>>): String {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = (if (text.isBlank()) header.replace("\n", nl) else text).split(nl).toMutableList()
        for ((day, newLines) in days.toSortedMap()) {
            val old = lines.withIndex().filter { LINE.find(it.value)?.groupValues?.get(1) == day }.map { it.index }
            var pos = old.firstOrNull()
            for (i in old.reversed()) lines.removeAt(i)
            if (pos == null) {
                val log = lines.withIndex().mapNotNull { (i, l) -> LINE.find(l)?.let { i to it.groupValues[1] } }
                val before = log.lastOrNull { it.second < day }
                pos = when {
                    before != null -> before.first + 1
                    log.isNotEmpty() -> log.first().first
                    else -> { while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex); lines += ""; lines.size }
                }
            }
            lines.addAll(pos, newLines)
        }
        val out = lines.joinToString(nl)
        return if (out.endsWith(nl)) out else out + nl
    }
}
