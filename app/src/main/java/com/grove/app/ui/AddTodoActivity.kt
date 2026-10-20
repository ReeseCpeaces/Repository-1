package com.grove.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.Store
import com.grove.app.data.Todos

/**
 * "Share → Grove to-do" from any app, and the list widget's "+": a small box to add to-dos without
 * opening Grove. Several lines can become one to-do each.
 */
class AddTodoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = if (intent?.action == Intent.ACTION_SEND) sharedText(intent) else ""
        setContent {
            GroveTheme {
                var text by remember { mutableStateOf(shared) }
                val lines = text.lines().map { it.trim().removePrefix("- ").removePrefix("• ").trim() }.filter { it.isNotEmpty() }
                var split by remember { mutableStateOf(false) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Add to Grove to-dos") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                text, { text = it }, label = { Text("To-do") },
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).focusRequester(focus),
                            )
                            if (lines.size > 1) {
                                SwitchRow("One to-do per line", "Adds ${lines.size} to-dos", split) { split = it }
                            }
                            Text("Stays on Today and the list widget until you check it off.", fontSize = 12.sp, color = GroveColors.Muted)
                        }
                    },
                    confirmButton = {
                        TextButton(enabled = lines.isNotEmpty(), onClick = {
                            val names = if (split) lines else listOf(lines.joinToString(" "))
                            Store.update(applicationContext) { d -> names.fold(d) { acc, n -> Todos.add(acc, n.take(300)) } }
                            Toast.makeText(applicationContext, if (names.size == 1) "Added to Grove to-dos" else "Added ${names.size} to-dos", Toast.LENGTH_SHORT).show()
                            finish()
                        }) { Text("Add") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Cancel") } },
                )
            }
        }
    }

    /** Shared text, with the subject in front when there is one (e.g. a page title before its link). */
    private fun sharedText(intent: Intent): String {
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        return when {
            subject.isEmpty() -> body
            body.isEmpty() || body.contains(subject) -> body.ifEmpty { subject }
            else -> "$subject $body"
        }
    }
}
