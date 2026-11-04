package com.ghostsignal.app.ui

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ghostsignal.app.AppGraph
import com.ghostsignal.app.location.GpsLocation
import com.ghostsignal.app.voice.AndroidTts
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.LatLon
import com.ghostsignal.core.model.Mission
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The opening: rain over a moody pad while Vesper welcomes the runner and
 * suggests the nearest experience ("your nearest location appears to
 * be…"), confirmed with one tap. Audio starts on Begin — a deliberate
 * first gesture, like turning a radio on.
 */
@Composable
fun OpeningCard(
    graph: AppGraph,
    hasLocation: Boolean,
    onChooseDifferent: () -> Unit,
    onDone: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var line by remember { mutableStateOf("A premium DataRail experience.") }
  var started by remember { mutableStateOf(false) }
  var asked by remember { mutableStateOf(false) }
  var suggested by remember { mutableStateOf<Mission?>(null) }

  val ambience = remember { MediaPlayer() }
  val tts = remember { AndroidTts(context) }
  DisposableEffect(Unit) {
    onDispose {
      runCatching { ambience.stop() }
      ambience.release()
    }
  }

  fun playFile(file: File, onEnded: () -> Unit) {
    val player = MediaPlayer()
    player.setDataSource(file.absolutePath)
    player.setOnCompletionListener {
      it.release()
      onEnded()
    }
    player.setOnErrorListener { p, _, _ ->
      p.release()
      onEnded()
      true
    }
    player.prepare()
    player.start()
  }

  suspend fun vesperSay(text: String) {
    line = text
    val mission = graph.missions.mission.value
    val spec = mission.character("vesper")?.voice
    val file = File(context.cacheDir, "opening_${text.hashCode()}.wav")
    val ok = tts.synthesize(
        text = text,
        pitch = (spec?.pitch ?: 0.95).toFloat(),
        rate = (spec?.rate ?: 0.95).toFloat(),
        outputFile = file,
    )
    if (ok) {
      suspendCancellableCoroutine<Unit> { cont ->
        playFile(file) { if (cont.isActive) cont.resume(Unit) }
      }
    }
  }

  suspend fun locateNearest(): Mission? {
    if (!hasLocation) return null
    val fix = withTimeoutOrNull(9_000) {
      suspendCancellableCoroutine<LatLon?> { cont ->
        lateinit var gps: GpsLocation
        gps = GpsLocation(context) { first ->
          gps.stop() // one-shot: the first fix decides
          if (cont.isActive) cont.resume(first.position)
        }
        gps.start()
        cont.invokeOnCancellation { gps.stop() }
      }
    } ?: return null
    return graph.missions.bundled
        .filter { it.landmark != null }
        .minByOrNull { Geo.distanceM(fix, it.landmark!!.location) }
  }

  Section("Welcome") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Label("GHOSTSIGNAL", Rail.Brass, 16, FontWeight.Bold)
      Label(line, Rail.Text, 14)
      if (!started) {
        RailButton("Begin", {
          started = true
          scope.launch {
            runCatching {
              context.assets.openFd("music/ambience/rain_city.ogg").use { fd ->
                ambience.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
              }
              ambience.isLooping = true
              ambience.setVolume(0.55f, 0.55f)
              ambience.prepare()
              ambience.start()
            }
            vesperSay("Welcome to Ghost Signal, a premium DataRail experience.")
            val nearest = locateNearest()
            asked = true
            if (nearest != null) {
              suggested = nearest
              vesperSay(
                  "Your nearest location appears to be ${nearest.landmark!!.label}. Is this correct?",
              )
            } else {
              vesperSay("I can't see where you are yet. Choose your location to continue.")
            }
          }
        }, Modifier.fillMaxWidth())
      }
      if (asked) {
        if (suggested != null) {
          RailButton("Yes, that's right", {
            suggested?.let { chosen ->
              if (chosen.id != graph.missions.mission.value.id) {
                graph.missions.selectMission(chosen.id)
                graph.progress.reload()
              }
            }
            runCatching { ambience.setVolume(0.22f, 0.22f) }
            onDone()
          }, Modifier.fillMaxWidth())
        }
        RailButton("Choose a different location", {
          runCatching { ambience.setVolume(0.22f, 0.22f) }
          onChooseDifferent()
          onDone()
        }, Modifier.fillMaxWidth())
      }
    }
  }
}
