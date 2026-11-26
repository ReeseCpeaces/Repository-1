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
        // Oct 2026: My Deepest Me became a weekly prompt goal (Prompt 1 = week of Oct 19), replacing task t12.
        if (d.goals.none { it.kind == GoalKind.PROMPT } && !d.migratedPrompt) {
            val (id, next) = Logic.allocateId(d, "g")
            d = next.copy(
                goals = next.goals + Goal(
                    id, "My Deepest Me", GoalKind.PROMPT, deadline = "2027-06-21",
                    nudges = listOf(Nudge(1, "16:00", 1.0), Nudge(6, "15:00", 1.0)),
                    created = "2026-10-19", total = 30, current = 1, opened = "2026-10-19",
                    noteFolder = "Druidry/GCC/My Deepest Me", tracker = "Druidry/GCC/My Deepest Me - Tracker.md",
                ),
                tasks = next.tasks.filter { it.id != "t12" },
                migratedPrompt = true,
            )
        }
        // t12 ("My Deepest Me: start this week's prompt") stays gone, even if an older grove.json brings it back.
        if (d.migratedPrompt && d.tasks.any { it.id == "t12" }) d = d.copy(tasks = d.tasks.filter { it.id != "t12" })
        d = Prompts.advance(d)
        // Oct 29, 2026: "Nature time (15+ min)" can happen any day of the week, so it floats all week
        // instead of landing on Wednesday. Reminders on Wednesday and Saturday while it's still open.
        if (!d.migratedNatureWeek) {
            d = d.copy(
                tasks = d.tasks.map { if (it.id == "t3" && !it.anyDay) it.copy(anyDay = true, days = List(7) { i -> i == 3 || i == 6 }, monthDay = 0, date = "", firstOfMonth = false) else it },
                migratedNatureWeek = true,
            )
        }
        // Oct 2026: Druid exploration (plant study), 20 hours by the 2027 summer solstice.
        if (!d.migratedExplore) {
            if (d.goals.none { it.kind == GoalKind.MINUTES }) {
                val (id, next) = Logic.allocateId(d, "g")
                d = next.copy(goals = next.goals + Goal(
                    id, "Druid exploration", GoalKind.MINUTES, deadline = "2027-06-21",
                    nudges = listOf(Nudge(6, "15:00", 1.0)), created = d.currentDay.ifEmpty { "2026-10-22" }, total = 1200,
                ))
            }
            if (d.plants.isEmpty()) for (p in Explore.PLANTS) {
                val (id, next) = Logic.allocateId(d, "p")
                d = next.copy(plants = next.plants + p.copy(id = id))
            }
            d = d.copy(migratedExplore = true)
        }
        if (d.promptQueue.size > 200) d = d.copy(promptQueue = d.promptQueue.takeLast(200))
        // Nov 2026: the daily habits became weekly practice goals ("3 times a week beats 0"). Each piece of
        // practice is its own goal; old check-offs of the matching steps count as past sessions.
        if (!d.migratedPractice) d = practice(d)
        // A habit replaced by a goal stays retired, even if an older grove.json brings it back.
        // Its check-ins stay in history.
        val retired = (d.goals.map { it.legacyHabit } + d.goals.flatMap { g -> g.legacySteps.map { it.substringBefore(':') } })
            .filter { it.isNotEmpty() }.toSet()
        if (d.habits.any { it.id in retired }) d = d.copy(habits = d.habits.filter { it.id !in retired })
        d = Todos.prune(d)
        if (d.appliedInbox.size > 500) d = d.copy(appliedInbox = d.appliedInbox.takeLast(500))
        return d
    }

    /** h1 Morning practice, h2 Before-bed practice and h3 house reset → six weekly practice goals. */
    private fun practice(input: AppData): AppData {
        var d = input
        val h1 = d.habits.firstOrNull { it.id == "h1" }
        val h2 = d.habits.firstOrNull { it.id == "h2" }
        val h3 = d.habits.firstOrNull { it.id == "h3" }
        // Steps whose label has one of [words], as "habitId:index:prop" (found by label, since the steps may have been edited).
        fun steps(h: Habit?, vararg words: String): List<String> {
            if (h == null) return emptyList()
            return h.steps.withIndex().filter { (_, s) -> words.any { it in s.label.lowercase() } }.map { (i, s) -> "${h.id}:$i:${s.prop}" }
        }
        val created = d.currentDay.ifEmpty { "2026-11-26" }
        val goals = listOf(
            Goal("", "Sphere of Protection", GoalKind.SESSIONS, perWeek = 3, prop = "sphere of protection", created = created,
                group = Logic.PRACTICE, small = "SOP short form", linePrefix = "SOP", legacySteps = steps(h1, "sphere")),
            Goal("", "Morning prayer + altar moment", GoalKind.SESSIONS, perWeek = 3, created = created,
                group = Logic.PRACTICE, small = "One short prayer at the altar", legacySteps = steps(h1, "prayer", "altar")),
            Goal("", "Evening lection", GoalKind.SESSIONS, perWeek = 3, created = created,
                group = Logic.PRACTICE, small = "One short passage", evening = true, legacySteps = steps(h2, "lection")),
            Goal("", "Meditation", GoalKind.SESSIONS, perWeek = 3, created = created,
                group = Logic.PRACTICE, small = "5 minutes", linePrefix = "Meditation", evening = true,
                choices = listOf("discursive meditation", "contemplative meditation"), legacySteps = steps(h2, "meditation")),
            Goal("", "Journal line", GoalKind.SESSIONS, perWeek = 3, prop = "Daily Note", created = created,
                group = Logic.PRACTICE, small = "One sentence", evening = true, legacySteps = steps(h2, "journal")),
            Goal("", "15-min house reset", GoalKind.SESSIONS, perWeek = 4, created = created,
                group = Logic.PRACTICE, small = "5 minutes of tidying", legacyHabit = if (h3 != null) "h3" else ""),
        )
        for (g in goals) {
            if (d.goals.any { it.name == g.name && it.group == Logic.PRACTICE }) continue
            val (id, next) = Logic.allocateId(d, "g")
            d = next.copy(goals = next.goals + g.copy(id = id))
        }
        return d.copy(habits = d.habits.filter { it.id !in setOf("h1", "h2", "h3") }, migratedPractice = true)
    }
}
