package com.ghostsignal.app.audio

import android.content.Context
import android.util.Log
import com.ghostsignal.app.BuildConfig
import com.ghostsignal.core.engine.AudioScene
import com.ghostsignal.core.engine.Sfx
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.model.MusicManifest
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FMOD Core renderer for the soundtrack and voices, spatialised through
 * the Resonance Audio plugin when `libresonanceaudio.so` is packaged
 * (otherwise FMOD's own 3D panning). Sound effects, rhythm clicks, the
 * sonar ping, radio static, and the antagonist theme still come from the
 * built-in mixer running alongside on its own stream.
 *
 * Only functional when the FMOD SDK was present at build time
 * ([BuildConfig.FMOD_ENABLED]); see docs/AUDIO.md.
 */
class FmodAudioEngine(private val context: Context) : AudioEngine {

  override val name = "FMOD"

  private val mutableStats = MutableStateFlow(AudioStats(name, false, 0, 0))
  override val stats: StateFlow<AudioStats> = mutableStats

  /** Side mixer for everything FMOD does not render (see class docs). */
  private val sideMixer = BinauralMixer()

  private var nativeHandle = 0L
  private var updateThread: Thread? = null

  @Volatile
  private var isRunning = false

  @Volatile
  private var currentScene: AudioScene = AudioScene.SILENT

  @Volatile
  private var listenerYawDeg: Double? = null

  @Volatile
  private var listenerPitchDeg: Double? = null

  @Volatile
  private var commsMode = CommsMode.NONE

  private var stemIdList: List<String> = emptyList()
  private var barLengthMs = 2_000.0
  private var stemsStartedAtNs = 0L
  private val spatialBlendByStem = mutableMapOf<String, Float>()
  private val headLockAtMsByStem = mutableMapOf<String, Long>()
  private var voiceCompletion: (() -> Unit)? = null
  private var currentVoiceParams: VoiceParams? = null
  private var musicDuck = 1f
  private var musicLofi = 0f
  private var hasResonance = false

  override fun start() {
    if (isRunning) return
    initFmodJavaLayer(context)
    nativeHandle = FmodNative.nativeInit(Pcm.SAMPLE_RATE, "libresonanceaudio.so")
    check(nativeHandle != 0L) { "FMOD failed to initialise" }
    hasResonance = FmodNative.nativeHasResonance(nativeHandle)
    sideMixer.start()
    isRunning = true
    updateThread = Thread({ runUpdateLoop() }, "gs-fmod").apply { start() }
    mutableStats.value = AudioStats(
        name, true, 0, stemIdList.size,
        if (hasResonance) "Resonance Audio" else "FMOD 3D panning",
    )
  }

  override fun stop() {
    isRunning = false
    updateThread?.join(500)
    updateThread = null
    if (nativeHandle != 0L) FmodNative.nativeRelease(nativeHandle)
    nativeHandle = 0L
    completeVoice()
    sideMixer.stop()
    closeFmodJavaLayer()
    mutableStats.value = AudioStats(name, false, 0, 0)
  }

  override fun release() {
    stop()
    sideMixer.release()
  }

  override fun loadStems(music: MusicManifest, stems: Map<String, Pcm>) {
    check(nativeHandle != 0L) { "start() first" }
    barLengthMs = music.barMs
    stemIdList = music.stems.map { it.id }
    music.stems.forEachIndexed { index, spec ->
      val loopPcm = (stems[spec.id] ?: Pcm.silence((music.barMs * 4).toLong()))
          .toStereo()
          .withFrames(music.loopFrames(Pcm.SAMPLE_RATE))
      FmodNative.nativeLoadStem(
          nativeHandle, index, loopPcm.data, 2, loopPcm.sampleRate,
          dbToLinear(spec.gainDb).toFloat(),
      )
    }
    FmodNative.nativeStartStems(nativeHandle)
    stemsStartedAtNs = System.nanoTime()
    mutableStats.value = mutableStats.value.copy(loadedStems = stemIdList.size)
  }

  // The side mixer renders the theme together with static and the ping.
  override fun loadTheme(theme: Pcm?, gainDb: Double) {
    sideMixer.loadTheme(theme, gainDb)
  }

  override fun setScene(scene: AudioScene) {
    currentScene = scene
    // FMOD owns the stems; the side mixer gets everything else.
    sideMixer.setScene(scene.copy(stems = emptyMap()))
  }

  override fun setListenerOrientation(yawDeg: Double?, pitchDeg: Double?) {
    listenerYawDeg = yawDeg
    listenerPitchDeg = pitchDeg
    sideMixer.setListenerOrientation(yawDeg, pitchDeg)
  }

  @Synchronized
  override fun playVoice(pcm: Pcm, params: VoiceParams, onDone: () -> Unit) {
    completeVoice()
    val monoPcm = pcm.toMono()
    val (x, y, z) = sourcePosition(params.worldBearingDeg, 3.0, params.elevationDeg)
    FmodNative.nativePlayVoice(
        nativeHandle, monoPcm.data, monoPcm.sampleRate,
        params.worldBearingDeg != null, x, y, z, params.radio.toFloat(),
    )
    voiceCompletion = onDone
    currentVoiceParams = params
  }

  override fun stopVoice() {
    if (nativeHandle != 0L) FmodNative.nativeStopVoice(nativeHandle)
    completeVoice()
  }

  @Synchronized
  private fun completeVoice() {
    val completion = voiceCompletion
    voiceCompletion = null
    currentVoiceParams = null
    completion?.invoke()
  }

  override fun playSfx(sfx: Sfx, worldBearingDeg: Double?) {
    sideMixer.playSfx(sfx, worldBearingDeg)
  }

  override fun playUiSfx(sfx: UiSfx) {
    sideMixer.playUiSfx(sfx)
  }

  override fun playRhythm(onsetsBeats: List<Double>, onDone: () -> Unit) {
    sideMixer.playRhythm(onsetsBeats, onDone)
  }

  override fun setComms(mode: CommsMode) {
    commsMode = mode
    sideMixer.setComms(mode)
  }

  override fun setMasterVolume(volume: Float) {
    sideMixer.setMasterVolume(volume)
    if (nativeHandle != 0L) FmodNative.nativeSetMaster(nativeHandle, volume)
  }

  // -- update loop ----------------------------------------------------------------------

  private fun runUpdateLoop() {
    while (isRunning) {
      try {
        pushSceneToNative()
      } catch (error: Exception) {
        Log.e(TAG, "fmod update failed", error)
      }
      Thread.sleep(UPDATE_INTERVAL_MS)
    }
  }

  private fun pushSceneToNative() {
    val handle = nativeHandle
    if (handle == 0L) return
    val scene = currentScene
    val elapsedMs = (System.nanoTime() - stemsStartedAtNs) / 1_000_000
    val pausedFactor = if (scene.paused) 0.15f else 1f

    stemIdList.forEachIndexed { index, stemId ->
      val stemState = scene.stems[stemId]
      val gain = (stemState?.gain ?: 0.0).toFloat() * pausedFactor
      val wantsInHead = stemState == null || stemState.worldBearingDeg == null

      // Glide collected stems into the head on the next bar line.
      var spatialTarget = if (wantsInHead) 0f else 1f
      val currentBlend = spatialBlendByStem[stemId] ?: 1f
      if (wantsInHead && currentBlend > 0.5f && gain > 0.02f) {
        val lockAtMs = headLockAtMsByStem.getOrPut(stemId) {
          ((elapsedMs / barLengthMs).toLong() + 1) * barLengthMs.toLong()
        }
        spatialTarget = if (elapsedMs >= lockAtMs) 0f else 1f
      } else if (!wantsInHead) {
        headLockAtMsByStem.remove(stemId)
      }
      val smoothedBlend = currentBlend + (spatialTarget - currentBlend) * 0.08f
      spatialBlendByStem[stemId] = smoothedBlend

      val distance = max(2.0, (stemState?.distanceM ?: 10.0) / 6.0)
      val (x, y, z) = sourcePosition(
          stemState?.worldBearingDeg, distance, stemState?.elevationDeg ?: 0.0,
      )
      FmodNative.nativeSetStem(
          handle, index, gain, smoothedBlend, x, y, z,
          (stemState?.clarity ?: 1.0).toFloat(),
      )
    }

    currentVoiceParams?.worldBearingDeg?.let { voiceBearing ->
      val (x, y, z) = sourcePosition(
          voiceBearing, 3.0, currentVoiceParams?.elevationDeg ?: 0.0,
      )
      FmodNative.nativeSetVoicePosition(handle, x, y, z)
    }

    val (duckTarget, lofiTarget) = when (commsMode) {
      CommsMode.NONE -> 1f to 0f
      CommsMode.VOICE -> 0.42f to 0f
      CommsMode.MIC -> 0.28f to 1f
    }
    musicDuck += (duckTarget - musicDuck) * 0.2f
    musicLofi += (lofiTarget - musicLofi) * 0.2f
    FmodNative.nativeSetMusicBus(handle, musicDuck, musicLofi)
    FmodNative.nativeUpdate(handle)

    if (voiceCompletion != null && !FmodNative.nativeVoicePlaying(handle)) {
      completeVoice()
    }
  }

  /**
   * FMOD space is left-handed: +x right, +z forward. Positions are
   * relative to the head, from a world bearing and the live yaw/pitch.
   */
  private fun sourcePosition(
      worldBearingDeg: Double?,
      distanceM: Double,
      elevationDeg: Double = 0.0,
  ): Triple<Float, Float, Float> {
    if (worldBearingDeg == null) return Triple(0f, 0f, 1f)
    val relativeAzimuthRad = Math.toRadians(Geo.deltaDeg(listenerYawDeg ?: 0.0, worldBearingDeg))
    val relativeElevationRad = Math.toRadians(elevationDeg - (listenerPitchDeg ?: 0.0))
    val horizontalDistance = cos(relativeElevationRad) * distanceM
    return Triple(
        (sin(relativeAzimuthRad) * horizontalDistance).toFloat(),
        (sin(relativeElevationRad) * distanceM).toFloat(),
        (cos(relativeAzimuthRad) * horizontalDistance).toFloat(),
    )
  }

  private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

  companion object {
    private const val TAG = "GhostSignalFmod"
    private const val UPDATE_INTERVAL_MS = 16L

    /** True when this build includes FMOD and its native libraries load. */
    fun isAvailable(): Boolean {
      if (!BuildConfig.FMOD_ENABLED) return false
      return runCatching {
        System.loadLibrary("fmod")
        System.loadLibrary("ghostsignal_fmod")
        true
      }.getOrElse { error ->
        Log.w(TAG, "FMOD libraries not loadable", error)
        false
      }
    }

    // org.fmod.FMOD ships in fmod.jar, on the classpath only in FMOD builds.
    private fun initFmodJavaLayer(context: Context) {
      Class.forName("org.fmod.FMOD")
          .getMethod("init", Context::class.java)
          .invoke(null, context)
    }

    private fun closeFmodJavaLayer() {
      runCatching {
        Class.forName("org.fmod.FMOD").getMethod("close").invoke(null)
      }
    }
  }
}

/** JNI surface of src/main/cpp/fmod_bridge.cpp. Signatures are fixed by the C++ side. */
object FmodNative {

  @JvmStatic
  external fun nativeInit(sampleRate: Int, resonancePlugin: String): Long

  @JvmStatic
  external fun nativeHasResonance(handle: Long): Boolean

  @JvmStatic
  external fun nativeLoadStem(
      handle: Long,
      index: Int,
      pcm: ShortArray,
      channels: Int,
      rate: Int,
      gain: Float,
  ): Boolean

  @JvmStatic
  external fun nativeStartStems(handle: Long)

  @JvmStatic
  external fun nativeSetStem(
      handle: Long,
      index: Int,
      gain: Float,
      spatial: Float,
      x: Float,
      y: Float,
      z: Float,
      clarity: Float,
  )

  @JvmStatic
  external fun nativePlayVoice(
      handle: Long,
      pcm: ShortArray,
      rate: Int,
      spatial: Boolean,
      x: Float,
      y: Float,
      z: Float,
      radio: Float,
  )

  @JvmStatic
  external fun nativeSetVoicePosition(handle: Long, x: Float, y: Float, z: Float)

  @JvmStatic
  external fun nativeVoicePlaying(handle: Long): Boolean

  @JvmStatic
  external fun nativeStopVoice(handle: Long)

  @JvmStatic
  external fun nativeSetMusicBus(handle: Long, volume: Float, lofi: Float)

  @JvmStatic
  external fun nativeSetMaster(handle: Long, volume: Float)

  @JvmStatic
  external fun nativeUpdate(handle: Long)

  @JvmStatic
  external fun nativeRelease(handle: Long)
}
