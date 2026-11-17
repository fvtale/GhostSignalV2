package com.ghostsignal.app.audio

import com.ghostsignal.core.engine.AudioScene
import com.ghostsignal.core.engine.Sfx
import com.ghostsignal.core.model.MusicManifest
import kotlinx.coroutines.flow.StateFlow

/** How the music should make room for the voice channel. */
enum class CommsMode {
  /** Normal mix. */
  NONE,

  /** A character is talking: duck the music under the voice. */
  VOICE,

  /** The mic is open: duck harder and narrow the music to a radio band. */
  MIC,
}

/** Sounds the engine generates itself (not driven by the game rules). */
enum class UiSfx {
  MIC_OPEN,
  MIC_CLOSE,
  CLICK,
}

/** Placement and treatment for one spoken line. */
data class VoiceParams(
    /** World bearing to voice the line from, or null for inside the head. */
    val worldBearingDeg: Double?,
    /** 0 = clean studio voice, 1 = crushed walkie-talkie. */
    val radio: Double,
    val gain: Double = 1.0,
    /** Authored source elevation in degrees (0 = horizontal plane). */
    val elevationDeg: Double = 0.0,
)

data class AudioStats(
    val engine: String,
    val running: Boolean,
    val underruns: Int,
    val loadedStems: Int,
    val note: String = "",
)

/**
 * Everything the game needs from an audio renderer. Two implementations:
 * [BinauralMixer] (pure Kotlin, always available) and [FmodAudioEngine]
 * (FMOD with Resonance Audio, when built with the FMOD SDK).
 */
interface AudioEngine {

  val name: String
  val stats: StateFlow<AudioStats>

  fun start()

  fun stop()

  /** Loads a soundtrack; every stem loops sample-locked from playback start. */
  fun loadStems(music: MusicManifest, stems: Map<String, Pcm>)

  /**
   * Loads the antagonist's looping theme (null clears it). It plays in the
   * head at the scene's antagonist level; the scene already ducks the
   * stems underneath it.
   */
  fun loadTheme(theme: Pcm?, gainDb: Double)

  fun setScene(scene: AudioScene)

  /**
   * Called at IMU rate so world-anchored sound stays put as the head turns
   * or nods. Pitch is carried for elevation rendering; engines that cannot
   * render it yet still receive it.
   */
  fun setListenerOrientation(yawDeg: Double?, pitchDeg: Double?)

  /** Plays one line; [onDone] fires once, when it ends or is stopped. */
  fun playVoice(pcm: Pcm, params: VoiceParams, onDone: () -> Unit)

  fun stopVoice()

  fun playSfx(sfx: Sfx, worldBearingDeg: Double? = null)

  fun playUiSfx(sfx: UiSfx)

  /** Clicks a rhythm on the beat grid; [onDone] after the last click. */
  fun playRhythm(onsetsBeats: List<Double>, onDone: () -> Unit)

  fun setComms(mode: CommsMode)

  fun setMasterVolume(volume: Float)

  fun release()
}
