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
import androidx.compose.ui.graphics.Color
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

@Composable
fun RunScreen(graph: AppGraph, go: (Screen) -> Unit) {
  val game = graph.game
  val ui by game.ui.collectAsStateWithLifecycle()
  val act: (PlayerAction) -> Unit = { game.input(it) }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label("GHOST 93.3", Neon.Magenta, 14, FontWeight.Bold, Modifier.weight(1f))
      Label(if (ui.paused) "PAUSED" else ui.phase.name, if (ui.paused) Neon.Amber else Neon.Dim, 12)
    }
    FragmentStrip(ui)
    Radar(ui, Modifier.fillMaxWidth().aspectRatio(1f))
    Label(ui.objective, Neon.Text, 18, FontWeight.Bold)
    if (ui.phase == Phase.HUNTING && ui.distanceM != null) {
      val dir = ui.bearingDeg?.let { b ->
        ui.heading.deg?.let { h -> "${Geo.clockPosition(Geo.deltaDeg(h, b))} o'clock" } ?: Geo.cardinal(b)
      } ?: ""
      Label("${Geo.spokenDistance(ui.distanceM!!)} · $dir", Neon.Cyan, 14)
    }
    if (ui.lockAvailable) Label("◉ LOCK AVAILABLE · press capture", Neon.Magenta, 14, FontWeight.Bold)
    ui.unlocked.forEach { title -> Label("★ UNLOCKED · $title · plays on your next run", Neon.Amber, 14, FontWeight.Bold) }

    Transmission(ui)

    if (ui.rhythmOpen) {
      Box(
          Modifier.fillMaxWidth()
              .height(120.dp)
              .background(Neon.Magenta.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
              .border(2.dp, Neon.Magenta, RoundedCornerShape(12.dp))
              .pointerInput(Unit) { detectTapGestures(onPress = { act(PlayerAction.TAP) }) },
          contentAlignment = Alignment.Center,
      ) {
        Label("TAP THE RHYTHM", Neon.Magenta, 18, FontWeight.Bold)
      }
    }

    // Phone controls mirror the glasses: the run is designed to be played with the glasses alone.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      GhostButton("Repeat", { act(PlayerAction.REPEAT) }, Modifier.weight(1f))
      GhostButton("Hint", { act(PlayerAction.HINT) }, Modifier.weight(1f))
      GhostButton("Status", { act(PlayerAction.STATUS) }, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      NeonButton("SCAN", { act(PlayerAction.SCAN) }, Modifier.weight(1f).heightIn(min = 56.dp))
      NeonButton(if (ui.listening) "LISTENING…" else "TALK", { act(PlayerAction.TALK) }, Modifier.weight(1f).heightIn(min = 56.dp), Neon.Magenta)
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
        Neon.Dim,
        11)
    Transcript(ui)
    Label("${ui.audioEngine} · ${ui.soundtrack.ifBlank { "original" }} · heading ${ui.heading.source.name.lowercase()} · GPS ±${ui.fix?.accuracyM?.toInt() ?: "?"} m", Neon.Dim, 11)
  }
}

@Composable
private fun FragmentStrip(ui: RunUi) {
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
    ui.stems.forEachIndexed { i, (name, got) ->
      val current = i == ui.signalIndex && !got
      Box(
          Modifier.weight(1f)
              .height(34.dp)
              .background(if (got) Neon.Cyan.copy(alpha = 0.85f) else Neon.Panel, RoundedCornerShape(4.dp))
              .border(1.dp, if (current) Neon.Magenta else Neon.Line, RoundedCornerShape(4.dp)),
          contentAlignment = Alignment.Center,
      ) {
        Label(if (got) name.take(4).uppercase() else "${i + 1}", if (got) Neon.Void else Neon.Dim, 10, FontWeight.Bold)
      }
    }
  }
}

/**
 * Heading-up radar: the active signal sits at its real bearing relative to where you face, on a
 * log distance scale, glowing brighter as the signal strengthens.
 */
@Composable
private fun Radar(ui: RunUi, modifier: Modifier) {
  Canvas(modifier.background(Neon.Panel, RoundedCornerShape(16.dp))) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = min(size.width, size.height) / 2 * 0.9f
    for (k in 1..4) drawCircle(Neon.Line, r * k / 4, c, style = Stroke(1.5f))
    drawLine(Neon.Line, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
    drawLine(Neon.Line, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
    // You, facing up.
    drawCircle(Neon.Cyan, 7f, c)
    drawLine(Neon.Cyan, c, Offset(c.x, c.y - 26f), 3f)

    val heading = ui.heading.deg ?: 0.0
    // North marker on the rim.
    val northAngle = Math.toRadians(Geo.deltaDeg(heading, 0.0))
    drawCircle(Neon.Dim, 5f, Offset(c.x + (r * sin(northAngle)).toFloat(), c.y - (r * cos(northAngle)).toFloat()))

    val b = ui.bearingDeg
    val d = ui.distanceM
    if (b != null && d != null) {
      val rel = Math.toRadians(Geo.deltaDeg(heading, b))
      val scaled = (ln(1 + d) / ln(1 + 600.0)).coerceIn(0.05, 1.0).toFloat() * r
      val p = Offset(c.x + scaled * sin(rel).toFloat(), c.y - scaled * cos(rel).toFloat())
      val glow = (0.3 + 0.7 * ui.strength).toFloat()
      val color = if (ui.zone == Zone.LOCK) Neon.Magenta else Neon.Cyan
      drawCircle(color.copy(alpha = 0.18f * glow), 46f * glow, p)
      drawCircle(color.copy(alpha = 0.45f), 22f * glow, p)
      drawCircle(color, 9f, p)
    }
    if (ui.heading.deg == null) {
      drawCircle(Color(0x33FFC94D), r, c, style = Stroke(3f))
    }
  }
}

@Composable
private fun Transmission(ui: RunUi) {
  Column(
      Modifier.fillMaxWidth()
          .heightIn(min = 72.dp)
          .background(Neon.Panel, RoundedCornerShape(10.dp))
          .border(1.dp, if (ui.listening) Neon.Magenta else Neon.Line, RoundedCornerShape(10.dp))
          .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    when {
      ui.listening -> {
        Label("● CHANNEL OPEN · speak now", Neon.Magenta, 12, FontWeight.Bold)
        Label(ui.partial ?: "…", Neon.Text, 15)
      }
      ui.recording -> Label("● RECORDING · press capture to finish", Neon.Bad, 13, FontWeight.Bold)
      ui.speaking != null -> {
        Label(ui.speaking!!.speaker.uppercase(), Neon.Cyan, 12, FontWeight.Bold)
        Label(ui.speaking!!.text, Neon.Text, 15)
      }
      else -> Label("static…", Neon.Dim, 13)
    }
  }
}

@Composable
private fun Transcript(ui: RunUi) {
  if (ui.transcript.isEmpty()) return
  Section("Channel log") {
    ui.transcript.takeLast(6).forEach { e ->
      Label(
          (if (e.fromPlayer) "YOU" else e.speaker.uppercase()) + ": " + e.text,
          if (e.fromPlayer) Neon.Magenta else Neon.Text,
          12,
      )
    }
  }
}
