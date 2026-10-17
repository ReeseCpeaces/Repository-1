package com.grove.app.data

import java.time.LocalDate

/** First-run data: the habits, lists and holy days set up in TickTick. */
object Seed {
    private val ALL = List(7) { true }
    private fun days(vararg sundayFirst: Int) = List(7) { it in sundayFirst }
    private fun plus30(t: String): String {
        val (h, m) = t.split(":").map { it.toInt() }
        val x = h * 60 + m + 30
        return "%02d:%02d".format(x / 60 % 24, x % 60)
    }

    private fun task(id: String, name: String, list: String, day: Int, time: String, prop: String = "", high: Boolean = false, firstOfMonth: Boolean = false) =
        Task(id, name, list, days(day), "", time, firstOfMonth, prop, high, listOf(time, plus30(time)))

    fun data(today: LocalDate): AppData {
        val created = today.toString()
        return AppData(
            habits = listOf(
                Habit("h1", "Morning practice", "Right after waking", ALL,
                    listOf(Step("Morning prayer"), Step("Sphere of Protection", "sphere of protection"), Step("Altar moment")),
                    role = Role.MORNING, created = created),
                Habit("h2", "Before-bed practice", "Right before sleep", ALL,
                    listOf(
                        Step("Evening lection"),
                        Step("Discursive meditation", "discursive meditation"),
                        Step("Contemplative meditation", "contemplative meditation"),
                        Step("One journal line"),
                        Step("My Deepest Me prompt"),
                    ),
                    role = Role.BEDTIME, created = created),
                Habit("h3", "15-min house reset", "2–6 PM window", ALL, listOf(Step("15 minutes of tidying")),
                    reminders = listOf("14:00", "14:30"), nag = true, nagMinutes = 30, created = created),
                Habit("h4", "Write the book", "2–6 PM window", ALL, listOf(Step("Writing session in the novel vault")),
                    reminders = listOf("15:00", "15:30"), nag = true, nagMinutes = 30, created = created),
                Habit("h5", "Field guide reading (20 min)", "2–6 PM window", ALL,
                    listOf(Step("Read 20 minutes of the current field guide", "read")),
                    reminders = listOf("17:30", "18:00"), nag = true, nagMinutes = 30, created = created),
            ),
            tasks = listOf(
                task("t1", "Laundry", "Home", 1, "16:30"),
                task("t2", "Clean the bathroom", "Home", 2, "16:30"),
                task("t3", "Nature time (15+ min)", "Druid Path", 3, "16:30", prop = "fifteen in nature"),
                task("t4", "Vacuum", "Home", 4, "16:00"),
                task("t5", "Plant study hour", "Druid Path", 4, "16:30"),
                task("t6", "Take the trash out", "Home", 4, "18:00", high = true),
                task("t7", "Groceries & restock", "Home", 5, "16:30"),
                task("t8", "Plant study + nature time (1–2 hrs)", "Druid Path", 6, "16:30", prop = "fifteen in nature"),
                task("t9", "Water & check the elderberry", "Druid Path", 0, "16:00", prop = "tree"),
                task("t10", "Weekly review (10 min)", "Home", 0, "16:30"),
                task("t11", "Journal reflection: Earth, Sun & Moon Paths", "Druid Path", 0, "15:00", firstOfMonth = true),
            ),
            holyDays = listOf(
                HolyDay("samhain", "Samhain", "2026-11-01", "Observe + GCC check-in email"),
                HolyDay("winter", "Winter Solstice", "2026-12-21", "Ritual, journal, GCC email"),
                HolyDay("imbolc", "Imbolc", "2027-02-01", "Observe + GCC check-in email"),
                HolyDay("spring", "Spring Equinox", "2027-03-20", "Ritual, journal, GCC email"),
                HolyDay("beltane", "Beltane", "2027-05-01", "Observe + GCC check-in email"),
                HolyDay("summer", "Summer Solstice", "2027-06-21", "Ritual, journal, GCC email"),
            ),
            currentDay = created,
        )
    }
}
