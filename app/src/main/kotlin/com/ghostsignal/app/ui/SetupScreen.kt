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
  val route by graph.routing.route.collectAsStateWithLifecycle()
  val prep by game.prepare.collectAsStateWithLifecycle()
  val settings by graph.settings.flow.collectAsStateWithLifecycle()
  val mission by graph.missions.mission.collectAsStateWithLifecycle()
  val progress by graph.progress.progress.collectAsStateWithLifecycle()
  val rewards by graph.rewards.rewards.collectAsStateWithLifecycle()
  val locked by graph.missions.locked.collectAsStateWithLifecycle()
  val signedIn by graph.account.plan.collectAsStateWithLifecycle()
  val soundtrack = Soundtracks.resolve(mission, rewards.soundtrack, rewards.completions)
  val music = mission.withSoundtrack(soundtrack).music
  var backendStatus by remember { mutableStateOf<String?>(null) }
  var backendOk by remember { mutableStateOf<Boolean?>(null) }
  var testing by remember { mutableStateOf(game.audioTestRunning) }
  var heading by remember { mutableStateOf("—") }
  var calibration by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    graph.missions.syncIfStale(graph.scope)
    game.compass.start()
    if (graph.backend.configured) {
      runCatching { graph.backend.health() }
          .onSuccess {
            backendOk = it.ok
            backendStatus = "online" + (if (it.npc) " · agent" else "") + (if (it.tts) " · voices" else "")
          }
          .onFailure {
            backendOk = false
            backendStatus = it.message
          }
    }
    while (true) {
      val est = synchronized(game.fusion) { game.fusion.estimate(System.currentTimeMillis()) }
      heading = est.deg?.let { "${it.toInt()}° from ${est.source.name.lowercase()}" + if (est.glassesAligned) " · glasses aligned" else "" } ?: "no heading yet"
      delay(250)
    }
  }

  Column(
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Label("GHOST 93.3 // SIGNAL RUNNER", Neon.Magenta, 14, FontWeight.Bold)
    Label(mission.title, Neon.Text, 20, FontWeight.Bold)
    Label("${mission.signals.size} signals · ${music.title.ifBlank { "Original" }} · ${music.bpm.toInt()} BPM · ${mission.characters.size} voices", Neon.Dim, 12)

    Section("Permissions") {
      Status("Bluetooth", if (perms.bluetooth) "granted" else "needed", perms.bluetooth)
      Status("Location", if (perms.location) "granted" else "needed", perms.location)
      Status("Microphone", if (perms.microphone) "granted" else "needed", perms.microphone)
      Status("Notifications", if (perms.notifications) "granted" else "optional", if (perms.notifications) true else null)
      if (!perms.essentials) NeonButton("Grant permissions", onRequestPermissions)
    }

    Section("Meta glasses") {
      Status("Toolkit", if (glasses.mock) "mock glasses" else if (glasses.initialized) "ready" else "waiting for Bluetooth", glasses.initialized)
      Status("Meta AI link", glasses.registration, glasses.registered, warn = !glasses.registered)
      Status("Device", glasses.deviceName?.let { "$it${glasses.battery?.let { b -> " · $b%" } ?: ""}" } ?: "none", glasses.deviceName != null)
      Status("Worn", when (glasses.worn) { true -> "yes"; false -> "no"; null -> "unknown" }, glasses.worn)
      Status("Session", glasses.session, glasses.session == "started", warn = glasses.session == "paused")
      CapabilityStatus("Buttons", glasses.inputs)
      CapabilityStatus("Head tracking", glasses.motion, extra = if (glasses.imuRateHz > 0) " · ${glasses.imuRateHz.toInt()} Hz" else "")
      CapabilityStatus("Glasses speech", glasses.speech)
      Status("Glasses mic", glasses.micPermission, glasses.micPermission == "granted", warn = glasses.micPermission != "granted")
      glasses.lastInput?.let { Status("Last press", it, true) }
      glasses.error?.let { Label(it, Neon.Amber, 12) }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!glasses.registered) NeonButton("Connect via Meta AI", onRegister, enabled = glasses.initialized)
        else GhostButton("Disconnect app", onUnregister)
        if (glasses.registered && glasses.session != "started") GhostButton("Start session", { graph.glasses.connect() })
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (glasses.registered && glasses.micPermission != "granted") GhostButton("Allow glasses mic", onRequestGlassesMic)
        if (glasses.session == "started") GhostButton("Re-attach", { graph.glasses.reattach() })
      }
      Label("Press the capture button or tap the touchpad to test. Unavailable features fall back to the phone.", Neon.Dim, 11)
    }

    Section("Audio") {
      Status("Output", route.outputName, route.bluetoothOutput, warn = !route.bluetoothOutput)
      Status("Glasses mic link", if (route.glassesMicAvailable) "available" else "not found", route.glassesMicAvailable)
      Status("Engine", settings.engine.label, null)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!testing) GhostButton("Test spatial audio", {
          game.startAudioTest()
          testing = true
        }, enabled = !game.running)
        else NeonButton("Stop test", {
          game.stopAudioTest()
          testing = false
        })
      }
      if (testing) Label("A sonar ping is fixed 90° to your right. Turn your head toward it: it should swing to the front and stay put.", Neon.Amber, 12)
    }

    Section("Heading") {
      Status("Heading", heading, null)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Calibrate", { calibration = if (game.calibrateHeading()) "Aligned to the phone compass" else "Needs live glasses motion and the compass" })
      }
      Label("Hold the phone flat in front of you, top edge pointing where you face, then tap Calibrate. Walking also aligns it.", Neon.Dim, 11)
      calibration?.let { Label(it, Neon.Amber, 12) }
    }

    Section("Story and voices") {
      Status(
          "Backend",
          if (!graph.backend.configured) "not set (offline voices and replies)" else backendStatus ?: "checking…",
          if (!graph.backend.configured) null else backendOk,
          warn = !graph.backend.configured)
      prep.message?.let { Status("Prepared", it, !prep.busy) }
      if (prep.busy) {
        Label("Stems ${prep.stemsLoaded}/${prep.stemsTotal}" + (prep.voices?.let { " · voices ${it.done}/${it.total}" } ?: ""), Neon.Amber, 12)
      }
      NeonButton(if (prep.busy) "Preparing…" else "Prepare transmissions", { game.prepareAll() }, enabled = !prep.busy)
      val problems = graph.missions.problems
      if (problems.isNotEmpty()) problems.take(5).forEach { Label("⚠ $it", Neon.Amber, 11) }
    }

    if (mission.soundtracks.isNotEmpty()) {
      Section("Soundtrack") {
        Soundtracks.choices(mission, rewards.completions).forEach { c ->
          if (c.unlocked) {
            ChoiceRow(c.title, c.id == soundtrack) { graph.rewards.choose(c.id) }
            if (c.description.isNotBlank() && c.id == soundtrack) Label(c.description, Neon.Dim, 11)
          } else {
            val runs = if (c.unlockAfterCompletions == 1) "a run" else "${c.unlockAfterCompletions} runs"
            Status(c.title, "locked · finish $runs to unlock", null)
          }
        }
        locked.forEach { t -> Status(t.title, if (signedIn == null) "premium · sign in below" else "premium · see Account", null) }
        if (!prep.busy && prep.soundtrack != null && prep.soundtrack != music.title) {
          Label("Prepare again to decode ${music.title.ifBlank { "this soundtrack" }} before the run starts.", Neon.Amber, 11)
        }
      }
    }

    AccountSection(graph)

    Section("Route") {
      val first = mission.signals.first()
      val fix = game.currentFix()
      Status("Start", first.place.ifBlank { first.name }, null)
      Status("Location", settings.locationMode.label, settings.locationMode == LocationMode.GPS, warn = settings.locationMode == LocationMode.SIMULATED)
      if (fix != null) Status("To first signal", "${Geo.distanceM(fix.position, first.location).toInt()} m", null)
      Status("Venue placements", "${graph.missions.route()?.placements?.size ?: 0} of ${mission.signals.size}", null)
    }

    val resumable = progress?.let { !it.complete && it.collected.isNotEmpty() && it.missionId == mission.id } == true
    if (resumable) {
      NeonButton("Resume run · ${progress?.collected?.size}/${mission.signals.size}", {
        game.start(resume = true)
        go(Screen.RUN)
      }, Modifier.fillMaxWidth(), Neon.Magenta, enabled = perms.essentials)
    }
    NeonButton("Start new run", {
      game.resetProgress()
      game.start(resume = false)
      go(Screen.RUN)
    }, Modifier.fillMaxWidth(), enabled = perms.essentials)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      GhostButton("Operator", { go(Screen.OPERATOR) })
      GhostButton("Settings", { go(Screen.SETTINGS) })
      GhostButton("Messages", { go(Screen.ARCHIVE) })
    }
    if (!perms.essentials) Label("Grant Bluetooth, location and microphone to start.", Neon.Amber, 12)
  }
}

@Composable
private fun CapabilityStatus(label: String, cap: Capability, extra: String = "") {
  val text =
      when (cap.state) {
        CapState.OFF -> "off"
        CapState.STARTING -> "starting${if (cap.detail.isNotBlank()) " (${cap.detail})" else ""}"
        CapState.ACTIVE -> "active$extra"
        CapState.FAILED -> "unavailable: ${cap.detail}"
      }
  Status(label, text, if (cap.state == CapState.ACTIVE) true else if (cap.state == CapState.FAILED) false else null, warn = cap.state == CapState.FAILED)
}
