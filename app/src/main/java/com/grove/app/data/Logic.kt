package com.grove.app.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Pure rules for days, streaks and schedules. No Android here, so it can be unit tested. */
object Logic {
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun key(d: LocalDate): String = d.toString()
    fun date(k: String): LocalDate = LocalDate.parse(k)

    /** Sunday-first index used by the weekday flags. */
    fun weekdayIndex(d: LocalDate): Int = d.dayOfWeek.value % 7

    /**
     * Grove's "today". It lags the calendar after midnight until Morning practice or the cutoff hour,
     * so screens, widgets and reminders must use this, not LocalDate.now().
     */
    fun groveToday(data: AppData): LocalDate = date(data.currentDay)

    fun clock(t: LocalDateTime): String = TIME_FMT.format(t)

    fun fmtTime(hhmm: String): String {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        return TIME_FMT.format(LocalDateTime.of(2000, 1, 1, h, m))
    }

    // ----- the logical day -----

    /**
     * Which day check-offs count toward. Sleep is irregular, so midnight doesn't end the day:
     * the day rolls over when Morning practice is checked off, or at the cutoff hour as a backup.
     */
    fun rolledDay(data: AppData, now: LocalDateTime): String {
        val today = now.toLocalDate()
        if (data.currentDay.isEmpty()) return key(today)
        val current = date(data.currentDay)
        if (!today.isAfter(current)) return data.currentDay
        val gap = ChronoUnit.DAYS.between(current, today)
        return if (gap > 1 || now.hour >= data.settings.cutoffHour) key(today) else data.currentDay
    }

    /** The calendar has moved past Grove's day (after midnight, before Morning practice or the cutoff). */
    fun newDayWaiting(data: AppData, now: LocalDateTime): Boolean =
        data.currentDay.isNotEmpty() && now.toLocalDate().isAfter(date(data.currentDay))

    /** Start the calendar's day now, without checking anything off. */
    fun startNewDay(data: AppData, now: LocalDateTime): AppData =
        if (newDayWaiting(data, now)) data.copy(currentDay = key(now.toLocalDate())) else data

    // ----- habits -----

    fun scheduledOn(h: Habit, d: LocalDate): Boolean = h.days[weekdayIndex(d)]

    fun stepsDone(data: AppData, day: String, h: Habit): List<Int> =
        data.days[day]?.steps?.get(h.id).orEmpty().filter { it < h.steps.size }

    fun isDone(data: AppData, day: String, h: Habit): Boolean =
        h.steps.isNotEmpty() && stepsDone(data, day, h).toSet().size == h.steps.size

    fun isDoneToday(data: AppData, h: Habit): Boolean = isDone(data, data.currentDay, h)

    /**
     * Streak with one day of grace: a single missed day pauses the count, two missed days in a row
     * reset it. Days the habit isn't scheduled are skipped. Today only counts once it's done.
     */
    fun streak(data: AppData, h: Habit): Int {
        val today = date(data.currentDay)
        val created = date(h.created)
        var n = if (isDone(data, data.currentDay, h)) 1 else 0
        var misses = 0
        var d = today.minusDays(1)
        while (!d.isBefore(created)) {
            if (scheduledOn(h, d)) {
                if (isDone(data, key(d), h)) {
                    n++; misses = 0
                } else {
                    misses++
                    if (misses >= 2) break
                }
            }
            d = d.minusDays(1)
        }
        return n
    }

    fun totalDays(data: AppData, h: Habit): Int = data.days.keys.count { isDone(data, it, h) }

    enum class StreakState { OK, PAUSED, RESET, NEW }

    /** Whether yesterday (and the day before) were missed, for the "paused" warning. */
    fun streakState(data: AppData, h: Habit): StreakState {
        val today = date(data.currentDay)
        val created = date(h.created)
        if (!today.isAfter(created)) return StreakState.NEW
        if (isDoneToday(data, h)) return StreakState.OK
        val missed = generateSequence(today.minusDays(1)) { it.minusDays(1) }
            .takeWhile { !it.isBefore(created) }
            .filter { scheduledOn(h, it) }
            .take(2)
            .map { !isDone(data, key(it), h) }
            .toList()
        return when {
            missed.size == 2 && missed.all { it } -> StreakState.RESET
            missed.firstOrNull() == true -> StreakState.PAUSED
            else -> StreakState.OK
        }
    }

    // ----- tasks -----

    data class DueTask(
        val id: String,
        val name: String,
        val time: String,
        val list: String,
        val task: Task?,
        val holy: Boolean = false,
    )

