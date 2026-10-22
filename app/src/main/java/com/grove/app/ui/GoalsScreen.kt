package com.grove.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grove.app.data.AppData
import com.grove.app.data.Book
import com.grove.app.data.Goal
import com.grove.app.data.GoalKind
import com.grove.app.data.Logic
import com.grove.app.data.Prompts
import com.grove.app.data.Store
import com.grove.app.obsidian.ObsidianSync

/** "Mushrooms of the Upper Midwest (Marrone & Yerich)" -> "Mushrooms of the Upper Midwest". */
fun shortTitle(b: Book) = b.title.substringBefore(" (").substringBefore(":")

/** The weekly goals on Today: tap to log, no streaks. */
@Composable
fun GoalCards(data: AppData, go: (Screen) -> Unit) {
    var logging by remember { mutableStateOf<Goal?>(null) }
    for (g in data.goals.filter { it.kind != GoalKind.PROMPT && it.kind != GoalKind.MINUTES }) {
        val done = Logic.progress(data, g)
        val target = Logic.target(data, g)
        Card(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (g.kind == GoalKind.PAGES) {
                    val book = Logic.currentBook(data)
                    Column(Modifier.weight(1f).clickable { go(Screen.Books) }.padding(vertical = 10.dp)) {
                        Text(g.name, fontSize = 16.sp)
                        Text("$done / $target pages this week" + (book?.let { " · ${shortTitle(it)} p. ${Logic.bookPage(data, it)} of ${it.pages}" } ?: ""),
                            fontSize = 12.sp, color = GroveColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { if (target == 0) 1f else (done.toFloat() / target).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = GroveColors.Green, trackColor = GroveColors.Line,
                        )
                    }
                    FilledTonalButton(onClick = { logging = g }, modifier = Modifier.padding(start = 10.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text(" Pages")
                    }
                } else {
                    val today = Logic.sessionLogged(data, data.currentDay, g)
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(g.name, fontSize = 16.sp)
                        Text(Logic.sessionLabel(done, target) + if (today) " · logged today" else "", fontSize = 12.sp,
                            color = if (done > target) GroveColors.Amber else GroveColors.Muted)
                    }
                    val context = LocalContext.current
                    if (today) {
                        OutlinedButton(onClick = { Store.update(context) { Logic.toggleSession(it, g.id) } }) {
                            Icon(Icons.Filled.Check, null, Modifier.size(16.dp)); Text(" Undo")
                        }
                    } else {
                        FilledTonalButton(onClick = { Store.update(context) { Logic.toggleSession(it, g.id) } }) {
                            Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text(" Session")
                        }
                    }
                }
            }
        }
    }
    for (g in data.goals.filter { it.kind == GoalKind.MINUTES }) ExploreCard(data, g, go)
    for (g in data.goals.filter { it.kind == GoalKind.PROMPT }) PromptCard(data, g)
    logging?.let { LogPagesDialog(data) { logging = null } }
}

