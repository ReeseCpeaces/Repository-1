package com.grove.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The app's single source of truth, saved as JSON in app storage. The app, widgets, notifications
 * and alarm receivers all read and write through here, then [Effects] refreshes everything else.
 */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var flow: MutableStateFlow<AppData>? = null

    private fun file(context: Context) = File(context.filesDir, "grove.json")

    fun state(context: Context): StateFlow<AppData> = synchronized(lock) {
        flow ?: MutableStateFlow(load(context)).also { flow = it }
    }

    fun get(context: Context): AppData = state(context).value

    private fun load(context: Context): AppData {
        val f = file(context)
        val data = if (f.exists()) {
            runCatching { json.decodeFromString(AppData.serializer(), f.readText()) }.getOrNull()
        } else null
        return data ?: Seed.data(LocalDate.now()).also { save(context, it) }
    }

    private fun save(context: Context, data: AppData) {
        val f = file(context)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), data))
        tmp.renameTo(f)
    }

    /** Apply a change, persist it, and refresh notifications, alarms, widgets and the daily note. */
    fun update(context: Context, change: (AppData) -> AppData) {
        val app = context.applicationContext
        val (old, new) = synchronized(lock) {
            val s = state(app) as MutableStateFlow<AppData>
            val before = s.value
            val after = change(rolled(before, LocalDateTime.now()))
            if (after != before) {
                save(app, after)
                s.value = after
            }
            before to after
        }
        if (old != new) Effects.onChange(app, old, new)
    }

    /** Roll the logical day forward if it's time. */
    fun refreshDay(context: Context) = update(context) { it }

    private fun rolled(data: AppData, now: LocalDateTime): AppData {
        val day = Logic.rolledDay(data, now)
        return if (day == data.currentDay) data else data.copy(currentDay = day)
    }
}
