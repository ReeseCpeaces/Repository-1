package com.grove.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Druid exploration (plant study): minutes toward 20 hours by the deadline, sessions per plant, and
 * the "## Session log" lines in the plant-study note. Pure functions; mirrored in connector/grove_core.py.
 */
object Explore {
    /** The plants Reese chose for the study, in order, with backups and best months. */
    val PLANTS = listOf(
        Plant("", "White pine", "Pinus strobus", "sugar maple", "any time"),
        Plant("", "Staghorn sumac", "Rhus typhina", "goldenrod", "fall–winter (red fruit)", listOf(9, 10, 11, 12, 1, 2)),
        Plant("", "Northern white cedar", "Thuja occidentalis", "white oak", "any time"),
        Plant("", "Eastern red cedar", "Juniperus virginiana", "", "any time"),
        Plant("", "Wild strawberry", "Fragaria virginiana", "", "spring–June", listOf(4, 5, 6)),
        Plant("", "Yarrow", "Achillea millefolium", "", "spring–June", listOf(4, 5, 6)),
        Plant("", "Common milkweed", "Asclepias syriaca", "", "fall pods, spring shoots", listOf(9, 10, 11, 4, 5)),
        Plant("", "Mayapple", "Podophyllum peltatum", "", "late April–May", listOf(4, 5)),
        Plant("", "Wild bergamot", "Monarda fistulosa", "", "May–June", listOf(5, 6)),
        Plant("", "Reflection", "", "", "June (final 2 hours)", listOf(6)),
    )

    fun goal(data: AppData): Goal? = data.goals.firstOrNull { it.kind == GoalKind.MINUTES }

    fun plant(data: AppData, id: String): Plant? = data.plants.firstOrNull { it.id == id }
    fun plantName(data: AppData, id: String): String = plant(data, id)?.name ?: id

    /** Seasonal plants whose best months include [month]. "Any time" plants aren't singled out. */
    fun inSeason(p: Plant, month: Int): Boolean = p.months.size < 12 && month in p.months

    fun totalMinutes(data: AppData, before: LocalDate? = null): Int =
        data.explore.filter { before == null || Logic.date(it.day).isBefore(before) }.sumOf { it.minutes }

    fun plantMinutes(data: AppData, plantId: String): Int = data.explore.filter { it.plant == plantId }.sumOf { it.minutes }

    fun minutesThisWeek(data: AppData, ref: LocalDate): Int {
        val ws = Logic.weekStart(ref)
        return data.explore.filter { val d = Logic.date(it.day); !d.isBefore(ws) && !d.isAfter(ref) }.sumOf { it.minutes }
    }

    /** Minutes left when the week began, spread over the weeks until the deadline, rounded up (like pages). */
    fun weeklyTarget(data: AppData, g: Goal, ref: LocalDate): Int {
        val ws = Logic.weekStart(ref)
        val left = (g.total - totalMinutes(data, ws)).coerceAtLeast(0)
        val weeks = if (g.deadline.isEmpty()) 1L else maxOf(1L, (ChronoUnit.DAYS.between(ws, Logic.date(g.deadline)) + 6) / 7)
        return ((left + weeks - 1) / weeks).toInt()
    }

    fun weeksLeft(g: Goal, ref: LocalDate): Int =
        if (g.deadline.isEmpty()) 0 else maxOf(0L, (ChronoUnit.DAYS.between(Logic.weekStart(ref), Logic.date(g.deadline)) + 6) / 7).toInt()

    /** "3.5" hours, or "4" when whole. */
    fun hours(minutes: Int): String = (minutes / 60.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }

    /** The plant used last (the default for a new session), else the first one. */
    fun lastPlant(data: AppData): Plant? =
        data.explore.maxByOrNull { it.day }?.let { plant(data, it.plant) } ?: data.plants.firstOrNull()

    fun sessions(data: AppData, plantId: String? = null): List<ExploreSession> =
        data.explore.filter { plantId == null || it.plant == plantId }.sortedWith(compareByDescending<ExploreSession> { it.day }.thenByDescending { it.id })

    // ----- changes -----

    fun add(data: AppData, plantId: String, minutes: Int, note: String = "", day: String = data.currentDay): AppData {
        if (minutes <= 0) return data
        val (id, d) = Logic.allocateId(data, "e")
        return d.copy(explore = d.explore + ExploreSession(id, day, plantId, minutes, oneLine(note)))
    }

    fun edit(data: AppData, id: String, plantId: String, minutes: Int, note: String, day: String): AppData =
        data.copy(explore = data.explore.map { if (it.id == id) it.copy(plant = plantId, minutes = minutes, note = oneLine(note), day = day) else it })

    /** Delete a session; its line also leaves the note at the next sync. */
    fun delete(data: AppData, id: String): AppData {
        val s = data.explore.firstOrNull { it.id == id } ?: return data
        return data.copy(explore = data.explore - s, exploreDeleted = if (s.synced.isEmpty()) data.exploreDeleted else data.exploreDeleted + s.synced)
    }

    fun addPlant(data: AppData, name: String, latin: String = "", season: String = ""): AppData {
        val (id, d) = Logic.allocateId(data, "p")
        return d.copy(plants = d.plants + Plant(id, name.trim(), latin.trim(), season = season.trim()))
    }

    fun updatePlant(data: AppData, p: Plant): AppData = data.copy(plants = data.plants.map { if (it.id == p.id) p else it })

