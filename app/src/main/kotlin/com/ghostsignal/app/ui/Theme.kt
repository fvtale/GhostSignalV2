package com.ghostsignal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GhostSignal's rail-circuitry palette: the DataRail house tokens
 * (datarail.org/library) carried into the app. Track-bed blacks, signal
 * cyan on the rails, brass for warnings, mint for confirmed states.
 * Dark only: the game is played outdoors, often at night.
 */
object Rail {
  val TrackBed = Color(0xFF05070B)
  val Surface = Color(0xFF0B0F16)
  val Raised = Color(0xFF10151E)
  val Line = Color(0xFF1C232F)
  val LineBright = Color(0xFF2A3444)
  val Signal = Color(0xFF42F5FF)
  val Confirmed = Color(0xFF42FFB3)
  val Brass = Color(0xFFE0B25C)
  val Text = Color(0xFFF5F5F7)
  val Muted = Color(0xFF9CA3AF)
  val Dim = Color(0xFF7D8694)
  val Fault = Color(0xFFFF8098)
}

val Mono: FontFamily = FontFamily.Monospace

@Composable
fun GhostTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme = darkColorScheme(
          primary = Rail.Signal,
          onPrimary = Rail.TrackBed,
          secondary = Rail.Brass,
          onSecondary = Rail.TrackBed,
          tertiary = Rail.Confirmed,
          onTertiary = Rail.TrackBed,
          background = Rail.TrackBed,
          onBackground = Rail.Text,
          surface = Rail.Surface,
          onSurface = Rail.Text,
          surfaceVariant = Rail.Raised,
          onSurfaceVariant = Rail.Muted,
          outline = Rail.Line,
          error = Rail.Fault,
      ),
      content = content,
  )
}

/** Monospaced label: the app's signage voice. */
@Composable
fun Label(
    text: String,
    color: Color = Rail.Dim,
    size: Int = 12,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
  Text(
      text = text,
      color = color,
      style = TextStyle(fontFamily = Mono, fontSize = size.sp, fontWeight = weight),
      modifier = modifier,
  )
}

/**
 * A card headed like a station sign: a node dot, the title in tracked
 * mono, and a rail rule running to the edge.
 */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Column(
      modifier
          .fillMaxWidth()
          .background(Rail.Surface, RoundedCornerShape(10.dp))
          .border(1.dp, Rail.Line, RoundedCornerShape(10.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(7.dp).background(Rail.Signal, CircleShape))
      Spacer(Modifier.width(8.dp))
      Label(title.uppercase(), Rail.Text, 12, FontWeight.Bold)
      Spacer(Modifier.width(10.dp))
      Box(Modifier.weight(1f).height(1.dp).background(Rail.LineBright))
    }
    content()
  }
}

/** One status line with a node dot: mint ok, brass partial, rose fault, grey unknown. */
@Composable
fun Status(label: String, value: String, ok: Boolean?, warn: Boolean = false) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    val dotColor = when {
      ok == true -> Rail.Confirmed
      warn -> Rail.Brass
      ok == false -> Rail.Fault
      else -> Rail.Dim
    }
    Box(Modifier.size(8.dp).background(dotColor, CircleShape))
    Spacer(Modifier.width(8.dp))
    Label(label, Rail.Dim, 13, modifier = Modifier.width(120.dp))
    Label(value, Rail.Text, 13)
  }
}

/** Primary action: a filled signal button. */
@Composable
fun RailButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Rail.Signal,
    enabled: Boolean = true,
) {
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier,
      shape = RoundedCornerShape(8.dp),
      colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Rail.TrackBed),
  ) {
    Text(text, fontFamily = Mono, fontWeight = FontWeight.Bold)
  }
}

/** Secondary action: an outlined track button. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
  OutlinedButton(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier,
      shape = RoundedCornerShape(8.dp),
  ) {
    Text(text, fontFamily = Mono, color = if (enabled) Rail.Text else Rail.Dim)
  }
}
