package com.grove.app.data

/**
 * Floating to-dos, and keeping them in step with a checklist note in Obsidian. Pure functions, so
 * they can be unit tested; mirrored in connector/grove_core.py.
 */
object Todos {
    /** Open to-dos, plus ones checked off today (shown crossed out until the day ends). High first, then by due date. */
    fun shown(data: AppData): List<Todo> = data.todos
        .filter { it.done.isEmpty() || it.done == data.currentDay }
        .sortedWith(compareBy<Todo>({ it.done.isNotEmpty() }, { !it.high }, { it.due.ifEmpty { "9999" } }, { it.created }))

    fun open(data: AppData): List<Todo> = data.todos.filter { it.done.isEmpty() }

    /** Check a to-do off for Grove's day, or reopen it. */
    fun toggle(data: AppData, id: String): AppData =
        data.copy(todos = data.todos.map { if (it.id == id) it.copy(done = if (it.done.isEmpty()) data.currentDay else "") else it })

    fun add(data: AppData, name: String, list: String = "", high: Boolean = false, due: String = "", remind: String = ""): AppData {
        val (id, d) = Logic.allocateId(data, "d")
        return d.copy(todos = d.todos + Todo(id, name.trim(), list.trim(), high, due, remind, created = data.currentDay))
    }

    /** Delete a to-do. One that's in the to-do note is also removed from the note at the next sync. */
    fun delete(data: AppData, id: String): AppData {
        val t = data.todos.firstOrNull { it.id == id } ?: return data
        return data.copy(todos = data.todos - t, todoDeleted = if (t.inNote) data.todoDeleted + t.noteName else data.todoDeleted)
    }

    /** Done to-dos are kept a while for the record, then dropped. */
    fun prune(data: AppData): AppData {
        val cutoff = Logic.date(data.currentDay.ifEmpty { return data }).minusDays(60).toString()
        val keep = data.todos.filter { it.done.isEmpty() || it.done >= cutoff }
        return if (keep.size == data.todos.size) data else data.copy(todos = keep)
    }

    /** To-dos checked off on [day], for that day's note summary. */
    fun doneOn(data: AppData, day: String): List<Todo> = data.todos.filter { it.done == day }

    // ----- the to-do note -----

    private val ITEM = Regex("""^(\s*[-*+] \[)([ xX])(\] )(.*)$""")
    private val DONE_MARK = Regex("""\s*✅\s*\d{4}-\d{2}-\d{2}""")
    private val DUE_MARK = Regex("""\s*📅\s*(\d{4}-\d{2}-\d{2})""")

    /** A checklist line: its index, whether it's ticked, the name (without Tasks-plugin dates) and due date. */
    data class NoteItem(val line: Int, val checked: Boolean, val name: String, val due: String)

    fun parse(text: String): List<NoteItem> {
        var fenced = false
        return text.replace("\r\n", "\n").split("\n").mapIndexedNotNull { i, line ->
            if (line.trimStart().startsWith("```")) fenced = !fenced
            if (fenced) return@mapIndexedNotNull null
            val m = ITEM.matchEntire(line) ?: return@mapIndexedNotNull null
            val body = m.groupValues[4]
            val name = body.replace(DONE_MARK, "").replace(DUE_MARK, "").trim()
            if (name.isEmpty()) null else NoteItem(i, m.groupValues[2] != " ", name, DUE_MARK.find(body)?.groupValues?.get(1).orEmpty())
        }
    }

    private fun key(name: String) = name.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Bring Grove's to-dos and the note's checklist into step. For each to-do already in the note,
     * whichever side changed since the last sync wins (the note if both did). Unticked items new in
     * the note join Grove; to-dos new in Grove are added below the note's last checklist line. An item
     * removed from the note leaves Grove; one deleted in Grove is removed from the note. Ticked items
     * in the note that Grove never had are left alone. Returns the new data and the new note text.
     */
    fun syncNote(data: AppData, text: String): Pair<AppData, String> {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).toMutableList()
        val items = parse(text)
        val used = mutableSetOf<Int>()
        val removeLines = mutableSetOf<Int>()
        val today = data.currentDay

