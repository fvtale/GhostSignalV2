package com.ghostsignal.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import com.ghostsignal.core.engine.AudioScene
import com.ghostsignal.core.engine.Sfx
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.model.MusicManifest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The built-in renderer: one AudioTrack, a real-time mixing thread, and a
 * spherical-head binaural panner per source.
 *
 * ```
 *  stems (looping, sample-locked) ─┬─ in-head (stereo) ───────────┐
 *                                  └─ beacon: radio crush ─ binaural ┤
 *  static + sonar ping (binaural) ─────────────────────────────────┤
 *  antagonist theme (in-head, at the scene's level) ───────────────┤─ music bus ─ duck/lo-fi ─┐
 *  voices (radio band, binaural or centred) ──────────────────────────────────────────────────┤─ master ─ soft clip
 *  sfx, rhythm clicks ────────────────────────────────────────────────────────────────────────┘
 * ```
 *
 * Bearings arrive in world coordinates; the live head heading is subtracted
 * per block, so head turns land within ~10 ms of the IMU sample (plus
 * Bluetooth latency).
 */
class BinauralMixer : AudioEngine {

  override val name = "Built-in binaural"

  private val mutableStats = MutableStateFlow(AudioStats(name, false, 0, 0))
  override val stats: StateFlow<AudioStats> = mutableStats

  private val sampleRate = Pcm.SAMPLE_RATE
  private val blockFrames = 480

  private var audioTrack: AudioTrack? = null
  private var renderThread: Thread? = null

  @Volatile
  private var isRunning = false

