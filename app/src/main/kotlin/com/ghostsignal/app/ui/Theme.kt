package com.ghostsignal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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

object Neon {
  val Void = Color(0xFF07070C)
  val Panel = Color(0xFF11111B)
  val Line = Color(0xFF26263A)
  val Cyan = Color(0xFF2EF2E2)
  val Magenta = Color(0xFFFF2E88)
  val Amber = Color(0xFFFFC94D)
  val Text = Color(0xFFE6E6F0)
  val Dim = Color(0xFF8A8AA0)
  val Bad = Color(0xFFFF5A5A)
}

val Mono = FontFamily.Monospace

@Composable
fun GhostTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme =
          darkColorScheme(
              primary = Neon.Cyan,
              onPrimary = Neon.Void,
              secondary = Neon.Magenta,
              onSecondary = Neon.Void,
              tertiary = Neon.Amber,
              background = Neon.Void,
              onBackground = Neon.Text,
              surface = Neon.Panel,
              onSurface = Neon.Text,
              outline = Neon.Line,
          ),
      content = content,
  )
}

@Composable
fun Label(text: String, color: Color = Neon.Dim, size: Int = 12, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) =
    Text(text, color = color, style = TextStyle(fontFamily = Mono, fontSize = size.sp, fontWeight = weight), modifier = modifier)

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Column(
      modifier
          .fillMaxWidth()
          .background(Neon.Panel, RoundedCornerShape(10.dp))
          .border(1.dp, Neon.Line, RoundedCornerShape(10.dp))
          .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Label(title.uppercase(), Neon.Cyan, 12, FontWeight.Bold)
    content()
  }
}

/** One status line with a coloured dot: green ok, amber partial, red problem, grey unknown. */
@Composable
fun Status(label: String, value: String, ok: Boolean?, warn: Boolean = false) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    val dot =
        when {
          ok == true -> Neon.Cyan
          warn -> Neon.Amber
          ok == false -> Neon.Bad
          else -> Neon.Dim
        }
    Box(Modifier.size(8.dp).background(dot, CircleShape))
    Spacer(Modifier.width(8.dp))
    Label(label, Neon.Dim, 13, modifier = Modifier.width(120.dp))
    Label(value, Neon.Text, 13)
  }
}

@Composable
fun NeonButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Neon.Cyan, enabled: Boolean = true) {
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier,
      shape = RoundedCornerShape(8.dp),
      colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Neon.Void),
  ) {
    Text(text, fontFamily = Mono, fontWeight = FontWeight.Bold)
  }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(8.dp)) {
    Text(text, fontFamily = Mono, color = if (enabled) Neon.Text else Neon.Dim)
  }
}
