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
 * The built-in renderer: one AudioTrack, a real-time mixing thread, and a spherical-head binaural
 * panner per source.
 *
 * Signal flow:
 * ```
 *  stems (looping, sample-locked) ─┬─ in-head (stereo) ───────────┐
 *                                  └─ beacon: radio mush ─ binaural ┤
 *  static + sonar ping (binaural) ─────────────────────────────────┤
 *  antagonist theme (in-head, at the scene's level) ───────────────┤─ music bus ─ duck/lo-fi ─┐
 *  voices (radio band, binaural or centred) ──────────────────────────────────────────────────┤─ master ─ soft clip
 *  sfx, rhythm clicks ────────────────────────────────────────────────────────────────────────┘
 * ```
 * Bearings arrive in world coordinates; the live head heading is subtracted per block so head
 * turns update within ~10 ms of the IMU sample (plus Bluetooth latency).
 */
class BinauralMixer : AudioEngine {
  override val name = "Built-in binaural"
  private val _stats = MutableStateFlow(AudioStats(name, false, 0, 0))
  override val stats: StateFlow<AudioStats> = _stats

  private val sr = Pcm.SAMPLE_RATE
  private val block = 480
  private var track: AudioTrack? = null
  private var thread: Thread? = null
  @Volatile private var running = false
  private val callbacks = Executors.newSingleThreadExecutor { r -> Thread(r, "gs-audio-callbacks") }
  private val commands = ConcurrentLinkedQueue<(Long) -> Unit>()

  @Volatile private var scene: AudioScene = AudioScene.SILENT
  @Volatile private var heading: Double? = null
  @Volatile private var pitch: Double? = null
  @Volatile private var comms = CommsMode.NONE
  @Volatile private var masterVolume = 0.9f

  // Audio-thread state ------------------------------------------------------------------------
  private var frame = 0L
  private var bpm = 120.0
  private var beatsPerBar = 4
  private var stems: List<StemChannel> = emptyList()
  private var theme: ShortArray? = null
  private var themeBase = 1f
  private var themePos = 0
  private val themeGain = Ramp()
  private val mix = FloatArray(block * 2)
  private val musicBus = FloatArray(block * 2)
  private val noise = Noise()
  private val staticLp = OnePoleLp(2_500f)
  private val staticPan = Binaural()
  private val staticGain = Ramp()
  private var crackle = 0f
  private val ping = PingVoice()
  private var nextPingFrame = 0L
  private var voice: VoiceSlot? = null
  private val sfx = mutableListOf<SynthVoice>()
  private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
  private val duck = Ramp(1f)
  private val lofi = Ramp(0f)
  private val bandL = RadioBand()
  private val bandR = RadioBand()
  private var underruns = 0