    fun movePlant(data: AppData, id: String, delta: Int): AppData {
        val i = data.plants.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in data.plants.indices) return data
        val list = data.plants.toMutableList()
        list.add(j, list.removeAt(i))
        return data.copy(plants = list)
    }

    /** Only a plant with no sessions can be removed (its note lines would bring it back). */
    fun removePlant(data: AppData, id: String): AppData =
        if (data.explore.any { it.plant == id }) data else data.copy(plants = data.plants.filter { it.id != id })

    private fun oneLine(s: String) = s.replace(Regex("\\s*\\n\\s*"), " ").trim()

    // ----- the note's "## Session log" -----

    const val SECTION = "Session log"
    private val LINE = Regex("""^\s*[-*] (\d{4}-\d{2}-\d{2})\s*·\s*(.+?)\s*·\s*\(min::\s*(\d+)\)(?:\s*·\s*(.*?))?\s*$""")

    fun line(day: String, plant: String, minutes: Int, note: String): String =
        "- $day · $plant · (min:: $minutes)" + if (note.isNotBlank()) " · ${note.trim()}" else ""

    fun lineOf(data: AppData, s: ExploreSession) = line(s.day, plantName(data, s.plant), s.minutes, s.note)

    data class NoteLine(val index: Int, val day: String, val plant: String, val minutes: Int, val note: String) {
        val text get() = line(day, plant, minutes, note)
    }

    /** The Session log section's bounds: heading index and the index after its last line (-1 if there's no section). */
    private fun section(lines: List<String>): Pair<Int, Int> {
        val head = lines.indexOfFirst { Regex("^##\\s+${Regex.escape(SECTION)}\\s*$", RegexOption.IGNORE_CASE).matches(it) }
        if (head < 0) return -1 to -1
        val end = (head + 1 until lines.size).firstOrNull { Regex("^#{1,2}\\s").containsMatchIn(lines[it]) } ?: lines.size
        return head to end
    }

    fun parse(text: String): List<NoteLine> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val (head, end) = section(lines)
        if (head < 0) return emptyList()
        return (head + 1 until end).mapNotNull { i ->
            LINE.matchEntire(lines[i])?.let { m -> NoteLine(i, m.groupValues[1], m.groupValues[2], m.groupValues[3].toInt(), m.groupValues[4]) }
        }
    }

    private fun findPlant(data: AppData, name: String): Plant? {
        val k = name.trim().lowercase()
        return data.plants.firstOrNull { it.name.lowercase() == k || "${it.name} (${it.latin})".lowercase() == k }
    }

    /**
     * Bring Grove's sessions and the note's Session log into step. A session whose line is unchanged
     * since the last sync follows Grove; a changed line (same day and plant) follows the note; a line
     * gone from the note removes the session; new lines join Grove (unknown plants are added); new
     * sessions are added at the end of the section; sessions deleted in Grove leave the note.
     * Nothing outside the Session log lines is touched. Returns the new data and note text.
     */
    fun syncNote(data: AppData, text: String): Pair<AppData, String> {
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split(nl).toMutableList()
        val items = parse(text)
        val used = mutableSetOf<Int>()
        val remove = mutableSetOf<Int>()

        for (gone in data.exploreDeleted) {
            items.firstOrNull { it.index !in used && it.text == gone }?.let { used += it.index; remove += it.index }
        }

        var d = data
        val kept = mutableListOf<ExploreSession>()
        val lost = mutableListOf<ExploreSession>()
        val append = mutableListOf<ExploreSession>()
        for (s in data.explore) {
            if (s.synced.isEmpty()) { append += s; continue }
            val item = items.firstOrNull { it.index !in used && it.text == s.synced }
            if (item == null) { lost += s; continue }
            used += item.index
            val now = lineOf(data, s)
            if (now != s.synced) lines[item.index] = now
            kept += s.copy(synced = now)
        }
        // A line edited in Obsidian (same day and plant): the note wins. Otherwise a missing line was deleted there.
        for (s in lost) {
            val item = items.firstOrNull { it.index !in used && it.day == s.day && findPlant(d, it.plant)?.id == s.plant } ?: continue
            used += item.index
            kept += s.copy(minutes = item.minutes, note = item.note, synced = item.text)
        }
        // New lines from Obsidian join Grove; an unknown plant joins the list.
        val added = mutableListOf<ExploreSession>()
        for (item in items) {
            if (item.index in used || item.index in remove) continue
            var p = findPlant(d, item.plant)
            if (p == null) { d = addPlant(d, item.plant); p = d.plants.last() }
            val (id, next) = Logic.allocateId(d, "e")
            d = next
            added += ExploreSession(id, item.day, p.id, item.minutes, item.note, synced = item.text)
        }
        val appended = append.map { it.copy(synced = lineOf(d, it)) }

        val out = lines.filterIndexed { i, _ -> i !in remove }.toMutableList()
        if (appended.isNotEmpty()) {
            val (head, end) = section(out)
            if (head < 0) {
                while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
                out += listOf("", "## $SECTION") + appended.map { it.synced } + ""
            } else {
                var at = end
                while (at > head + 1 && out[at - 1].isBlank()) at--
                out.addAll(at, appended.map { it.synced })
            }
        }
        val order = data.explore.map { it.id }
        val sessions = (kept + appended).sortedBy { order.indexOf(it.id) } + added
        return d.copy(explore = sessions, exploreDeleted = emptyList()) to out.joinToString(nl)
    }
}
