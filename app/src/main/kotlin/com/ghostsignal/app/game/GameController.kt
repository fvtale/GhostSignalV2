package com.ghostsignal.app.game

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ghostsignal.app.EngineChoice
import com.ghostsignal.app.LocationMode
import com.ghostsignal.app.Settings
import com.ghostsignal.app.VoiceInputMode
import com.ghostsignal.app.audio.AudioEngine
import com.ghostsignal.app.audio.BinauralMixer
import com.ghostsignal.app.audio.CommsMode
import com.ghostsignal.app.audio.FmodAudioEngine
import com.ghostsignal.app.audio.Pcm
import com.ghostsignal.app.audio.PcmDecoder
import com.ghostsignal.app.audio.UiSfx
import com.ghostsignal.app.audio.VoiceParams
import com.ghostsignal.app.data.MissionRepository
import com.ghostsignal.app.data.ProgressStore
import com.ghostsignal.app.data.RewardsStore
import com.ghostsignal.app.glasses.CapState
import com.ghostsignal.app.glasses.GlassesManager
import com.ghostsignal.app.glasses.MediaButtonInput
import com.ghostsignal.app.location.GpsLocation
import com.ghostsignal.app.location.PhoneCompass
import com.ghostsignal.app.location.SimulatedLocation
import com.ghostsignal.app.net.BackendClient
import com.ghostsignal.app.voice.AndroidSpeechInput
import com.ghostsignal.app.voice.AudioRouting
import com.ghostsignal.app.voice.DatSpeechInput
import com.ghostsignal.app.voice.LineVoices
import com.ghostsignal.app.voice.ListenCallbacks
import com.ghostsignal.app.voice.PrepareProgress
import com.ghostsignal.app.voice.SpeechInput
import com.ghostsignal.app.voice.VoiceRecorder
import com.ghostsignal.core.engine.AudioScene
import com.ghostsignal.core.engine.AudioSceneComposer
import com.ghostsignal.core.engine.Effect
import com.ghostsignal.core.engine.GameEngine
import com.ghostsignal.core.engine.GameEvent
import com.ghostsignal.core.engine.Phase
import com.ghostsignal.core.engine.PlayerAction
import com.ghostsignal.core.engine.Sfx
import com.ghostsignal.core.engine.TranscriptEntry
import com.ghostsignal.core.engine.Zone
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.geo.LatLon
import com.ghostsignal.core.heading.HeadingEstimate
import com.ghostsignal.core.heading.HeadingFusion
import com.ghostsignal.core.heading.HeadingSource
import com.ghostsignal.core.model.Mission
import com.ghostsignal.core.model.Soundtracks
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class SpokenLine(val speaker: String, val text: String)

data class RunUi(
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val objective: String = "",
    val signalIndex: Int = 0,
    val total: Int = 0,
    val signalName: String? = null,
    val signalPlace: String? = null,
    val collected: List<String> = emptyList(),
    val stems: List<Pair<String, Boolean>> = emptyList(),
    val distanceM: Double? = null,
    val bearingDeg: Double? = null,
    val strength: Double = 0.0,
    val zone: Zone? = null,
    val lockAvailable: Boolean = false,
    val paused: Boolean = false,
    val heading: HeadingEstimate = HeadingEstimate(null, HeadingSource.NONE, false),
    val speaking: SpokenLine? = null,
    val listening: Boolean = false,
    val partial: String? = null,
    val recording: Boolean = false,
    val rhythmOpen: Boolean = false,
    val transcript: List<TranscriptEntry> = emptyList(),
    val fix: GeoFix? = null,
    val voiceInput: String = "",
    val audioEngine: String = "",
    val soundtrack: String = "",
    /** Soundtracks this run unlocked by finishing. */
    val unlocked: List<String> = emptyList(),
    val note: String? = null,
)

data class PrepareState(
    val busy: Boolean = false,
    val stemsLoaded: Int = 0,
    val stemsTotal: Int = 0,
    val voices: PrepareProgress? = null,
    val message: String? = null,
    /** Title of the soundtrack these stems belong to. */
    val soundtrack: String? = null,
)

