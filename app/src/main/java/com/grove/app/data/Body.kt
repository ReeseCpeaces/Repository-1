package com.grove.app.data

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One weigh-in (Withings Body+ via Health Connect), in pounds. Body numbers are null when the scale didn't send them. */
@Serializable
data class WeighIn(
    /** Local time, "yyyy-MM-ddTHH:mm". */
    val time: String,
    val weightLb: Double,
    val fatPct: Double? = null,
    val leanLb: Double? = null,
    val boneLb: Double? = null,
) {
    val day: String get() = time.take(10)
    val fatLb: Double? get() = fatPct?.let { weightLb * it / 100.0 }
}

/**
 * Weight and body composition from Health Connect, turned into weigh-ins, daily-note values and log lines.
 * Pure, so it can be unit tested; the Health Connect reading is in obsidian/BodyImport.kt.
 */
object Body {
    const val KG_TO_LB = 2.20462262185

    /** Body-composition readings this close to a weight reading belong to the same weigh-in. */
    const val MATCH_MIN = 5L

    data class Reading(val time: LocalDateTime, val value: Double)

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun one(x: Double): String = "%.1f".format(Locale.US, x)

    private fun nearest(readings: List<Reading>, t: LocalDateTime): Double? =
        readings.filter { Duration.between(it.time, t).abs().toMinutes() <= MATCH_MIN }
            .minByOrNull { Duration.between(it.time, t).abs() }?.value

    /** Each weight reading is one weigh-in; fat %, lean and bone readings from the same moment are joined to it. */
    fun weighIns(weightsLb: List<Reading>, fatPct: List<Reading>, leanLb: List<Reading>, boneLb: List<Reading>): List<WeighIn> =
        weightsLb.sortedBy { it.time }.map { w ->
            WeighIn(STAMP.format(w.time), w.value, nearest(fatPct, w.time), nearest(leanLb, w.time), nearest(boneLb, w.time))
        }

    fun byDay(all: List<WeighIn>): Map<String, List<WeighIn>> = all.groupBy { it.day }.mapValues { (_, l) -> l.sortedBy { it.time } }

    /**
     * The daily note's frontmatter: the day's first weigh-in (mornings are most consistent) and how many there were.
     * Plain numbers; keys the scale didn't send are left out.
     */
    fun frontmatter(day: List<WeighIn>): Map<String, String?> {
        val w = day.minByOrNull { it.time } ?: return emptyMap()
        val out = linkedMapOf<String, String?>("weight_lb" to one(w.weightLb))
        w.fatPct?.let { out["body_fat_pct"] = one(it) }
        w.fatLb?.let { out["fat_lb"] = one(it) }
        w.leanLb?.let { out["lean_lb"] = one(it) }
        w.boneLb?.let { out["bone_lb"] = one(it) }
        out["weight_time"] = w.time
        out["weight_count"] = day.size.toString()
        return out
    }

    /** "- 2026-11-09 21:49 — 248.9 lb · fat 89.3 lb (35.9%) · lean 151.7 lb" */
    fun logLine(w: WeighIn): String {
        val parts = mutableListOf("${one(w.weightLb)} lb")
        if (w.fatPct != null) parts += "fat ${one(w.fatLb!!)} lb (${one(w.fatPct)}%)"
        w.leanLb?.let { parts += "lean ${one(it)} lb" }
        w.boneLb?.let { parts += "bone ${one(it)} lb" }
        return "- ${w.day} ${w.time.takeLast(5)} — " + parts.joinToString(" · ")
    }

    fun applyLog(text: String, days: Map<String, List<WeighIn>>): String = DayLog.apply(
        text, "# Weight log\n\nFrom the Withings Body+ via Health Connect, written by Grove. One line per weigh-in, in pounds.\n",
        days.mapValues { (_, l) -> l.sortedBy { it.time }.map { logLine(it) } },
    )

    /** "248.9 lb (−6.4 since last)" for a weigh-in on Grove's day (or the calendar day after it), else null. */
    fun summary(data: AppData): String? {
        val all = data.weighIns.sortedBy { it.time }
        val last = all.lastOrNull()?.takeIf { it.day >= data.currentDay } ?: return null
        val prev = all.lastOrNull { it.day < last.day }
        val change = prev?.let { last.weightLb - it.weightLb }?.let { d ->
            " (" + (if (d < 0) "−" else "+") + one(kotlin.math.abs(d)) + " since last)"
        }.orEmpty()
        return "${one(last.weightLb)} lb$change"
    }
}
