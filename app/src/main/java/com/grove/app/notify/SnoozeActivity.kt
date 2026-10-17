package com.grove.app.notify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import com.grove.app.ui.GroveTheme

/** Small dialog opened from a reminder's "Snooze…" button to pick how long. */
class SnoozeActivity : ComponentActivity() {
    private val options = listOf(5 to "5 minutes", 10 to "10 minutes", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 hour", 120 to "2 hours")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(ReminderReceiver.EXTRA_KIND).orEmpty()
        val id = intent.getStringExtra(ReminderReceiver.EXTRA_ID).orEmpty()
        setContent {
            GroveTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Snooze for…") },
                    text = {
                        Column {
                            options.forEach { (minutes, label) ->
                                TextButton(onClick = {
                                    ReminderReceiver.snooze(applicationContext, kind, id, minutes)
                                    finish()
                                }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }
}
