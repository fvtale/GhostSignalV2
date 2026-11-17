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
import com.ghostsignal.app.voice.ServerSpeechInput
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
    /** Soundtrack titles this run unlocked by finishing. */
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
 * Runs a mission: owns the [GameEngine] and wires it to the glasses, GPS,
 * compass, audio, voices, recorder, and backend. Every engine call happens
 * on one serial dispatcher so the engine never sees concurrent events;
 * slow work (TTS, network, recognition) runs elsewhere and reports back
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
  private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)

  private val mutableUi = MutableStateFlow(RunUi())
  val ui: StateFlow<RunUi> = mutableUi

  private val mutablePrepare = MutableStateFlow(PrepareState())
  val prepare: StateFlow<PrepareState> = mutablePrepare

  val fusion = HeadingFusion()
  val sim = SimulatedLocation(scope) { fix -> onLocationFix(fix) }
  private val gps = GpsLocation(context) { fix -> onLocationFix(fix) }
  val compass = PhoneCompass(context) { azimuth -> onCompassAzimuth(azimuth) }
  private val mediaButtons = MediaButtonInput(context) { action, _ -> onMediaButton(action) }

  private val datSpeechInput = DatSpeechInput(glasses, scope)
  private val glassesMicRecognizer = AndroidSpeechInput(context, routing, useGlassesMic = true)
  private val phoneMicRecognizer = AndroidSpeechInput(context, routing, useGlassesMic = false)
  private val serverSpeechInput = ServerSpeechInput(context, backend)

  private var eventChannel = Channel<GameEvent>(Channel.UNLIMITED)
  private var engine: GameEngine? = null
  private var activeMission: Mission? = null
  private var audioEngine: AudioEngine? = null

  /** Decoded stems, keyed by file list so soundtrack switches never reuse the wrong set. */
  private var decodedStems: Pair<List<String>, Map<String, Pcm>>? = null
  private var decodedTheme: Pair<String, Pcm>? = null
  private var completionRecorded = false
  private val runJobs = mutableListOf<Job>()
  private var speechJob: Job? = null
  private var speechSequence = 0

  @Volatile
  private var voiceChannelActive = false

  @Volatile
  private var micChannelActive = false

  private var activeSpeechInput: SpeechInput? = null

  @Volatile
  private var latestFix: GeoFix? = null

  @Volatile
  var phoneInHand: Boolean = false

  @Volatile
  private var lastUiPublishMs = 0L

  private var declinationInitialized = false
  private var testAudioEngine: AudioEngine? = null
  private var testAudioJob: Job? = null

  val running: Boolean
    get() = engine != null

  // -- preparation --------------------------------------------------------------------

  /** The mission as the next run plays it: chosen soundtrack if unlocked, else original. */
  fun nextRunMission(): Mission {
    val baseMission = missions.mission.value
    val currentRewards = rewards.value
    val chosen = Soundtracks.resolve(baseMission, currentRewards.soundtrack, currentRewards.completions)
    return baseMission.withSoundtrack(chosen)
  }

  /** Decodes the soundtrack and renders every authored line. Safe to call again. */
  fun prepareAll() {
    if (mutablePrepare.value.busy) return
    scope.launch(Dispatchers.IO) {
      val mission = nextRunMission()
      mutablePrepare.value = PrepareState(
          busy = true,
          stemsTotal = mission.music.stems.size,
          message = "Loading soundtrack",
          soundtrack = mission.music.title,
      )
      decodeStems(mission)
      decodeTheme(mission)

      mutablePrepare.value = mutablePrepare.value.copy(message = "Rendering voices")
      val voiceResult = voices.prepareAll(mission) { progress ->
        mutablePrepare.value = mutablePrepare.value.copy(voices = progress)
      }
      val voiceSummary = if (voiceResult.cloud > 0) {
        "${voiceResult.cloud} cloud, ${voiceResult.local} on-phone"
      } else {
        "${voiceResult.local} on-phone"
      }
      val failureNote = if (voiceResult.failed > 0) ", ${voiceResult.failed} failed" else ""
      mutablePrepare.value = mutablePrepare.value.copy(
          busy = false,
          voices = voiceResult,
          message = "Ready: ${decodedStems?.second?.size ?: 0} stems, $voiceSummary voices$failureNote",
      )
    }
  }

  private fun decodeStems(mission: Mission): Map<String, Pcm> {
    val stemFiles = mission.music.stems.map { it.file }
    decodedStems?.let { (cachedFiles, cachedStems) ->
      if (cachedFiles == stemFiles) return cachedStems
    }
    decodedStems = null // Release the previous soundtrack before decoding the next.

    val loaded = mutableMapOf<String, Pcm>()
    for (spec in mission.music.stems) {
      try {
        val downloadedFile = missions.localFile(spec)
        loaded[spec.id] = if (downloadedFile != null) {
          PcmDecoder.decodeFile(downloadedFile)
        } else {
          PcmDecoder.decodeAsset(context, spec.file)
        }
      } catch (error: Exception) {
        Log.w(TAG, "stem ${spec.file} failed to decode", error)
      }
      mutablePrepare.value = mutablePrepare.value.copy(stemsLoaded = loaded.size)
    }
    decodedStems = stemFiles to loaded
    return loaded
  }

  /** The antagonist theme, decoded once. Null when absent or undecodable. */
  private fun decodeTheme(mission: Mission): Pcm? {
    val themeFile = mission.antagonist?.theme ?: return null
    decodedTheme?.let { (cachedFile, cachedPcm) ->
      if (cachedFile == themeFile) return cachedPcm
    }
    return try {
      PcmDecoder.decodeAsset(context, themeFile).also { decoded ->
        decodedTheme = themeFile to decoded
      }
    } catch (error: Exception) {
      Log.w(TAG, "theme $themeFile failed to decode", error)
      null
    }
  }

  // -- run lifecycle ---------------------------------------------------------------------

  fun start(resume: Boolean) {
    if (running) return
    stopAudioTest()

    val mission = nextRunMission()
    activeMission = mission
    // Lines announcing whatever finishing this run unlocks, after the outro.
    val nextRewards = rewards.value
    val rewardLines = Soundtracks.unlockedByNextCompletion(mission, nextRewards.completions)
        .flatMap { it.unlockLines }
    val newEngine = GameEngine(mission, rewardLines)
    engine = newEngine
    completionRecorded = false
    eventChannel = Channel(Channel.UNLIMITED)

    val currentSettings = settings.value
    fusion.mode = currentSettings.yawMode
    fusion.invertYaw = currentSettings.invertYaw

    val newAudio = createAudioEngine(currentSettings.engine)
    audioEngine = newAudio
    newAudio.setMasterVolume(currentSettings.masterVolume)
    newAudio.start()

    context.startForegroundService(Intent(context, GameService::class.java))
    mediaButtons.setActive(true)
    compass.start()
    if (currentSettings.locationMode == LocationMode.SIMULATED) sim.start() else gps.start()

    runJobs += scope.launch(engineDispatcher) {
      for (event in eventChannel) processEvent(event)
    }
    runJobs += scope.launch {
      newAudio.loadStems(mission.music, withContext(Dispatchers.IO) { decodeStems(mission) })
      newAudio.loadTheme(
          withContext(Dispatchers.IO) { decodeTheme(mission) },
          mission.antagonist?.gainDb ?: 0.0,
      )
      val savedProgress = if (resume) progressStore.progress.value else null
      eventChannel.send(GameEvent.Start(System.currentTimeMillis(), savedProgress))
    }
    runJobs += scope.launch {
      while (isActive) {
        val nowMs = System.currentTimeMillis()
        eventChannel.trySend(GameEvent.Heading(synchronized(fusion) { fusion.estimate(nowMs) }))
        eventChannel.trySend(GameEvent.Tick(nowMs))
        delay(50)
      }
    }
    runJobs += scope.launch {
      glasses.inputs.collect { glassesInput ->
        send(GameEvent.Input(glassesInput.action, System.currentTimeMillis(), glassesInput.deviceTimeMs))
      }
    }
    runJobs += scope.launch {
      glasses.imu.collect { sample ->
        val nowMs = System.currentTimeMillis()
        val estimate = synchronized(fusion) {
          fusion.onImu(sample, nowMs)
          fusion.estimate(nowMs)
        }
        if (estimate.source == HeadingSource.GLASSES) {
          audioEngine?.setListenerOrientation(estimate.deg, estimate.pitchDeg)
        }
      }
    }
    runJobs += scope.launch { pollBroadcasts() }

    mutableUi.value = RunUi(running = true, audioEngine = newAudio.name, soundtrack = mission.music.title)
  }

  fun stop() {
    runJobs.forEach { it.cancel() }
    runJobs.clear()
    speechJob?.cancel()
    activeSpeechInput?.stop()
    activeSpeechInput = null
    recorder.stop()
    routing.releaseMic()
    gps.stop()
    sim.stop()
    compass.stop()
    mediaButtons.setActive(false)
    audioEngine?.release()
    audioEngine = null
    engine = null
    eventChannel.close()
    voiceChannelActive = false
    micChannelActive = false
    context.stopService(Intent(context, GameService::class.java))
    mutableUi.value = RunUi()
  }

  fun resetProgress() {
    progressStore.clear()
  }

  private fun createAudioEngine(choice: EngineChoice): AudioEngine = when (choice) {
    EngineChoice.BUILT_IN -> BinauralMixer()
    EngineChoice.FMOD, EngineChoice.AUTO ->
        if (FmodAudioEngine.isAvailable()) FmodAudioEngine(context) else BinauralMixer()
  }

  // -- inputs ------------------------------------------------------------------------------

  fun send(event: GameEvent) {
    eventChannel.trySend(event)
  }

  fun input(action: PlayerAction) {
    send(GameEvent.Input(action, System.currentTimeMillis()))
  }

  private fun onMediaButton(action: PlayerAction) {
    // With DAT Inputs live, the glasses report presses directly; avoid doubles.
    if (glasses.state.value.inputs.state == CapState.ACTIVE) return
    input(action)
  }

  private fun onLocationFix(fix: GeoFix) {
    val simulatedExpected = settings.value.locationMode == LocationMode.SIMULATED
    if (fix.simulated != simulatedExpected) return
    latestFix = fix
    if (!declinationInitialized) {
      compass.updateDeclination(fix.position)
      declinationInitialized = true
    }
    synchronized(fusion) { fusion.onCourse(fix.courseDeg, fix.speedMps, fix.timeMs) }
    send(GameEvent.Position(fix))
  }

  private fun onCompassAzimuth(azimuthDeg: Double) {
    val nowMs = System.currentTimeMillis()
    val estimate = synchronized(fusion) {
      fusion.onPhoneAzimuth(azimuthDeg, nowMs, phoneInHand)
      fusion.estimate(nowMs)
    }
    if (estimate.source == HeadingSource.PHONE) {
      audioEngine?.setListenerOrientation(estimate.deg, estimate.pitchDeg)
      testAudioEngine?.setListenerOrientation(estimate.deg, estimate.pitchDeg)
    }
  }

  /** Ties glasses yaw to the phone compass. Hold the phone flat, facing where you look. */
  fun calibrateHeading(): Boolean =
      synchronized(fusion) { fusion.calibrateToPhone(System.currentTimeMillis()) }

  fun applyHeadingSettings() {
    val currentSettings = settings.value
    synchronized(fusion) {
      fusion.mode = currentSettings.yawMode
      fusion.invertYaw = currentSettings.invertYaw
      fusion.resetAlignment()
    }
  }

  // -- engine loop ----------------------------------------------------------------------------

  private fun processEvent(event: GameEvent) {
    val activeEngine = engine ?: return
    val effects = activeEngine.handle(event)
    for (effect in effects) {
      try {
        executeEffect(activeEngine, effect)
      } catch (error: Exception) {
        Log.e(TAG, "effect $effect failed", error)
      }
    }
    when {
      event is GameEvent.Tick -> {
        audioEngine?.setScene(AudioSceneComposer.compose(activeEngine))
        if (event.nowMs - lastUiPublishMs >= 100) publishUi(activeEngine)
      }
      event !is GameEvent.Heading -> publishUi(activeEngine)
    }
  }

  private fun executeEffect(activeEngine: GameEngine, effect: Effect) {
    val audio = audioEngine ?: return
    when (effect) {
      is Effect.Speak -> speakLines(activeEngine, effect)
      Effect.StopSpeaking -> {
        speechJob?.cancel()
        speechJob = null
        voiceChannelActive = false
        refreshCommsMode()
        audio.stopVoice()
        mutableUi.update { it.copy(speaking = null) }
      }
      is Effect.PlaySfx -> {
        val locatedCues = effect.sfx == Sfx.SIGNAL_ACQUIRED || effect.sfx == Sfx.LOCK_AVAILABLE
        audio.playSfx(effect.sfx, if (locatedCues) activeEngine.reading?.bearingDeg else null)
      }
      is Effect.Listen -> startListening(effect)
      Effect.StopListening -> activeSpeechInput?.stop()
      is Effect.AskCharacter -> askCharacterAgent(effect)
      is Effect.StartRecording -> {
        micChannelActive = true
        refreshCommsMode()
        recorder.start(effect.signalId, effect.maxSeconds) { recordedFile ->
          micChannelActive = false
          refreshCommsMode()
          send(GameEvent.RecordingFinished(recordedFile?.absolutePath))
        }
      }
      Effect.StopRecording -> recorder.stop()
      is Effect.PlayRhythm -> audio.playRhythm(effect.onsetsBeats) {
        send(GameEvent.RhythmPlayed(effect.token, System.currentTimeMillis()))
      }
      is Effect.UnlockStem -> Unit // The audio scene brings it in on the next bar.
      is Effect.Persist -> persistProgress(activeEngine, effect.progress)
    }
  }

  private fun askCharacterAgent(effect: Effect.AskCharacter) {
    val mission = activeMission ?: return
    scope.launch(Dispatchers.IO) {
      val replyText = if (!backend.configured) {
        null
      } else {
        try {
          withTimeout(10_000) { backend.askCharacter(mission, effect.request) }
        } catch (error: Exception) {
          Log.w(TAG, "character agent failed: ${error.message}")
          null
        }
      }
      send(GameEvent.CharacterReplied(effect.request.requestId, replyText))
    }
  }

  private fun persistProgress(activeEngine: GameEngine, progress: com.ghostsignal.core.engine.Progress) {
    progressStore.save(progress)
    if (!progress.complete || completionRecorded) return
    completionRecorded = true
    val newlyUnlocked = rewards.recordCompletion(activeEngine.mission)
    if (newlyUnlocked.isNotEmpty()) {
      mutableUi.update { it.copy(unlocked = newlyUnlocked.map { track -> track.title }) }
    }
  }

  private fun speakLines(activeEngine: GameEngine, effect: Effect.Speak) {
    val mission = activeMission ?: return
    speechJob?.cancel()
    val sequence = ++speechSequence
    speechJob = scope.launch {
      voiceChannelActive = true
      refreshCommsMode()
      try {
        for (utterance in effect.utterances) {
          val pcm = try {
            withContext(Dispatchers.IO) { voices.render(mission, utterance) }
          } catch (error: Exception) {
            Log.w(TAG, "voice failed: ${error.message}")
            continue
          }
          val character = mission.character(utterance.speaker)
          val sourceSignal = utterance.fromSignal?.let { id -> mission.signal(id) }
          val bearing = sourceSignal?.let { signal ->
            latestFix?.let { fix -> Geo.bearingDeg(fix.position, signal.location) }
          }
          mutableUi.update {
            it.copy(speaking = SpokenLine(character?.name ?: utterance.speaker, utterance.text))
          }
          suspendCancellableCoroutine { continuation ->
            val started = audioEngine?.playVoice(
                pcm,
                VoiceParams(
                    worldBearingDeg = bearing,
                    radio = character?.radio ?: 0.4,
                    elevationDeg = sourceSignal?.elevationDeg ?: 0.0,
                ),
            ) {
              if (continuation.isActive) continuation.resume(Unit)
            }
            if (started == null && continuation.isActive) continuation.resume(Unit)
          }
        }
        send(GameEvent.SpeechFinished(effect.token))
      } finally {
        if (speechSequence == sequence) {
          voiceChannelActive = false
          refreshCommsMode()
          mutableUi.update { it.copy(speaking = null) }
        }
      }
    }
  }

  private fun startListening(effect: Effect.Listen) {
    val input = chooseSpeechInput()
    activeSpeechInput = input
    micChannelActive = true
    refreshCommsMode()
    audioEngine?.playUiSfx(UiSfx.MIC_OPEN)
    mutableUi.update { it.copy(listening = true, partial = null, voiceInput = input.name) }
    input.start(
        effect.mode,
        effect.timeoutMs,
        effect.hints,
        object : ListenCallbacks {
          override fun onText(text: String, isFinal: Boolean, confidence: Double) {
            mutableUi.update { it.copy(partial = text) }
            send(GameEvent.Heard(effect.token, text, isFinal, confidence))
          }

          override fun onEnded(reason: String) {
            micChannelActive = false
            refreshCommsMode()
            audioEngine?.playUiSfx(UiSfx.MIC_CLOSE)
            mutableUi.update { it.copy(listening = false) }
            send(GameEvent.ListenEnded(effect.token))
          }
        },
    )
  }

  private fun chooseSpeechInput(): SpeechInput =
      when (settings.value.voiceInput) {
        VoiceInputMode.AUTO ->
            if (datSpeechInput.isAvailable()) datSpeechInput
            else if (serverSpeechInput.isAvailable()) serverSpeechInput
            else glassesMicRecognizer
        VoiceInputMode.GLASSES ->
            if (datSpeechInput.isAvailable()) datSpeechInput else glassesMicRecognizer
        VoiceInputMode.PHONE_RECOGNIZER -> glassesMicRecognizer
        VoiceInputMode.PHONE_MIC -> phoneMicRecognizer
      }

  private fun refreshCommsMode() {
    audioEngine?.setComms(
        when {
          micChannelActive -> CommsMode.MIC
          voiceChannelActive -> CommsMode.VOICE
          else -> CommsMode.NONE
        },
    )
  }

  private suspend fun pollBroadcasts() {
    var sinceMs = System.currentTimeMillis()
    while (true) {
      delay(10_000)
      if (!backend.configured) continue
      try {
        val freshBroadcasts = backend.broadcasts(sinceMs)
        for (broadcast in freshBroadcasts.sortedBy { it.createdAtMs }) {
          send(GameEvent.Broadcast(broadcast.speaker, broadcast.text))
          sinceMs = maxOf(sinceMs, broadcast.createdAtMs)
        }
      } catch (error: Exception) {
        Log.d(TAG, "broadcast poll failed: ${error.message}")
      }
    }
  }

  private fun publishUi(activeEngine: GameEngine) {
    lastUiPublishMs = System.currentTimeMillis()
    val mission = activeEngine.mission
    val signal = activeEngine.currentSignal
    val currentReading = activeEngine.reading
    mutableUi.update { current ->
      current.copy(
          running = true,
          phase = activeEngine.phase,
          objective = activeEngine.objective(),
          signalIndex = activeEngine.signalIndex,
          total = activeEngine.total,
          signalName = signal?.name,
          signalPlace = signal?.place,
          collected = activeEngine.collected.toList(),
          stems = mission.signals.map { sig ->
            val stemName = mission.music.stems.firstOrNull { it.id == sig.stem }?.name ?: sig.stem
            stemName to (sig.id in activeEngine.collected)
          },
          distanceM = currentReading?.distanceM,
          bearingDeg = currentReading?.bearingDeg,
          strength = currentReading?.strength ?: 0.0,
          zone = currentReading?.zone,
          lockAvailable = activeEngine.lockAvailable,
          paused = activeEngine.paused,
          heading = activeEngine.heading,
          recording = activeEngine.recording,
          rhythmOpen = activeEngine.rhythmTaps != null,
          transcript = activeEngine.transcript.toList().takeLast(8),
          fix = latestFix,
      )
    }
  }

  // -- operator tools ---------------------------------------------------------------------------

  fun forceLock() = send(GameEvent.ForceLock)

  fun forceSolve() = send(GameEvent.ForceSolve)

  fun jumpTo(index: Int) = send(GameEvent.JumpTo(index))

  fun walkToCurrentSignal() {
    val target = engine?.currentSignal?.location
        ?: missions.mission.value.signals.firstOrNull()?.location
        ?: return
    sim.walkTo(target)
  }

  fun currentFix(): GeoFix? = latestFix

  /** Switches between real GPS and the simulator, live during a run. */
  fun setLocationMode(mode: LocationMode) {
    settings.update { it.copy(locationMode = mode) }
    if (!running) return
    if (mode == LocationMode.SIMULATED) {
      gps.stop()
      latestFix?.let { fix -> sim.teleport(fix.position) }
      sim.start()
    } else {
      sim.stop()
      gps.start()
    }
  }

  /**
   * Spatial-audio check: a sonar ping fixed 90° right and 30° up in the
   * world. Turning toward it should swing it to the front and hold it.
   */
  fun startAudioTest() {
    if (running || testAudioEngine != null) return
    val testAudio = createAudioEngine(settings.value.engine)
    testAudioEngine = testAudio
    testAudio.start()
    compass.start()
    val referenceHeading = synchronized(fusion) {
      fusion.estimate(System.currentTimeMillis()).deg
    } ?: compass.lastAzimuth ?: 0.0
    testAudio.setScene(
        AudioScene(
            stems = emptyMap(),
            staticLevel = 0.0,
            staticBearingDeg = null,
            pingIntervalMs = 900,
            pingBearingDeg = Geo.normalizeDeg(referenceHeading + 90),
            paused = false,
            pingElevationDeg = 30.0,
        ),
    )
    testAudioJob = scope.launch {
      glasses.imu.collect { sample ->
        val nowMs = System.currentTimeMillis()
        val estimate = synchronized(fusion) {
          fusion.onImu(sample, nowMs)
          fusion.estimate(nowMs)
        }
        testAudio.setListenerOrientation(estimate.deg, estimate.pitchDeg)
      }
    }
  }

  fun stopAudioTest() {
    testAudioJob?.cancel()
    testAudioJob = null
    testAudioEngine?.release()
    testAudioEngine = null
    if (!running) compass.stop()
  }

  val audioTestRunning: Boolean
    get() = testAudioEngine != null

  fun teleportToSignal(index: Int) {
    missions.mission.value.signals.getOrNull(index)?.let { signal -> sim.teleport(signal.location) }
  }

  fun simulatedStart(): LatLon = missions.mission.value.signals.first().location

  private companion object {
    const val TAG = "GhostSignalGame"
  }
}
