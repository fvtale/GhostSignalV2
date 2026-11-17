package com.ghostsignal.app.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostsignal.app.AppGraph
import com.ghostsignal.app.EngineChoice
import com.ghostsignal.app.LocationMode
import com.ghostsignal.app.VoiceInputMode
import com.ghostsignal.app.net.GlassesEvidence
import com.ghostsignal.core.engine.GameEvent
import com.ghostsignal.core.engine.PlayerAction
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.heading.HeadingFusion
import com.ghostsignal.core.model.SignalPlacement
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Event-operator tools: placing the venue route, simulating a runner,
 * forcing game steps, enrolling event glasses, and sending live
 * transmissions. Kept off the runner's path; reached from setup.
 */
@Composable
fun OperatorScreen(graph: AppGraph, back: () -> Unit) {
  val game = graph.game
  val ui by game.ui.collectAsStateWithLifecycle()
  val mission by graph.missions.mission.collectAsStateWithLifecycle()
  val settings by graph.settings.flow.collectAsStateWithLifecycle()
  val glasses by graph.glasses.state.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()

  var status by remember { mutableStateOf<String?>(null) }
  var broadcastText by remember { mutableStateOf("") }
  var broadcastSpeaker by remember { mutableStateOf(mission.briefing.first().speaker) }
  var mockSpeech by remember { mutableStateOf("") }
  var lockRadiusM by remember(mission) { mutableStateOf(mission.signals.first().lockRadiusM.toFloat()) }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label("OPERATOR", Rail.Brass, 16, FontWeight.Bold, Modifier.weight(1f))
      GhostButton("Back", back)
    }
    status?.let { Label(it, Rail.Brass, 12) }

    Section("Location") {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Simulated runner", Rail.Text, 13, modifier = Modifier.weight(1f))
        Switch(
            checked = settings.locationMode == LocationMode.SIMULATED,
            onCheckedChange = { simulated ->
              game.setLocationMode(if (simulated) LocationMode.SIMULATED else LocationMode.GPS)
            },
        )
      }
      ui.fix?.let { fix ->
        Status(
            "Fix",
            "%.6f, %.6f ±%d m".format(fix.position.lat, fix.position.lon, fix.accuracyM.toInt()),
            fix.accuracyM < 20,
            warn = fix.accuracyM >= 20,
        )
      }
      if (settings.locationMode == LocationMode.SIMULATED) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          GhostButton("Walk to signal", { game.walkToCurrentSignal() })
          GhostButton("Start point", { game.sim.teleport(game.simulatedStart()) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, bearing) ->
            GhostButton("$label 10m", { game.sim.nudge(bearing, 10.0) }, Modifier.weight(1f))
          }
        }
      }
    }

    Section("Game") {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GhostButton("Force lock", { game.forceLock() }, Modifier.weight(1f), enabled = ui.running)
        GhostButton("Force solve", { game.forceSolve() }, Modifier.weight(1f), enabled = ui.running)
      }
      Label("Jump to signal (collects everything before it):", Rail.Dim, 11)
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mission.signals.indices.forEach { index ->
          GhostButton("${index + 1}", { game.jumpTo(index) }, Modifier.weight(1f), enabled = ui.running)
        }
      }
    }

    Section("Venue route") {
      Label(
          "Stand on each spot and press Place. GPS fix: ${ui.fix?.accuracyM?.toInt()?.let { "±$it m" } ?: "none"}",
          Rail.Dim,
          11,
      )
      mission.signals.forEachIndexed { index, signal ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Label("${index + 1}. ${signal.name}", Rail.Text, 13, FontWeight.Bold)
            val distanceNote = ui.fix?.let { fix ->
              "${Geo.distanceM(fix.position, signal.location).toInt()} m away"
            } ?: ""
            Label(
                "%.5f, %.5f · r%d/%d m %s".format(
                    signal.location.lat, signal.location.lon,
                    signal.radiusM.toInt(), signal.lockRadiusM.toInt(), distanceNote,
                ),
                Rail.Dim,
                11,
            )
          }
          GhostButton("Place", {
            val fix = game.currentFix()
            if (fix == null) {
              status = "No GPS fix yet"
            } else {
              graph.missions.place(
                  signal.id,
                  SignalPlacement(fix.position, signal.radiusM, signal.lockRadiusM),
              )
              status = "Placed ${signal.name} (±${fix.accuracyM.toInt()} m). Restart the run to use it."
            }
          })
        }
      }
      Label(
          "Lock radius: ${lockRadiusM.toInt()} m (all signals; wider is more forgiving of bad GPS)",
          Rail.Dim,
          11,
      )
      Slider(
          value = lockRadiusM,
          onValueChange = { lockRadiusM = it },
          onValueChangeFinished = {
            graph.missions.placeAll(
                mission.signals.associate { signal ->
                  signal.id to SignalPlacement(
                      signal.location,
                      maxOf(signal.radiusM, lockRadiusM + 15.0),
                      lockRadiusM.toDouble(),
                  )
                },
            )
            status = "Lock radius set to ${lockRadiusM.toInt()} m"
          },
          valueRange = 12f..45f,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GhostButton("Pull shared", { scope.launch { status = graph.missions.sync(manual = true) } })
        GhostButton("Push shared", { scope.launch { status = graph.missions.push(settings.operatorToken) } })
        GhostButton("Reset", {
          graph.missions.resetRoute()
          status = "Route reset to the authored Bryant Park placements"
        })
      }
    }

    Section("Live transmission") {
      Label("Voice a message to every runner, e.g. a hint or a story twist.", Rail.Dim, 11)
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mission.characters.forEach { character ->
          if (character.id == broadcastSpeaker) {
            RailButton(character.name.take(8), { broadcastSpeaker = character.id }, Modifier.weight(1f), Rail.Brass)
          } else {
            GhostButton(character.name.take(8), { broadcastSpeaker = character.id }, Modifier.weight(1f))
          }
        }
      }
      OutlinedTextField(
          broadcastText,
          { broadcastText = it },
          Modifier.fillMaxWidth(),
          label = { Text("Message") },
      )
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RailButton(
            "Send to all",
            {
              scope.launch {
                status = runCatching {
                  graph.backend.sendBroadcast(broadcastSpeaker, broadcastText, settings.operatorToken)
                }.fold({ "Sent" }, { error -> "Send failed: ${error.message}" })
              }
            },
            enabled = broadcastText.isNotBlank() && graph.backend.configured,
        )
        GhostButton(
            "Play here",
            { game.send(GameEvent.Broadcast(broadcastSpeaker, broadcastText)) },
            enabled = broadcastText.isNotBlank() && ui.running,
        )
      }
    }

    Section("Event glasses") {
      Label(
          "Enroll the pair connected to this phone: any runner who links it gets premium straight away, without waiting for approval. Needs the operator token.",
          Rail.Dim,
          11,
      )
      val connectedDeviceId = glasses.deviceId
      RailButton(
          if (connectedDeviceId == null) "Connect a pair first" else "Enroll ${glasses.deviceName ?: "this pair"}",
          {
            if (connectedDeviceId != null) {
              scope.launch {
                val note = "Enrolled ${DateFormat.getDateInstance().format(Date())} from the operator phone"
                status = runCatching {
                  graph.backend.enrollGlasses(
                      GlassesEvidence(
                          identifier = connectedDeviceId,
                          deviceType = glasses.deviceType ?: "UNKNOWN",
                          name = glasses.deviceName.orEmpty(),
                          firmware = glasses.firmware.orEmpty(),
                          note = note,
                      ),
                      settings.operatorToken,
                  )
                }.fold(
                    { "Enrolled ${glasses.deviceName ?: "the pair"}" },
                    { error -> "Enroll failed: ${error.message}" },
                )
              }
            }
          },
          enabled = connectedDeviceId != null && !glasses.mock &&
            settings.operatorToken.isNotBlank() && graph.backend.configured,
      )
    }

    if (glasses.mock) {
      Section("Mock glasses") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          listOf(PlayerAction.SCAN, PlayerAction.TALK, PlayerAction.TAP, PlayerAction.HINT)
              .forEach { action ->
                GhostButton(action.name.lowercase(), { graph.glasses.mockInput(action) }, Modifier.weight(1f))
              }
        }
        OutlinedTextField(
            mockSpeech,
            { mockSpeech = it },
            Modifier.fillMaxWidth(),
            label = { Text("Say through mock glasses") },
        )
        GhostButton("Speak", { graph.glasses.mockSpeech(mockSpeech) })
      }
    }

    Section("Diagnostics") {
      Status(
          "Heading",
          ui.heading.deg?.let { "${it.toInt()}° ${ui.heading.source.name.lowercase()}" } ?: "none",
          ui.heading.deg != null,
      )
      Status(
          "Head pitch",
          ui.heading.pitchDeg?.let { "${it.toInt()}°" } ?: "none",
          ui.heading.pitchDeg != null,
      )
      Status(
          "Glasses IMU",
          if (glasses.imuRateHz > 0) "${glasses.imuRateHz.toInt()} Hz" else "no samples",
          glasses.imuRateHz > 0,
      )
      Status("Last press", glasses.lastInput ?: "none", glasses.lastInput != null)
      Status("Voice input", ui.voiceInput.ifBlank { settings.voiceInput.label }, null)
      Status("Audio", ui.audioEngine.ifBlank { "not running" }, ui.running)
    }
  }
}

