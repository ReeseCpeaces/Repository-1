package com.grove.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Round check for habits, square for tasks, so the two never look alike. */
@Composable
fun CheckMark(checked: Boolean, label: String, square: Boolean, onToggle: () -> Unit, size: Int = 28) {
    val color = if (square) GroveColors.Amber else GroveColors.Green
    val shape: Shape = if (square) RoundedCornerShape(7.dp) else CircleShape
    Box(
        Modifier
            .size(44.dp)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "Done" else "Not done"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size.dp).clip(shape).background(if (checked) color else Color.Transparent).border(2.dp, color, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Filled.Check, null, tint = GroveColors.Bg, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}

@Composable
fun ItemTitle(text: String, done: Boolean, modifier: Modifier = Modifier, size: Int = 16) {
    Text(
        text, modifier = modifier, fontSize = size.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (done) GroveColors.Muted else GroveColors.Text,
        textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
    )
}

@Composable
fun SectionLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.padding(top = 8.dp, bottom = 2.dp), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
fun Title(text: String, sub: String? = null) {
    Column {
        Text(text, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = GroveColors.Text)
        if (sub != null) Text(sub, fontSize = 12.sp, color = GroveColors.Muted)
    }
}

@Composable
fun BackBar(onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
        actions()
    }
}

@Composable
fun Tabs(forest: Boolean, onToday: () -> Unit, onForest: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(GroveColors.Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("Today" to !forest, "Forest" to forest).forEach { (label, on) ->
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) GroveColors.Line else Color.Transparent)
                    .clickable(role = Role.Tab) { if (label == "Today") onToday() else onForest() },
                contentAlignment = Alignment.Center,
            ) { Text(label, fontWeight = FontWeight.Bold, color = if (on) GroveColors.Text else GroveColors.Muted) }
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GroveColors.Surface).padding(horizontal = 12.dp, vertical = 6.dp)) { content() }
}