    fun tasksOn(data: AppData, d: LocalDate): List<DueTask> {
        val out = mutableListOf<DueTask>()
        val wd = weekdayIndex(d)
        for (t in data.tasks) {
            val on = when {
                // Monthly on a day of the month; in shorter months a 29th–31st falls on the last day.
                t.monthDay > 0 -> d.dayOfMonth == minOf(t.monthDay, d.lengthOfMonth())
                t.days.any { it } -> t.days[wd] && (!t.firstOfMonth || d.dayOfMonth <= 7)
                else -> t.date == key(d)
            }
            if (on) out += DueTask(t.id, t.name, t.time, t.list, t)
        }
        for (h in data.holyDays) {
            val hd = date(h.date)
            if (hd == d) out += DueTask("holy-${h.id}", "${h.name}: ${h.detail}", "14:00", "Holy day", null, true)
            if (hd.minusDays(3) == d) out += DueTask("pre-${h.id}", "${h.name} in 3 days: prepare", "14:00", "Holy day", null, true)
        }
        return out.sortedBy { it.time }
    }

    fun isTaskDone(data: AppData, day: String, taskId: String): Boolean = data.days[day]?.tasks?.containsKey(taskId) == true

    fun upcomingHolyDays(data: AppData, from: LocalDate): List<Pair<HolyDay, Long>> =
        data.holyDays.map { it to ChronoUnit.DAYS.between(from, date(it.date)) }.filter { it.second >= 0 }.sortedBy { it.second }