/** Single-choice row used across settings: a filled button when selected. */
@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
  if (selected) {
    RailButton("● $label", onSelect, Modifier.fillMaxWidth())
  } else {
    GhostButton(label, onSelect, Modifier.fillMaxWidth())
  }
}

@Composable
fun SettingsScreen(graph: AppGraph, back: () -> Unit) {
  val settings by graph.settings.flow.collectAsStateWithLifecycle()
  val rewards by graph.rewards.rewards.collectAsStateWithLifecycle()

  var backendUrl by remember { mutableStateOf(settings.backendUrl) }
  var eventToken by remember { mutableStateOf(settings.eventToken) }
  var operatorToken by remember { mutableStateOf(settings.operatorToken) }
  var note by remember { mutableStateOf<String?>(null) }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label("SETTINGS", Rail.Signal, 16, FontWeight.Bold, Modifier.weight(1f))
      GhostButton("Back", back)
    }
    note?.let { Label(it, Rail.Brass, 12) }

    Section("Backend") {
      OutlinedTextField(
          backendUrl, { backendUrl = it }, Modifier.fillMaxWidth(),
          label = { Text("Backend URL") }, singleLine = true,
      )
      OutlinedTextField(
          eventToken, { eventToken = it }, Modifier.fillMaxWidth(),
          label = { Text("Event token") }, singleLine = true,
      )
      OutlinedTextField(
          operatorToken, { operatorToken = it }, Modifier.fillMaxWidth(),
          label = { Text("Operator token") }, singleLine = true,
      )
      RailButton("Save", {
        graph.settings.update {
          it.copy(backendUrl = backendUrl, eventToken = eventToken, operatorToken = operatorToken)
        }
        note = "Saved"
      })
    }

    Section("Voice input") {
      VoiceInputMode.entries.forEach { mode ->
        ChoiceRow(mode.label, settings.voiceInput == mode) {
          graph.settings.update { it.copy(voiceInput = mode) }
        }
      }
    }

    Section("Audio engine") {
      EngineChoice.entries.forEach { choice ->
        ChoiceRow(choice.label, settings.engine == choice) {
          graph.settings.update { it.copy(engine = choice) }
        }
      }
      Label("FMOD is used only in builds made with the FMOD SDK present (see docs/AUDIO.md).", Rail.Dim, 11)
      Label("Volume ${(settings.masterVolume * 100).toInt()}%", Rail.Dim, 12)
      Slider(
          value = settings.masterVolume,
          onValueChange = { volume -> graph.settings.update { it.copy(masterVolume = volume) } },
      )
    }

    Section("Head tracking") {
      HeadingFusion.YawMode.entries.forEach { mode ->
        val label = if (mode == HeadingFusion.YawMode.GYRO) {
          "Gyro around gravity (default)"
        } else {
          "Orientation quaternion"
        }
        ChoiceRow(label, settings.yawMode == mode) {
          graph.settings.update { it.copy(yawMode = mode) }
          graph.game.applyHeadingSettings()
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Label(
            "Invert yaw (if turning right moves sounds the wrong way)",
            Rail.Text,
            12,
            modifier = Modifier.weight(1f),
        )
        Switch(settings.invertYaw, { inverted ->
          graph.settings.update { it.copy(invertYaw = inverted) }
          graph.game.applyHeadingSettings()
        })
      }
    }

    Section("Testing") {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Label(
            "Mock glasses (MockDeviceKit). Restart the app after changing.",
            Rail.Text,
            12,
            modifier = Modifier.weight(1f),
        )
        Switch(settings.mockGlasses, { mock ->
          graph.settings.update { it.copy(mockGlasses = mock) }
        })
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GhostButton("Clear voice cache", {
          graph.voices.clearCache()
          note = "Voice cache cleared; prepare again"
        })
        GhostButton("Reset progress", {
          graph.progress.clear()
          note = "Progress reset"
        })
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Label(
            "Finished runs on this phone: ${rewards.completions}. Re-lock before handing a shared phone to a new runner.",
            Rail.Text,
            12,
            modifier = Modifier.weight(1f),
        )
        GhostButton("Re-lock rewards", {
          graph.rewards.relock()
          note = "Rewards re-locked: original soundtrack, nothing unlocked"
        })
      }
    }
  }
}

/** Messages runners recorded at the Voice signal. They never leave the phone. */
@Composable
fun ArchiveScreen(graph: AppGraph, back: () -> Unit) {
  var recordings by remember { mutableStateOf(graph.recorder.list()) }
  var player by remember { mutableStateOf<MediaPlayer?>(null) }
  DisposableEffect(Unit) { onDispose { player?.release() } }
  val dateFormat = remember {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
  }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label("MESSAGES", Rail.Brass, 16, FontWeight.Bold, Modifier.weight(1f))
      GhostButton("Back", back)
    }
    Label("Stored on this phone only.", Rail.Dim, 12)
    if (recordings.isEmpty()) Label("No messages yet.", Rail.Dim, 13)

    recordings.forEach { recording ->
      Section(dateFormat.format(Date(recording.createdAtMs))) {
        Label("${recording.signalId} · ${recording.durationMs / 1000}s", Rail.Text, 12)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          GhostButton("Play", {
            player?.release()
            player = MediaPlayer().apply {
              setDataSource(recording.file.absolutePath)
              prepare()
              start()
            }
          })
          GhostButton("Delete", {
            graph.recorder.delete(recording)
            recordings = graph.recorder.list()
          })
        }
      }
    }
  }
}
