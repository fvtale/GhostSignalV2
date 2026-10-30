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

/** Tools for whoever runs the event: placing the route, simulating, forcing steps, live messages. */
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
  var lockRadius by remember(mission) { mutableStateOf(mission.signals.first().lockRadiusM.toFloat()) }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label("OPERATOR", Neon.Amber, 16, FontWeight.Bold, Modifier.weight(1f))
      GhostButton("Back", back)
    }
    status?.let { Label(it, Neon.Amber, 12) }

    Section("Location") {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Simulated runner", Neon.Text, 13, modifier = Modifier.weight(1f))
        Switch(
            checked = settings.locationMode == LocationMode.SIMULATED,
            onCheckedChange = { game.setLocationMode(if (it) LocationMode.SIMULATED else LocationMode.GPS) })
      }
      ui.fix?.let {
        Status("Fix", "%.6f, %.6f ±%d m".format(it.position.lat, it.position.lon, it.accuracyM.toInt()), it.accuracyM < 20, warn = it.accuracyM >= 20)
      }
      if (settings.locationMode == LocationMode.SIMULATED) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          GhostButton("Walk to signal", { game.walkToCurrentSignal() })
          GhostButton("Start point", { game.sim.teleport(game.simulatedStart()) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, b) ->
            GhostButton("$label 10m", { game.sim.nudge(b, 10.0) }, Modifier.weight(1f))
          }
        }
      }
    }

    Section("Game") {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GhostButton("Force lock", { game.forceLock() }, Modifier.weight(1f), enabled = ui.running)
        GhostButton("Force solve", { game.forceSolve() }, Modifier.weight(1f), enabled = ui.running)
      }
      Label("Jump to signal (collects everything before it):", Neon.Dim, 11)
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mission.signals.indices.forEach { i ->
          GhostButton("${i + 1}", { game.jumpTo(i) }, Modifier.weight(1f), enabled = ui.running)
        }
      }
    }

    Section("Venue route") {
      Label("Stand on each spot and press Place. GPS fix: ${ui.fix?.accuracyM?.toInt()?.let { "±$it m" } ?: "none"}", Neon.Dim, 11)
      mission.signals.forEachIndexed { i, s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Label("${i + 1}. ${s.name}", Neon.Text, 13, FontWeight.Bold)
            val dist = ui.fix?.let { "${Geo.distanceM(it.position, s.location).toInt()} m away" } ?: ""
            Label("%.5f, %.5f · r%d/%d m %s".format(s.location.lat, s.location.lon, s.radiusM.toInt(), s.lockRadiusM.toInt(), dist), Neon.Dim, 11)
          }
          GhostButton("Place", {
            val fix = game.currentFix()
            if (fix == null) status = "No GPS fix yet"
            else {
              graph.missions.place(s.id, SignalPlacement(fix.position, s.radiusM, s.lockRadiusM))
              status = "Placed ${s.name} (±${fix.accuracyM.toInt()} m). Restart the run to use it."
            }
          })
        }
      }
      Label("Lock radius: ${lockRadius.toInt()} m (all signals; wider is more forgiving of bad GPS)", Neon.Dim, 11)
      Slider(
          value = lockRadius,
          onValueChange = { lockRadius = it },
          onValueChangeFinished = {
            graph.missions.placeAll(
                mission.signals.associate { s ->
                  s.id to SignalPlacement(s.location, maxOf(s.radiusM, lockRadius + 15.0), lockRadius.toDouble())
                })
            status = "Lock radius set to ${lockRadius.toInt()} m"
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
      Label("Voice a message to every runner, e.g. a hint or a story twist.", Neon.Dim, 11)
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        mission.characters.forEach { c ->
          val selected = c.id == broadcastSpeaker
          if (selected) NeonButton(c.name.take(8), { broadcastSpeaker = c.id }, Modifier.weight(1f), Neon.Amber)
          else GhostButton(c.name.take(8), { broadcastSpeaker = c.id }, Modifier.weight(1f))
        }
      }
      OutlinedTextField(broadcastText, { broadcastText = it }, Modifier.fillMaxWidth(), label = { Text("Message") })
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        NeonButton("Send to all", {
          scope.launch {
            status =
                runCatching { graph.backend.sendBroadcast(broadcastSpeaker, broadcastText, settings.operatorToken) }
                    .fold({ "Sent" }, { "Send failed: ${it.message}" })
          }
        }, enabled = broadcastText.isNotBlank() && graph.backend.configured)
        GhostButton("Play here", { game.send(GameEvent.Broadcast(broadcastSpeaker, broadcastText)) }, enabled = broadcastText.isNotBlank() && ui.running)
      }
    }

    Section("Event glasses") {
      Label(
          "Enroll the pair connected to this phone: any runner who links it gets premium straight away, without waiting for approval. Needs the operator token.",
          Neon.Dim,
          11)
      val deviceId = glasses.deviceId
      NeonButton(
          if (deviceId == null) "Connect a pair first" else "Enroll ${glasses.deviceName ?: "this pair"}",
          {
            if (deviceId != null) {
              scope.launch {
                val note = "Enrolled ${DateFormat.getDateInstance().format(Date())} from the operator phone"
                status =
                    runCatching {
                          graph.backend.enrollGlasses(
                              GlassesEvidence(deviceId, glasses.deviceType ?: "UNKNOWN", glasses.deviceName.orEmpty(), glasses.firmware.orEmpty(), note = note),
                              settings.operatorToken)
                        }
                        .fold({ "Enrolled ${glasses.deviceName ?: "the pair"}" }, { "Enroll failed: ${it.message}" })
              }
            }
          },
          enabled = deviceId != null && !glasses.mock && settings.operatorToken.isNotBlank() && graph.backend.configured)
    }

    if (glasses.mock) {
      Section("Mock glasses") {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          listOf(PlayerAction.SCAN, PlayerAction.TALK, PlayerAction.TAP, PlayerAction.HINT).forEach { a ->
            GhostButton(a.name.lowercase(), { graph.glasses.mockInput(a) }, Modifier.weight(1f))
          }
        }
        OutlinedTextField(mockSpeech, { mockSpeech = it }, Modifier.fillMaxWidth(), label = { Text("Say through mock glasses") })
        GhostButton("Speak", { graph.glasses.mockSpeech(mockSpeech) })
      }
    }

    Section("Diagnostics") {
      Status("Heading", ui.heading.deg?.let { "${it.toInt()}° ${ui.heading.source.name.lowercase()}" } ?: "none", ui.heading.deg != null)
      Status("Head pitch", ui.heading.pitchDeg?.let { "${it.toInt()}°" } ?: "none", ui.heading.pitchDeg != null)
      Status("Glasses IMU", if (glasses.imuRateHz > 0) "${glasses.imuRateHz.toInt()} Hz" else "no samples", glasses.imuRateHz > 0)
      Status("Last press", glasses.lastInput ?: "none", glasses.lastInput != null)
      Status("Voice input", ui.voiceInput.ifBlank { settings.voiceInput.label }, null)
      Status("Audio", ui.audioEngine.ifBlank { "not running" }, ui.running)
    }
  }
}