    fun countdown(days: Long): String = when (days) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> "in $days days"
    }

    // ----- mutations (return new data) -----

    fun withDay(data: AppData, day: String, f: (DayRecord) -> DayRecord): AppData =
        data.copy(days = data.days + (day to f(data.days[day] ?: DayRecord())))

    /** A past day or today can be marked; the future can't. */
    fun canMark(data: AppData, day: String): Boolean = !date(day).isAfter(date(data.currentDay))

    /**
     * Check a habit off (or undo it) on [day]: today by default, or any earlier day, so a forgotten
     * check-in can be filled in later. Times are only recorded for today.
     */
    fun toggleHabit(data: AppData, habitId: String, now: LocalDateTime, day: String = data.currentDay): AppData {
        val h = data.habits.firstOrNull { it.id == habitId } ?: return data
        if (!canMark(data, day)) return data
        var d = data
        var target = day
        // Checking off the morning habit once the calendar has moved on starts the new day, even if
        // yesterday's Morning practice is still showing as done (it's never unchecked by mistake).
        if (day == data.currentDay && h.role == Role.MORNING) {
            val today = key(now.toLocalDate())
            if (date(today).isAfter(date(d.currentDay))) { d = d.copy(currentDay = today); target = today }
        }
        val done = isDone(d, target, h)
        val live = target == d.currentDay
        // Marking a day before the habit was added moves its start back, so the streak counts it.
        if (!done && date(target).isBefore(date(h.created))) d = d.copy(habits = d.habits.map { if (it.id == h.id) it.copy(created = target) else it })
        return withDay(d, target) { r ->
            val steps = if (done) emptyList() else h.steps.indices.toList()
            val times = if (done || !live) r.habitTimes - h.id else r.habitTimes + (h.id to clock(now))
            r.copy(steps = r.steps + (h.id to steps), habitTimes = times)
        }
    }

    fun toggleStep(data: AppData, habitId: String, index: Int, now: LocalDateTime, day: String = data.currentDay): AppData {
        val h = data.habits.firstOrNull { it.id == habitId } ?: return data
        if (!canMark(data, day)) return data
        val next = withDay(data, day) { r ->
            val cur = r.steps[h.id].orEmpty().toSet()
            val steps = (if (index in cur) cur - index else cur + index).sorted()
            r.copy(steps = r.steps + (h.id to steps))
        }
        val nowDone = isDone(next, day, h) && day == next.currentDay
        return withDay(next, day) { r ->
            r.copy(habitTimes = if (nowDone) r.habitTimes + (h.id to clock(now)) else r.habitTimes - h.id)
        }
    }

    /** Check a task off (or undo it) on [day]; for an earlier day no time is recorded. */
    fun toggleTask(data: AppData, taskId: String, now: LocalDateTime, day: String = data.currentDay): AppData {
        if (!canMark(data, day)) return data
        return withDay(data, day) { r ->
            r.copy(tasks = if (taskId in r.tasks) r.tasks - taskId else r.tasks + (taskId to if (day == data.currentDay) clock(now) else ""))
        }
    }

    /** Mark a habit done (all steps) or not done on [day], as the connector asks. No time is recorded. */
    fun setHabitDone(data: AppData, habitId: String, day: String, done: Boolean): AppData {
        val h = data.habits.firstOrNull { it.id == habitId } ?: return data
        if (!canMark(data, day) || isDone(data, day, h) == done) return data
        var d = data
        if (done && date(day).isBefore(date(h.created))) d = d.copy(habits = d.habits.map { if (it.id == h.id) it.copy(created = day) else it })
        return withDay(d, day) { r ->
            r.copy(steps = r.steps + (habitId to if (done) h.steps.indices.toList() else emptyList()), habitTimes = r.habitTimes - habitId)
        }
    }

    /** Habits scheduled on [day] (and existing by then) that weren't finished. */
    fun missedOn(data: AppData, day: String): List<Habit> {
        val d = date(day)
        return data.habits.filter { scheduledOn(it, d) && !d.isBefore(date(it.created)) && !isDone(data, day, it) }
    }

    fun doneCount(data: AppData): Int = data.habits.count { isDoneToday(data, it) }

    /** Next habit to nudge: earliest reminder first, then anything else not done. */
    fun nextHabit(data: AppData): Habit? {
        val undone = data.habits.filter { !isDoneToday(data, it) && scheduledOn(it, date(data.currentDay)) }
        return undone.filter { it.reminders.isNotEmpty() }.minByOrNull { it.reminders.min() } ?: undone.firstOrNull()
    }

    fun isoWeek(d: LocalDate): String {
        val wf = java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR
        val yf = java.time.temporal.IsoFields.WEEK_BASED_YEAR
        return "%d-W%02d".format(d.get(yf), d.get(wf))
    }

    fun weekdayName(d: LocalDate): String = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)

    // ----- ids -----

    /** Counter ids look like "t31"; other ids (holy days, old timestamp ids) don't count. */
    private val NUM = Regex("""^[a-z](\d{1,7})$""")

    /** Highest number used in any id so far, in the setup or in history. */
    fun highestIdNumber(data: AppData): Int {
        val ids = data.habits.map { it.id } + data.tasks.map { it.id } + data.goals.map { it.id } + data.books.map { it.id } + data.todos.map { it.id } +
            data.days.values.flatMap { it.steps.keys + it.tasks.keys + it.sessions + it.pages.keys }
        return ids.mapNotNull { NUM.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
    }

    /** A fresh id like "t31". The counter only goes up, so deleted ids are never reused. */
    fun allocateId(data: AppData, prefix: String): Pair<String, AppData> {
        val n = maxOf(data.nextId, highestIdNumber(data) + 1)
        return "$prefix$n" to data.copy(nextId = n + 1)
    }

    // ----- weekly goals (weeks start Monday) -----

    fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /** Sunday-first weekday index -> that day in the Monday-start week containing [ref]. */
    fun dayInWeek(ref: LocalDate, weekday: Int): LocalDate = weekStart(ref).plusDays(((weekday + 6) % 7).toLong())

    fun sessionLogged(data: AppData, day: String, g: Goal): Boolean {
        val r = data.days[day] ?: return false
        return g.id in r.sessions || (g.legacyHabit.isNotEmpty() && r.steps[g.legacyHabit].orEmpty().isNotEmpty())
    }

    fun sessionsThisWeek(data: AppData, g: Goal, ref: LocalDate = groveToday(data)): Int =
        (0L..6L).map { weekStart(ref).plusDays(it) }.count { !it.isAfter(ref) && sessionLogged(data, key(it), g) }

    fun totalSessions(data: AppData, g: Goal): Int = data.days.keys.count { sessionLogged(data, it, g) }

    fun toggleSession(data: AppData, goalId: String): AppData =
        withDay(data, data.currentDay) { r -> r.copy(sessions = if (goalId in r.sessions) r.sessions - goalId else r.sessions + goalId) }

    private fun pagesLogged(data: AppData, bookId: String, before: LocalDate? = null): Int =
        data.days.filter { before == null || date(it.key).isBefore(before) }.values.sumOf { it.pages[bookId] ?: 0 }

    /** Page reached in [b]: the starting page plus everything logged, capped at the book's length. */
    fun bookPage(data: AppData, b: Book): Int = (b.startPage + pagesLogged(data, b.id)).coerceIn(0, b.pages)

    fun bookFinished(data: AppData, b: Book): Boolean = b.pages > 0 && bookPage(data, b) >= b.pages

    fun pagesOn(data: AppData, day: String): Int = data.days[day]?.pages?.values?.sum() ?: 0

    fun pagesThisWeek(data: AppData, ref: LocalDate = groveToday(data)): Int =
        (0L..6L).map { weekStart(ref).plusDays(it) }.filter { !it.isAfter(ref) }.sumOf { pagesOn(data, key(it)) }

    fun pagesLeft(data: AppData): Int = data.books.sumOf { it.pages - bookPage(data, it) }

    /**
     * Pages to read this week: what was left when the week began, spread over the weeks until the
     * deadline, rounded up. Fixed for the whole week; recalculated each Monday.
     */
    fun weeklyPageTarget(data: AppData, g: Goal, ref: LocalDate = groveToday(data)): Int {
        val ws = weekStart(ref)
        val leftAtStart = data.books.sumOf { b -> b.pages - (b.startPage + pagesLogged(data, b.id, ws)).coerceIn(0, b.pages) }
        val weeks = if (g.deadline.isEmpty()) 1L else maxOf(1L, ceilDiv(ChronoUnit.DAYS.between(ws, date(g.deadline)), 7L))
        return ceilDiv(leftAtStart.toLong(), weeks).toInt()
    }

    private fun ceilDiv(a: Long, b: Long): Long = if (a <= 0) 0 else (a + b - 1) / b

    fun progress(data: AppData, g: Goal, ref: LocalDate = groveToday(data)): Int = when (g.kind) {
        GoalKind.PAGES -> pagesThisWeek(data, ref)
        GoalKind.PROMPT -> if (Prompts.doneThisWeek(g, ref) || Prompts.finishedAll(g)) 1 else 0
        else -> sessionsThisWeek(data, g, ref)
    }

    fun target(data: AppData, g: Goal, ref: LocalDate = groveToday(data)): Int = when (g.kind) {
        GoalKind.PAGES -> weeklyPageTarget(data, g, ref)
        GoalKind.PROMPT -> 1
        else -> g.perWeek
    }

    /** One-line weekly status for the pinned notification and widget, e.g. "2/3 this week" or "Prompt 3 ✓". */
    fun goalStatus(data: AppData, g: Goal): String = when (g.kind) {
        GoalKind.PROMPT -> if (Prompts.finishedAll(g)) "all done ✓" else "Prompt ${g.current}" + if (progress(data, g) > 0) " ✓" else ""
        GoalKind.PAGES -> "${progress(data, g)}/${target(data, g)} pages this week"
        else -> "${progress(data, g)}/${target(data, g)} this week"
    }

    /** The nudge for [day], if it's that weekday and progress is still under its threshold. */
    fun dueNudge(data: AppData, g: Goal, day: LocalDate): Nudge? {
        val n = g.nudges.firstOrNull { it.weekday == weekdayIndex(day) } ?: return null
        return n.takeIf { progress(data, g, day) < target(data, g, day) * n.below }
    }

    fun logPages(data: AppData, bookId: String, pages: Int): AppData {
        val next = withDay(data, data.currentDay) { r ->
            val total = ((r.pages[bookId] ?: 0) + pages).coerceAtLeast(0)
            r.copy(pages = if (total == 0) r.pages - bookId else r.pages + (bookId to total))
        }
        return next.copy(lastBook = bookId)
    }

    fun currentBook(data: AppData): Book? =
        data.books.firstOrNull { it.id == data.lastBook && !bookFinished(data, it) } ?: data.books.firstOrNull { !bookFinished(data, it) }

    /** "1/3 this week", or "4/3 ★" once past the goal. */
    fun sessionLabel(done: Int, target: Int): String = if (done > target) "$done/$target ★" else "$done/$target this week"

    // ----- book notes (Media DB notes in the vault's Books folder) -----

    /** "Michigan Snakes: A Field Guide" and "Michigan Snakes (Harding & Holman)" -> "michigan snakes". */
    fun titleKey(title: String): String =
        title.lowercase().substringBefore(" (").substringBefore(":").replace(Regex("[^a-z0-9]+"), " ").trim()

    private val STOP = setOf("a", "an", "the", "of", "and", "to", "for", "in")

    /** 1.0 when one title is the other plus a subtitle; otherwise how many words they share (0–1). */
    fun titleScore(a: String, b: String): Double {
        val x = titleKey(a)
        val y = titleKey(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y || x.startsWith("$y ") || y.startsWith("$x ")) return 1.0
        val wx = x.split(" ").filter { it !in STOP }.toSet()
        val wy = y.split(" ").filter { it !in STOP }.toSet()
        if (minOf(wx.size, wy.size) >= 2 && (wx.containsAll(wy) || wy.containsAll(wx))) return 0.9
        return (wx intersect wy).size.toDouble() / (wx union wy).size.coerceAtLeast(1)
    }

    /** Forest piece for a new book, guessed from its title. */
    fun pieceFor(title: String): String {
        val t = title.lowercase()
        return listOf(
            "mushroom" to "mushrooms", "fung" to "mushrooms", "wildflower" to "wildflowers", "flower" to "wildflowers",
            "weed" to "weeds", "tree" to "trees", "bird" to "birds", "mammal" to "mammals", "rock" to "rocks",
            "mineral" to "rocks", "frog" to "frogs", "toad" to "frogs", "salamander" to "frogs", "snake" to "snakes", "reptile" to "snakes",
        ).firstOrNull { it.first in t }?.second.orEmpty()
    }

    /** A "Druid book" note found in the vault. */
    data class BookNote(val path: String, val title: String, val pages: Int)

    /**
     * Syncthing's own files: conflict copies ("x.sync-conflict-20261018-202415-KUKTOLE.md"), temp files,
     * and hidden folders like .stfolder and .stversions. Grove never reads or links these.
     */
    fun isSyncJunk(name: String): Boolean =
        ".sync-conflict-" in name || name.startsWith(".") || name.startsWith("~syncthing~")

    /**
     * Link books to their notes by title, and add a book for each Druid book note that matches none.
     * - A linked note in [missing] (deleted) is unlinked, never written back, and matched again.
     * - An exact title match wins over a looser one ("Mushrooms of the Upper Midwest" over
     *   "Magic Mushrooms of the Upper Midwest"), even if the looser one was linked first.
     * - A note whose title is close to any existing book is never added as a new book.
     * - Notes without a page count wait until it's filled in.
     */
    fun linkBookNotes(data: AppData, notes: List<BookNote>, missing: Set<String> = emptySet()): AppData {
        val byPath = notes.associateBy { it.path }
        var books = data.books.map { if (it.notePath.isNotEmpty() && it.notePath in missing) it.copy(notePath = "") else it }
        val claimed = books.map { it.notePath }.filter { it.isNotEmpty() }.toMutableSet()
        books = books.map { b ->
            val best = notes.filter { it.path !in claimed }.maxByOrNull { titleScore(b.title, it.title) } ?: return@map b
            val score = titleScore(b.title, best.title)
            val switch = if (b.notePath.isEmpty()) score >= 0.6
            else score >= 1.0 && (byPath[b.notePath]?.let { titleScore(b.title, it.title) } ?: 1.0) < 1.0
            if (!switch) return@map b
            claimed -= b.notePath
            claimed += best.path
            b.copy(notePath = best.path)
        }
        var d = data.copy(books = books)
        for (n in notes) {
            if (n.path in claimed || n.pages <= 0 || d.books.any { titleScore(it.title, n.title) >= 0.6 }) continue
            val (id, next) = allocateId(d, "b")
            d = next.copy(books = next.books + Book(id, n.title, n.pages, piece = pieceFor(n.title), notePath = n.path))
            claimed += n.path
        }
        return d
    }

    /** A frontmatter page count, or null when it's missing, empty, "unknown" or otherwise not a number. */
    fun pageCount(v: String?): Int? = v?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    /** Last day pages were logged for [b], or null. */
    fun lastReadOn(data: AppData, b: Book): String? = data.days.filter { (it.value.pages[b.id] ?: 0) > 0 }.keys.maxOrNull()

    /** Day [b] reached its last page through logged pages, or null. */
    fun finishedOn(data: AppData, b: Book): String? {
        var total = b.startPage
        for (day in data.days.keys.sorted()) {
            total += data.days[day]?.pages?.get(b.id) ?: 0
            if (total >= b.pages) return day
        }
        return null
    }

    /**
     * What Grove writes into a book note's frontmatter, given what's there now ([note]); null removes
     * the key. `read` here means finished (Earth Path lists it under Read). Media DB's `lastRead` is left alone.
     */
    fun bookNoteValues(data: AppData, b: Book, note: Map<String, String>): Map<String, String?> {
        val out = linkedMapOf<String, String?>("pagesRead" to bookPage(data, b).toString())
        lastReadOn(data, b)?.let { out["lastReadOn"] = it }
        if (pageCount(note["pages"]) == null) out["pages"] = b.pages.toString()
        if (bookFinished(data, b)) {
            out["read"] = "true"
            if (note["finished"].isNullOrBlank()) out["finished"] = finishedOn(data, b) ?: data.currentDay
        } else if (!note["finished"].isNullOrBlank()) {
            // Grove marked it finished before, but the pages went back down.
            out["read"] = "false"
            out["finished"] = null
        }
        return out
    }

    // ----- "Add a line?" for the daily note -----

    const val READING = "reading"

    /** Something that offers "Add a line?" when checked off: [key] is a habit/task id or [READING]. */
    data class LineItem(val key: String, val label: String, val prefix: String)

    fun lineItems(data: AppData): List<LineItem> {
        val out = mutableListOf<LineItem>()
        data.habits.firstOrNull { it.role == Role.MORNING }?.let { out += LineItem(it.id, it.name, "SOP") }
        data.habits.firstOrNull { it.role == Role.BEDTIME }?.let { out += LineItem(it.id, it.name, "Meditation") }
        for (t in data.tasks) {
            when {
                t.prop == "fifteen in nature" || t.id == "t3" || t.id == "t8" -> out += LineItem(t.id, t.name, "Nature")
                t.prop == "tree" || t.id == "t9" -> out += LineItem(t.id, t.name, "Plant")
            }
        }
        data.goals.firstOrNull { it.kind == GoalKind.PAGES }?.let { out += LineItem(READING, it.name, "Reading") }
        return out
    }

    fun wantsLine(data: AppData, key: String): Boolean = key !in data.settings.linePromptOff && key !in data.today.prompted

    /** A habit or task checked off today that hasn't been offered "Add a line?" yet (e.g. done from a widget). */
    fun pendingLine(data: AppData): LineItem? = lineItems(data).firstOrNull { item ->
        item.key != READING && wantsLine(data, item.key) &&
            (data.today.tasks.containsKey(item.key) || data.habits.firstOrNull { it.id == item.key }?.let { isDoneToday(data, it) } == true)
    }

    /** Save "Prefix: text" for today's note. Reading can be asked again after another log; the rest once a day. */
    fun addLine(data: AppData, key: String, line: String): AppData = withDay(data, data.currentDay) { r ->
        r.copy(lines = r.lines + line, prompted = if (key == READING || key in r.prompted) r.prompted else r.prompted + key)
    }

    /** One tap: no line, and don't ask again today. */
    fun skipLine(data: AppData, key: String): AppData =
        withDay(data, data.currentDay) { r -> if (key in r.prompted) r else r.copy(prompted = r.prompted + key) }

    fun markLinesWritten(data: AppData, day: String, n: Int): AppData =
        if (!data.days.containsKey(day)) data else withDay(data, day) { r -> r.copy(linesWritten = maxOf(r.linesWritten, n)) }

    // ----- evening check-in and quiet hours -----

    private fun minutes(hhmm: String): Int = hhmm.split(":").let { it[0].toInt() * 60 + it[1].toInt() }

    /** Quiet hours go by the clock, wrapping past midnight (21:00–08:00). Start == end means none. */
    fun inQuietHours(s: Settings, t: java.time.LocalTime): Boolean {
        val start = minutes(s.quietStart)
        val end = minutes(s.quietEnd)
        val m = t.hour * 60 + t.minute
        return when {
            start == end -> false
            start < end -> m in start until end
            else -> m >= start || m < end
        }
    }

    /** Something the evening check-in lists: [kind] is "habit" or "task". */
    data class OpenItem(val kind: String, val id: String, val label: String)

    /** What's still not done in Grove's day: habits (but not Morning practice) and tasks. */
    fun checkInItems(data: AppData): List<OpenItem> {
        val day = groveToday(data)
        val open = data.habits.filter { it.role != Role.MORNING && scheduledOn(it, day) && !isDone(data, data.currentDay, it) }
        val (bedtime, habits) = open.partition { it.role == Role.BEDTIME }
        val tasks = tasksOn(data, day).filter { !isTaskDone(data, data.currentDay, it.id) }
        // Before-bed practice goes last: it's meant for later.
        return habits.map { OpenItem("habit", it.id, it.name) } + tasks.map { OpenItem("task", it.id, it.name) } +
            bedtime.map { OpenItem("habit", it.id, "${it.name} (when you go to bed)") }
    }
}
