package com.ghostsignal.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostsignal.app.AppGraph
import com.ghostsignal.app.game.RunUi
import com.ghostsignal.core.engine.Phase
import com.ghostsignal.core.engine.PlayerAction
import com.ghostsignal.core.engine.Zone
import com.ghostsignal.core.geo.Geo
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * The run screen: radar, objective, transmission window, and phone
 * controls that mirror the glasses (capture = scan, hold = talk). The run
 * is designed to be played glasses-only; this screen is the companion.
 */
@Composable
fun RunScreen(graph: AppGraph, go: (Screen) -> Unit) {
  val game = graph.game
  val ui by game.ui.collectAsStateWithLifecycle()
  val act: (PlayerAction) -> Unit = { action -> game.input(action) }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label("GHOST 93.3", Rail.Brass, 14, FontWeight.Bold, Modifier.weight(1f))
      Label(
          if (ui.paused) "PAUSED" else ui.phase.name,
          if (ui.paused) Rail.Brass else Rail.Dim,
          12,
      )
    }

    FragmentStrip(ui)
    Radar(ui, Modifier.fillMaxWidth().aspectRatio(1f))
    Label(ui.objective, Rail.Text, 18, FontWeight.Bold)

    if (ui.phase == Phase.HUNTING && ui.distanceM != null) {
      val directionText = ui.bearingDeg?.let { bearing ->
        ui.heading.deg?.let { heading -> "${Geo.clockPosition(Geo.deltaDeg(heading, bearing))} o'clock" }
            ?: Geo.cardinal(bearing)
      } ?: ""
      Label("${Geo.spokenDistance(ui.distanceM!!)} · $directionText", Rail.Signal, 14)
    }
    if (ui.lockAvailable) {
      Label("◉ LOCK AVAILABLE · press capture", Rail.Confirmed, 14, FontWeight.Bold)
    }
    ui.unlocked.forEach { title ->
      Label("★ UNLOCKED · $title · plays on your next run", Rail.Brass, 14, FontWeight.Bold)
    }

    TransmissionPanel(ui)

    if (ui.rhythmOpen) {
      Box(
          Modifier.fillMaxWidth()
              .height(120.dp)
              .background(Rail.Brass.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
              .border(2.dp, Rail.Brass, RoundedCornerShape(12.dp))
              .pointerInput(Unit) { detectTapGestures(onPress = { act(PlayerAction.TAP) }) },
          contentAlignment = Alignment.Center,
      ) {
        Label("TAP THE RHYTHM", Rail.Brass, 18, FontWeight.Bold)
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      GhostButton("Repeat", { act(PlayerAction.REPEAT) }, Modifier.weight(1f))
      GhostButton("Hint", { act(PlayerAction.HINT) }, Modifier.weight(1f))
      GhostButton("Status", { act(PlayerAction.STATUS) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      RailButton("SCAN", { act(PlayerAction.SCAN) }, Modifier.weight(1f).heightIn(min = 56.dp))
      RailButton(
          if (ui.listening) "LISTENING…" else "TALK",
          { act(PlayerAction.TALK) },
          Modifier.weight(1f).heightIn(min = 56.dp),
          Rail.Brass,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      GhostButton(if (ui.paused) "Resume" else "Pause", { act(PlayerAction.PAUSE_TOGGLE) }, Modifier.weight(1f))
      GhostButton("Operator", { go(Screen.OPERATOR) }, Modifier.weight(1f))
      GhostButton("End run", {
        game.stop()
        go(Screen.SETUP)
      }, Modifier.weight(1f))
    }

    Label(
        "Glasses: capture = scan · hold capture = talk · double press = repeat · swipe = hint/status · say \"pause\" any time",
        Rail.Dim,
        11,
    )
    ChannelLog(ui)
    Label(
        "${ui.audioEngine} · ${ui.soundtrack.ifBlank { "original" }} · heading ${ui.heading.source.name.lowercase()} · GPS ±${ui.fix?.accuracyM?.toInt() ?: "?"} m",
        Rail.Dim,
        11,
    )
  }
}

/** The eight track segments, filling as stems are collected. */
@Composable
private fun FragmentStrip(ui: RunUi) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
    ui.stems.forEachIndexed { index, (name, collected) ->
      val isCurrent = index == ui.signalIndex && !collected
      Box(
          Modifier.weight(1f)
              .height(34.dp)
              .background(
                  if (collected) Rail.Signal.copy(alpha = 0.85f) else Rail.Surface,
                  RoundedCornerShape(4.dp),
              )
              .border(
                  1.dp,
                  if (isCurrent) Rail.Brass else Rail.Line,
                  RoundedCornerShape(4.dp),
              ),
          contentAlignment = Alignment.Center,
      ) {
        Label(
            if (collected) name.take(4).uppercase() else "${index + 1}",
            if (collected) Rail.TrackBed else Rail.Dim,
            10,
            FontWeight.Bold,
        )
      }
    }
  }
}

/**
 * Heading-up radar: track rings around the runner, the active signal at
 * its true relative bearing on a log distance scale, brightening with
 * signal strength. A brass rim warns when no heading is known.
 */
@Composable
private fun Radar(ui: RunUi, modifier: Modifier) {
  Canvas(modifier.background(Rail.Surface, RoundedCornerShape(16.dp))) {
    val center = Offset(size.width / 2, size.height / 2)
    val radius = min(size.width, size.height) / 2 * 0.9f

    for (ring in 1..4) {
      drawCircle(Rail.Line, radius * ring / 4, center, style = Stroke(1.5f))
    }
    drawLine(Rail.Line, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.5f)
    drawLine(Rail.Line, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.5f)

    // The runner, facing up.
    drawCircle(Rail.Signal, 7f, center)
    drawLine(Rail.Signal, center, Offset(center.x, center.y - 26f), 3f)

    val headingDeg = ui.heading.deg ?: 0.0

    // North marker on the rim.
    val northAngle = Math.toRadians(Geo.deltaDeg(headingDeg, 0.0))
    drawCircle(
        Rail.Dim,
        5f,
        Offset(
            center.x + (radius * sin(northAngle)).toFloat(),
            center.y - (radius * cos(northAngle)).toFloat(),
        ),
    )

    val bearing = ui.bearingDeg
    val distance = ui.distanceM
    if (bearing != null && distance != null) {
      val relative = Math.toRadians(Geo.deltaDeg(headingDeg, bearing))
      val scaledDistance = (ln(1 + distance) / ln(1 + 600.0)).coerceIn(0.05, 1.0).toFloat() * radius
      val point = Offset(
          center.x + scaledDistance * sin(relative).toFloat(),
          center.y - scaledDistance * cos(relative).toFloat(),
      )
      val glow = (0.3 + 0.7 * ui.strength).toFloat()
      val signalColor = if (ui.zone == Zone.LOCK) Rail.Confirmed else Rail.Signal
      drawCircle(signalColor.copy(alpha = 0.18f * glow), 46f * glow, point)
      drawCircle(signalColor.copy(alpha = 0.45f), 22f * glow, point)
      drawCircle(signalColor, 9f, point)
    }

    if (ui.heading.deg == null) {
      drawCircle(Rail.Brass.copy(alpha = 0.25f), radius, center, style = Stroke(3f))
    }
  }
}

/** The live transmission window: listening, recording, speaking, or static. */
@Composable
private fun TransmissionPanel(ui: RunUi) {
  Column(
      Modifier.fillMaxWidth()
          .heightIn(min = 72.dp)
          .background(Rail.Surface, RoundedCornerShape(10.dp))
          .border(1.dp, if (ui.listening) Rail.Brass else Rail.Line, RoundedCornerShape(10.dp))
          .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    when {
      ui.listening -> {
        Label("● CHANNEL OPEN · speak now", Rail.Brass, 12, FontWeight.Bold)
        Label(ui.partial ?: "…", Rail.Text, 15)
      }
      ui.recording -> Label("● RECORDING · press capture to finish", Rail.Fault, 13, FontWeight.Bold)
      ui.speaking != null -> {
        Label(ui.speaking!!.speaker.uppercase(), Rail.Signal, 12, FontWeight.Bold)
        Label(ui.speaking!!.text, Rail.Text, 15)
      }
      else -> Label("static…", Rail.Dim, 13)
    }
  }
}

@Composable
private fun ChannelLog(ui: RunUi) {
  if (ui.transcript.isEmpty()) return
  Section("Channel log") {
    ui.transcript.takeLast(6).forEach { entry ->
      val speakerLabel = if (entry.fromPlayer) "YOU" else entry.speaker.uppercase()
      Label(
          "$speakerLabel: ${entry.text}",
          if (entry.fromPlayer) Rail.Signal else Rail.Text,
          12,
      )
    }
  }
}
