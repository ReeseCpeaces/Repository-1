package com.grove.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grove.app.data.Effects
import com.grove.app.data.Store

/** Where the user is in the app. A plain back stack keeps navigation dependency-free. */
sealed interface Screen {
    data object Today : Screen
    data object Forest : Screen
    data object Settings : Screen
    data class HabitDetail(val id: String) : Screen
    data class EditHabit(val id: String?) : Screen
    data class EditTask(val id: String?) : Screen
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_HABIT = "habit"
        const val EXTRA_SCREEN = "screen"
    }

    private val stack = mutableStateListOf<Screen>(Screen.Today)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Effects.refreshAll(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        setContent {
            GroveTheme {
                Box(Modifier.fillMaxSize().background(GroveColors.Bg).safeDrawingPadding()) { App() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        Thread { Effects.refreshAll(applicationContext) }.start()
    }

    private fun handle(intent: Intent?) {
        val habit = intent?.getStringExtra(EXTRA_HABIT)
        if (habit != null) {
            stack.clear(); stack += Screen.Today; stack += Screen.HabitDetail(habit)
        }
    }

    private fun go(s: Screen) { stack += s }
    private fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
    private fun tab(s: Screen) { stack.clear(); stack += s }

    @Composable
    private fun App() {
        val data by Store.state(applicationContext).collectAsStateWithLifecycle()
        BackHandler(enabled = stack.size > 1) { back() }
        when (val s = stack.last()) {
            Screen.Today -> TodayScreen(data, ::go, ::tab)
            Screen.Forest -> ForestScreen(data, ::go, ::tab)
            Screen.Settings -> SettingsScreen(data, ::back)
            is Screen.HabitDetail -> HabitScreen(data, s.id, ::go, ::back)
            is Screen.EditHabit -> EditHabitScreen(data, s.id, ::back)
            is Screen.EditTask -> EditTaskScreen(data, s.id, ::back)
        }
    }
}