  override fun start() {
    if (running) return
    val minBuf =
        AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
    val attrs =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
              // We do our own spatialisation; keep the platform spatializer out of it.
              if (Build.VERSION.SDK_INT >= 32) setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
            }
            .build()
    val t =
        AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build())
            .setBufferSizeInBytes(max(minBuf, block * 2 * 4 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    track = t
    running = true
    t.play()
    thread =
        Thread({ renderLoop(t) }, "gs-audio").apply {
          priority = Thread.MAX_PRIORITY
          start()
        }
    publishStats()
  }

  override fun stop() {
    running = false
    thread?.join(500)
    thread = null
    track?.let {
      runCatching { it.stop() }
      it.release()
    }
    track = null
    voice?.let { v -> callbacks.execute { v.onDone() } }
    voice = null
    publishStats()
  }

  override fun release() {
    stop()
    callbacks.shutdown()
  }

  private fun renderLoop(t: AudioTrack) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    var lastUnderrun = 0
    while (running) {
      try {
        render()
        val written = t.write(mix, 0, mix.size, AudioTrack.WRITE_BLOCKING)
        if (written < 0) {
          Log.w(TAG, "AudioTrack write failed: $written")
          break
        }
        val u = t.underrunCount
        if (u != lastUnderrun) {
          underruns += u - lastUnderrun
          lastUnderrun = u
          publishStats()
        }
      } catch (e: Exception) {
        Log.e(TAG, "render failed", e)
      }
    }
  }

  private fun publishStats() {
    _stats.value = AudioStats(name, running, underruns, stems.size)
  }

  // ----------------------------------------------------------------------- public controls

  override fun loadStems(music: MusicManifest, stems: Map<String, Pcm>) {
    val channels =
        music.stems.map { spec ->
          val pcm = (stems[spec.id] ?: Pcm.silence((music.barMs * 4).toLong())).toStereo().withFrames(music.loopFrames(sr))
          StemChannel(spec.id, pcm.data, 10.0.pow(spec.gainDb / 20.0).toFloat())
        }
    commands.add {
      this.stems = channels
      bpm = music.bpm
      beatsPerBar = music.beatsPerBar
      frame = 0
      publishStats()
    }
  }

  override fun loadTheme(theme: Pcm?, gainDb: Double) {
    val data = theme?.toStereo()?.data
    val base = 10.0.pow(gainDb / 20.0).toFloat()
    commands.add {
      this.theme = data
      themeBase = base
      themePos = 0
    }
  }

  override fun setScene(scene: AudioScene) {
    this.scene = scene
  }

  override fun setListenerOrientation(yawDeg: Double?, pitchDeg: Double?) {
    heading = yawDeg
    pitch = pitchDeg
  }

  override fun playVoice(pcm: Pcm, params: VoiceParams, onDone: () -> Unit) {
    val mono = pcm.toMono()
    commands.add {
      voice?.let { old -> callbacks.execute { old.onDone() } }
      voice = VoiceSlot(mono.data, params, onDone)
    }
  }

  override fun stopVoice() {
    commands.add {
      voice?.let { v -> callbacks.execute { v.onDone() } }
      voice = null
    }
  }

  override fun playSfx(sfx: Sfx, worldBearingDeg: Double?) {
    commands.add { this.sfx.add(SynthVoice.forSfx(sfx, worldBearingDeg)) }
  }

  override fun playUiSfx(sfx: UiSfx) {
    commands.add { this.sfx.add(SynthVoice.forUi(sfx)) }
  }

  override fun playRhythm(onsetsBeats: List<Double>, onDone: () -> Unit) {
    commands.add { now ->
      val beatFrames = 60.0 / bpm * sr
      // Start on the next beat (at least half a beat away) so the pattern sits on the groove.
      var start = (kotlin.math.ceil(now / beatFrames) * beatFrames).toLong()
      if (start - now < beatFrames / 2) start += beatFrames.toLong()
      onsetsBeats.forEach { b ->
        scheduled.add((start + (b * beatFrames).toLong()) to { sfx.add(SynthVoice.forUi(UiSfx.CLICK)) })
      }
      val end = start + ((onsetsBeats.maxOrNull() ?: 0.0) * beatFrames).toLong() + (beatFrames / 2).toLong()
      scheduled.add(end to { callbacks.execute { onDone() } })
    }
  }

  override fun setComms(mode: CommsMode) {
    comms = mode
  }

  override fun setMasterVolume(volume: Float) {
    masterVolume = volume.coerceIn(0f, 1f)
  }

  // ----------------------------------------------------------------------- rendering

  private fun render() {
    while (true) commands.poll()?.invoke(frame) ?: break
    mix.fill(0f)
    musicBus.fill(0f)
    val sc = scene
    val head = heading

    val pausedFactor = if (sc.paused) 0.15f else 1f
    renderStems(sc, head, pausedFactor)
    renderTheme(sc, pausedFactor)
    renderStatic(sc, head, pausedFactor)
    renderPing(sc, head)

    // Duck and lo-fi the music bus when someone talks or the mic is open.
    val (duckTarget, lofiTarget) =
        when (comms) {
          CommsMode.NONE -> 1f to 0f
          CommsMode.VOICE -> 0.42f to 0f
          CommsMode.MIC -> 0.28f to 1f
        }
    duck.begin(approach(duck.value, duckTarget, 0.25f), block)
    lofi.begin(approach(lofi.value, lofiTarget, 0.2f), block)
    for (i in 0 until block) {
      val d = duck.next()
      val lf = lofi.next()
      var l = musicBus[2 * i]
      var r = musicBus[2 * i + 1]
      if (lf > 0.001f) {
        l += (bandL.process(l) * 1.6f - l) * lf
        r += (bandR.process(r) * 1.6f - r) * lf
      }
      mix[2 * i] += l * d
      mix[2 * i + 1] += r * d
    }
    duck.end()
    lofi.end()

    renderVoice(head)
    renderSfx(head)
    runScheduled()

    val mv = masterVolume
    for (i in mix.indices) mix[i] = softClip(mix[i] * mv)
    frame += block
  }

  private fun approach(current: Float, target: Float, rate: Float) = current + (target - current) * rate

  private fun relative(worldBearing: Double?, head: Double?): Double? =
      if (worldBearing == null) null else Geo.deltaDeg(head ?: 0.0, worldBearing)

  /** Source elevation relative to where the head is pitched; 0 when pitch is unknown. */
  private fun relativeElevation(sourceElevationDeg: Double): Double = sourceElevationDeg - (pitch ?: 0.0)

  private fun renderStems(sc: AudioScene, head: Double?, pausedFactor: Float) {
    if (stems.isEmpty()) return
    val barFrames = (60.0 / bpm * beatsPerBar * sr).toLong()
    for (ch in stems) {
      val st = sc.stems[ch.id]
      val targetGain = ((st?.gain ?: 0.0).toFloat() * ch.baseGain * pausedFactor)
      val wantsHead = st == null || st.worldBearingDeg == null
      // Collected stems glide into the head on the next bar line.
      if (wantsHead && ch.spatialTarget > 0f) {
        if (ch.gain.value < 0.02f || st == null) ch.spatialTarget = 0f
        else if (ch.headLockAt < 0) ch.headLockAt = (frame / barFrames + 1) * barFrames
      } else if (!wantsHead) {
        ch.spatialTarget = 1f
        ch.headLockAt = -1
      }
      if (ch.headLockAt in 0..frame) {
        ch.spatialTarget = 0f
        ch.headLockAt = -1
      }
      ch.gain.begin(approach(ch.gain.value, targetGain, 0.08f), block)
      ch.spatial.begin(approach(ch.spatial.value, ch.spatialTarget, 0.03f), block)
      if (ch.gain.value < 1e-4f && targetGain < 1e-4f) {
        ch.gain.end()
        ch.spatial.end()
        continue
      }
      val clarity = (st?.clarity ?: 1.0).toFloat()
      ch.clarityLp.setCutoff(500f + 15_500f * clarity * clarity)
      ch.crushHold = (1 + ((1f - clarity) * 7).toInt())
      val az = relative(st?.worldBearingDeg, head)
      ch.binaural.prepare(az ?: 0.0, block, relativeElevation(st?.elevationDeg ?: 0.0))
      val frames = ch.data.size / 2
      var pos = (frame % frames).toInt()
      for (i in 0 until block) {
        val l = ch.data[2 * pos] / 32768f
        val r = ch.data[2 * pos + 1] / 32768f
        val g = ch.gain.next()
        val s = ch.spatial.next()
        val inHead = g * (1f - s)
        musicBus[2 * i] += l * inHead
        musicBus[2 * i + 1] += r * inHead
        if (s > 0.001f) {
          // Distant signals are band-limited and sample-and-hold "bit-rotted".
          if (++ch.crushCount >= ch.crushHold) {
            ch.crushCount = 0
            ch.crushed = ch.clarityLp.process((l + r) * 0.5f)
          }
          ch.binaural.process(ch.crushed * g * s * 1.25f, musicBus, i)
        }
        pos++
        if (pos >= frames) pos = 0
      }
      ch.gain.end()
      ch.spatial.end()
      ch.binaural.finishBlock()
    }
  }

  /** The antagonist's theme: everywhere at once, so it plays inside the head rather than from a place. */
  private fun renderTheme(sc: AudioScene, pausedFactor: Float) {
    val data = theme ?: return
    val target = sc.antagonistLevel.toFloat().coerceIn(0f, 1f) * themeBase * pausedFactor
    themeGain.begin(approach(themeGain.value, target, 0.06f), block)
    if (themeGain.value < 1e-4f && target < 1e-4f) {
      themeGain.end()
      return
    }
    val frames = data.size / 2
    for (i in 0 until block) {
      val g = themeGain.next()
      musicBus[2 * i] += data[2 * themePos] / 32768f * g
      musicBus[2 * i + 1] += data[2 * themePos + 1] / 32768f * g
      if (++themePos >= frames) themePos = 0
    }
    themeGain.end()
  }

  private fun renderStatic(sc: AudioScene, head: Double?, pausedFactor: Float) {
    val level = (sc.staticLevel.toFloat() * (if (sc.paused) 0f else pausedFactor))
    staticGain.begin(approach(staticGain.value, level, 0.1f), block)
    if (staticGain.value < 1e-4f && level < 1e-4f) {
      staticGain.end()
      return
    }
    staticPan.prepare(relative(sc.staticBearingDeg, head) ?: 0.0, block, relativeElevation(sc.staticElevationDeg))
    for (i in 0 until block) {
      var n = staticLp.process(noise.next()) * 0.8f
      // Occasional crackles, like a radio between stations.
      if (noise.next() > 0.9985f) crackle = 1f
      if (crackle > 0.01f) {
        n += noise.next() * crackle
        crackle *= 0.93f
      }
      val g = staticGain.next() * 0.5f
      if (sc.staticBearingDeg == null) {
        musicBus[2 * i] += n * g
        musicBus[2 * i + 1] += n * g
      } else {
        staticPan.process(n * g * 1.3f, musicBus, i)
      }
    }
    staticGain.end()
    staticPan.finishBlock()
  }

  private fun renderPing(sc: AudioScene, head: Double?) {
    val interval = sc.pingIntervalMs
    if (interval != null && !sc.paused && frame >= nextPingFrame) {
      ping.trigger()
      nextPingFrame = frame + interval * sr / 1000
    } else if (interval == null) {
      nextPingFrame = frame
    }
    if (!ping.active) return
    ping.binaural.prepare(relative(sc.pingBearingDeg, head) ?: 0.0, block, relativeElevation(sc.pingElevationDeg))
    for (i in 0 until block) ping.binaural.process(ping.next() * 0.35f, musicBus, i)
    ping.binaural.finishBlock()
  }

  private fun renderVoice(head: Double?) {
    val v = voice ?: return
    val az = relative(v.params.worldBearingDeg, head)
    if (az != null) v.binaural.prepare(az, block, relativeElevation(v.params.elevationDeg))
    val radio = v.params.radio.toFloat()
    val g = v.params.gain.toFloat() * 1.05f
    for (i in 0 until block) {
      if (v.pos >= v.data.size) break
      var x = v.data[v.pos++] / 32768f
      if (radio > 0.01f) {
        val band = v.band.process(x) * (1f + 2f * radio)
        x = x + (softClip(band * (1f + 3f * radio)) - x) * radio
        x += noise.next() * 0.012f * radio
      }
      if (az != null) {
        v.binaural.process(x * g * 1.2f, mix, i)
      } else {
        mix[2 * i] += x * g * 0.85f
        mix[2 * i + 1] += x * g * 0.85f
      }
    }
    if (az != null) v.binaural.finishBlock()
    if (v.pos >= v.data.size) {
      voice = null
      callbacks.execute { v.onDone() }
    }
  }

  private fun renderSfx(head: Double?) {
    if (sfx.isEmpty()) return
    val it = sfx.iterator()
    while (it.hasNext()) {
      val s = it.next()
      val az = relative(s.worldBearingDeg, head)
      if (az != null) s.binaural.prepare(az, block)
      for (i in 0 until block) {
        val x = s.next()
        if (az != null) s.binaural.process(x * 1.2f, mix, i)
        else {
          mix[2 * i] += x
          mix[2 * i + 1] += x
        }
      }
      if (az != null) s.binaural.finishBlock()
      if (s.done) it.remove()
    }
  }

  private fun runScheduled() {
    if (scheduled.isEmpty()) return
    val due = scheduled.filter { it.first <= frame + block }
    if (due.isEmpty()) return
    scheduled.removeAll(due.toSet())
    due.forEach { it.second() }
  }

  // ----------------------------------------------------------------------- voices

  private class StemChannel(val id: String, val data: ShortArray, val baseGain: Float) {
    val gain = Ramp(0f)
    val spatial = Ramp(1f)
    var spatialTarget = 1f
    var headLockAt = -1L
    val binaural = Binaural()
    val clarityLp = OnePoleLp(16_000f)
    var crushHold = 1
    var crushCount = 0
    var crushed = 0f
  }

  private class VoiceSlot(val data: ShortArray, val params: VoiceParams, val onDone: () -> Unit) {
    var pos = 0
    val binaural = Binaural()
    val band = RadioBand()
  }

  private class PingVoice {
    val binaural = Binaural()
    private var t = 0
    private val length = (0.18 * Pcm.SAMPLE_RATE).toInt()
    var active = false
      private set

    fun trigger() {
      t = 0
      active = true
    }

    fun next(): Float {
      if (!active) return 0f
      val time = t.toDouble() / Pcm.SAMPLE_RATE
      val env = exp(-time * 28).toFloat()
      val x = (sin(2 * PI * 1_650 * time) + 0.3 * sin(2 * PI * 3_300 * time)).toFloat() * env
      if (++t >= length) active = false
      return x
    }
  }

  companion object {
    private const val TAG = "GhostSignalAudio"
  }
}
