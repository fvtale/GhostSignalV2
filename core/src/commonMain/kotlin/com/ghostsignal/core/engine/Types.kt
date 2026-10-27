package com.ghostsignal.core.engine

import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.heading.HeadingEstimate
import kotlinx.serialization.Serializable

enum class Phase {
  IDLE,
  BRIEFING,
  HUNTING,
  ENCOUNTER,
  COLLECTING,
  FINALE,
  COMPLETE,
}

/** Everything a runner can do with the glasses (or the phone's fallback buttons). */
enum class PlayerAction {
  /** Capture button press: lock a signal, confirm, or open the mic, depending on context. */
  SCAN,
  /** Touchpad tap: rhythm input, otherwise the same as SCAN. */
  TAP,
  /** Capture hold: talk to whoever is on the channel. */
  TALK,
  REPEAT,
  HINT,
  STATUS,
  PAUSE_TOGGLE,
}

enum class ListenMode {
  /** Short commands and passphrases. */
  COMMAND,
  /** Open questions for the character agent. */
  FREEFORM,
}

enum class Sfx {
  SIGNAL_ACQUIRED,
  LOCK_AVAILABLE,
  LOCK,
  DENIED,
  COLLECT,
  PAUSE,
  RESUME,
  RECORD_START,
  TRANSMISSION,
  FINALE,
}

/**
 * One line to voice. [fromSignal] places the voice at that signal in the world (characters speak
 * from where their relay is); null means it plays inside the runner's head (the handler and the
 * SmartSpecs).
 */
data class Utterance(
    val lineId: String?,
    val speaker: String,
    val text: String,
    val fromSignal: String? = null,
)

data class Turn(val fromPlayer: Boolean, val text: String)

data class CharacterContext(
    val phase: Phase,
    val objective: String,
    val signalName: String?,
    val place: String?,
    val distance: String?,
    val direction: String?,
    val collected: Int,
    val total: Int,
    val flags: Map<String, String>,
    val challenge: String?,
)

data class CharacterRequest(
    val requestId: Int,
    val characterId: String,
    val signalId: String?,
    val utterance: String,
    val context: CharacterContext,
    val history: List<Turn>,
)

sealed interface Effect {
  /** Voice the utterances in order. Report completion with [GameEvent.SpeechFinished] + [token]. */
  data class Speak(val utterances: List<Utterance>, val token: String, val interruptible: Boolean) : Effect

  data object StopSpeaking : Effect

  data class PlaySfx(val sfx: Sfx) : Effect

  /** Open a listening window. Report [GameEvent.Heard] and then [GameEvent.ListenEnded]. */
  data class Listen(val mode: ListenMode, val timeoutMs: Long, val token: String, val hints: List<String>) : Effect

  data object StopListening : Effect

  data class AskCharacter(val request: CharacterRequest) : Effect

  data class StartRecording(val signalId: String, val maxSeconds: Int) : Effect

  data object StopRecording : Effect

  /** Play the clicks of a rhythm on the beat grid; report [GameEvent.RhythmPlayed]. */
  data class PlayRhythm(val onsetsBeats: List<Double>, val token: String) : Effect

  /** Bring a stem into the mix (quantized to the next bar by the audio engine). */
  data class UnlockStem(val stemId: String) : Effect

  data class Persist(val progress: Progress) : Effect
}

sealed interface GameEvent {
  data class Start(val nowMs: Long, val resume: Progress? = null) : GameEvent

  data class Position(val fix: GeoFix) : GameEvent

  data class Heading(val estimate: HeadingEstimate) : GameEvent

  /** [deviceTimeMs] is the glasses' own timestamp, used for rhythm timing when available. */
  data class Input(val action: PlayerAction, val nowMs: Long, val deviceTimeMs: Long? = null) : GameEvent

  data class Heard(val token: String, val text: String, val isFinal: Boolean, val confidence: Double) : GameEvent

  data class ListenEnded(val token: String) : GameEvent

  data class SpeechFinished(val token: String) : GameEvent

  data class RhythmPlayed(val token: String, val nowMs: Long) : GameEvent

  data class CharacterReplied(val requestId: Int, val text: String?) : GameEvent

  data class RecordingFinished(val path: String?) : GameEvent

  data class Tick(val nowMs: Long) : GameEvent

  /** A live message from the event operator, voiced by [speaker] when the runner is free. */
  data class Broadcast(val speaker: String, val text: String) : GameEvent

  /** Operator overrides for running a live event. */
  data object ForceLock : GameEvent

  data object ForceSolve : GameEvent

  data class JumpTo(val signalIndex: Int) : GameEvent
}

@Serializable
data class Progress(
    val missionId: String,
    val signalIndex: Int,
    val collected: List<String>,
    val flags: Map<String, String>,
    val complete: Boolean = false,
)

data class TranscriptEntry(val speaker: String, val text: String, val fromPlayer: Boolean)
