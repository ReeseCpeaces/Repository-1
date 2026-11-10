package com.grove.app.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.grove.app.data.AppData
import com.grove.app.data.Body
import com.grove.app.data.Logic
import com.grove.app.data.Store
import com.grove.app.data.WeighIn
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Reads weigh-ins (read-only) from Health Connect, where the Withings app shares the Body+ scale's readings,
 * and writes each day's first weigh-in into that day's note (frontmatter) and one line per weigh-in in
 * Health/Weight log.md. Same pattern as [SleepImport]. Grove never writes health data and sends no notifications.
 */
object BodyImport {
    private const val TAG = "BodyImport"
    /** Weight is required; body fat, lean and bone mass are read when the Withings app shares them. */
    val WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)
    val PERMISSIONS = setOf(
        WEIGHT,
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
    )
    /** Lets the first import reach back further than 30 days (newer Health Connect only; ignored elsewhere). */
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"
    @Volatile private var lastRun = 0L

    fun granted(context: Context): Boolean = SleepImport.available(context) && runCatching {
        runBlocking { WEIGHT in HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions() }
    }.getOrDefault(false)

    /** Import if it's turned on (at most every 20 minutes unless [force]). Call off the main thread. Returns weigh-ins written. */
    fun importIfOn(context: Context, force: Boolean = false): Int {
        if (!Store.get(context).settings.weightImport || !SleepImport.available(context)) return 0
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < 20 * 60_000) return 0
        lastRun = now
        return runCatching { runBlocking { import(context) } }.onFailure { Log.w(TAG, "Weight import failed", it) }.getOrDefault(0)
    }

    /** Every record of a type since [from], all pages. */
    private suspend fun <T : Record> readAll(client: HealthConnectClient, type: KClass<T>, from: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.after(from), pageSize = 5000, pageToken = token))
            out += page.records
            token = page.pageToken
        } while (token != null)
        return out
    }

    private suspend fun import(context: Context): Int {
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (WEIGHT !in granted) return 0
        val zone = ZoneId.systemDefault()
        val data = Store.get(context)
        // Re-read from the start of the day before the last weigh-in (a day's lines are rewritten as a set).
        // First time: everything Health Connect will give (Withings may have backfilled months).
        val last = data.lastWeightImportEnd.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val from = last?.atZone(zone)?.toLocalDate()?.minusDays(1)?.atStartOfDay(zone)?.toInstant() ?: Instant.parse("2000-01-01T00:00:00Z")
        fun local(i: Instant) = LocalDateTime.ofInstant(i, zone)
        val weights = readAll(client, WeightRecord::class, from)
        if (weights.isEmpty()) return 0
        suspend fun <T : Record> optional(type: KClass<T>, read: (T) -> Body.Reading): List<Body.Reading> =
            if (HealthPermission.getReadPermission(type) in granted) runCatching { readAll(client, type, from).map(read) }.getOrDefault(emptyList()) else emptyList()
        val fat = optional(BodyFatRecord::class) { Body.Reading(local(it.time), it.percentage.value) }
        val lean = optional(LeanBodyMassRecord::class) { Body.Reading(local(it.time), it.mass.inPounds) }
        val bone = optional(BoneMassRecord::class) { Body.Reading(local(it.time), it.mass.inPounds) }
        val all = Body.weighIns(weights.map { Body.Reading(local(it.time), it.weight.inPounds) }, fat, lean, bone)
        val firstDay = local(from).toLocalDate().plusDays(1).toString()
        val days = Body.byDay(all.filter { last == null || it.day >= firstDay })
        val changed = days.filter { (day, list) -> data.weighIns.filter { it.day == day }.sortedBy { it.time } != list }
        val newest = weights.maxOf { it.time }
        Store.update(context) { d ->
            val keep = (d.weighIns.filter { it.day !in days.keys } + days.values.flatten()).sortedBy { it.time }.takeLast(5000)
            d.copy(weighIns = keep, lastWeightImportEnd = maxOf(newest, last ?: newest).toString())
        }
        if (changed.isNotEmpty()) write(context, Store.get(context), changed)
        return changed.values.sumOf { it.size }
    }

    /** The day's values in each existing daily note (today's is created if needed), and the log lines. */
    private fun write(context: Context, data: AppData, days: Map<String, List<WeighIn>>) {
        if (data.settings.vaultUri.isEmpty()) return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return
        val resolver = context.contentResolver
        fun read(f: DocumentFile) = runCatching { resolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        fun save(f: DocumentFile, text: String) = resolver.openOutputStream(f.uri, "wt")?.use { it.write(text.encodeToByteArray()) }
        for ((day, list) in days) {
            val d = Logic.date(day)
            val rel = NoteText.path(data.settings.dailyNotePath, d)
            // Past days: only notes that exist (old history isn't turned into hundreds of new notes).
            val note = if (day >= data.currentDay) ObsidianSync.findOrCreate(context, root, rel, data, d) else ObsidianSync.find(root, rel)
            val before = note?.let { read(it) } ?: continue
            val after = NoteText.setFrontmatter(before, Body.frontmatter(list))
            if (after != before) save(note, after)
        }
        val path = data.settings.weightLogPath.trim('/').ifEmpty { "Health/Weight log.md" }
        var log = ObsidianSync.find(root, path)
        if (log == null) {
            val parts = path.split('/')
            var dir = root
            for (p in parts.dropLast(1)) dir = dir.findFile(p) ?: dir.createDirectory(p) ?: return
            log = dir.createFile("application/octet-stream", parts.last()) ?: return
            save(log, "")
        }
        val before = read(log) ?: return
        val after = Body.applyLog(before, days)
        if (after != before) save(log, after)
    }
}