@Composable
fun SettingsScreen(graph: AppGraph, back: () -> Unit) {
  val settings by graph.settings.flow.collectAsStateWithLifecycle()
  val rewards by graph.rewards.rewards.collectAsStateWithLifecycle()
  var url by remember { mutableStateOf(settings.backendUrl) }
  var token by remember { mutableStateOf(settings.eventToken) }
  var operator by remember { mutableStateOf(settings.operatorToken) }
  var note by remember { mutableStateOf<String?>(null) }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label("SETTINGS", Neon.Cyan, 16, FontWeight.Bold, Modifier.weight(1f))
      GhostButton("Back", back)
    }
    note?.let { Label(it, Neon.Amber, 12) }
    Section("Backend") {
      OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Backend URL") }, singleLine = true)
      OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Event token") }, singleLine = true)
      OutlinedTextField(operator, { operator = it }, Modifier.fillMaxWidth(), label = { Text("Operator token") }, singleLine = true)
      NeonButton("Save", {
        graph.settings.update { it.copy(backendUrl = url, eventToken = token, operatorToken = operator) }
        note = "Saved"
      })
    }
    Section("Voice input") {
      VoiceInputMode.entries.forEach { m ->
        ChoiceRow(m.label, settings.voiceInput == m) { graph.settings.update { it.copy(voiceInput = m) } }
      }
    }
    Section("Audio engine") {
      EngineChoice.entries.forEach { e ->
        ChoiceRow(e.label, settings.engine == e) { graph.settings.update { it.copy(engine = e) } }
      }
      Label("FMOD is used only in builds made with the FMOD SDK present (see docs/AUDIO.md).", Neon.Dim, 11)
      Label("Volume ${(settings.masterVolume * 100).toInt()}%", Neon.Dim, 12)
      Slider(value = settings.masterVolume, onValueChange = { v -> graph.settings.update { it.copy(masterVolume = v) } })
    }
    Section("Head tracking") {
      HeadingFusion.YawMode.entries.forEach { m ->
        ChoiceRow(if (m == HeadingFusion.YawMode.GYRO) "Gyro around gravity (default)" else "Orientation quaternion", settings.yawMode == m) {
          graph.settings.update { it.copy(yawMode = m) }
          graph.game.applyHeadingSettings()
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Invert yaw (if turning right moves sounds the wrong way)", Neon.Text, 12, modifier = Modifier.weight(1f))
        Switch(settings.invertYaw, { v ->
          graph.settings.update { it.copy(invertYaw = v) }
          graph.game.applyHeadingSettings()
        })
      }
    }
    Section("Testing") {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Label("Mock glasses (MockDeviceKit). Restart the app after changing.", Neon.Text, 12, modifier = Modifier.weight(1f))
        Switch(settings.mockGlasses, { v -> graph.settings.update { it.copy(mockGlasses = v) } })
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
        Label("Finished runs on this phone: ${rewards.completions}. Re-lock before handing a shared phone to a new runner.", Neon.Text, 12, modifier = Modifier.weight(1f))
        GhostButton("Re-lock rewards", {
          graph.rewards.relock()
          note = "Rewards re-locked: original soundtrack, nothing unlocked"
        })
      }
    }
  }
}