/** A weekly prompt (My Deepest Me): mull it over all week, add thoughts any time, finish by Friday. */
@Composable
private fun PromptCard(data: AppData, g: Goal) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf("") }
    val today = Logic.groveToday(data)
    val all = Prompts.finishedAll(g)
    val done = all || Prompts.doneThisWeek(g, today)
    val n = g.current
    Card(Modifier.padding(horizontal = 16.dp, vertical = 3.dp)) {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(g.name, fontSize = 16.sp)
            Text(Prompts.status(g, today), fontSize = 12.sp, color = if (done) GroveColors.Green else GroveColors.Muted)
            if (!all) {
                Text(if (g.title.isBlank()) "+ Add a short title in your own words" else "“${g.title}”",
                    fontSize = 13.sp, color = if (g.title.isBlank()) GroveColors.Green else GroveColors.Text,
                    modifier = Modifier.clickable { dialog = "title" }.padding(vertical = 4.dp))
                val spare = Prompts.spareWeeks(g, today)
                if (g.deadline.isNotEmpty()) Text("$spare spare week${if (spare == 1) "" else "s"} before ${g.deadline}", fontSize = 11.sp,
                    color = if (spare < 0) GroveColors.Warn else GroveColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { dialog = "thought" }) { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Text(" Thought") }
                    if (!done) FilledTonalButton(onClick = { dialog = "done" }) { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)); Text(" Done") }
                    else OutlinedButton(onClick = { Store.update(context) { Prompts.undoDone(it, g.id) } }) { Text("Undo") }
                }
            }
        }
    }
    when (dialog) {
        "thought" -> TextEntryDialog("A thought on Prompt $n", "Thought", "Saved to Prompt $n in Obsidian, under Thoughts.", "Save",
            onSave = { t -> Store.update(context) { Prompts.addThought(it, g.id, t) } }, onClose = { dialog = "" })
        "done" -> TextEntryDialog("Done with Prompt $n?", "Final answer (optional)",
            "Saved under Answer in the prompt's note. The daily note only gets “✓ ${g.name}: Prompt $n”.", "Done", allowEmpty = true,
            onSave = { t -> Store.update(context) { Prompts.markDone(it, g.id, t) } }, onClose = { dialog = "" })
        "title" -> TextEntryDialog("Prompt $n title", "Short title, your own words", "e.g. who I was as a kid. Never the book's text.", "Save",
            initial = g.title, allowEmpty = true, singleLine = true,
            onSave = { t -> Store.update(context) { Prompts.setTitle(it, g.id, t) } }, onClose = { dialog = "" })
    }
}

/** Pick a book (defaults to the last one used) and add pages, even 2. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogPagesDialog(data: AppData, initialBook: Book? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val open = data.books.filter { !Logic.bookFinished(data, it) }
    var bookId by remember { mutableStateOf((initialBook ?: Logic.currentBook(data))?.id.orEmpty()) }
    var pages by remember { mutableStateOf("") }
    var logged by remember { mutableStateOf<Triple<Book, Int, Int>?>(null) }
    val n = pages.toIntOrNull() ?: 0
    logged?.let { (b, from, to) -> AfterPagesDialog(data, b, from, to, onClose); return }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Log pages") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Book", fontWeight = FontWeight.SemiBold)
                (open.ifEmpty { data.books }).forEach { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (b.id == bookId) GroveColors.Line else GroveColors.Surface)
                            .clickable { bookId = b.id }.heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(shortTitle(b), Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("p. ${Logic.bookPage(data, b)}/${b.pages}", fontSize = 12.sp, color = GroveColors.Muted)
                    }
                }
                Text("Pages read", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2, 5, 10, 20).forEach { q -> FilterChip(selected = n == q, onClick = { pages = q.toString() }, label = { Text("+$q") }) }
                }
                OutlinedTextField(pages, { v -> pages = v.filter { it.isDigit() }.take(4) }, label = { Text("Pages") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = n > 0 && bookId.isNotEmpty(), onClick = {
                val b = data.books.first { it.id == bookId }
                val from = Logic.bookPage(data, b)
                Store.update(context) { Logic.logPages(it, bookId, n) }
                // Then offer a line for the note (and the book's own note), unless both are off.
                if (Logic.wantsLine(data, Logic.READING) || b.notePath.isNotEmpty()) logged = Triple(b, from + 1, minOf(b.pages, from + n)) else onClose()
            }) { Text("Add $n pages") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/** Right after logging pages: "Add a line?" for the daily note, and a button to open the book's note. */
@Composable
private fun AfterPagesDialog(data: AppData, b: Book, from: Int, to: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val openNote: (@Composable () -> Unit)? = if (b.notePath.isEmpty()) null else {
        { TextButton(onClick = { ObsidianSync.openNote(context, data, b.notePath); onClose() }) { Text("Add a note? Open ${shortTitle(b)}") } }
    }
    if (Logic.wantsLine(data, Logic.READING)) {
        LinePromptDialog(
            about = "${shortTitle(b)}, p. $from–$to",
            prefix = "Reading",
            onSave = { t -> Store.update(context) { Logic.addLine(it, Logic.READING, "Reading (${shortTitle(b)}, p. $from–$to): $t") }; onClose() },
            onSkip = { Store.update(context) { Logic.skipLine(it, Logic.READING) }; onClose() },
            extra = openNote,
        )
    } else {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Logged p. $from–$to") },
            text = { openNote?.invoke() },
            confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        )
    }
}

