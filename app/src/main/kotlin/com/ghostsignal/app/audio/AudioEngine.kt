package com.ghostsignal.app.audio

import com.ghostsignal.core.engine.AudioScene
import com.ghostsignal.core.engine.Sfx
import com.ghostsignal.core.model.MusicManifest
import kotlinx.coroutines.flow.StateFlow

/** How the music should make room for voices. */
enum class CommsMode {
  /** Normal mix. */
  NONE,
  /** A character is talking: duck the music. */
  VOICE,
  /** The mic is open: duck harder and squash the music into a walkie-talkie band. */
  MIC,
}

/** Extra sounds the audio engine generates itself (not driven by the game rules). */
enum class UiSfx {
  MIC_OPEN,
  MIC_CLOSE,
  CLICK,
}

data class VoiceParams(
    /** World bearing to voice the line from, or null for inside the head. */
    val worldBearingDeg: Double?,
    /** 0 = clean, 1 = crushed walkie-talkie. */
    val radio: Double,
    val gain: Double = 1.0,
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
 * [BinauralMixer] (pure Kotlin, always available) and [FmodAudioEngine] (FMOD + Resonance Audio,
 * when the FMOD SDK is present at build time).
 */
interface AudioEngine {
  val name: String
  val stats: StateFlow<AudioStats>

  fun start()

  fun stop()

  /** Loads the soundtrack; every stem loops in sync from the moment playback starts. */
  fun loadStems(music: MusicManifest, stems: Map<String, Pcm>)

  /**
   * Loads the antagonist's looping theme (or clears it with null). It plays in the head at the
   * scene's [AudioScene.antagonistLevel]; the scene already pushes the stems down underneath it.
   */
  fun loadTheme(theme: Pcm?, gainDb: Double)

  fun setScene(scene: AudioScene)

  /** Called at IMU rate so world-anchored sounds stay put when the head turns. */
  fun setListenerHeading(deg: Double?)

  /** Plays one line; [onDone] fires once when it ends or is stopped. */
  fun playVoice(pcm: Pcm, params: VoiceParams, onDone: () -> Unit)

  fun stopVoice()

  fun playSfx(sfx: Sfx, worldBearingDeg: Double? = null)

  fun playUiSfx(sfx: UiSfx)

  /** Clicks the pattern on the beat grid; [onDone] fires after the last click. */
  fun playRhythm(onsetsBeats: List<Double>, onDone: () -> Unit)

  fun setComms(mode: CommsMode)

  fun setMasterVolume(volume: Float)

  fun release()
}
