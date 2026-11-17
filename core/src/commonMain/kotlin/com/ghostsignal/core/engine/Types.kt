package com.ghostsignal.core.engine

import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.heading.HeadingEstimate
import kotlinx.serialization.Serializable

// The engine's vocabulary: phases the run moves through, inputs the runner
// can make, effects the host app must perform, and events it reports back.
// Long-running effects carry a token; the matching completion event quotes
// that token so stale completions can be ignored safely.

/** Stages of a run, in the order a clean playthrough visits them. */
enum class Phase { IDLE, BRIEFING, HUNTING, ENCOUNTER, COLLECTING, FINALE, COMPLETE }

/** Everything the runner can do from the glasses or the phone fallback UI. */
enum class PlayerAction {
  /** Capture press: lock, confirm, or open the mic, depending on context. */
  SCAN,
  /** Touchpad tap: rhythm input; otherwise behaves like [SCAN]. */
  TAP,
  /** Capture hold: talk on the open channel. */
  TALK,
  REPEAT,
  HINT,
  STATUS,
  PAUSE_TOGGLE,
}

/** What a listening window is for, which shapes how transcripts are judged. */
enum class ListenMode {
  /** Short commands and passphrases. */
  COMMAND,
  /** Open questions for the character agent. */
  FREEFORM,
}

/** One-shot sound effects the host renders locally. */
enum class Sfx {
  SIGNAL_ACQUIRED, LOCK_AVAILABLE, LOCK, DENIED, COLLECT,
  PAUSE, RESUME, RECORD_START, TRANSMISSION, FINALE,
}

/**
 * One line to be voiced. [fromSignal] anchors the voice at that signal's
 * position in the world; null plays inside the runner's head (handler and
 * system voice).
 */
data class Utterance(
    val lineId: String?,
    val speaker: String,
    val text: String,
    val fromSignal: String? = null,
)

data class Turn(val fromPlayer: Boolean, val text: String)

/** Snapshot of game state handed to the character agent with each request. */
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

/** Instructions from the engine to the host. The host performs, then reports. */
sealed interface Effect {
  /** Voice [utterances] in order; report [GameEvent.SpeechFinished] with [token]. */
  data class Speak(val utterances: List<Utterance>, val token: String, val interruptible: Boolean) : Effect
  data object StopSpeaking : Effect
  data class PlaySfx(val sfx: Sfx) : Effect
  /** Open a listening window; report [GameEvent.Heard], then [GameEvent.ListenEnded]. */
  data class Listen(val mode: ListenMode, val timeoutMs: Long, val token: String, val hints: List<String>) : Effect
  data object StopListening : Effect
  data class AskCharacter(val request: CharacterRequest) : Effect
  data class StartRecording(val signalId: String, val maxSeconds: Int) : Effect
  data object StopRecording : Effect
  /** Play a rhythm on the beat grid; report [GameEvent.RhythmPlayed]. */
  data class PlayRhythm(val onsetsBeats: List<Double>, val token: String) : Effect
  /** Bring a collected stem into the mix (hosts quantize to the next bar). */
  data class UnlockStem(val stemId: String) : Effect
  data class Persist(val progress: Progress) : Effect
}

/** Everything the host can tell the engine. */
sealed interface GameEvent {
  data class Start(val nowMs: Long, val resume: Progress? = null) : GameEvent
  data class Position(val fix: GeoFix) : GameEvent
  data class Heading(val estimate: HeadingEstimate) : GameEvent
  /** [deviceTimeMs] is the glasses' own clock, preferred for rhythm timing. */
  data class Input(val action: PlayerAction, val nowMs: Long, val deviceTimeMs: Long? = null) : GameEvent
  data class Heard(val token: String, val text: String, val isFinal: Boolean, val confidence: Double) : GameEvent
  data class ListenEnded(val token: String) : GameEvent
  data class SpeechFinished(val token: String) : GameEvent
  data class RhythmPlayed(val token: String, val nowMs: Long) : GameEvent
  data class CharacterReplied(val requestId: Int, val text: String?) : GameEvent
  data class RecordingFinished(val path: String?) : GameEvent
  data class Tick(val nowMs: Long) : GameEvent
  /** Live operator message, voiced by [speaker] once the runner is free. */
  data class Broadcast(val speaker: String, val text: String) : GameEvent
  /** Operator overrides for running a live event. */
  data object ForceLock : GameEvent
  data object ForceSolve : GameEvent
  data class JumpTo(val signalIndex: Int) : GameEvent
}

/** Serializable run progress, persisted by the host. */
@Serializable
data class Progress(
    val missionId: String,
    val signalIndex: Int,
    val collected: List<String>,
    val flags: Map<String, String>,
    val complete: Boolean = false,
)

data class TranscriptEntry(val speaker: String, val text: String, val fromPlayer: Boolean)
