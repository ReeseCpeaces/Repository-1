package com.grove.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What Health Connect shows when asked why Grove wants sleep data (required for its permission screen). */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GroveTheme {
                Surface(Modifier.fillMaxSize(), color = GroveColors.Bg, contentColor = GroveColors.Text) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Title("Grove and your health data")
                        Text("Grove only reads your sleep sessions (with their stages) and your weigh-ins (weight, body fat, lean and bone " +
                            "mass) from Health Connect. It never writes health data and never sends it anywhere except your own Obsidian " +
                            "vault: the numbers go into that day's daily note and Health/Sleep log.md or Health/Weight log.md. You can turn " +
                            "each off in Grove's Settings or in Health Connect at any time.",
                            fontSize = 15.sp)
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
