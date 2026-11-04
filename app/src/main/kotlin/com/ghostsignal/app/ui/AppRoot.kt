package com.ghostsignal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostsignal.app.AppGraph
import kotlinx.coroutines.flow.StateFlow

/** Runtime permission snapshot the setup screen reports on. */
data class Permissions(
    val bluetooth: Boolean = false,
    val location: Boolean = false,
    val microphone: Boolean = false,
    val notifications: Boolean = false,
) {
  /** Everything a run cannot start without. */
  val essentials: Boolean
    get() = bluetooth && location && microphone
}

enum class Screen { SETUP, RUN, OPERATOR, SETTINGS, ARCHIVE }

/**
 * Top-level navigation. A running game pulls the runner back to the run
 * screen; Back from anywhere else returns to the run (or to setup when
 * nothing is running).
 */
@Composable
fun AppRoot(
    graph: AppGraph,
    permissions: StateFlow<Permissions>,
    onRequestPermissions: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onRequestGlassesMic: () -> Unit,
) {
  GhostTheme {
    val ui by graph.game.ui.collectAsStateWithLifecycle()
    var screen by remember {
      mutableStateOf(if (graph.game.running) Screen.RUN else Screen.SETUP)
    }

    LaunchedEffect(ui.running) {
      if (ui.running && screen == Screen.SETUP) screen = Screen.RUN
    }
    BackHandler(enabled = screen != Screen.SETUP && screen != Screen.RUN) {
      screen = if (ui.running) Screen.RUN else Screen.SETUP
    }

    Box(Modifier.fillMaxSize().background(Rail.TrackBed).safeDrawingPadding()) {
      when (screen) {
        Screen.SETUP -> SetupScreen(
            graph = graph,
            permissions = permissions,
            onRequestPermissions = onRequestPermissions,
            onRegister = onRegister,
            onUnregister = onUnregister,
            onRequestGlassesMic = onRequestGlassesMic,
            go = { target -> screen = target },
        )
        Screen.RUN -> RunScreen(graph, go = { target -> screen = target })
        Screen.OPERATOR -> OperatorScreen(
            graph,
            back = { screen = if (ui.running) Screen.RUN else Screen.SETUP },
        )
        Screen.SETTINGS -> SettingsScreen(graph, back = { screen = Screen.SETUP })
        Screen.ARCHIVE -> ArchiveScreen(
            graph,
            back = { screen = if (ui.running) Screen.RUN else Screen.SETUP },
        )
      }
    }
  }
}
