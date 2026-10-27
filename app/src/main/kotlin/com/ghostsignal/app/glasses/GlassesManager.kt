package com.ghostsignal.app.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ghostsignal.core.engine.PlayerAction
import com.ghostsignal.core.heading.ImuSample
import com.ghostsignal.core.heading.Quat
import com.ghostsignal.core.heading.Vec3
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.ButtonType
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.NavDirection
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.motion.Motion
import com.meta.wearable.dat.motion.addMotion
import com.meta.wearable.dat.motion.removeMotion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import com.meta.wearable.dat.motion.types.MotionSamplingRate
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.SpeechConfiguration
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CapState {
  OFF,
  STARTING,
  ACTIVE,
  FAILED,
}

data class Capability(val state: CapState = CapState.OFF, val detail: String = "")

data class GlassesState(
    val initialized: Boolean = false,
    val registration: String = "unknown",
    val registered: Boolean = false,
    val deviceName: String? = null,
    val deviceType: String? = null,
    /**
     * DAT's identifier for the connected pair, and its firmware: what linking the glasses to an
     * account sends. Not a serial number, and not signed by Meta, which is why an admin approves.
     */
    val deviceId: String? = null,
    val firmware: String? = null,
    val battery: Int? = null,
    val worn: Boolean? = null,
    val session: String = "none",
    val inputs: Capability = Capability(),
    val motion: Capability = Capability(),
    val speech: Capability = Capability(),
    val micPermission: String = "unknown",
    val lastInput: String? = null,
    val imuRateHz: Double = 0.0,
    val mock: Boolean = false,
    val error: String? = null,
)

/** A glasses input mapped to a game action, with the raw description for diagnostics. */
data class GlassesInput(val action: PlayerAction, val deviceTimeMs: Long?, val raw: String)

/**
 * Owns the Meta Wearables Device Access Toolkit: registration with the Meta AI app, the device
 * session, and the three experimental capabilities GhostSignal uses: Inputs (capture button and
 * touchpad), Motion (head tracking) and Speech (on-glasses recognition). Each capability is
 * optional; whatever fails to attach is reported and the game falls back to phone-side input,
 * the phone compass or GPS course, and the phone's recognizer.
 */
class GlassesManager(private val context: Context, private val scope: CoroutineScope) {
  private val _state = MutableStateFlow(GlassesState())
  val state: StateFlow<GlassesState> = _state

  private val _inputs = MutableSharedFlow<GlassesInput>(extraBufferCapacity = 32)
  val inputs: SharedFlow<GlassesInput> = _inputs

  private val _imu = MutableSharedFlow<ImuSample>(extraBufferCapacity = 128)
  val imu: SharedFlow<ImuSample> = _imu

  @Volatile var speech: Speech? = null
    private set

  private var session: DeviceSession? = null
  private var motion: Motion? = null
  private val sessionJobs = mutableListOf<Job>()
  private var wantConnected = false
  private var mockGlasses: MockGlasses? = null
  private var imuCount = 0
  private var imuWindowStart = 0L

  // --------------------------------------------------------------- setup

  /** Call once Bluetooth permission is granted. Safe to call repeatedly. */
  fun initialize() {
    if (_state.value.initialized) return
    Wearables.initialize(context)
        .onSuccess {
          _state.update { it.copy(initialized = true, error = null) }
          observe()
        }
        .onFailure { error, _ -> _state.update { it.copy(error = "DAT init failed: ${error.description}") } }
  }

  private var observing = false

  private fun observe() {
    if (observing) return
    observing = true
    scope.launch {
      Wearables.registrationState.collect { reg ->
        _state.update { it.copy(registration = reg.name.lowercase(), registered = reg == RegistrationState.REGISTERED) }
        if (reg == RegistrationState.REGISTERED && wantConnected && session == null) connect()
      }
    }
    scope.launch {
      Wearables.registrationErrorStream.collect { e -> _state.update { it.copy(error = "Registration: ${e.description}") } }
    }
    scope.launch {
      Wearables.devices.collectLatest { ids ->
        val id = ids.firstOrNull()
        if (id == null) {
          _state.update { it.copy(deviceName = null, deviceType = null, deviceId = null, firmware = null, battery = null, worn = null) }
          return@collectLatest
        }
        Wearables.devicesMetadata[id]?.collect { d ->
          _state.update {
            it.copy(
                deviceName = d.name.ifBlank { d.deviceType.description },
                deviceType = d.deviceType.name,
                deviceId = id.identifier,
                firmware = d.firmwareInfo,
                battery = d.batteryLevel,
                worn = when (d.donState.name) {
                  "DONNED" -> true
                  "DOFFED" -> false
                  else -> null
                },
            )
          }
        }
      }
    }
  }

  private val _launchRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

  /** "Hey Meta, start GhostSignal" while the app is already running. */
  val launchRequests: SharedFlow<Unit> = _launchRequests
  private var voiceInvocations: VoiceInvocationsStream? = null

