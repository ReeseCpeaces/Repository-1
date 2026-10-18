package com.grove.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.data.Store
import com.grove.app.obsidian.ObsidianSync

@SuppressLint("BatteryLife")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(data: AppData, back: () -> Unit) {
    val context = LocalContext.current
    val s = data.settings
    var notePath by remember { mutableStateOf(s.dailyNotePath) }
    var templatePath by remember { mutableStateOf(s.templatePath) }
    fun saveSettings(f: (com.grove.app.data.Settings) -> com.grove.app.data.Settings) = Store.update(context) { it.copy(settings = f(it.settings)) }

    val pickVault = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            saveSettings { it.copy(vaultUri = uri.toString()) }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        BackBar(back)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("Settings")

            SectionLabel("Obsidian", GroveColors.Green)
            Text(if (s.vaultUri.isEmpty()) "No vault folder chosen yet." else "Vault: " + Uri.parse(s.vaultUri).lastPathSegment, fontSize = 13.sp, color = GroveColors.Muted)
            OutlinedButton(onClick = { pickVault.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.vaultUri.isEmpty()) "Choose vault folder" else "Change vault folder")
            }
            Text("Pick the vault's top folder (the one Syncthing keeps in sync).", fontSize = 12.sp, color = GroveColors.Muted)
            Text("Grove keeps its setup in Grove/grove.json in the vault. Edits there (by hand or by Claude on your computer) are applied when you open the app; Grove/README.md explains the format.",
                fontSize = 12.sp, color = GroveColors.Muted)
            OutlinedTextField(notePath, { notePath = it; saveSettings { st -> st.copy(dailyNotePath = it) } },
                label = { Text("Daily note path") }, supportingText = { Text("Tokens: {yyyy} {MM} {MMMM} {dd} {yyyy-MM-dd}") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(templatePath, { templatePath = it; saveSettings { st -> st.copy(templatePath = it) } },
                label = { Text("Daily note template path") }, supportingText = { Text("e.g. Templates/Template, Daily Notes.md. Used when today's note doesn't exist yet.") },
                modifier = Modifier.fillMaxWidth())

            SectionLabel("Today's note", GroveColors.Green)
            SwitchRow("Lifestyle changes today", "Ticks \"lifestyle changes\" in today's note", data.today.lifestyle) { on ->
                Store.update(context) { Logic.withDay(it, it.currentDay) { r -> r.copy(lifestyle = on) } }
            }
            if (s.vaultUri.isNotEmpty()) {
                OutlinedButton(onClick = { ObsidianSync.syncAsync(context, null, Store.get(context)) }, modifier = Modifier.fillMaxWidth()) { Text("Write today's summary now") }
                OutlinedButton(onClick = { ObsidianSync.openInObsidian(context, data) }, modifier = Modifier.fillMaxWidth()) { Text("Open today's note in Obsidian") }
            }

            SectionLabel("Add a line?", GroveColors.Green)
            Text("After checking these off, Grove offers a quick line for today's note (under Daily Log). Turn off any you don't want asked about.",
                fontSize = 12.sp, color = GroveColors.Muted)
            Logic.lineItems(data).forEach { item ->
                SwitchRow(item.label, "Writes \"- ${item.prefix}: …\"", item.key !in s.linePromptOff) { on ->
                    saveSettings { st -> st.copy(linePromptOff = if (on) st.linePromptOff - item.key else (st.linePromptOff + item.key).distinct()) }
                }
            }
            var booksFolder by remember { mutableStateOf(s.booksFolder) }
            OutlinedTextField(booksFolder, { booksFolder = it; saveSettings { st -> st.copy(booksFolder = it) } },
                label = { Text("Book notes folder") }, supportingText = { Text("Notes here with subType: Druid book appear on the Books screen and get your progress.") },
                modifier = Modifier.fillMaxWidth())

            SectionLabel("Your day", GroveColors.Green)
            Text("A new day starts when you check off Morning practice, or at this hour if you haven't:", fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 12, 14, 16).forEach { h ->
                    FilterChip(selected = s.cutoffHour == h, onClick = { saveSettings { it.copy(cutoffHour = h) } }, label = { Text(Logic.fmtTime("%02d:00".format(h))) })
                }
            }
            Text("Default snooze (the one-tap button on reminders):", fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 10, 15, 30, 60).forEach { m ->
                    FilterChip(selected = s.defaultSnoozeMinutes == m, onClick = { saveSettings { it.copy(defaultSnoozeMinutes = m) } }, label = { Text("$m min") })
                }
            }

            SectionLabel("Keep reminders reliable", GroveColors.Amber)
            Text("Samsung puts apps to sleep to save battery, which can delay reminders. Allow Grove to run unrestricted, and add it to Never sleeping apps (Settings › Battery › Background usage limits).",
                fontSize = 13.sp, color = GroveColors.Muted)
            OutlinedButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
            }, modifier = Modifier.fillMaxWidth()) { Text("Allow unrestricted battery") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
            }, modifier = Modifier.fillMaxWidth()) { Text("Open Grove's app settings") }
            if (Build.VERSION.SDK_INT >= 31) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName)))
                }, modifier = Modifier.fillMaxWidth()) { Text("Allow exact-time alarms") }
            }
            val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
            Text("Grove $version", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GroveColors.Muted)
            Text("Art from SVG Repo (svgrepo.com).", fontSize = 12.sp, color = GroveColors.Muted)
            Spacer(Modifier.height(32.dp))
        }
    }
}
