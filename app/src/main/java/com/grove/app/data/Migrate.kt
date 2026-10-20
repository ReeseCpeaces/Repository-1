package com.grove.app.data

/** One-way upgrades to saved data. Safe to run on every load and every change. */
object Migrate {
    /** The AODA candidate-year field guides (titles from the approved curriculum). */
    private val FIELD_GUIDES = listOf(
        Triple("Michigan Trees: A Guide to the Trees of the Great Lakes Region (Barnes & Wagner)", 456, "trees"),
        Triple("Wildflowers of Michigan Field Guide (Tekiela)", 429, "wildflowers"),
        Triple("Common Backyard Weeds of the Upper Midwest (Marrone)", 218, "weeds"),
        Triple("Mushrooms of the Upper Midwest (Marrone & Yerich)", 304, "mushrooms"),
        Triple("Birds of Michigan Field Guide (Tekiela)", 316, "birds"),
        Triple("Mammals of the Great Lakes Region (Kurta)", 392, "mammals"),
        Triple("Michigan Rocks & Minerals (Lynch & Lynch)", 252, "rocks"),
        Triple("Michigan Frogs, Toads, and Salamanders (Harding & Holman)", 144, "frogs"),
        Triple("Michigan Snakes (Harding & Holman)", 76, "snakes"),
    )

    /** Ids t15–t26 were handed out and removed before the counter existed; never reuse them. */
    private const val MIN_NEXT_ID = 27

    fun run(input: AppData): AppData {
        var d = input
        if (d.nextId == 0) d = d.copy(nextId = maxOf(MIN_NEXT_ID, Logic.highestIdNumber(d) + 1))

        // Oct 2026: writing and field-guide reading became weekly goals instead of daily streaks.
        val writing = d.habits.firstOrNull { it.id == "h4" }
        if (writing != null && d.goals.none { it.legacyHabit == "h4" }) {
            val (id, next) = Logic.allocateId(d, "g")
            d = next.copy(goals = next.goals + Goal(
                id, "Write the book", GoalKind.SESSIONS, perWeek = 3,
                nudges = listOf(Nudge(4, "15:00", 1.0 / 3), Nudge(6, "15:00", 1.0)),
                legacyHabit = "h4", created = writing.created,
            ))
        }
        val reading = d.habits.firstOrNull { it.id == "h5" }
        if (reading != null && d.goals.none { it.legacyHabit == "h5" }) {
            val (id, next) = Logic.allocateId(d, "g")
            d = next.copy(goals = next.goals + Goal(
                id, "Field guides", GoalKind.PAGES, deadline = "2027-06-21", prop = "read",
                nudges = listOf(Nudge(4, "17:30", 1.0 / 3), Nudge(6, "17:30", 1.0)),
                legacyHabit = "h5", created = reading.created,
            ))
            if (d.books.isEmpty()) {
                for ((title, pages, piece) in FIELD_GUIDES) {
                    val (bookId, n) = Logic.allocateId(d, "b")
                    d = n.copy(books = n.books + Book(bookId, title, pages, piece = piece))
                }
            }
        }
        // A habit replaced by a goal stays retired, even if an older grove.json brings it back.
        // Its check-ins stay in history.
        val retired = d.goals.map { it.legacyHabit }.filter { it.isNotEmpty() }.toSet()
        if (d.habits.any { it.id in retired }) d = d.copy(habits = d.habits.filter { it.id !in retired })
        d = Todos.prune(d)
        if (d.appliedInbox.size > 500) d = d.copy(appliedInbox = d.appliedInbox.takeLast(500))
        return d
    }
}
