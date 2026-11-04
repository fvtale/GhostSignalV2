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

enum class CapState { OFF, STARTING, ACTIVE, FAILED }

data class Capability(val state: CapState = CapState.OFF, val detail: String = "")

data class GlassesState(
    val initialized: Boolean = false,
    val registration: String = "unknown",
    val registered: Boolean = false,
    val deviceName: String? = null,
    val deviceType: String? = null,
    /**
     * DAT's identifier for the connected pair, plus its firmware: what an
     * account link sends. Not a serial number and not signed by Meta,
     * which is why an admin approves each link.
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
 * Owns the Meta Wearables Device Access Toolkit: registration with the
 * Meta AI app, the device session, and the three capabilities GhostSignal
 * uses — Inputs (capture button, touchpad), Motion (head tracking), and
 * Speech (on-glasses recognition). Each is optional; whatever fails to
 * attach is reported, and the game falls back to phone input, phone
 * compass/GPS course, and the phone recognizer.
 */
class GlassesManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

  private val mutableState = MutableStateFlow(GlassesState())
  val state: StateFlow<GlassesState> = mutableState

  private val mutableInputs = MutableSharedFlow<GlassesInput>(extraBufferCapacity = 32)
  val inputs: SharedFlow<GlassesInput> = mutableInputs

  private val mutableImu = MutableSharedFlow<ImuSample>(extraBufferCapacity = 128)
  val imu: SharedFlow<ImuSample> = mutableImu

  private val mutableLaunchRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

  /** "Hey Meta, start GhostSignal" while the app is already running. */
  val launchRequests: SharedFlow<Unit> = mutableLaunchRequests

  @Volatile
  var speech: Speech? = null
    private set

  private var deviceSession: DeviceSession? = null
  private var motionCapability: Motion? = null
  private val sessionJobs = mutableListOf<Job>()
  private var wantsConnection = false
  private var mockPair: MockGlasses? = null
  private var imuSampleCount = 0
  private var imuWindowStartMs = 0L
  private var isObserving = false
  private var voiceInvocations: VoiceInvocationsStream? = null

  // -- setup ---------------------------------------------------------------------

  /** Call once Bluetooth permission is granted. Safe to call repeatedly. */
  fun initialize() {
    if (mutableState.value.initialized) return
    Wearables.initialize(context)
        .onSuccess {
          mutableState.update { it.copy(initialized = true, error = null) }
          startObserving()
        }
        .onFailure { error, _ ->
          mutableState.update { it.copy(error = "DAT init failed: ${error.description}") }
        }
  }

  private fun startObserving() {
    if (isObserving) return
    isObserving = true

    scope.launch {
      Wearables.registrationState.collect { registration ->
        val isRegistered = registration == RegistrationState.REGISTERED
        mutableState.update {
          it.copy(registration = registration.name.lowercase(), registered = isRegistered)
        }
        if (isRegistered && wantsConnection && deviceSession == null) connect()
      }
    }
    scope.launch {
      Wearables.registrationErrorStream.collect { error ->
        mutableState.update { it.copy(error = "Registration: ${error.description}") }
      }
    }
    scope.launch {
      Wearables.devices.collectLatest { deviceIds ->
        val firstDeviceId = deviceIds.firstOrNull()
        if (firstDeviceId == null) {
          mutableState.update {
            it.copy(
                deviceName = null, deviceType = null, deviceId = null,
                firmware = null, battery = null, worn = null,
            )
          }
          return@collectLatest
        }
        Wearables.devicesMetadata[firstDeviceId]?.collect { metadata ->
          mutableState.update { current ->
            current.copy(
                deviceName = metadata.name.ifBlank { metadata.deviceType.description },
                deviceType = metadata.deviceType.name,
                deviceId = firstDeviceId.identifier,
                firmware = metadata.firmwareInfo,
                battery = metadata.batteryLevel,
                worn = when (metadata.donState.name) {
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

  /**
   * Listens for Meta AI voice launches. Needs Voice Invocations approved
   * and a spoken app name in the Wearables Developer Center; harmless
   * otherwise.
   */
  fun startVoiceInvocations() {
    if (voiceInvocations != null || !mutableState.value.initialized) return
    val stream = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
    voiceInvocations = stream
    scope.launch {
      stream.invocations.collect { invocation ->
        when (invocation) {
          is LaunchApp -> {
            mutableLaunchRequests.tryEmit(Unit)
            invocation.responseHandle.sendSuccess(null)
          }
        }
      }
    }
  }

  fun startRegistration(activity: Activity) = Wearables.startRegistration(activity)

  fun startUnregistration(activity: Activity) = Wearables.startUnregistration(activity)

  /** Registration can also start from the Meta AI app and arrive as a deep link. */
  fun handleIntent(activity: Activity, intent: Intent) {
    if (!mutableState.value.initialized) return
    Wearables.handleIntent(intent) { request ->
      request.continueRegistration(activity).onFailure { error, _ ->
        mutableState.update { it.copy(error = "Registration: ${error.description}") }
      }
    }
  }

  suspend fun refreshMicPermission() {
    if (!mutableState.value.initialized) return
    Wearables.checkPermissionStatus(Permission.MICROPHONE)
        .onSuccess { status ->
          mutableState.update {
            it.copy(micPermission = if (status == PermissionStatus.Granted) "granted" else "denied")
          }
        }
        .onFailure { error, _ ->
          mutableState.update { it.copy(micPermission = "error: ${error.description}") }
        }
  }

  fun onMicPermissionResult(status: PermissionStatus) {
    mutableState.update {
      it.copy(micPermission = if (status == PermissionStatus.Granted) "granted" else "denied")
    }
    // Speech attaches only with the permission in hand; reattach if live.
    deviceSession?.let { session ->
      val sessionLive = session.state.value == DeviceSessionState.STARTED
      if (sessionLive && speech == null) attachSpeechCapability(session)
    }
  }

  // -- session ----------------------------------------------------------------------

  fun connect() {
    wantsConnection = true
    if (!mutableState.value.initialized || deviceSession != null) return
    Wearables.createSession(AutoDeviceSelector())
        .onSuccess { newSession ->
          deviceSession = newSession
          sessionJobs += scope.launch {
            newSession.state.collect { sessionState ->
              mutableState.update { it.copy(session = sessionState.name.lowercase()) }
              when (sessionState) {
                DeviceSessionState.STARTED -> attachAllCapabilities(newSession)
                DeviceSessionState.STOPPED -> handleSessionStopped(newSession)
                else -> Unit
              }
            }
          }
          sessionJobs += scope.launch {
            newSession.errors.collect { error ->
              mutableState.update { it.copy(error = "Session: ${error.description}") }
            }
          }
          newSession.start()
        }
        .onFailure { error, _ ->
          mutableState.update {
            it.copy(error = "Session: ${error.description}", session = "failed")
          }
        }
  }

  fun disconnect() {
    wantsConnection = false
    deviceSession?.stop()
  }

  private fun handleSessionStopped(stoppedSession: DeviceSession) {
    if (deviceSession !== stoppedSession) return
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    deviceSession = null
    speech = null
    motionCapability = null
    mutableState.update {
      it.copy(
          session = "stopped",
          inputs = Capability(),
          motion = Capability(),
          speech = Capability(),
      )
    }
    // A stopped session is terminal; rebuild if the glasses are still wanted.
    if (wantsConnection) {
      scope.launch {
        delay(3_000)
        if (wantsConnection && deviceSession == null) connect()
      }
    }
  }

  private fun attachAllCapabilities(session: DeviceSession) {
    if (mutableState.value.inputs.state == CapState.OFF) attachInputsCapability(session)
    if (mutableState.value.motion.state == CapState.OFF) attachMotionCapability(session)
    if (mutableState.value.speech.state == CapState.OFF) attachSpeechCapability(session)
  }

  /** Detaches and reattaches every capability (e.g. after Developer Center changes). */
  fun reattach() {
    val session = deviceSession ?: return
    runCatching { session.removeInputs() }
    runCatching { session.removeMotion() }
    runCatching { session.removeSpeech() }
    speech = null
    motionCapability = null
    mutableState.update {
      it.copy(inputs = Capability(), motion = Capability(), speech = Capability())
    }
    if (session.state.value == DeviceSessionState.STARTED) attachAllCapabilities(session)
  }

  // -- inputs ------------------------------------------------------------------------

  private fun attachInputsCapability(session: DeviceSession) {
    mutableState.update { it.copy(inputs = Capability(CapState.STARTING)) }
    session.addInputs(InputsConfiguration(consumeBack = true))
        .onSuccess { inputs ->
          mutableState.update { it.copy(inputs = Capability(CapState.ACTIVE)) }
          sessionJobs += scope.launch {
            inputs.events.collect { event -> handleInputEvent(event) }
          }
          sessionJobs += scope.launch {
            inputs.errors.filterNotNull().collect { error ->
              mutableState.update {
                it.copy(inputs = Capability(CapState.FAILED, error.description))
              }
            }
          }
        }
        .onFailure { error, _ ->
          mutableState.update {
            it.copy(inputs = Capability(CapState.FAILED, error.description))
          }
        }
  }

  private fun handleInputEvent(event: InputEvent) {
    val (rawDescription, mappedAction) = when (event) {
      is InputEvent.Capture -> {
        val action = when (event.pressType) {
          CapturePressType.SHORT_PRESS -> PlayerAction.SCAN
          CapturePressType.HOLD -> PlayerAction.TALK
          CapturePressType.DOUBLE_PRESS -> PlayerAction.REPEAT
        }
        "capture ${event.pressType.name.lowercase()}" to action
      }
      is InputEvent.Select -> "touchpad tap" to PlayerAction.TAP
      is InputEvent.Nav -> {
        val action = when (event.direction) {
          NavDirection.LEFT -> PlayerAction.REPEAT
          NavDirection.RIGHT -> PlayerAction.HINT
          NavDirection.UP, NavDirection.DOWN -> PlayerAction.STATUS
        }
        "swipe ${event.direction.name.lowercase()}" to action
      }
      is InputEvent.Back -> "back" to PlayerAction.PAUSE_TOGGLE
      is InputEvent.Button -> {
        val action = if (event.button == ButtonType.ACTION) PlayerAction.TALK else null
        "button ${event.button.name.lowercase()}" to action
      }
      is InputEvent.Drag -> "drag ${event.action.name.lowercase()}" to null
    }
    mutableState.update {
      it.copy(lastInput = "$rawDescription (${event.source.name.lowercase()})")
    }
    if (mappedAction != null) {
      mutableInputs.tryEmit(GlassesInput(mappedAction, event.timestampMs, rawDescription))
    }
  }

  // -- motion --------------------------------------------------------------------------

  private fun attachMotionCapability(session: DeviceSession) {
    mutableState.update { it.copy(motion = Capability(CapState.STARTING)) }
    session.addMotion(MotionConfiguration(MotionSamplingRate.HZ_60))
        .onSuccess { motion ->
          motionCapability = motion
          sessionJobs += scope.launch {
            motion.samples.collect { sample -> forwardImuSample(sample) }
          }
          sessionJobs += scope.launch {
            motion.state.collect { motionState ->
              val capability = if (motionState.name == "STARTED") {
                Capability(CapState.ACTIVE)
              } else {
                Capability(CapState.STARTING, motionState.name.lowercase())
              }
              mutableState.update { it.copy(motion = capability) }
            }
          }
          sessionJobs += scope.launch {
            motion.errors.filterNotNull().collect { error ->
              mutableState.update {
                it.copy(motion = Capability(CapState.FAILED, error.description))
              }
            }
          }
          motion.start()
        }
        .onFailure { error, _ ->
          mutableState.update {
            it.copy(motion = Capability(CapState.FAILED, error.description))
          }
        }
  }

  // DAT motion types are toolkit-specific; the core heading package stays
  // toolkit-free, so conversion happens here at the boundary.
  private fun forwardImuSample(sample: com.meta.wearable.dat.motion.types.MotionSample) {
    trackImuRate()
    mutableImu.tryEmit(
        ImuSample(
            timestampNs = sample.timestampNs,
            accel = sample.accelerometer?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) },
            gyro = sample.gyroscope?.let { Vec3(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) },
            orientation = sample.orientation?.let {
              Quat(it.x.toDouble(), it.y.toDouble(), it.z.toDouble(), it.w.toDouble())
            },
        ),
    )
  }

  private fun trackImuRate() {
    val nowMs = System.currentTimeMillis()
    if (imuWindowStartMs == 0L) imuWindowStartMs = nowMs
    imuSampleCount++
    val windowMs = nowMs - imuWindowStartMs
    if (windowMs >= 2_000) {
      mutableState.update { it.copy(imuRateHz = imuSampleCount * 1000.0 / windowMs) }
      imuSampleCount = 0
      imuWindowStartMs = nowMs
    }
  }

  // -- speech ----------------------------------------------------------------------------

  private fun attachSpeechCapability(session: DeviceSession) {
    mutableState.update { it.copy(speech = Capability(CapState.STARTING)) }
    session.addSpeech(SpeechConfiguration())
        .onSuccess { attachedSpeech ->
          speech = attachedSpeech
          mutableState.update { it.copy(speech = Capability(CapState.ACTIVE)) }
        }
        .onFailure { error, _ ->
          mutableState.update {
            it.copy(speech = Capability(CapState.FAILED, error.description))
          }
        }
  }

  // -- mock glasses (desk testing) ---------------------------------------------------------

  /** Simulated glasses from MockDeviceKit: the full DAT path without hardware. */
  fun enableMock() {
    val kit = MockDeviceKit.getInstance(context)
    if (!kit.isEnabled) {
      kit.enable(
          MockDeviceKitConfig(
              initiallyRegistered = true,
              initialPermissionsGranted = true,
          ),
      )
    }
    mutableState.update { it.copy(initialized = true, mock = true) }
    startObserving()
    kit.pairGlasses(GlassesModel.RAYBAN_META)
        .onSuccess { pair ->
          mockPair = pair
          pair.powerOn()
          pair.unfold()
          pair.don()
          connect()
        }
        .onFailure { error, _ ->
          mutableState.update { it.copy(error = "Mock glasses: ${error.description}") }
        }
  }

  fun mockInput(action: PlayerAction) {
    val inputService = mockPair?.services?.input ?: return
    when (action) {
      PlayerAction.SCAN -> inputService.capture(CapturePressType.SHORT_PRESS)
      PlayerAction.TALK -> inputService.capture(CapturePressType.HOLD)
      PlayerAction.REPEAT -> inputService.capture(CapturePressType.DOUBLE_PRESS)
      PlayerAction.TAP ->
        inputService.select(com.meta.wearable.dat.inputs.types.InputSource.CAPTOUCH)
      PlayerAction.HINT ->
        inputService.navRight(com.meta.wearable.dat.inputs.types.InputSource.CAPTOUCH)
      PlayerAction.STATUS ->
        inputService.navUp(com.meta.wearable.dat.inputs.types.InputSource.CAPTOUCH)
      PlayerAction.PAUSE_TOGGLE ->
        inputService.back(com.meta.wearable.dat.inputs.types.InputSource.CAPTOUCH)
    }
  }

  fun mockSpeech(text: String) {
    mockPair?.services?.speech?.simulateTranscription(text, isFinal = true, confidence = 0.9f)
  }

  companion object {
    private const val TAG = "GhostSignalGlasses"

    fun log(message: String) = Log.i(TAG, message)
  }
}
