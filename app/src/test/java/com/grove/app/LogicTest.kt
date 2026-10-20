package com.grove.app

import com.grove.app.data.AppData
import com.grove.app.data.DayRecord
import com.grove.app.data.Habit
import com.grove.app.data.GoalKind
import com.grove.app.data.Logic
import com.grove.app.data.Migrate
import com.grove.app.data.Role
import com.grove.app.data.Seed
import com.grove.app.data.Step
import com.grove.app.obsidian.NoteText
import com.grove.app.obsidian.SharedSetup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LogicTest {
    private val habit = Habit("h", "Read", steps = listOf(Step("Read")), created = "2026-10-01")

    private fun data(today: String, doneDays: List<String>) = AppData(
        habits = listOf(habit),
        currentDay = today,
        days = doneDays.associateWith { DayRecord(steps = mapOf("h" to listOf(0))) },
    )

    @Test fun oneMissedDayPausesTheStreak() {
        // done 10, 11, (miss 12), 13, 14; today 15 not done yet
        val d = data("2026-10-15", listOf("2026-10-10", "2026-10-11", "2026-10-13", "2026-10-14"))
        assertEquals(4, Logic.streak(d, habit))
    }

    @Test fun twoMissedDaysResetTheStreak() {
        val d = data("2026-10-15", listOf("2026-10-10", "2026-10-11", "2026-10-14"))
        assertEquals(1, Logic.streak(d, habit))
    }

    @Test fun todayCountsOnceDone() {
        val d = data("2026-10-15", listOf("2026-10-14", "2026-10-15"))
        assertEquals(2, Logic.streak(d, habit))
        assertEquals(Logic.StreakState.OK, Logic.streakState(d, habit))
    }

    @Test fun missedYesterdayIsPaused() {
        val d = data("2026-10-15", listOf("2026-10-13"))
        assertEquals(Logic.StreakState.PAUSED, Logic.streakState(d, habit))
    }

    @Test fun dayDoesNotRollAtMidnight() {
        val d = data("2026-10-15", emptyList())
        assertEquals("2026-10-15", Logic.rolledDay(d, LocalDateTime.of(2026, 10, 16, 3, 0)))
        assertEquals("2026-10-16", Logic.rolledDay(d, LocalDateTime.of(2026, 10, 16, 14, 0)))
        assertEquals("2026-10-17", Logic.rolledDay(d, LocalDateTime.of(2026, 10, 17, 3, 0)))
    }

    @Test fun morningPracticeStartsTheNewDay() {
        val seed = Seed.data(LocalDate.of(2026, 10, 15))
        val morning = seed.habits.first { it.role == Role.MORNING }
        val next = Logic.toggleHabit(seed, morning.id, LocalDateTime.of(2026, 10, 16, 9, 0))
        assertEquals("2026-10-16", next.currentDay)
        assertTrue(Logic.isDoneToday(next, morning))
    }

    @Test fun saturdayHasPlantStudy() {
        val seed = Seed.data(LocalDate.of(2026, 10, 17))
        assertEquals(listOf("Plant study + nature time (1–2 hrs)"), Logic.tasksOn(seed, LocalDate.of(2026, 10, 17)).map { it.name })
    }

    @Test fun notePathAndWeek() {
        assertEquals("Journal/Daily notes/2026/October/2026-10-17.md",
            NoteText.path("Journal/Daily notes/{yyyy}/{MMMM}/{yyyy-MM-dd}.md", LocalDate.of(2026, 10, 17)))
        assertEquals("## Week [[Weekly/2026-W42]]",
            NoteText.fillTemplate("## Week [[Weekly/<% tp.date.now(\"GGGG-[W]WW\") %>]]", LocalDate.of(2026, 10, 17)))
    }

    @Test fun propertiesKeepOtherLines() {
        val note = "---\nDaily Note:\nsphere of protection:\ndate:\n---\n## Daily Log\n"
        val out = NoteText.applyProperties(note, mapOf("sphere of protection" to true, "discursive meditation" to false))
        assertEquals("---\nDaily Note:\nsphere of protection: true\ndate:\ndiscursive meditation: false\n---\n## Daily Log\n", out)
    }

    @Test fun summaryGoesUnderDailyLogAndIsReplaced() {
        val note = "---\n---\n## Daily Log\nmy own words\n"
        val once = NoteText.applySummary(note, "A")
        assertEquals("---\n---\n## Daily Log\n${NoteText.START}\nA\n${NoteText.END}\n\nmy own words\n", once)
        val twice = NoteText.applySummary(once, "B")
        assertEquals(once.replace("\nA\n", "\nB\n"), twice)
    }

    @Test fun readsSetupWrittenByTheConnector() {
        // Shape written by connector/grove_core.py (snake-free camelCase keys, extra version field).
        val text = """{"version": 1, "habits": [{"id": "h3", "name": "Discursive meditation", "whenText": "Any time today",
            "days": [true, true, true, true, true, true, true], "steps": [{"label": "Meditate", "prop": "discursive meditation"}],
            "reminders": ["21:00"], "nag": true, "nagMinutes": 15, "pinned": true, "role": "", "created": "2026-10-15"}],
            "tasks": [{"id": "t2", "name": "Email GCC", "list": "Inbox", "days": [false, false, false, false, false, false, false],
            "date": "2026-10-30", "time": "15:00", "firstOfMonth": false, "prop": "", "high": false, "reminders": ["15:00"],
            "nag": true, "nagMinutes": 30}], "holyDays": [], "settings": {"vaultUri": "", "dailyNotePath": "x", "templatePath": "",
            "cutoffHour": 14, "defaultSnoozeMinutes": 10, "wolfName": ""}}"""
        val setup = Json { ignoreUnknownKeys = true }.decodeFromString(SharedSetup.serializer(), text)
        assertEquals("discursive meditation", setup.habits[0].steps[0].prop)
        assertEquals("2026-10-30", setup.tasks[0].date)
    }

    @Test fun afterMidnightStillShowsYesterdaysTasks() {
        // Saturday Oct 17; at 01:00 on Sunday with Morning practice not done, Grove's day is still Saturday.
        val seed = Seed.data(LocalDate.of(2026, 10, 17))
        val night = seed.copy(currentDay = Logic.rolledDay(seed, LocalDateTime.of(2026, 10, 18, 1, 0)))
        assertEquals("2026-10-17", night.currentDay)
        assertEquals(listOf("Plant study + nature time (1–2 hrs)"), Logic.tasksOn(night, Logic.groveToday(night)).map { it.name })
    }

    @Test fun monthlyTasksRepeatOnTheDayOfTheMonth() {
        val taltz = com.grove.app.data.Task("t30", "Take Taltz", "Health", time = "14:00", monthDay = 13)
        val endOfMonth = com.grove.app.data.Task("t31", "Pay rent", monthDay = 31)
        val d = AppData(tasks = listOf(taltz, endOfMonth), currentDay = "2026-11-13")
        assertEquals(listOf("Take Taltz"), Logic.tasksOn(d, LocalDate.of(2026, 11, 13)).map { it.name })
        assertTrue(Logic.tasksOn(d, LocalDate.of(2026, 11, 14)).isEmpty())
        assertEquals(listOf("Pay rent"), Logic.tasksOn(d, LocalDate.of(2027, 2, 28)).map { it.name })
        assertEquals(listOf("Pay rent"), Logic.tasksOn(d, LocalDate.of(2026, 12, 31)).map { it.name })
        assertTrue(Logic.tasksOn(d, LocalDate.of(2026, 12, 30)).isEmpty())
    }

    // ----- weekly goals -----

    private fun migrated() = Migrate.run(Seed.data(LocalDate.parse("2026-10-19")))

    @Test fun writingAndReadingBecomeWeeklyGoals() {
        val d = migrated()
        assertEquals(listOf("h1", "h2", "h3"), d.habits.map { it.id })
        assertEquals(listOf(GoalKind.SESSIONS, GoalKind.PAGES), d.goals.map { it.kind })
        assertEquals(9, d.books.size)
        assertEquals(2587, d.books.sumOf { it.pages })
        assertTrue(d.goals.none { it.id == "h4" || it.id == "h5" })
        assertEquals(d, Migrate.run(d)) // safe to run again
    }

    @Test fun weeklyPageTargetIs74FromOct19AndHoldsAllWeek() {
        val d = migrated()
        val reading = d.goals.first { it.kind == GoalKind.PAGES }
        assertEquals(74, Logic.weeklyPageTarget(d, reading, LocalDate.parse("2026-10-19")))
        val book = d.books.first { it.title.startsWith("Mushrooms") }
        val read = Logic.logPages(d, book.id, 18)
        assertEquals(74, Logic.weeklyPageTarget(read, reading, LocalDate.parse("2026-10-25")))
        assertEquals(18, Logic.progress(read, reading, LocalDate.parse("2026-10-19")))
        assertEquals(book.id, Logic.currentBook(read)?.id)
    }

    @Test fun writingSessionsCountByMondayWeekAndOldCheckOffsCount() {
        var d = migrated()
        val writing = d.goals.first { it.kind == GoalKind.SESSIONS }
        d = d.copy(days = d.days + ("2026-10-14" to DayRecord(steps = mapOf("h4" to listOf(0)))))
        d = Logic.toggleSession(d, writing.id)
        assertEquals(1, Logic.sessionsThisWeek(d, writing))
        assertEquals(2, Logic.totalSessions(d, writing))
        assertEquals("1/3 this week", Logic.sessionLabel(1, 3))
        assertEquals("4/3 ★", Logic.sessionLabel(4, 3))
        // Thursday nudge fires with no sessions yet; Saturday nudge goes quiet at 3.
        val fresh = migrated()
        assertTrue(Logic.dueNudge(fresh, writing, LocalDate.parse("2026-10-22")) != null)
        assertEquals(null, Logic.dueNudge(fresh, writing, LocalDate.parse("2026-10-21")))
        val three = fresh.copy(days = listOf("2026-10-19", "2026-10-20", "2026-10-21").associateWith { DayRecord(sessions = listOf(writing.id)) })
        assertEquals(null, Logic.dueNudge(three, writing, LocalDate.parse("2026-10-24")))
    }

    @Test fun idsAreNeverReused() {
        val d = migrated()
        val (first, after) = Logic.allocateId(d, "t")
        val removed = after.copy(tasks = after.tasks.filter { it.id != first })
        val (second, _) = Logic.allocateId(removed, "t")
        assertTrue(first != second)
        assertTrue(first.drop(1).toInt() >= 27)
    }

    // ----- book notes and "Add a line?" -----

    @Test fun frontmatterEditsOnlyTouchGivenKeys() {
        val text = "---\na: 1\nlist:\n  - x\nb: 2\n---\nbody\n"
        assertEquals("---\nlist: 3\nb: 2\nc: 4\n---\nbody\n", NoteText.setFrontmatter(text, mapOf("list" to "3", "c" to "4", "a" to null)))
        assertEquals(mapOf("a" to "1", "list" to "", "b" to "2"), NoteText.frontmatter(text))
    }

    @Test fun logLinesGoAtTheEndOfDailyLogOutsideGrovesBlock() {
        val note = "---\n---\n## Daily Log\n${NoteText.START}\n### Grove\n- [x] Morning\n${NoteText.END}\n- my own line\n\n## Notes\nx\n"
        val out = NoteText.appendLogLines(note, listOf("Nature: saw a heron", "SOP: steady"))
        assertEquals("---\n---\n## Daily Log\n${NoteText.START}\n### Grove\n- [x] Morning\n${NoteText.END}\n- my own line\n- Nature: saw a heron\n- SOP: steady\n\n## Notes\nx\n", out)
        assertTrue(NoteText.appendLogLines("# Day\n", listOf("Plant: leaves out")).endsWith("## Daily Log\n- Plant: leaves out\n"))
    }

    @Test fun booksLinkToDruidBookNotesByTitle() {
        val d = migrated()
        val notes = listOf(
            Logic.BookNote("Books/Michigan Snakes.md", "Michigan Snakes: A Field Guide and Pocket Reference", 76),
            Logic.BookNote("Books/Michigan Trees.md", "Michigan Trees, Revised and Updated: A Guide to the Trees", 456),
            Logic.BookNote("Books/Lichens.md", "Lichens of the North Woods", 150),
        )
        val linked = Logic.linkBookNotes(d, notes)
        assertEquals("Books/Michigan Snakes.md", linked.books.first { it.title.startsWith("Michigan Snakes") }.notePath)
        assertEquals("Books/Michigan Trees.md", linked.books.first { it.title.startsWith("Michigan Trees") }.notePath)
        val added = linked.books.last()
        assertEquals("Lichens of the North Woods", added.title)
        assertEquals(10, linked.books.size)
        assertEquals(linked, Logic.linkBookNotes(linked, notes)) // nothing new the second time
    }

    @Test fun bookNoteGetsProgressAndReadWhenFinished() {
        var d = Logic.linkBookNotes(migrated(), listOf(Logic.BookNote("Books/Michigan Snakes.md", "Michigan Snakes", 76)))
        val snakes = d.books.first { it.notePath.isNotEmpty() }
        d = Logic.logPages(d, snakes.id, 20)
        assertEquals(mapOf("pagesRead" to "20", "lastReadOn" to "2026-10-19"), Logic.bookNoteValues(d, snakes, mapOf("pages" to "76", "read" to "false")))
        d = Logic.logPages(d, snakes.id, 56)
        val done = Logic.bookNoteValues(d, snakes, mapOf("pages" to "76", "read" to "false"))
        assertEquals("true", done["read"])
        assertEquals("2026-10-19", done["finished"])
        d = Logic.logPages(d, snakes.id, -6)
        val back = Logic.bookNoteValues(d, snakes, mapOf("pages" to "76", "read" to "true", "finished" to "2026-10-19"))
        assertEquals("false", back["read"])
        assertTrue(back.containsKey("finished") && back["finished"] == null)
    }

    @Test fun addALineIsOfferedOnceADay() {
        var d = migrated()
        assertEquals(null, Logic.pendingLine(d))
        d = Logic.toggleTask(d, "t3", LocalDateTime.parse("2026-10-19T16:40"))
        assertEquals("Nature", Logic.pendingLine(d)?.prefix)
        d = Logic.addLine(d, "t3", "Nature: heron on the pond")
        assertEquals(null, Logic.pendingLine(d))
        assertEquals(listOf("Nature: heron on the pond"), d.today.lines)
        d = d.copy(settings = d.settings.copy(linePromptOff = listOf("t9")))
        d = Logic.toggleTask(d, "t9", LocalDateTime.parse("2026-10-19T16:45"))
        assertEquals(null, Logic.pendingLine(d))
        d = Logic.skipLine(d, Logic.READING)
        assertTrue(!Logic.wantsLine(d, Logic.READING))
    }

    @Test fun conflictCopiesDeletedNotesAndLooseMatchesDontMakeBooks() {
        assertTrue(Logic.isSyncJunk("Michigan Trees (2004).sync-conflict-20261018-202415-KUKTOLE.md"))
        assertTrue(Logic.isSyncJunk(".stversions"))
        assertTrue(!Logic.isSyncJunk("Michigan Trees (2004).md"))
        assertEquals(null, Logic.pageCount("unknown"))
        assertEquals(null, Logic.pageCount(""))
        assertEquals(304, Logic.pageCount(" 304 "))

        val magic = Logic.BookNote("Books/Magic Mushrooms of the Upper Midwest (2022).md", "Magic Mushrooms of the Upper Midwest", 200)
        val right = Logic.BookNote("Books/Mushrooms of the Upper Midwest.md", "Mushrooms of the Upper Midwest", 304)
        // Only the wrong note exists at first, so Mushrooms links to it.
        var d = Logic.linkBookNotes(migrated(), listOf(magic))
        val mushrooms = { x: com.grove.app.data.AppData -> x.books.first { it.title.startsWith("Mushrooms") } }
        assertEquals(magic.path, mushrooms(d).notePath)
        // The right note appears: the exact title wins, and neither note becomes a new book.
        d = Logic.linkBookNotes(d, listOf(magic, right))
        assertEquals(right.path, mushrooms(d).notePath)
        assertEquals(9, d.books.size)
        // The note is deleted: unlinked, not recreated, and no other book picks up the loose match.
        d = Logic.linkBookNotes(d, listOf(magic), missing = setOf(right.path))
        assertEquals(magic.path, mushrooms(d).notePath) // re-matched by title on the same scan
        d = Logic.linkBookNotes(d, emptyList(), missing = setOf(magic.path))
        assertEquals("", mushrooms(d).notePath)
        assertEquals(9, d.books.size)

        val values = Logic.bookNoteValues(d, mushrooms(d), mapOf("pages" to "unknown"))
        assertEquals("304", values["pages"])
    }

    // ----- marking earlier days -----

    @Test fun forgottenCheckInsCanBeMarkedOnAnyEarlierDay() {
        // done 10, 11, 13 (missed 12 and 14); today 15: two misses in a row would reset... except 13 was done
        var d = data("2026-10-15", listOf("2026-10-10", "2026-10-11", "2026-10-13"))
        assertEquals(listOf("h"), Logic.missedOn(d, "2026-10-14").map { it.id })
        assertEquals(3, Logic.streak(d, habit))
        d = Logic.toggleHabit(d, "h", LocalDateTime.parse("2026-10-15T09:00"), "2026-10-14")
        assertEquals(4, Logic.streak(d, habit))
        assertTrue(Logic.isDone(d, "2026-10-14", habit))
        assertEquals(null, d.days["2026-10-14"]?.habitTimes?.get("h")) // no made-up time for a past day
        assertEquals(emptyList<Any>(), Logic.missedOn(d, "2026-10-14"))
        d = Logic.toggleHabit(d, "h", LocalDateTime.parse("2026-10-15T09:00"), "2026-10-12")
        assertEquals(5, Logic.streak(d, habit))
        // The future can't be marked; today still works as before.
        assertEquals(d, Logic.toggleHabit(d, "h", LocalDateTime.parse("2026-10-15T09:00"), "2026-10-16"))
        assertEquals("2026-10-15", Logic.toggleHabit(d, "h", LocalDateTime.parse("2026-10-15T09:00")).currentDay)
        // Marking before the habit existed moves its start back so the streak can count it.
        d = Logic.toggleHabit(d, "h", LocalDateTime.parse("2026-10-15T09:00"), "2026-09-30")
        assertEquals("2026-09-30", d.habits.first().created)
        assertEquals(d, Logic.setHabitDone(d, "h", "2026-09-30", true))
        assertTrue(!Logic.isDone(Logic.setHabitDone(d, "h", "2026-09-30", false), "2026-09-30", habit))
    }

    @Test fun pastDayTasksAndNoteSummaryUseThatDay() {
        var d = migrated().copy(currentDay = "2026-10-22") // Thursday
        d = Logic.toggleTask(d, "t3", LocalDateTime.parse("2026-10-22T10:00"), "2026-10-21") // Wednesday's nature time
        assertEquals("", d.days["2026-10-21"]?.tasks?.get("t3"))
        val summary = com.grove.app.obsidian.ObsidianSync.summary(d, "2026-10-21")
        assertTrue(summary, summary.contains("- [x] Nature time (15+ min)\n") || summary.endsWith("- [x] Nature time (15+ min)"))
        assertEquals(true, com.grove.app.obsidian.ObsidianSync.properties(d, "2026-10-21")["fifteen in nature"])
        assertEquals(false, com.grove.app.obsidian.ObsidianSync.properties(d)["fifteen in nature"])
    }

    // ----- evening check-in and quiet hours -----

    @Test fun quietHoursWrapPastMidnight() {
        val s = com.grove.app.data.Settings()
        val quiet = listOf("20:59", "21:00", "23:30", "00:30", "07:59", "08:00").map { Logic.inQuietHours(s, java.time.LocalTime.parse(it)) }
        assertEquals(listOf(false, true, true, true, true, false), quiet)
        assertTrue(!Logic.inQuietHours(s.copy(quietStart = "08:00"), java.time.LocalTime.parse("23:00"))) // start == end: off
        assertTrue(Logic.inQuietHours(s.copy(quietStart = "13:00", quietEnd = "14:00"), java.time.LocalTime.parse("13:30")))
    }

    @Test fun eveningCheckInListsWhatsOpenButNotMorningPractice() {
        var d = migrated() // Monday Oct 19: Laundry (t1) is due
        assertEquals(listOf("15-min house reset", "Laundry", "Before-bed practice (when you go to bed)"), Logic.checkInItems(d).map { it.label })
        d = Logic.toggleHabit(d, "h3", LocalDateTime.parse("2026-10-19T15:00"))
        d = Logic.toggleTask(d, "t1", LocalDateTime.parse("2026-10-19T16:00"))
        assertEquals(listOf("h2"), Logic.checkInItems(d).map { it.id })
        d = Logic.toggleHabit(d, "h2", LocalDateTime.parse("2026-10-19T22:00"))
        assertEquals(emptyList<Logic.OpenItem>(), Logic.checkInItems(d))
    }

    // ----- floating to-dos -----

    @Test fun todosStayUntilCheckedOffThenLeaveTheNextDay() {
        var d = com.grove.app.data.Todos.add(migrated(), "Renew registration", due = "2026-11-30")
        d = com.grove.app.data.Todos.add(d, "Call the vet", high = true)
        assertEquals(listOf("Call the vet", "Renew registration"), com.grove.app.data.Todos.shown(d).map { it.name })
        val vet = d.todos.first { it.name == "Call the vet" }.id
        d = com.grove.app.data.Todos.toggle(d, vet)
        assertEquals(listOf("Renew registration", "Call the vet"), com.grove.app.data.Todos.shown(d).map { it.name }) // crossed out, last
        d = d.copy(currentDay = "2026-10-20")
        assertEquals(listOf("Renew registration"), com.grove.app.data.Todos.shown(d).map { it.name })
        assertTrue(Logic.checkInItems(d).none { it.label == "Renew registration" }) // not in the 9 PM check-in
    }

    @Test fun todoNoteStaysInStepBothWays() {
        val note = "# To-do\n- [ ] Fix the gate\n- [x] Old thing ✅ 2026-10-01\n- [ ] Buy seeds 📅 2026-11-01\n\nNotes below\n"
        var d = com.grove.app.data.Todos.add(migrated(), "Call the vet")
        var (d1, text) = com.grove.app.data.Todos.syncNote(d, note)
        // Note items join Grove (not the ticked old one); Grove's item joins the note after the last checklist line.
        assertEquals(listOf("Call the vet", "Fix the gate", "Buy seeds"), d1.todos.map { it.name })
        assertEquals("2026-11-01", d1.todos.first { it.name == "Buy seeds" }.due)
        assertEquals("# To-do\n- [ ] Fix the gate\n- [x] Old thing ✅ 2026-10-01\n- [ ] Buy seeds 📅 2026-11-01\n- [ ] Call the vet\n\nNotes below\n", text)
        assertEquals(d1 to text, com.grove.app.data.Todos.syncNote(d1, text).let { (a, b) -> a to b }) // stable
        // Checked off in Grove -> ticked in the note.
        d = com.grove.app.data.Todos.toggle(d1, d1.todos.first { it.name == "Fix the gate" }.id)
        val (d2, text2) = com.grove.app.data.Todos.syncNote(d, text)
        assertTrue(text2, text2.contains("- [x] Fix the gate ✅ 2026-10-19\n"))
        // Ticked in Obsidian -> done in Grove; removed in Obsidian -> gone from Grove.
        val edited = text2.replace("- [ ] Buy seeds", "- [x] Buy seeds").replace("- [ ] Call the vet\n", "")
        val (d3, _) = com.grove.app.data.Todos.syncNote(d2, edited)
        assertEquals("2026-10-19", d3.todos.first { it.name == "Buy seeds" }.done)
        assertTrue(d3.todos.none { it.name == "Call the vet" })
        // Deleted in Grove -> removed from the note.
        val seeds = d3.todos.first { it.name == "Buy seeds" }
        val reopened = com.grove.app.data.Todos.toggle(d3, seeds.id)
        val (d4, text4) = com.grove.app.data.Todos.syncNote(reopened, edited)
        assertTrue(text4, text4.contains("- [ ] Buy seeds 📅 2026-11-01\n"))
        val (d5, text5) = com.grove.app.data.Todos.syncNote(com.grove.app.data.Todos.delete(d4, seeds.id), text4)
        assertTrue(text5, !text5.contains("Buy seeds"))
        assertTrue(d5.todos.none { it.name == "Buy seeds" })
        text = text5
    }
}
