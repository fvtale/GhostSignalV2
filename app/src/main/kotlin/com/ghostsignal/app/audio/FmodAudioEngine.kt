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
 * FMOD Core renderer for the soundtrack and voices, binaural through the Resonance Audio plugin
 * when `libresonanceaudio.so` is packaged (otherwise FMOD's own 3D panning). Sound effects,
 * rhythm clicks, the sonar ping and radio static still come from the built-in mixer, which runs
 * alongside on its own stream.
 *
 * Only compiled into a working state when the FMOD SDK is present at build time
 * ([BuildConfig.FMOD_ENABLED]); see docs/AUDIO.md.
 */
class FmodAudioEngine(private val context: Context) : AudioEngine {
  override val name = "FMOD"
  private val _stats = MutableStateFlow(AudioStats(name, false, 0, 0))
  override val stats: StateFlow<AudioStats> = _stats

  private val effects = BinauralMixer()
  private var handle = 0L
  private var updater: Thread? = null
  @Volatile private var running = false
  @Volatile private var scene = AudioScene.SILENT
  @Volatile private var heading: Double? = null
  @Volatile private var listenerPitch: Double? = null
  @Volatile private var comms = CommsMode.NONE
  private var stemIds: List<String> = emptyList()
  private var barMs = 2_000.0
  private var stemsStartNs = 0L
  private val spatial = mutableMapOf<String, Float>()
  private val headLockAt = mutableMapOf<String, Long>()
  private var voiceDone: (() -> Unit)? = null
  private var voiceParams: VoiceParams? = null
  private var duck = 1f
  private var lofi = 0f
  private var resonance = false

  override fun start() {
    if (running) return
    initFmodJava(context)
    handle = FmodNative.nativeInit(Pcm.SAMPLE_RATE, "libresonanceaudio.so")
    if (handle == 0L) error("FMOD failed to initialise")
    resonance = FmodNative.nativeHasResonance(handle)
    effects.start()
    running = true
    updater = Thread({ updateLoop() }, "gs-fmod").apply { start() }
    _stats.value = AudioStats(name, true, 0, stemIds.size, if (resonance) "Resonance Audio" else "FMOD 3D panning")
  }

  override fun stop() {
    running = false
    updater?.join(500)
    updater = null
    if (handle != 0L) FmodNative.nativeRelease(handle)
    handle = 0L
    finishVoice()
    effects.stop()
    closeFmodJava()
    _stats.value = AudioStats(name, false, 0, 0)
  }

  override fun release() {
    stop()
    effects.release()
  }

  override fun loadStems(music: MusicManifest, stems: Map<String, Pcm>) {
    check(handle != 0L) { "start() first" }
    barMs = music.barMs
    stemIds = music.stems.map { it.id }
    music.stems.forEachIndexed { i, spec ->
      val pcm = (stems[spec.id] ?: Pcm.silence((music.barMs * 4).toLong())).toStereo().withFrames(music.loopFrames(Pcm.SAMPLE_RATE))
      FmodNative.nativeLoadStem(handle, i, pcm.data, 2, pcm.sampleRate, 10.0.pow(spec.gainDb / 20.0).toFloat())
    }
    FmodNative.nativeStartStems(handle)
    stemsStartNs = System.nanoTime()
    _stats.value = _stats.value.copy(loadedStems = stemIds.size)
  }

  // The side mixer renders the antagonist's theme along with the static and the ping.
  override fun loadTheme(theme: Pcm?, gainDb: Double) = effects.loadTheme(theme, gainDb)

  override fun setScene(scene: AudioScene) {
    this.scene = scene
    // The side mixer only renders static, the ping and the antagonist's theme.
    effects.setScene(scene.copy(stems = emptyMap()))
  }

  override fun setListenerOrientation(yawDeg: Double?, pitchDeg: Double?) {
    heading = yawDeg
    listenerPitch = pitchDeg
    effects.setListenerOrientation(yawDeg, pitchDeg)
  }

  @Synchronized
  override fun playVoice(pcm: Pcm, params: VoiceParams, onDone: () -> Unit) {
    finishVoice()
    val mono = pcm.toMono()
    val (x, y, z) = position(params.worldBearingDeg, 3.0, params.elevationDeg)
    FmodNative.nativePlayVoice(handle, mono.data, mono.sampleRate, params.worldBearingDeg != null, x, y, z, params.radio.toFloat())
    voiceDone = onDone
    voiceParams = params
  }

  override fun stopVoice() {
    if (handle != 0L) FmodNative.nativeStopVoice(handle)
    finishVoice()
  }

  @Synchronized
  private fun finishVoice() {
    val done = voiceDone
    voiceDone = null
    voiceParams = null
    done?.invoke()
  }

  override fun playSfx(sfx: Sfx, worldBearingDeg: Double?) = effects.playSfx(sfx, worldBearingDeg)

  override fun playUiSfx(sfx: UiSfx) = effects.playUiSfx(sfx)

  override fun playRhythm(onsetsBeats: List<Double>, onDone: () -> Unit) = effects.playRhythm(onsetsBeats, onDone)

  override fun setComms(mode: CommsMode) {
    comms = mode
    effects.setComms(mode)
  }

  override fun setMasterVolume(volume: Float) {
    effects.setMasterVolume(volume)
    if (handle != 0L) FmodNative.nativeSetMaster(handle, volume)
  }

  private fun updateLoop() {
    while (running) {
      try {
        update()
      } catch (e: Exception) {
        Log.e(TAG, "fmod update failed", e)
      }
      Thread.sleep(16)
    }
  }

  private fun update() {
    val h = handle
    if (h == 0L) return
    val sc = scene
    val nowMs = (System.nanoTime() - stemsStartNs) / 1_000_000
    val pausedFactor = if (sc.paused) 0.15f else 1f
    stemIds.forEachIndexed { i, id ->
      val st = sc.stems[id]
      val gain = (st?.gain ?: 0.0).toFloat() * pausedFactor
      val wantsHead = st == null || st.worldBearingDeg == null
      var target = if (wantsHead) 0f else 1f
      // Glide collected stems into the head on the next bar line, as the built-in mixer does.
      val current = spatial[id] ?: 1f
      if (wantsHead && current > 0.5f && gain > 0.02f) {
        val at = headLockAt.getOrPut(id) { ((nowMs / barMs).toLong() + 1) * barMs.toLong() }
        target = if (nowMs >= at) 0f else 1f
      } else if (!wantsHead) {
        headLockAt.remove(id)
      }
      val s = current + (target - current) * 0.08f
      spatial[id] = s
      val (x, y, z) = position(st?.worldBearingDeg, max(2.0, (st?.distanceM ?: 10.0) / 6.0), st?.elevationDeg ?: 0.0)
      FmodNative.nativeSetStem(h, i, gain, s, x, y, z, (st?.clarity ?: 1.0).toFloat())
    }
    voiceParams?.worldBearingDeg?.let { bearing ->
      val (x, y, z) = position(bearing, 3.0, voiceParams?.elevationDeg ?: 0.0)
      FmodNative.nativeSetVoicePosition(h, x, y, z)
    }
    val (duckTarget, lofiTarget) =
        when (comms) {
          CommsMode.NONE -> 1f to 0f
          CommsMode.VOICE -> 0.42f to 0f
          CommsMode.MIC -> 0.28f to 1f
        }
    duck += (duckTarget - duck) * 0.2f
    lofi += (lofiTarget - lofi) * 0.2f
    FmodNative.nativeSetMusicBus(h, duck, lofi)
    FmodNative.nativeUpdate(h)
    if (voiceDone != null && !FmodNative.nativeVoicePlaying(h)) finishVoice()
  }

  /** FMOD is left-handed: +x right, +z forward. Sources are placed relative to the head. */
  private fun position(worldBearing: Double?, distance: Double, elevationDeg: Double = 0.0): Triple<Float, Float, Float> {
    if (worldBearing == null) return Triple(0f, 0f, 1f)
    val rel = Math.toRadians(Geo.deltaDeg(heading ?: 0.0, worldBearing))
    val relElev = Math.toRadians(elevationDeg - (listenerPitch ?: 0.0))
    val horiz = cos(relElev) * distance
    return Triple((sin(rel) * horiz).toFloat(), (sin(relElev) * distance).toFloat(), (cos(rel) * horiz).toFloat())
  }

  companion object {
    private const val TAG = "GhostSignalFmod"

    /** True when this build includes FMOD and its native libraries load. */
    fun isAvailable(): Boolean {
      if (!BuildConfig.FMOD_ENABLED) return false
      return runCatching {
            System.loadLibrary("fmod")
            System.loadLibrary("ghostsignal_fmod")
            true
          }
          .getOrElse {
            Log.w(TAG, "FMOD libraries not loadable", it)
            false
          }
    }

    // org.fmod.FMOD ships in fmod.jar, which is only on the classpath in FMOD builds.
    private fun initFmodJava(context: Context) {
      Class.forName("org.fmod.FMOD").getMethod("init", Context::class.java).invoke(null, context)
    }

    private fun closeFmodJava() {
      runCatching { Class.forName("org.fmod.FMOD").getMethod("close").invoke(null) }
    }
  }
}

/** JNI surface of src/main/cpp/fmod_bridge.cpp. */
object FmodNative {
  @JvmStatic external fun nativeInit(sampleRate: Int, resonancePlugin: String): Long

  @JvmStatic external fun nativeHasResonance(handle: Long): Boolean

  @JvmStatic external fun nativeLoadStem(handle: Long, index: Int, pcm: ShortArray, channels: Int, rate: Int, gain: Float): Boolean

  @JvmStatic external fun nativeStartStems(handle: Long)

  @JvmStatic external fun nativeSetStem(handle: Long, index: Int, gain: Float, spatial: Float, x: Float, y: Float, z: Float, clarity: Float)

  @JvmStatic external fun nativePlayVoice(handle: Long, pcm: ShortArray, rate: Int, spatial: Boolean, x: Float, y: Float, z: Float, radio: Float)

  @JvmStatic external fun nativeSetVoicePosition(handle: Long, x: Float, y: Float, z: Float)

  @JvmStatic external fun nativeVoicePlaying(handle: Long): Boolean

  @JvmStatic external fun nativeStopVoice(handle: Long)

  @JvmStatic external fun nativeSetMusicBus(handle: Long, volume: Float, lofi: Float)

  @JvmStatic external fun nativeSetMaster(handle: Long, volume: Float)

  @JvmStatic external fun nativeUpdate(handle: Long)

  @JvmStatic external fun nativeRelease(handle: Long)
}