  private val callbackExecutor =
      Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "gs-audio-callbacks") }
  private val pendingCommands = ConcurrentLinkedQueue<(Long) -> Unit>()

  @Volatile
  private var currentScene: AudioScene = AudioScene.SILENT

  @Volatile
  private var listenerYawDeg: Double? = null

  @Volatile
  private var listenerPitchDeg: Double? = null

  @Volatile
  private var commsMode = CommsMode.NONE

  @Volatile
  private var masterVolume = 0.9f

  // -- audio-thread state (touched only by the render thread) -------------------

  private var frameCounter = 0L
  private var beatsPerMinute = 120.0
  private var beatsPerBarCount = 4
  private var stemChannels: List<StemChannel> = emptyList()
  private var themeSamples: ShortArray? = null
  private var themeBaseGain = 1f
  private var themePosition = 0
  private val themeGainRamp = Ramp()
  private val mixBuffer = FloatArray(blockFrames * 2)
  private val musicBuffer = FloatArray(blockFrames * 2)
  private val noiseSource = Noise()
  private val staticFilter = OnePoleLp(2_500f)
  private val staticPanner = Binaural()
  private val staticGainRamp = Ramp()
  private var crackleLevel = 0f
  private val pingSynth = PingSynth()
  private var nextPingAtFrame = 0L
  private var activeVoice: VoicePlayback? = null
  private val activeSfx = mutableListOf<SynthVoice>()
  private val scheduledActions = mutableListOf<Pair<Long, () -> Unit>>()
  private val duckRamp = Ramp(1f)
  private val lofiRamp = Ramp(0f)
  private val radioBandLeft = RadioBand()
  private val radioBandRight = RadioBand()
  private var underrunTotal = 0

  // -- lifecycle ------------------------------------------------------------------

  override fun start() {
    if (isRunning) return
    val minimumBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_FLOAT,
    )
    val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .apply {
          // We spatialise ourselves; keep the platform spatializer out.
          if (Build.VERSION.SDK_INT >= 32) {
            setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
          }
        }
        .build()
    val format = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()

    val track = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(format)
        .setBufferSizeInBytes(max(minimumBuffer, blockFrames * 2 * 4 * 6))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    audioTrack = track
    isRunning = true
    track.play()
    renderThread = Thread({ renderLoop(track) }, "gs-audio").apply {
      priority = Thread.MAX_PRIORITY
      start()
    }
    publishStats()
  }

  override fun stop() {
    isRunning = false
    renderThread?.join(500)
    renderThread = null
    audioTrack?.let { track ->
      runCatching { track.stop() }
      track.release()
    }
    audioTrack = null
    activeVoice?.let { voice -> callbackExecutor.execute { voice.onDone() } }
    activeVoice = null
    publishStats()
  }

  override fun release() {
    stop()
    callbackExecutor.shutdown()
  }

  private fun renderLoop(track: AudioTrack) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    var lastUnderrunCount = 0
    while (isRunning) {
      try {
        renderBlock()
        val written = track.write(mixBuffer, 0, mixBuffer.size, AudioTrack.WRITE_BLOCKING)
        if (written < 0) {
          Log.w(TAG, "AudioTrack write failed: $written")
          break
        }
        val underrunsNow = track.underrunCount
        if (underrunsNow != lastUnderrunCount) {
          underrunTotal += underrunsNow - lastUnderrunCount
          lastUnderrunCount = underrunsNow
          publishStats()
        }
      } catch (error: Exception) {
        Log.e(TAG, "render failed", error)
      }
    }
  }

  private fun publishStats() {
    mutableStats.value = AudioStats(name, isRunning, underrunTotal, stemChannels.size)
  }

  // -- public controls ---------------------------------------------------------------

  override fun loadStems(music: MusicManifest, stems: Map<String, Pcm>) {
    val channels = music.stems.map { spec ->
      val loopPcm = (stems[spec.id] ?: Pcm.silence((music.barMs * 4).toLong()))
          .toStereo()
          .withFrames(music.loopFrames(sampleRate))
      StemChannel(spec.id, loopPcm.data, dbToLinear(spec.gainDb).toFloat())
    }
    pendingCommands.add {
      stemChannels = channels
      beatsPerMinute = music.bpm
      beatsPerBarCount = music.beatsPerBar
      frameCounter = 0
      publishStats()
    }
  }

  override fun loadTheme(theme: Pcm?, gainDb: Double) {
    val stereoData = theme?.toStereo()?.data
    val baseGain = dbToLinear(gainDb).toFloat()
    pendingCommands.add {
      themeSamples = stereoData
      themeBaseGain = baseGain
      themePosition = 0
    }
  }

  override fun setScene(scene: AudioScene) {
    currentScene = scene
  }

  override fun setListenerOrientation(yawDeg: Double?, pitchDeg: Double?) {
    listenerYawDeg = yawDeg
    listenerPitchDeg = pitchDeg
  }

  override fun playVoice(pcm: Pcm, params: VoiceParams, onDone: () -> Unit) {
    val monoPcm = pcm.toMono()
    pendingCommands.add {
      activeVoice?.let { previous -> callbackExecutor.execute { previous.onDone() } }
      activeVoice = VoicePlayback(monoPcm.data, params, onDone)
    }
  }

  override fun stopVoice() {
    pendingCommands.add {
      activeVoice?.let { voice -> callbackExecutor.execute { voice.onDone() } }
      activeVoice = null
    }
  }

  override fun playSfx(sfx: Sfx, worldBearingDeg: Double?) {
    pendingCommands.add { activeSfx.add(SynthVoice.forSfx(sfx, worldBearingDeg)) }
  }

  override fun playUiSfx(sfx: UiSfx) {
    pendingCommands.add { activeSfx.add(SynthVoice.forUi(sfx)) }
  }

  override fun playRhythm(onsetsBeats: List<Double>, onDone: () -> Unit) {
    pendingCommands.add { nowFrame ->
      val framesPerBeat = 60.0 / beatsPerMinute * sampleRate
      // Start on the next beat (at least half a beat out) so it sits on the groove.
      var startFrame = (kotlin.math.ceil(nowFrame / framesPerBeat) * framesPerBeat).toLong()
      if (startFrame - nowFrame < framesPerBeat / 2) startFrame += framesPerBeat.toLong()
      for (onset in onsetsBeats) {
        val clickFrame = startFrame + (onset * framesPerBeat).toLong()
        scheduledActions.add(clickFrame to { activeSfx.add(SynthVoice.forUi(UiSfx.CLICK)) })
      }
      val lastOnset = onsetsBeats.maxOrNull() ?: 0.0
      val endFrame = startFrame + (lastOnset * framesPerBeat).toLong() + (framesPerBeat / 2).toLong()
      scheduledActions.add(endFrame to { callbackExecutor.execute { onDone() } })
    }
  }

  override fun setComms(mode: CommsMode) {
    commsMode = mode
  }

  override fun setMasterVolume(volume: Float) {
    masterVolume = volume.coerceIn(0f, 1f)
  }

  // -- rendering -----------------------------------------------------------------------

  private fun renderBlock() {
    while (true) {
      val command = pendingCommands.poll() ?: break
      command(frameCounter)
    }
    mixBuffer.fill(0f)
    musicBuffer.fill(0f)

    val scene = currentScene
    val headingDeg = listenerYawDeg
    val pauseFactor = if (scene.paused) 0.15f else 1f

    renderStems(scene, headingDeg, pauseFactor)
    renderTheme(scene, pauseFactor)
    renderStatic(scene, headingDeg, pauseFactor)
    renderPing(scene, headingDeg)
    mixMusicBus()
    renderVoice(headingDeg)
    renderEffects(headingDeg)
    runScheduledActions()

    val volume = masterVolume
    for (index in mixBuffer.indices) {
      mixBuffer[index] = softClip(mixBuffer[index] * volume)
    }
    frameCounter += blockFrames
  }

  /** Applies comms ducking (and mic-mode lo-fi) as music joins the mix. */
  private fun mixMusicBus() {
    val (duckTarget, lofiTarget) = when (commsMode) {
      CommsMode.NONE -> 1f to 0f
      CommsMode.VOICE -> 0.42f to 0f
      CommsMode.MIC -> 0.28f to 1f
    }
    duckRamp.begin(easeToward(duckRamp.value, duckTarget, 0.25f), blockFrames)
    lofiRamp.begin(easeToward(lofiRamp.value, lofiTarget, 0.2f), blockFrames)
    for (frame in 0 until blockFrames) {
      val duck = duckRamp.next()
      val lofiAmount = lofiRamp.next()
      var left = musicBuffer[2 * frame]
      var right = musicBuffer[2 * frame + 1]
      if (lofiAmount > 0.001f) {
        left += (radioBandLeft.process(left) * 1.6f - left) * lofiAmount
        right += (radioBandRight.process(right) * 1.6f - right) * lofiAmount
      }
      mixBuffer[2 * frame] += left * duck
      mixBuffer[2 * frame + 1] += right * duck
    }
    duckRamp.end()
    lofiRamp.end()
  }

  private fun easeToward(current: Float, target: Float, rate: Float): Float =
      current + (target - current) * rate

  private fun relativeBearing(worldBearingDeg: Double?, headingDeg: Double?): Double? =
      if (worldBearingDeg == null) null else Geo.deltaDeg(headingDeg ?: 0.0, worldBearingDeg)

  /** Source elevation relative to the head's pitch (0 when pitch is unknown). */
  private fun relativeElevation(sourceElevationDeg: Double): Double =
      sourceElevationDeg - (listenerPitchDeg ?: 0.0)

  private fun renderStems(scene: AudioScene, headingDeg: Double?, pauseFactor: Float) {
    if (stemChannels.isEmpty()) return
    val framesPerBar = (60.0 / beatsPerMinute * beatsPerBarCount * sampleRate).toLong()

    for (channel in stemChannels) {
      val stemState = scene.stems[channel.id]
      val targetGain = (stemState?.gain ?: 0.0).toFloat() * channel.baseGain * pauseFactor
      val wantsInHead = stemState == null || stemState.worldBearingDeg == null

      // Collected stems glide into the head exactly on the next bar line.
      if (wantsInHead && channel.spatialTarget > 0f) {
        if (channel.gainRamp.value < 0.02f || stemState == null) {
          channel.spatialTarget = 0f
        } else if (channel.headLockAtFrame < 0) {
          channel.headLockAtFrame = (frameCounter / framesPerBar + 1) * framesPerBar
        }
      } else if (!wantsInHead) {
        channel.spatialTarget = 1f
        channel.headLockAtFrame = -1
      }
      if (channel.headLockAtFrame in 0..frameCounter) {
        channel.spatialTarget = 0f
        channel.headLockAtFrame = -1
      }

      channel.gainRamp.begin(easeToward(channel.gainRamp.value, targetGain, 0.08f), blockFrames)
      channel.spatialRamp.begin(easeToward(channel.spatialRamp.value, channel.spatialTarget, 0.03f), blockFrames)
      if (channel.gainRamp.value < 1e-4f && targetGain < 1e-4f) {
        channel.gainRamp.end()
        channel.spatialRamp.end()
        continue
      }

      val clarity = (stemState?.clarity ?: 1.0).toFloat()
      channel.clarityFilter.setCutoff(500f + 15_500f * clarity * clarity)
      channel.crushHoldFrames = 1 + ((1f - clarity) * 7).toInt()

      val azimuth = relativeBearing(stemState?.worldBearingDeg, headingDeg)
      channel.panner.prepare(
          azimuth ?: 0.0, blockFrames, relativeElevation(stemState?.elevationDeg ?: 0.0),
      )

      val loopFrameCount = channel.samples.size / 2
      var position = (frameCounter % loopFrameCount).toInt()
      for (frame in 0 until blockFrames) {
        val leftSample = channel.samples[2 * position] / 32768f
        val rightSample = channel.samples[2 * position + 1] / 32768f
        val gain = channel.gainRamp.next()
        val spatialMix = channel.spatialRamp.next()

        val inHeadGain = gain * (1f - spatialMix)
        musicBuffer[2 * frame] += leftSample * inHeadGain
        musicBuffer[2 * frame + 1] += rightSample * inHeadGain

        if (spatialMix > 0.001f) {
          // Distant signals are band-limited and sample-held ("bit-rotted").
          if (++channel.crushCounter >= channel.crushHoldFrames) {
            channel.crushCounter = 0
            channel.crushedSample = channel.clarityFilter.process((leftSample + rightSample) * 0.5f)
          }
          channel.panner.process(channel.crushedSample * gain * spatialMix * 1.25f, musicBuffer, frame)
        }

        position++
        if (position >= loopFrameCount) position = 0
      }
      channel.gainRamp.end()
      channel.spatialRamp.end()
      channel.panner.finishBlock()
    }
  }

  /** The antagonist theme: everywhere at once, so it plays inside the head. */
  private fun renderTheme(scene: AudioScene, pauseFactor: Float) {
    val samples = themeSamples ?: return
    val targetGain = scene.antagonistLevel.toFloat().coerceIn(0f, 1f) * themeBaseGain * pauseFactor
    themeGainRamp.begin(easeToward(themeGainRamp.value, targetGain, 0.06f), blockFrames)
    if (themeGainRamp.value < 1e-4f && targetGain < 1e-4f) {
      themeGainRamp.end()
      return
    }
    val loopFrameCount = samples.size / 2
    for (frame in 0 until blockFrames) {
      val gain = themeGainRamp.next()
      musicBuffer[2 * frame] += samples[2 * themePosition] / 32768f * gain
      musicBuffer[2 * frame + 1] += samples[2 * themePosition + 1] / 32768f * gain
      if (++themePosition >= loopFrameCount) themePosition = 0
    }
    themeGainRamp.end()
  }

  private fun renderStatic(scene: AudioScene, headingDeg: Double?, pauseFactor: Float) {
    val targetLevel = scene.staticLevel.toFloat() * (if (scene.paused) 0f else pauseFactor)
    staticGainRamp.begin(easeToward(staticGainRamp.value, targetLevel, 0.1f), blockFrames)
    if (staticGainRamp.value < 1e-4f && targetLevel < 1e-4f) {
      staticGainRamp.end()
      return
    }
    staticPanner.prepare(
        relativeBearing(scene.staticBearingDeg, headingDeg) ?: 0.0,
        blockFrames,
        relativeElevation(scene.staticElevationDeg),
    )
    for (frame in 0 until blockFrames) {
      var noise = staticFilter.process(noiseSource.next()) * 0.8f
      // Occasional crackles, like a radio between stations.
      if (noiseSource.next() > 0.9985f) crackleLevel = 1f
      if (crackleLevel > 0.01f) {
        noise += noiseSource.next() * crackleLevel
        crackleLevel *= 0.93f
      }
      val gain = staticGainRamp.next() * 0.5f
      if (scene.staticBearingDeg == null) {
        musicBuffer[2 * frame] += noise * gain
        musicBuffer[2 * frame + 1] += noise * gain
      } else {
        staticPanner.process(noise * gain * 1.3f, musicBuffer, frame)
      }
    }
    staticGainRamp.end()
    staticPanner.finishBlock()
  }

  private fun renderPing(scene: AudioScene, headingDeg: Double?) {
    val intervalMs = scene.pingIntervalMs
    if (intervalMs != null && !scene.paused && frameCounter >= nextPingAtFrame) {
      pingSynth.trigger()
      nextPingAtFrame = frameCounter + intervalMs * sampleRate / 1000
    } else if (intervalMs == null) {
      nextPingAtFrame = frameCounter
    }
    if (!pingSynth.isActive) return
    pingSynth.panner.prepare(
        relativeBearing(scene.pingBearingDeg, headingDeg) ?: 0.0,
        blockFrames,
        relativeElevation(scene.pingElevationDeg),
    )
    for (frame in 0 until blockFrames) {
      pingSynth.panner.process(pingSynth.nextSample() * 0.35f, musicBuffer, frame)
    }
    pingSynth.panner.finishBlock()
  }

  private fun renderVoice(headingDeg: Double?) {
    val voice = activeVoice ?: return
    val azimuth = relativeBearing(voice.params.worldBearingDeg, headingDeg)
    if (azimuth != null) {
      voice.panner.prepare(azimuth, blockFrames, relativeElevation(voice.params.elevationDeg))
    }
    val radioAmount = voice.params.radio.toFloat()
    val voiceGain = voice.params.gain.toFloat() * 1.05f

    for (frame in 0 until blockFrames) {
      if (voice.position >= voice.samples.size) break
      var sample = voice.samples[voice.position++] / 32768f
      if (radioAmount > 0.01f) {
        val bandSample = voice.radioBand.process(sample) * (1f + 2f * radioAmount)
        val crushed = softClip(bandSample * (1f + 3f * radioAmount))
        sample += (crushed - sample) * radioAmount
        sample += noiseSource.next() * 0.012f * radioAmount
      }
      if (azimuth != null) {
        voice.panner.process(sample * voiceGain * 1.2f, mixBuffer, frame)
      } else {
        mixBuffer[2 * frame] += sample * voiceGain * 0.85f
        mixBuffer[2 * frame + 1] += sample * voiceGain * 0.85f
      }
    }

    if (azimuth != null) voice.panner.finishBlock()
    if (voice.position >= voice.samples.size) {
      activeVoice = null
      callbackExecutor.execute { voice.onDone() }
    }
  }

  private fun renderEffects(headingDeg: Double?) {
    if (activeSfx.isEmpty()) return
    val iterator = activeSfx.iterator()
    while (iterator.hasNext()) {
      val effect = iterator.next()
      val azimuth = relativeBearing(effect.worldBearingDeg, headingDeg)
      if (azimuth != null) effect.binaural.prepare(azimuth, blockFrames)
      for (frame in 0 until blockFrames) {
        val sample = effect.next()
        if (azimuth != null) {
          effect.binaural.process(sample * 1.2f, mixBuffer, frame)
        } else {
          mixBuffer[2 * frame] += sample
          mixBuffer[2 * frame + 1] += sample
        }
      }
      if (azimuth != null) effect.binaural.finishBlock()
      if (effect.done) iterator.remove()
    }
  }

  private fun runScheduledActions() {
    if (scheduledActions.isEmpty()) return
    val dueActions = scheduledActions.filter { it.first <= frameCounter + blockFrames }
    if (dueActions.isEmpty()) return
    scheduledActions.removeAll(dueActions.toSet())
    dueActions.forEach { it.second() }
  }

  private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

  // -- per-source state -----------------------------------------------------------------

  private class StemChannel(val id: String, val samples: ShortArray, val baseGain: Float) {
    val gainRamp = Ramp(0f)
    val spatialRamp = Ramp(1f)
    var spatialTarget = 1f
    var headLockAtFrame = -1L
    val panner = Binaural()
    val clarityFilter = OnePoleLp(16_000f)
    var crushHoldFrames = 1
    var crushCounter = 0
    var crushedSample = 0f
  }

  private class VoicePlayback(
      val samples: ShortArray,
      val params: VoiceParams,
      val onDone: () -> Unit,
  ) {
    var position = 0
    val panner = Binaural()
    val radioBand = RadioBand()
  }

  /** Sonar ping: a bright two-tone blip with a fast decay. */
  private class PingSynth {
    val panner = Binaural()
    private var sampleIndex = 0
    private val lengthFrames = (0.18 * Pcm.SAMPLE_RATE).toInt()

    var isActive: Boolean = false
      private set

    fun trigger() {
      sampleIndex = 0
      isActive = true
    }

    fun nextSample(): Float {
      if (!isActive) return 0f
      val timeS = sampleIndex.toDouble() / Pcm.SAMPLE_RATE
      val envelope = exp(-timeS * 28).toFloat()
      val fundamental = sin(2 * PI * 1_650 * timeS)
      val harmonic = 0.3 * sin(2 * PI * 3_300 * timeS)
      if (++sampleIndex >= lengthFrames) isActive = false
      return ((fundamental + harmonic) * envelope).toFloat()
    }
  }

  private companion object {
    const val TAG = "GhostSignalAudio"
  }
}
