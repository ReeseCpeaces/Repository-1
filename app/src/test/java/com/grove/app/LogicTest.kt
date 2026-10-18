package com.grove.app

import com.grove.app.data.AppData
import com.grove.app.data.DayRecord
import com.grove.app.data.Habit
import com.grove.app.data.Logic
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
}
