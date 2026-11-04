package com.ghostsignal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostsignal.app.AppGraph
import com.ghostsignal.app.LocationMode
import com.ghostsignal.app.glasses.CapState
import com.ghostsignal.app.glasses.Capability
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.model.Soundtracks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Pre-flight: permissions, glasses, audio, heading, story prep, soundtrack
 * choice, account, and route — each a station on the line, checked in
 * order before the runner leaves.
 */
@Composable
fun SetupScreen(
    graph: AppGraph,
    permissions: StateFlow<Permissions>,
    onRequestPermissions: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onRequestGlassesMic: () -> Unit,
    go: (Screen) -> Unit,
) {
  val game = graph.game
  val perms by permissions.collectAsStateWithLifecycle()
  val glasses by graph.glasses.state.collectAsStateWithLifecycle()
  val audioRoute by graph.routing.route.collectAsStateWithLifecycle()
  val prep by game.prepare.collectAsStateWithLifecycle()
  val settings by graph.settings.flow.collectAsStateWithLifecycle()
  val mission by graph.missions.mission.collectAsStateWithLifecycle()
  val progress by graph.progress.progress.collectAsStateWithLifecycle()
  val rewards by graph.rewards.rewards.collectAsStateWithLifecycle()
  val lockedTracks by graph.missions.locked.collectAsStateWithLifecycle()
  val plan by graph.account.plan.collectAsStateWithLifecycle()

  val chosenSoundtrack = Soundtracks.resolve(mission, rewards.soundtrack, rewards.completions)
  val activeMusic = mission.withSoundtrack(chosenSoundtrack).music

  var backendStatus by remember { mutableStateOf<String?>(null) }
  var backendOk by remember { mutableStateOf<Boolean?>(null) }
  var audioTesting by remember { mutableStateOf(game.audioTestRunning) }
  var headingText by remember { mutableStateOf("—") }
  var calibrationNote by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    graph.missions.syncIfStale(graph.scope)
    game.compass.start()
    if (graph.backend.configured) {
      runCatching { graph.backend.health() }
          .onSuccess { health ->
            backendOk = health.ok
            backendStatus = "online" +
              (if (health.npc) " · agent" else "") +
              (if (health.tts) " · voices" else "")
          }
          .onFailure { error ->
            backendOk = false
            backendStatus = error.message
          }
    }
    while (true) {
      val estimate = synchronized(game.fusion) {
        game.fusion.estimate(System.currentTimeMillis())
      }
      headingText = estimate.deg?.let { degrees ->
        "${degrees.toInt()}° from ${estimate.source.name.lowercase()}" +
          if (estimate.glassesAligned) " · glasses aligned" else ""
      } ?: "no heading yet"
      delay(250)
    }
  }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Label("GHOST 93.3 // SIGNAL RUNNER", Rail.Brass, 14, FontWeight.Bold)
    Label(mission.title, Rail.Text, 20, FontWeight.Bold)
    Label(
        "${mission.signals.size} signals · ${activeMusic.title.ifBlank { "Original" }} · ${activeMusic.bpm.toInt()} BPM · ${mission.characters.size} voices",
        Rail.Dim,
        12,
    )

    Section("Permissions") {
      Status("Bluetooth", if (perms.bluetooth) "granted" else "needed", perms.bluetooth)
      Status("Location", if (perms.location) "granted" else "needed", perms.location)
      Status("Microphone", if (perms.microphone) "granted" else "needed", perms.microphone)
      Status(
          "Notifications",
          if (perms.notifications) "granted" else "optional",
          if (perms.notifications) true else null,
      )
      if (!perms.essentials) RailButton("Grant permissions", onRequestPermissions)
    }

    Section("Meta glasses") {
      Status(
          "Toolkit",
          if (glasses.mock) "mock glasses" else if (glasses.initialized) "ready" else "waiting for Bluetooth",
          glasses.initialized,
      )
      Status("Meta AI link", glasses.registration, glasses.registered, warn = !glasses.registered)
      Status(
          "Device",
          glasses.deviceName?.let { name ->
            name + (glasses.battery?.let { level -> " · $level%" } ?: "")
          } ?: "none",
          glasses.deviceName != null,
      )
      Status(
          "Worn",
          when (glasses.worn) { true -> "yes"; false -> "no"; null -> "unknown" },
          glasses.worn,
      )
      Status("Session", glasses.session, glasses.session == "started", warn = glasses.session == "paused")
      CapabilityStatus("Buttons", glasses.inputs)
      CapabilityStatus(
          "Head tracking",
          glasses.motion,
          extra = if (glasses.imuRateHz > 0) " · ${glasses.imuRateHz.toInt()} Hz" else "",
      )
      CapabilityStatus("Glasses speech", glasses.speech)
      Status(
          "Glasses mic",
          glasses.micPermission,
          glasses.micPermission == "granted",
          warn = glasses.micPermission != "granted",
      )
      glasses.lastInput?.let { Status("Last press", it, true) }
      glasses.error?.let { Label(it, Rail.Brass, 12) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!glasses.registered) {
          RailButton("Connect via Meta AI", onRegister, enabled = glasses.initialized)
        } else {
          GhostButton("Disconnect app", onUnregister)
        }
        if (glasses.registered && glasses.session != "started") {
          GhostButton("Start session", { graph.glasses.connect() })
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (glasses.registered && glasses.micPermission != "granted") {
          GhostButton("Allow glasses mic", onRequestGlassesMic)
        }
        if (glasses.session == "started") {
          GhostButton("Re-attach", { graph.glasses.reattach() })
        }
      }
      Label(
          "Press the capture button or tap the touchpad to test. Unavailable features fall back to the phone.",
          Rail.Dim,
          11,
      )
    }

    Section("Audio") {
      Status("Output", audioRoute.outputName, audioRoute.bluetoothOutput, warn = !audioRoute.bluetoothOutput)
      Status(
          "Glasses mic link",
          if (audioRoute.glassesMicAvailable) "available" else "not found",
          audioRoute.glassesMicAvailable,
      )
      Status("Engine", settings.engine.label, null)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!audioTesting) {
          GhostButton("Test spatial audio", {
            game.startAudioTest()
            audioTesting = true
          }, enabled = !game.running)
        } else {
          RailButton("Stop test", {
            game.stopAudioTest()
            audioTesting = false
          })
        }
      }
      if (audioTesting) {
        Label(
            "A sonar ping is fixed 90° to your right and 30° up. Turn your head toward it: it should swing to the front and stay put.",
            Rail.Brass,
            12,
        )
      }
    }

    Section("Heading") {
      Status("Heading", headingText, null)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Calibrate", {
          calibrationNote = if (game.calibrateHeading()) {
            "Aligned to the phone compass"
          } else {
            "Needs live glasses motion and the compass"
          }
        })
      }
      Label(
          "Hold the phone flat in front of you, top edge pointing where you face, then tap Calibrate. Walking also aligns it.",
          Rail.Dim,
          11,
      )
      calibrationNote?.let { Label(it, Rail.Brass, 12) }
    }

    Section("Story and voices") {
      Status(
          "Backend",
          if (!graph.backend.configured) {
            "not set (offline voices and replies)"
          } else {
            backendStatus ?: "checking…"
          },
          if (!graph.backend.configured) null else backendOk,
          warn = !graph.backend.configured,
      )
      prep.message?.let { Status("Prepared", it, !prep.busy) }
      if (prep.busy) {
        Label(
            "Stems ${prep.stemsLoaded}/${prep.stemsTotal}" +
              (prep.voices?.let { v -> " · voices ${v.done}/${v.total}" } ?: ""),
            Rail.Brass,
            12,
        )
      }
      RailButton(
          if (prep.busy) "Preparing…" else "Prepare transmissions",
          { game.prepareAll() },
          enabled = !prep.busy,
      )
      graph.missions.problems.take(5).forEach { problem ->
        Label("⚠ $problem", Rail.Brass, 11)
      }
    }

    if (mission.soundtracks.isNotEmpty()) {
      Section("Soundtrack") {
        Soundtracks.choices(mission, rewards.completions).forEach { choice ->
          if (choice.unlocked) {
            ChoiceRow(choice.title, choice.id == chosenSoundtrack) {
              graph.rewards.choose(choice.id)
            }
            if (choice.description.isNotBlank() && choice.id == chosenSoundtrack) {
              Label(choice.description, Rail.Dim, 11)
            }
          } else {
            val runsLabel = if (choice.unlockAfterCompletions == 1) {
              "a run"
            } else {
              "${choice.unlockAfterCompletions} runs"
            }
            Status(choice.title, "locked · finish $runsLabel to unlock", null)
          }
        }
        lockedTracks.forEach { track ->
          Status(
              track.title,
              if (plan == null) "premium · sign in below" else "premium · see Account",
              null,
          )
        }
        if (!prep.busy && prep.soundtrack != null && prep.soundtrack != activeMusic.title) {
          Label(
              "Prepare again to decode ${activeMusic.title.ifBlank { "this soundtrack" }} before the run starts.",
              Rail.Brass,
              11,
          )
        }
      }
    }

    AccountSection(graph)

    Section("Route") {
      val firstSignal = mission.signals.first()
      val currentFix = game.currentFix()
      Status("Start", firstSignal.place.ifBlank { firstSignal.name }, null)
      Status(
          "Location",
          settings.locationMode.label,
          settings.locationMode == LocationMode.GPS,
          warn = settings.locationMode == LocationMode.SIMULATED,
      )
      if (currentFix != null) {
        Status(
            "To first signal",
            "${Geo.distanceM(currentFix.position, firstSignal.location).toInt()} m",
            null,
        )
      }
      Status(
          "Venue placements",
          "${graph.missions.route()?.placements?.size ?: 0} of ${mission.signals.size}",
          null,
      )
    }

    val canResume = progress?.let { saved ->
      !saved.complete && saved.collected.isNotEmpty() && saved.missionId == mission.id
    } == true
    if (canResume) {
      RailButton(
          "Resume run · ${progress?.collected?.size}/${mission.signals.size}",
          {
            game.start(resume = true)
            go(Screen.RUN)
          },
          Modifier.fillMaxWidth(),
          Rail.Brass,
          enabled = perms.essentials,
      )
    }
    RailButton(
        "Start new run",
        {
          game.resetProgress()
          game.start(resume = false)
          go(Screen.RUN)
        },
        Modifier.fillMaxWidth(),
        enabled = perms.essentials,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      GhostButton("Operator", { go(Screen.OPERATOR) })
      GhostButton("Settings", { go(Screen.SETTINGS) })
      GhostButton("Messages", { go(Screen.ARCHIVE) })
    }
    if (!perms.essentials) {
      Label("Grant Bluetooth, location and microphone to start.", Rail.Brass, 12)
    }
  }
}

@Composable
private fun CapabilityStatus(label: String, capability: Capability, extra: String = "") {
  val (text, ok, warn) = when (capability.state) {
    CapState.OFF -> Triple("off", null, false)
    CapState.STARTING -> Triple(
        "starting" + if (capability.detail.isNotBlank()) " (${capability.detail})" else "",
        null,
        false,
    )
    CapState.ACTIVE -> Triple("active$extra", true, false)
    CapState.FAILED -> Triple("unavailable: ${capability.detail}", false, true)
  }
  Status(label, text, ok, warn = warn)
}