        fun setChecked(i: Int, checked: Boolean) {
            val m = ITEM.matchEntire(lines[i]) ?: return
            val body = m.groupValues[4].replace(DONE_MARK, "")
            lines[i] = m.groupValues[1] + (if (checked) "x" else " ") + m.groupValues[3] + body + if (checked) " ✅ $today" else ""
        }

        fun rename(i: Int, name: String) {
            val m = ITEM.matchEntire(lines[i]) ?: return
            val marks = DUE_MARK.findAll(m.groupValues[4]).joinToString("") { it.value } + DONE_MARK.findAll(m.groupValues[4]).joinToString("") { it.value }
            lines[i] = m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + name + marks
        }

        // Deleted in Grove: take them out of the note.
        for (gone in data.todoDeleted) {
            items.firstOrNull { it.line !in used && !it.checked && key(it.name) == key(gone) }?.let { used += it.line; removeLines += it.line }
        }

        val added = mutableListOf<String>()
        val todos = mutableListOf<Todo>()
        for (t in data.todos) {
            if (!t.inNote) {
                if (t.done.isNotEmpty() && t.done < today) { todos += t; continue } // finished before linking: leave the note alone
                // Already in the note under the same name (say, added on both sides): link to that line.
                val same = items.firstOrNull { it.line !in used && key(it.name) == key(t.name) && (!it.checked || t.done.isNotEmpty()) }
                if (same != null) {
                    used += same.line
                    if (same.checked != t.done.isNotEmpty()) setChecked(same.line, t.done.isNotEmpty())
                    todos += t.copy(inNote = true, noteName = t.name, noteDone = t.done.isNotEmpty())
                    continue
                }
                added += "- [${if (t.done.isEmpty()) " " else "x"}] ${t.name}" + (if (t.due.isNotEmpty()) " 📅 ${t.due}" else "") +
                    (if (t.done.isNotEmpty()) " ✅ ${t.done}" else "")
                todos += t.copy(inNote = true, noteName = t.name, noteDone = t.done.isNotEmpty())
                continue
            }
            val item = items.firstOrNull { it.line !in used && key(it.name) == key(t.noteName) }
            if (item == null) {
                // Removed from the note: an open one leaves Grove; a done one is kept for today's record.
                if (t.done.isNotEmpty()) todos += t.copy(inNote = false)
                continue
            }
            used += item.line
            val groveDone = t.done.isNotEmpty()
            var done = t.done
            when {
                item.checked != t.noteDone -> done = if (item.checked) t.done.ifEmpty { today } else ""
                groveDone != t.noteDone -> setChecked(item.line, groveDone)
            }
            if (t.name != t.noteName) rename(item.line, t.name)
            val due = if (item.due.isNotEmpty()) item.due else t.due
            todos += t.copy(done = done, due = due, noteName = t.name, noteDone = done.isNotEmpty())
        }

        // New, unticked items in the note join Grove.
        var d = data.copy(todos = todos, todoDeleted = emptyList())
        for (item in items) {
            if (item.line in used || item.checked) continue
            val (id, next) = Logic.allocateId(d, "d")
            d = next.copy(todos = next.todos + Todo(id, item.name, due = item.due, created = today, inNote = true, noteName = item.name))
        }

        // Write: drop removed lines, then add new items after the last checklist line (or at the end).
        val kept = lines.filterIndexed { i, _ -> i !in removeLines }.toMutableList()
        if (added.isNotEmpty()) {
            val last = kept.indexOfLast { ITEM.matches(it) }
            if (last >= 0) kept.addAll(last + 1, added)
            else {
                while (kept.isNotEmpty() && kept.last().isEmpty()) kept.removeAt(kept.lastIndex)
                kept.addAll(added)
                kept += ""
            }
        }
        return d to kept.joinToString(nl)
    }
}
