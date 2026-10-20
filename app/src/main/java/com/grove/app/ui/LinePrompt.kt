package com.grove.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.data.Store

/**
 * "Add a line?" for the daily note. The keyboard opens right away, so its mic is one tap; tapping
 * outside or Skip is the one-tap "no", and it won't ask again for this today.
 */
@Composable
fun LinePromptDialog(
    about: String,
    prefix: String,
    onSave: (String) -> Unit,
    onSkip: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Add a line?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(about, fontSize = 13.sp, color = GroveColors.Muted)
                OutlinedTextField(
                    text, { text = it }, label = { Text("$prefix:") },
                    supportingText = { Text("Goes under Daily Log in today's note. The keyboard's mic works.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                extra?.invoke()
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}

/** Offers a line for anything checked off today (in the app, a widget or a notification) that hasn't been asked yet. */
@Composable
fun PendingLinePrompt(data: AppData) {
    val context = LocalContext.current
    val item = Logic.pendingLine(data) ?: return
    key(item.key) {
        LinePromptDialog(
            about = "${item.label} is done.",
            prefix = item.prefix,
            onSave = { t -> Store.update(context) { Logic.addLine(it, item.key, "${item.prefix}: $t") } },
            onSkip = { Store.update(context) { Logic.skipLine(it, item.key) } },
        )
    }
}

/** A small text box for a few words (the keyboard's mic works). Empty is allowed only if [allowEmpty]. */
@Composable
fun TextEntryDialog(
    title: String,
    label: String,
    hint: String,
    confirm: String,
    initial: String = "",
    allowEmpty: Boolean = false,
    singleLine: Boolean = false,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(label) }, supportingText = { Text(hint) }, singleLine = singleLine,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = allowEmpty || text.isNotBlank(), onClick = { onSave(text.trim()); onClose() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
