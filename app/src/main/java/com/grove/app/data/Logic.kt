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

    fun toggleHabit(data: AppData, habitId: String, now: LocalDateTime): AppData {
        val h = data.habits.firstOrNull { it.id == habitId } ?: return data
        // Checking off the morning habit after a calendar rollover starts the new day.
        var d = data
        if (h.role == Role.MORNING && !isDoneToday(d, h)) {
            val today = key(now.toLocalDate())
            if (date(today).isAfter(date(d.currentDay))) d = d.copy(currentDay = today)
        }
        val done = isDone(d, d.currentDay, h)
        return withDay(d, d.currentDay) { r ->
            val steps = if (done) emptyList() else h.steps.indices.toList()
            val times = if (done) r.habitTimes - h.id else r.habitTimes + (h.id to clock(now))
            r.copy(steps = r.steps + (h.id to steps), habitTimes = times)
        }
    }

    fun toggleStep(data: AppData, habitId: String, index: Int, now: LocalDateTime): AppData {
        val h = data.habits.firstOrNull { it.id == habitId } ?: return data
        val next = withDay(data, data.currentDay) { r ->
            val cur = r.steps[h.id].orEmpty().toSet()
            val steps = (if (index in cur) cur - index else cur + index).sorted()
            r.copy(steps = r.steps + (h.id to steps))
        }
        val nowDone = isDone(next, next.currentDay, h)
        return withDay(next, next.currentDay) { r ->
            r.copy(habitTimes = if (nowDone) r.habitTimes + (h.id to clock(now)) else r.habitTimes - h.id)
        }
    }

    fun toggleTask(data: AppData, taskId: String, now: LocalDateTime): AppData =
        withDay(data, data.currentDay) { r ->
            r.copy(tasks = if (taskId in r.tasks) r.tasks - taskId else r.tasks + (taskId to clock(now)))
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
        val ids = data.habits.map { it.id } + data.tasks.map { it.id } + data.goals.map { it.id } + data.books.map { it.id } +
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

    fun progress(data: AppData, g: Goal, ref: LocalDate = groveToday(data)): Int =
        if (g.kind == GoalKind.PAGES) pagesThisWeek(data, ref) else sessionsThisWeek(data, g, ref)

    fun target(data: AppData, g: Goal, ref: LocalDate = groveToday(data)): Int =
        if (g.kind == GoalKind.PAGES) weeklyPageTarget(data, g, ref) else g.perWeek

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
}