/** All the books with progress; finished ones get a check and move to the bottom. */
@Composable
fun BooksScreen(data: AppData, back: () -> Unit) {
    val context = LocalContext.current
    val goal = data.goals.firstOrNull { it.kind == GoalKind.PAGES }
    var editing by remember { mutableStateOf<Book?>(null) }
    var adding by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf<Book?>(null) }
    val total = data.books.sumOf { it.pages }
    val read = data.books.sumOf { Logic.bookPage(data, it) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        BackBar(back) { TextButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, null); Text(" Add book") } }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("Books", "$read / $total pages read" + (goal?.let { " · this week ${Logic.progress(data, it)} / ${Logic.target(data, it)}" } ?: ""))
            if (goal != null && goal.deadline.isNotEmpty()) {
                Text("Weekly target = pages left ÷ weeks until ${goal.deadline}, set each Monday. Reading extra makes later weeks lighter.",
                    fontSize = 12.sp, color = GroveColors.Muted)
            }
            val (finished, open) = data.books.partition { Logic.bookFinished(data, it) }
            (open + finished).forEach { b ->
                val page = Logic.bookPage(data, b)
                val done = Logic.bookFinished(data, b)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GroveColors.Surface)
                        .clickable { editing = b }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (done) Icon(Icons.Filled.Check, "Finished", tint = GroveColors.Green, modifier = Modifier.size(20.dp).padding(end = 4.dp))
                        Text(b.title, Modifier.weight(1f), fontSize = 15.sp, color = if (done) GroveColors.Muted else GroveColors.Text)
                        if (!done) TextButton(onClick = { logging = b }) { Text("+ Pages") }
                    }
                    LinearProgressIndicator(
                        progress = { if (b.pages == 0) 0f else page.toFloat() / b.pages },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = GroveColors.Green, trackColor = GroveColors.Line,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$page / ${b.pages}", Modifier.weight(1f), fontSize = 12.sp, color = GroveColors.Muted)
                        if (b.notePath.isNotEmpty()) TextButton(onClick = { ObsidianSync.openNote(context, data, b.notePath) }) { Text("Open note", fontSize = 13.sp) }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    logging?.let { LogPagesDialog(data, it) { logging = null } }
    if (adding || editing != null) {
        BookDialog(editing, onClose = { adding = false; editing = null }) { title, pages, start ->
            val old = editing
            Store.update(context) { d ->
                if (old == null) Logic.allocateId(d, "b").let { (id, n) -> n.copy(books = n.books + Book(id, title, pages, start)) }
                else d.copy(books = d.books.map { if (it.id == old.id) it.copy(title = title, pages = pages, startPage = start) else it })
            }
            adding = false; editing = null
        }
    }
}

@Composable
private fun BookDialog(book: Book?, onClose: () -> Unit, onSave: (String, Int, Int) -> Unit) {
    var title by remember { mutableStateOf(book?.title.orEmpty()) }
    var pages by remember { mutableStateOf(book?.pages?.toString().orEmpty()) }
    var start by remember { mutableStateOf(book?.startPage?.toString() ?: "0") }
    val p = pages.toIntOrNull() ?: 0
    val s = start.toIntOrNull() ?: 0
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (book == null) "Add book" else "Edit book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pages, { v -> pages = v.filter { it.isDigit() }.take(5) }, label = { Text("Pages") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(start, { v -> start = v.filter { it.isDigit() }.take(5) }, label = { Text("Starting page") }, singleLine = true,
                    supportingText = { Text("Pages already read before you started logging") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && p > 0, onClick = { onSave(title.trim(), p, s.coerceIn(0, p)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