  /**
   * Listens for Meta AI voice launches. Needs Voice Invocations approved and a spoken app name set
   * in Wearables Developer Center; harmless otherwise.
   */
  fun startVoiceInvocations() {
    if (voiceInvocations != null || !_state.value.initialized) return
    val stream = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
    voiceInvocations = stream
    scope.launch {
      stream.invocations.collect { invocation ->
        when (invocation) {
          is LaunchApp -> {
            _launchRequests.tryEmit(Unit)
            invocation.responseHandle.sendSuccess(null)
          }
        }
      }
    }
  }

  fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)

  fun startUnregistration(activity: Activity) = Wearables.startUnregistration(activity)

  /** Registration requests can also start from the Meta AI app and arrive as a deep link. */
  fun handleIntent(activity: Activity, intent: Intent) {
    if (!_state.value.initialized) return
    Wearables.handleIntent(intent) { request ->
      request.continueRegistration(activity).onFailure { error, _ ->
        _state.update { it.copy(error = "Registration: ${error.description}") }
      }
    }
  }

  suspend fun refreshMicPermission() {
    if (!_state.value.initialized) return
    Wearables.checkPermissionStatus(Permission.MICROPHONE)
        .onSuccess { status ->
          _state.update { it.copy(micPermission = if (status == PermissionStatus.Granted) "granted" else "denied") }
        }
        .onFailure { error, _ -> _state.update { it.copy(micPermission = "error: ${error.description}") } }
  }

  fun onMicPermissionResult(status: PermissionStatus) {
    _state.update { it.copy(micPermission = if (status == PermissionStatus.Granted) "granted" else "denied") }
    // Speech needs the permission at attach time; reattach if the session is up.
    session?.let { s -> if (s.state.value == DeviceSessionState.STARTED && speech == null) attachSpeech(s) }
  }

  // --------------------------------------------------------------- session

  fun connect() {
    wantConnected = true
    if (!_state.value.initialized || session != null) return
    Wearables.createSession(AutoDeviceSelector())
        .onSuccess { s ->
          session = s
          sessionJobs +=
              scope.launch {
                s.state.collect { st ->
                  _state.update { it.copy(session = st.name.lowercase()) }
                  when (st) {
                    DeviceSessionState.STARTED -> attachCapabilities(s)
                    DeviceSessionState.STOPPED -> onSessionStopped(s)
                    else -> Unit
                  }
                }
              }
          sessionJobs += scope.launch { s.errors.collect { e -> _state.update { it.copy(error = "Session: ${e.description}") } } }
          s.start()
        }
        .onFailure { error, _ -> _state.update { it.copy(error = "Session: ${error.description}", session = "failed") } }
  }

  fun disconnect() {
    wantConnected = false
    session?.stop()
  }

  private fun onSessionStopped(s: DeviceSession) {
    if (session !== s) return
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    session = null
    speech = null
    motion = null
    _state.update {
      it.copy(session = "stopped", inputs = Capability(), motion = Capability(), speech = Capability())
    }
    // A stopped session is terminal; make a new one if we still want the glasses.
    if (wantConnected) {
      scope.launch {
        delay(3_000)
        if (wantConnected && session == null) connect()
      }
    }
  }

  private fun attachCapabilities(s: DeviceSession) {
    if (_state.value.inputs.state == CapState.OFF) attachInputs(s)
    if (_state.value.motion.state == CapState.OFF) attachMotion(s)
    if (_state.value.speech.state == CapState.OFF) attachSpeech(s)
  }

  private fun attachInputs(s: DeviceSession) {
    _state.update { it.copy(inputs = Capability(CapState.STARTING)) }
    s.addInputs(InputsConfiguration(consumeBack = true))
        .onSuccess { inputs ->
          _state.update { it.copy(inputs = Capability(CapState.ACTIVE)) }
          sessionJobs += scope.launch { inputs.events.collect { onInput(it) } }
          sessionJobs +=
              scope.launch {
                inputs.errors.filterNotNull().collect { e ->
                  _state.update { it.copy(inputs = Capability(CapState.FAILED, e.description)) }
                }
              }
        }
        .onFailure { error, _ -> _state.update { it.copy(inputs = Capability(CapState.FAILED, error.description)) } }
  }

  private fun onInput(event: InputEvent) {
    val action: PlayerAction?
    val raw: String
    when (event) {
      is InputEvent.Capture -> {
        raw = "capture ${event.pressType.name.lowercase()}"
        action =
            when (event.pressType) {
              CapturePressType.SHORT_PRESS -> PlayerAction.SCAN
              CapturePressType.HOLD -> PlayerAction.TALK
              CapturePressType.DOUBLE_PRESS -> PlayerAction.REPEAT
            }
      }
      is InputEvent.Select -> {
        raw = "touchpad tap"
        action = PlayerAction.TAP
      }
      is InputEvent.Nav -> {
        raw = "swipe ${event.direction.name.lowercase()}"
        action =
            when (event.direction) {
              NavDirection.LEFT -> PlayerAction.REPEAT
              NavDirection.RIGHT -> PlayerAction.HINT
              NavDirection.UP, NavDirection.DOWN -> PlayerAction.STATUS
            }
      }
      is InputEvent.Back -> {
        raw = "back"
        action = PlayerAction.PAUSE_TOGGLE
      }
      is InputEvent.Button -> {
        raw = "button ${event.button.name.lowercase()}"
        action = if (event.button == ButtonType.ACTION) PlayerAction.TALK else null
      }
      is InputEvent.Drag -> {
        raw = "drag ${event.action.name.lowercase()}"
        action = null
      }
    }
    _state.update { it.copy(lastInput = "$raw (${event.source.name.lowercase()})") }
    if (action != null) _inputs.tryEmit(GlassesInput(action, event.timestampMs, raw))
  }

  private fun attachMotion(s: DeviceSession) {
    _state.update { it.copy(motion = Capability(CapState.STARTING)) }
    s.addMotion(MotionConfiguration(MotionSamplingRate.HZ_60))
        .onSuccess { m ->
          motion = m
          sessionJobs +=
              scope.launch {
                m.samples.collect { sample ->
                  countImu()
                  _imu.tryEmit(
                      ImuSample(
                          sample.timestampNs,
                          sample.accelerometer?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) },
                          sample.gyroscope?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) },
                          sample.orientation?.let { Quat(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.w.toDouble()) },
                      ))
                }
              }
          sessionJobs +=
              scope.launch {
                m.state.collect { st ->
                  val cap = if (st.name == "STARTED") Capability(CapState.ACTIVE) else Capability(CapState.STARTING, st.name.lowercase())
                  _state.update { it.copy(motion = cap) }
                }
              }
          sessionJobs +=
              scope.launch {
                m.errors.filterNotNull().collect { e -> _state.update { it.copy(motion = Capability(CapState.FAILED, e.description)) } }
              }
          m.start()
        }
        .onFailure { error, _ -> _state.update { it.copy(motion = Capability(CapState.FAILED, error.description)) } }
  }

  private fun countImu() {
    val now = System.currentTimeMillis()
    if (imuWindowStart == 0L) imuWindowStart = now
    imuCount++
    if (now - imuWindowStart >= 2_000) {
      val rate = imuCount * 1000.0 / (now - imuWindowStart)
      _state.update { it.copy(imuRateHz = rate) }
      imuCount = 0
      imuWindowStart = now
    }
  }

  private fun attachSpeech(s: DeviceSession) {
    _state.update { it.copy(speech = Capability(CapState.STARTING)) }
    s.addSpeech(SpeechConfiguration())
        .onSuccess { sp ->
          speech = sp
          _state.update { it.copy(speech = Capability(CapState.ACTIVE)) }
        }
        .onFailure { error, _ ->
          _state.update { it.copy(speech = Capability(CapState.FAILED, error.description)) }
        }
  }

  /** Detaches and reattaches every capability, e.g. after enabling them in the Developer Center. */
  fun reattach() {
    val s = session ?: return
    runCatching { s.removeInputs() }
    runCatching { s.removeMotion() }
    runCatching { s.removeSpeech() }
    speech = null
    motion = null
    _state.update { it.copy(inputs = Capability(), motion = Capability(), speech = Capability()) }
    if (s.state.value == DeviceSessionState.STARTED) attachCapabilities(s)
  }

  // --------------------------------------------------------------- mock glasses for desk testing

  /** Simulated glasses from MockDeviceKit, so the whole DAT path can be exercised without hardware. */
  fun enableMock() {
    val kit = MockDeviceKit.getInstance(context)
    if (!kit.isEnabled) kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
    _state.update { it.copy(initialized = true, mock = true) }
    observe()
    kit.pairGlasses(GlassesModel.RAYBAN_META)
        .onSuccess { g ->
          mockGlasses = g
          g.powerOn()
          g.unfold()
          g.don()
          connect()
        }
        .onFailure { error, _ -> _state.update { it.copy(error = "Mock glasses: ${error.description}") } }
  }

  fun mockInput(action: PlayerAction) {
    val input = mockGlasses?.services?.input ?: return
    when (action) {
      PlayerAction.SCAN -> input.capture(CapturePressType.SHORT_PRESS)
      PlayerAction.TALK -> input.capture(CapturePressType.HOLD)
      PlayerAction.REPEAT -> input.capture(CapturePressType.DOUBLE_PRESS)
      PlayerAction.TAP -> input.select(com.meta.wearable.dat.inputs.types.InputSource.CAPTOUCH)
      PlayerAction.HINT -> input.navRight(com.meta.wearable.dat.inputs.types.InputSource.CAPTOUCH)
      PlayerAction.STATUS -> input.navUp(com.meta.wearable.dat.inputs.types.InputSource.CAPTOUCH)
      PlayerAction.PAUSE_TOGGLE -> input.back(com.meta.wearable.dat.inputs.types.InputSource.CAPTOUCH)
    }
  }

  fun mockSpeech(text: String) {
    mockGlasses?.services?.speech?.simulateTranscription(text, true, 0.9f)
  }

  companion object {
    private const val TAG = "GhostSignalGlasses"

    fun log(msg: String) = Log.i(TAG, msg)
  }
}
