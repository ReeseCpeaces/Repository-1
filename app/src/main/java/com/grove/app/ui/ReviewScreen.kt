package com.grove.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.AppData
import com.grove.app.data.Logic
import com.grove.app.data.Review
import com.grove.app.data.Store
import com.grove.app.obsidian.ReviewNote

/** The weekly review: this week's counts for every weekly goal, three optional boxes, and Save to the vault. */
@Composable
fun ReviewScreen(data: AppData, back: () -> Unit) {
    val context = LocalContext.current
    val monday = Review.weekOf(Logic.groveToday(data))
    val label = Review.label(monday)
    val answers = remember(label) { mutableStateListOf("", "", "") }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        BackBar(back)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("Weekly review", "$label · week of ${monday.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${monday.dayOfMonth}" +
                if (label in data.reviewedWeeks) " · saved" else "")
            SectionLabel("This week", GroveColors.Green)
            Review.counts(data, monday).forEach { Text(it, fontSize = 14.sp) }
            Review.QUESTIONS.forEachIndexed { i, q ->
                OutlinedTextField(
                    answers[i], { answers[i] = it }, label = { Text(q) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                )
            }
            Text("All optional. Saved to ${Review.path(data.settings.weeklyReviewPath, monday)} (or added to that note as \"## Weekly review\").",
                fontSize = 12.sp, color = GroveColors.Muted)
            Button(onClick = {
                val app = context.applicationContext
                ReviewNote.saveAsync(app, data, monday, answers.toList()) { ok ->
                    if (ok) Store.update(app) { d -> if (label in d.reviewedWeeks) d else d.copy(reviewedWeeks = (d.reviewedWeeks + label).takeLast(60)) }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(app, if (ok) "Weekly review saved" else "Couldn't save: check the vault folder in Settings", Toast.LENGTH_SHORT).show()
                    }
                }
                back()
            }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            Spacer(Modifier.height(32.dp))
        }
    }
}