/**
 * Runs a mission: owns the [GameEngine] and wires it to the glasses, GPS, compass, audio, voices,
 * recorder and backend. Every engine call happens on one serial dispatcher, so the engine never
 * sees concurrent events; slow work (TTS, network, recognition) runs elsewhere and reports back
 * through the event channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameController(
    private val context: Context,
    private val settings: Settings,
    private val missions: MissionRepository,
    private val progressStore: ProgressStore,
    private val rewards: RewardsStore,
    private val backend: BackendClient,
    val glasses: GlassesManager,
    val routing: AudioRouting,
    private val voices: LineVoices,
    val recorder: VoiceRecorder,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val engineThread = Dispatchers.Default.limitedParallelism(1)

  private val _ui = MutableStateFlow(RunUi())
  val ui: StateFlow<RunUi> = _ui
  private val _prepare = MutableStateFlow(PrepareState())
  val prepare: StateFlow<PrepareState> = _prepare

  val fusion = HeadingFusion()
  val sim = SimulatedLocation(scope) { onFix(it) }
  private val gps = GpsLocation(context) { onFix(it) }
  val compass = PhoneCompass(context) { onAzimuth(it) }
  private val mediaButtons = MediaButtonInput(context) { action, _ -> onMediaButton(action) }
  private val datSpeech = DatSpeechInput(glasses, scope)
  private val glassesMicRecognizer = AndroidSpeechInput(context, routing, useGlassesMic = true)
  private val phoneMicRecognizer = AndroidSpeechInput(context, routing, useGlassesMic = false)

  private var events = Channel<GameEvent>(Channel.UNLIMITED)
  private var engine: GameEngine? = null
  private var mission: Mission? = null
  private var audio: AudioEngine? = null
  /** Decoded stems, keyed by their files so switching soundtracks never reuses the wrong set. */
  private var stems: Pair<List<String>, Map<String, Pcm>>? = null
  private var theme: Pair<String, Pcm>? = null
  private var completionRecorded = false
  private val jobs = mutableListOf<Job>()
  private var speakJob: Job? = null
  private var speakSeq = 0
  @Volatile private var voiceActive = false
  @Volatile private var micActive = false
  private var listenInput: SpeechInput? = null
  @Volatile private var lastFix: GeoFix? = null
  @Volatile var phoneInHand = false
  @Volatile private var lastUiMs = 0L
  private var declinationSet = false
  private var testAudio: AudioEngine? = null

  val running: Boolean
    get() = engine != null

  // ------------------------------------------------------------------ preparation

  /** The mission as the next run will play it: the runner's soundtrack if it's unlocked, else the original. */
  fun nextRunMission(): Mission {
    val base = missions.mission.value
    val r = rewards.value
    return base.withSoundtrack(Soundtracks.resolve(base, r.soundtrack, r.completions))
  }

  /** Decodes the soundtrack and renders every authored line. Safe to call again. */
  fun prepareAll() {
    if (_prepare.value.busy) return
    scope.launch(Dispatchers.IO) {
      val m = nextRunMission()
      _prepare.value = PrepareState(busy = true, stemsTotal = m.music.stems.size, message = "Loading soundtrack", soundtrack = m.music.title)
      loadStems(m)
      loadTheme(m)
      _prepare.value = _prepare.value.copy(message = "Rendering voices")
      val result =
          voices.prepareAll(m) { p -> _prepare.value = _prepare.value.copy(voices = p) }
      val where = if (result.cloud > 0) "${result.cloud} cloud, ${result.local} on-phone" else "${result.local} on-phone"
      _prepare.value =
          _prepare.value.copy(
              busy = false,
              voices = result,
              message = "Ready: ${stems?.second?.size ?: 0} stems, $where voices" + if (result.failed > 0) ", ${result.failed} failed" else "")
    }
  }

  private fun loadStems(m: Mission): Map<String, Pcm> {
    val files = m.music.stems.map { it.file }
    stems?.let { (key, loaded) -> if (key == files) return loaded }
    stems = null // let the previous soundtrack go before decoding the next one
    val loaded = mutableMapOf<String, Pcm>()
    m.music.stems.forEach { spec ->
      try {
        // Deployed stems were downloaded to the phone; bundled ones are in the app.
        loaded[spec.id] = missions.localFile(spec)?.let { PcmDecoder.decodeFile(it) } ?: PcmDecoder.decodeAsset(context, spec.file)
      } catch (e: Exception) {
        Log.w(TAG, "stem ${spec.file} failed to decode", e)
      }
      _prepare.value = _prepare.value.copy(stemsLoaded = loaded.size)
    }
    stems = files to loaded
    return loaded
  }

  /** The antagonist's theme, decoded once. Null when the mission has none or it fails to decode. */
  private fun loadTheme(m: Mission): Pcm? {
    val file = m.antagonist?.theme ?: return null
    theme?.let { (key, pcm) -> if (key == file) return pcm }
    return try {
      PcmDecoder.decodeAsset(context, file).also { theme = file to it }
    } catch (e: Exception) {
      Log.w(TAG, "theme $file failed to decode", e)
      null
    }
  }

  // ------------------------------------------------------------------ lifecycle

  fun start(resume: Boolean) {
    if (running) return
    stopAudioTest()
    val m = nextRunMission()
    mission = m
    // If finishing this run unlocks something, Vesper announces it after the outro.
    val reward = Soundtracks.unlockedByNextCompletion(m, rewards.value.completions).flatMap { it.unlockLines }
    val eng = GameEngine(m, reward)
    engine = eng
    completionRecorded = false
    events = Channel(Channel.UNLIMITED)
    val s = settings.value
    fusion.mode = s.yawMode
    fusion.invertYaw = s.invertYaw

    val a = createAudio(s.engine)
    audio = a
    a.setMasterVolume(s.masterVolume)
    a.start()

    context.startForegroundService(Intent(context, GameService::class.java))
    mediaButtons.setActive(true)
    compass.start()
    if (s.locationMode == LocationMode.SIMULATED) sim.start() else gps.start()

    jobs += scope.launch(engineThread) { for (event in events) process(event) }
    jobs +=
        scope.launch {
          a.loadStems(m.music, withContext(Dispatchers.IO) { loadStems(m) })
          a.loadTheme(withContext(Dispatchers.IO) { loadTheme(m) }, m.antagonist?.gainDb ?: 0.0)
          val progress = if (resume) progressStore.progress.value else null
          events.send(GameEvent.Start(System.currentTimeMillis(), progress))
        }
    jobs +=
        scope.launch {
          while (isActive) {
            val now = System.currentTimeMillis()
            events.trySend(GameEvent.Heading(synchronized(fusion) { fusion.estimate(now) }))
            events.trySend(GameEvent.Tick(now))
            delay(50)
          }
        }
    jobs += scope.launch { glasses.inputs.collect { send(GameEvent.Input(it.action, System.currentTimeMillis(), it.deviceTimeMs)) } }
    jobs +=
        scope.launch {
          glasses.imu.collect { sample ->
            val now = System.currentTimeMillis()
            val est = synchronized(fusion) {
              fusion.onImu(sample, now)
              fusion.estimate(now)
            }
            if (est.source == HeadingSource.GLASSES) audio?.setListenerHeading(est.deg)
          }
        }
    jobs += scope.launch { pollBroadcasts() }
    _ui.value = RunUi(running = true, audioEngine = a.name, soundtrack = m.music.title)
  }

  fun stop() {
    jobs.forEach { it.cancel() }
    jobs.clear()
    speakJob?.cancel()
    listenInput?.stop()
    listenInput = null
    recorder.stop()
    routing.releaseMic()
    gps.stop()
    sim.stop()
    compass.stop()
    mediaButtons.setActive(false)
    audio?.release()
    audio = null
    engine = null
    events.close()
    voiceActive = false
    micActive = false
    context.stopService(Intent(context, GameService::class.java))
    _ui.value = RunUi()
  }

  fun resetProgress() = progressStore.clear()

  private fun createAudio(choice: EngineChoice): AudioEngine =
      when (choice) {
        EngineChoice.BUILT_IN -> BinauralMixer()
        EngineChoice.FMOD, EngineChoice.AUTO ->
            if (FmodAudioEngine.isAvailable()) FmodAudioEngine(context) else BinauralMixer()
      }

  // ------------------------------------------------------------------ inputs

  fun send(event: GameEvent) {
    events.trySend(event)
  }

  fun input(action: PlayerAction) = send(GameEvent.Input(action, System.currentTimeMillis()))

  private fun onMediaButton(action: PlayerAction) {
    // When DAT Inputs is live the glasses report presses directly; avoid doubles.
    if (glasses.state.value.inputs.state == CapState.ACTIVE) return
    input(action)
  }

  private fun onFix(fix: GeoFix) {
    if (fix.simulated != (settings.value.locationMode == LocationMode.SIMULATED)) return
    lastFix = fix
    if (!declinationSet) {
      compass.updateDeclination(fix.position)
      declinationSet = true
    }
    synchronized(fusion) { fusion.onCourse(fix.courseDeg, fix.speedMps, fix.timeMs) }
    send(GameEvent.Position(fix))
  }

  private fun onAzimuth(az: Double) {
    val now = System.currentTimeMillis()
    val est = synchronized(fusion) {
      fusion.onPhoneAzimuth(az, now, phoneInHand)
      fusion.estimate(now)
    }
    if (est.source == HeadingSource.PHONE) {
      audio?.setListenerHeading(est.deg)
      testAudio?.setListenerHeading(est.deg)
    }
  }

  /** Ties glasses yaw to the phone compass. Hold the phone flat, pointing where you face. */
  fun calibrateHeading(): Boolean = synchronized(fusion) { fusion.calibrateToPhone(System.currentTimeMillis()) }

  fun applyHeadingSettings() {
    val s = settings.value
    synchronized(fusion) {
      fusion.mode = s.yawMode
      fusion.invertYaw = s.invertYaw
      fusion.resetAlignment()
    }
  }

  // ------------------------------------------------------------------ engine loop

  private fun process(event: GameEvent) {
    val eng = engine ?: return
    val effects = eng.handle(event)
    for (e in effects) {
      try {
        execute(eng, e)
      } catch (ex: Exception) {
        Log.e(TAG, "effect $e failed", ex)
      }
    }
    if (event is GameEvent.Tick) {
      audio?.setScene(AudioSceneComposer.compose(eng))
      if (event.nowMs - lastUiMs >= 100) publish(eng)
    } else if (event !is GameEvent.Heading) {
      publish(eng)
    }
  }

  private fun execute(eng: GameEngine, effect: Effect) {
    val a = audio ?: return
    when (effect) {
      is Effect.Speak -> speak(eng, effect)
      Effect.StopSpeaking -> {
        speakJob?.cancel()
        speakJob = null
        voiceActive = false
        updateComms()
        a.stopVoice()
        _ui.update { it.copy(speaking = null) }
      }
      is Effect.PlaySfx -> {
        val bearing =
            if (effect.sfx == Sfx.SIGNAL_ACQUIRED || effect.sfx == Sfx.LOCK_AVAILABLE) eng.reading?.bearingDeg else null
        a.playSfx(effect.sfx, bearing)
      }
      is Effect.Listen -> listen(effect)
      Effect.StopListening -> listenInput?.stop()
      is Effect.AskCharacter -> {
        val m = mission ?: return
        scope.launch(Dispatchers.IO) {
          val reply =
              if (!backend.configured) null
              else
                  try {
                    withTimeout(10_000) { backend.askCharacter(m, effect.request) }
                  } catch (e: Exception) {
                    Log.w(TAG, "character agent failed: ${e.message}")
                    null
                  }
          send(GameEvent.CharacterReplied(effect.request.requestId, reply))
        }
      }
      is Effect.StartRecording -> {
        micActive = true
        updateComms()
        recorder.start(effect.signalId, effect.maxSeconds) { file ->
          micActive = false
          updateComms()
          send(GameEvent.RecordingFinished(file?.absolutePath))
        }
      }
      Effect.StopRecording -> recorder.stop()
      is Effect.PlayRhythm -> a.playRhythm(effect.onsetsBeats) { send(GameEvent.RhythmPlayed(effect.token, System.currentTimeMillis())) }
      is Effect.UnlockStem -> Unit // the audio scene brings it in on the next bar
      is Effect.Persist -> {
        progressStore.save(effect.progress)
        if (effect.progress.complete && !completionRecorded) {
          completionRecorded = true
          val unlocked = rewards.recordCompletion(eng.mission)
          if (unlocked.isNotEmpty()) _ui.update { it.copy(unlocked = unlocked.map { u -> u.title }) }
        }
      }
    }
  }

  private fun speak(eng: GameEngine, effect: Effect.Speak) {
    val m = mission ?: return
    speakJob?.cancel()
    val id = ++speakSeq
    speakJob =
        scope.launch {
          voiceActive = true
          updateComms()
          try {
            for (u in effect.utterances) {
              val pcm =
                  try {
                    withContext(Dispatchers.IO) { voices.render(m, u) }
                  } catch (e: Exception) {
                    Log.w(TAG, "voice failed: ${e.message}")
                    continue
                  }
              val character = m.character(u.speaker)
              val bearing =
                  u.fromSignal?.let { sid -> m.signal(sid) }?.let { s -> lastFix?.let { Geo.bearingDeg(it.position, s.location) } }
              _ui.update { it.copy(speaking = SpokenLine(character?.name ?: u.speaker, u.text)) }
              suspendCancellableCoroutine { cont ->
                audio?.playVoice(pcm, VoiceParams(bearing, character?.radio ?: 0.4)) { if (cont.isActive) cont.resume(Unit) }
                    ?: cont.resume(Unit)
              }
            }
            send(GameEvent.SpeechFinished(effect.token))
          } finally {
            if (speakSeq == id) {
              voiceActive = false
              updateComms()
              _ui.update { it.copy(speaking = null) }
            }
          }
        }
  }

  private fun listen(effect: Effect.Listen) {
    val input = chooseSpeechInput()
    listenInput = input
    micActive = true
    updateComms()
    audio?.playUiSfx(UiSfx.MIC_OPEN)
    _ui.update { it.copy(listening = true, partial = null, voiceInput = input.name) }
    input.start(
        effect.mode,
        effect.timeoutMs,
        effect.hints,
        object : ListenCallbacks {
          override fun onText(text: String, isFinal: Boolean, confidence: Double) {
            _ui.update { it.copy(partial = text) }
            send(GameEvent.Heard(effect.token, text, isFinal, confidence))
          }

          override fun onEnded(reason: String) {
            micActive = false
            updateComms()
            audio?.playUiSfx(UiSfx.MIC_CLOSE)
            _ui.update { it.copy(listening = false) }
            send(GameEvent.ListenEnded(effect.token))
          }
        })
  }

  private fun chooseSpeechInput(): SpeechInput =
      when (settings.value.voiceInput) {
        VoiceInputMode.AUTO, VoiceInputMode.GLASSES -> if (datSpeech.isAvailable()) datSpeech else glassesMicRecognizer
        VoiceInputMode.PHONE_RECOGNIZER -> glassesMicRecognizer
        VoiceInputMode.PHONE_MIC -> phoneMicRecognizer
      }

  private fun updateComms() {
    audio?.setComms(
        when {
          micActive -> CommsMode.MIC
          voiceActive -> CommsMode.VOICE
          else -> CommsMode.NONE
        })
  }

  private suspend fun pollBroadcasts() {
    var since = System.currentTimeMillis()
    while (true) {
      delay(10_000)
      if (!backend.configured) continue
      try {
        val list = backend.broadcasts(since)
        list.sortedBy { it.createdAtMs }.forEach {
          send(GameEvent.Broadcast(it.speaker, it.text))
          since = maxOf(since, it.createdAtMs)
        }
      } catch (e: Exception) {
        Log.d(TAG, "broadcast poll failed: ${e.message}")
      }
    }
  }

  private fun publish(eng: GameEngine) {
    lastUiMs = System.currentTimeMillis()
    val m = eng.mission
    val signal = eng.currentSignal
    val r = eng.reading
    _ui.update {
      it.copy(
            running = true,
            phase = eng.phase,
            objective = eng.objective(),
            signalIndex = eng.signalIndex,
            total = eng.total,
            signalName = signal?.name,
            signalPlace = signal?.place,
            collected = eng.collected.toList(),
            stems = m.signals.map { s -> (m.music.stems.firstOrNull { it.id == s.stem }?.name ?: s.stem) to (s.id in eng.collected) },
            distanceM = r?.distanceM,
            bearingDeg = r?.bearingDeg,
            strength = r?.strength ?: 0.0,
            zone = r?.zone,
            lockAvailable = eng.lockAvailable,
            paused = eng.paused,
            heading = eng.heading,
            recording = eng.recording,
            rhythmOpen = eng.rhythmTaps != null,
            transcript = eng.transcript.toList().takeLast(8),
            fix = lastFix,
        )
    }
  }

  // ------------------------------------------------------------------ operator tools

  fun forceLock() = send(GameEvent.ForceLock)

  fun forceSolve() = send(GameEvent.ForceSolve)

  fun jumpTo(index: Int) = send(GameEvent.JumpTo(index))

  fun walkToCurrentSignal() {
    val target = engine?.currentSignal?.location ?: missions.mission.value.signals.firstOrNull()?.location ?: return
    sim.walkTo(target)
  }

  fun currentFix(): GeoFix? = lastFix

  /** Switches between real GPS and the simulator, live if a run is going. */
  fun setLocationMode(mode: LocationMode) {
    settings.update { it.copy(locationMode = mode) }
    if (!running) return
    if (mode == LocationMode.SIMULATED) {
      gps.stop()
      lastFix?.let { sim.teleport(it.position) }
      sim.start()
    } else {
      sim.stop()
      gps.start()
    }
  }

  /** Plays a sonar ping fixed in the world 90° to your right, to check binaural rendering and head tracking. */
  fun startAudioTest() {
    if (running || testAudio != null) return
    val a = createAudio(settings.value.engine)
    testAudio = a
    a.start()
    compass.start()
    val start = synchronized(fusion) { fusion.estimate(System.currentTimeMillis()).deg } ?: compass.lastAzimuth ?: 0.0
    a.setScene(AudioScene(emptyMap(), 0.0, null, 900, Geo.normalizeDeg(start + 90), paused = false))
    val job =
        scope.launch {
          glasses.imu.collect { sample ->
            val now = System.currentTimeMillis()
            val est = synchronized(fusion) {
              fusion.onImu(sample, now)
              fusion.estimate(now)
            }
            a.setListenerHeading(est.deg)
          }
        }
    testJob = job
  }

  private var testJob: Job? = null

  fun stopAudioTest() {
    testJob?.cancel()
    testJob = null
    testAudio?.release()
    testAudio = null
    if (!running) compass.stop()
  }

  val audioTestRunning: Boolean
    get() = testAudio != null

  fun teleportToSignal(index: Int) {
    missions.mission.value.signals.getOrNull(index)?.let { sim.teleport(it.location) }
  }

  fun simulatedStart(): LatLon = missions.mission.value.signals.first().location

  companion object {
    private const val TAG = "GhostSignalGame"
  }
}