@Composable
internal fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
  if (selected) NeonButton("● $label", onSelect, Modifier.fillMaxWidth())
  else GhostButton(label, onSelect, Modifier.fillMaxWidth())
}

/** Messages runners recorded at the Voice signal. They never leave the phone. */
@Composable
fun ArchiveScreen(graph: AppGraph, back: () -> Unit) {
  var recordings by remember { mutableStateOf(graph.recorder.list()) }
  var player by remember { mutableStateOf<MediaPlayer?>(null) }
  DisposableEffect(Unit) { onDispose { player?.release() } }
  val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Label("MESSAGES", Neon.Magenta, 16, FontWeight.Bold, Modifier.weight(1f))
      GhostButton("Back", back)
    }
    Label("Stored on this phone only.", Neon.Dim, 12)
    if (recordings.isEmpty()) Label("No messages yet.", Neon.Dim, 13)
    recordings.forEach { r ->
      Section(format.format(Date(r.createdAtMs))) {
        Label("${r.signalId} · ${r.durationMs / 1000}s", Neon.Text, 12)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          GhostButton("Play", {
            player?.release()
            player = MediaPlayer().apply {
              setDataSource(r.file.absolutePath)
              prepare()
              start()
            }
          })
          GhostButton("Delete", {
            graph.recorder.delete(r)
            recordings = graph.recorder.list()
          })
        }
      }
    }
  }
}
