package com.grove.app.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.data.Sleep
import com.grove.app.data.SleepNight
import com.grove.app.data.Store
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Reads sleep sessions (read-only) from Health Connect, where Samsung Health shares the Galaxy Watch's
 * sleep, and writes each wake-up day's main sleep into that day's note (frontmatter) and one line in
 * Health/Sleep log.md. Grove never writes health data, and sends no notifications about it.
 */
object SleepImport {
    private const val TAG = "SleepImport"
    val PERMISSIONS = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
    /** Lets the morning background check read too (newer Health Connect only; ignored elsewhere). */
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    @Volatile private var lastRun = 0L

    fun available(context: Context): Boolean =
        runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)

    fun granted(context: Context): Boolean = available(context) && runCatching {
        runBlocking { HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS) }
    }.getOrDefault(false)

    /** Import if it's turned on (at most every 20 minutes unless [force]). Call off the main thread. Returns nights written. */
    fun importIfOn(context: Context, force: Boolean = false): Int {
        val data = Store.get(context)
        if (!data.settings.sleepImport || !available(context)) return 0
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < 20 * 60_000) return 0
        lastRun = now
        return runCatching { runBlocking { import(context) } }.onFailure { Log.w(TAG, "Sleep import failed", it) }.getOrDefault(0)
    }

    private suspend fun import(context: Context): Int {
        val client = HealthConnectClient.getOrCreate(context)
        if (!client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) return 0
        val zone = ZoneId.systemDefault()
        val data = Store.get(context)
        // Re-read from a day before the last imported wake-up, so a night split in two (or a nap the
        // same day) is worked out again from all its pieces. First time: the last week.
        val last = data.lastSleepImportEnd.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val from = last?.atZone(zone)?.toLocalDate()?.minusDays(1)?.atStartOfDay(zone)?.toInstant()
            ?: Instant.now().minus(7, ChronoUnit.DAYS)
        val records = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.after(from))).records
        if (records.isEmpty()) return 0
        fun local(i: Instant) = LocalDateTime.ofInstant(i, zone)
        val sessions = records.map { r ->
            Sleep.Session(local(r.startTime), local(r.endTime), r.stages.map { st -> Sleep.Stage(st.stage, local(st.startTime), local(st.endTime)) })
        }
        // Only nights whose wake-up day is fully covered by this read.
        val firstDay = local(from).toLocalDate().plusDays(1).toString()
        val nights = Sleep.nights(sessions).filter { last == null || it.day >= firstDay }
        val changed = nights.filter { n -> data.sleepNights.none { it == n } }
        val newest = records.maxOf { it.endTime }
        Store.update(context) { d ->
            val keep = (d.sleepNights.filter { old -> nights.none { it.day == old.day } } + nights).sortedBy { it.day }.takeLast(60)
            d.copy(sleepNights = keep, lastSleepImportEnd = maxOf(newest, last ?: newest).toString())
        }
        if (changed.isNotEmpty()) write(context, Store.get(context), changed)
        return changed.size
    }

    /** Frontmatter in each wake-up day's note, and the log lines. A note that can't be read is never written. */
    private fun write(context: Context, data: AppData, nights: List<SleepNight>) {
        if (data.settings.vaultUri.isEmpty()) return
        val root = DocumentFile.fromTreeUri(context, Uri.parse(data.settings.vaultUri)) ?: return
        val resolver = context.contentResolver
        fun read(f: DocumentFile) = runCatching { resolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
        fun save(f: DocumentFile, text: String) = resolver.openOutputStream(f.uri, "wt")?.use { it.write(text.encodeToByteArray()) }
        for (n in nights) {
            val d = Logic.date(n.day)
            val rel = NoteText.path(data.settings.dailyNotePath, d)
            // Today's or the next note may not exist yet (woke before Morning practice): make it like Grove does.
            val note = if (n.day >= data.currentDay) ObsidianSync.findOrCreate(context, root, rel, data, d) else ObsidianSync.find(root, rel)
            val before = note?.let { read(it) } ?: continue
            val after = NoteText.setFrontmatter(before, Sleep.frontmatter(n))
            if (after != before) save(note, after)
        }
        val path = data.settings.sleepLogPath.trim('/').ifEmpty { "Health/Sleep log.md" }
        var log = ObsidianSync.find(root, path)
        if (log == null) {
            val parts = path.split('/')
            var dir = root
            for (p in parts.dropLast(1)) dir = dir.findFile(p) ?: dir.createDirectory(p) ?: return
            log = dir.createFile("application/octet-stream", parts.last()) ?: return
            save(log, "")
        }
        val before = read(log) ?: return
        val after = Sleep.applyLog(before, nights)
        if (after != before) save(log, after)
    }
}
