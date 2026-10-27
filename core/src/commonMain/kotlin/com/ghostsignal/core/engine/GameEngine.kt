package com.ghostsignal.core.engine

import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.heading.HeadingEstimate
import com.ghostsignal.core.heading.HeadingSource
import com.ghostsignal.core.model.Challenge
import com.ghostsignal.core.model.Line
import com.ghostsignal.core.model.Mission
import com.ghostsignal.core.model.Signal
import com.ghostsignal.core.rhythm.RhythmMatcher
import com.ghostsignal.core.voice.GlobalCommand
import com.ghostsignal.core.voice.PhraseMatcher
import kotlin.math.abs

/**
 * The GhostSignal rules, with no Android in sight.
 *
 * Feed it [GameEvent]s; it updates its state and returns the [Effect]s the app must perform
 * (speak, listen, unlock a stem...). Long effects report completion as events carrying the effect's
 * unique token, so the flow is deterministic and unit-testable.
 *
 * Rules for a live event: a runner can never get stuck. Every challenge degrades to a hint and
 * eventually lets them through, pause/resume replays whatever was interrupted, and the operator
 * can force any step.
 *
 * [rewardLines] are spoken after the outro, before the run counts as complete: the app passes the
 * announcement for anything this finish unlocks (a soundtrack, say), and nothing otherwise.
 */
class GameEngine(val mission: Mission, private val rewardLines: List<Line> = emptyList()) {
  var phase: Phase = Phase.IDLE
    private set

  var signalIndex = 0
    private set

  var paused = false
    private set

  var lockAvailable = false
    private set

  var lastFix: GeoFix? = null
    private set

  var reading: SignalReading? = null
    private set

  var heading: HeadingEstimate = HeadingEstimate(null, HeadingSource.NONE, false)
    private set

  val collected = mutableListOf<String>()
  val flags = mutableMapOf<String, String>()
  val transcript = ArrayDeque<TranscriptEntry>()

  /** The speech currently playing, if any. */
  var activeSpeech: Effect.Speak? = null
    private set

  var listening: ListenState? = null
    private set

  var recording = false
    private set

  /** Taps so far while the rhythm tap window is open; null when closed. */
  var rhythmTaps: MutableList<Long>? = null
    private set

  var rhythmPlaying = false
    private set

  private var interruptedSpeech: Effect.Speak? = null
  private var rhythmWindowEndsMs = 0L
  private var rhythmAttempts = 0
  private var challengeAttempts = 0
  private var questionsAsked = 0
  private var listenMisses = 0
  private var approachPlayed = false
  private var lockAnnouncedAtMs = 0L
  private var tuneNoHeadingWarned = false
  private var requestSeq = 0
  private var pendingRequest: PendingRequest? = null
  private var lastAuthored: List<Utterance> = emptyList()
  private val histories = mutableMapOf<String, MutableList<Turn>>()
  private val pendingBroadcasts = ArrayDeque<GameEvent.Broadcast>()
  private var tokenSeq = 0
  private var nowMs = 0L

  data class ListenState(val token: String, val purpose: ListenPurpose, val mode: ListenMode)

  enum class ListenPurpose {
    CHALLENGE,
    ACCEPT,
    FREE_TALK,
  }

  private data class PendingRequest(val id: Int, val speaker: String, val phase: Phase, val signalIndex: Int)

  val currentSignal: Signal?
    get() = mission.signals.getOrNull(signalIndex)

  /** The character who runs the mission over the radio: the mission's handler, else the first briefing speaker. */
  val handler: String
    get() = mission.handler.ifBlank { null } ?: mission.briefing.firstOrNull()?.speaker ?: mission.characters.first().id

  /** What kind of line is playing right now (one of the KIND_ constants), or null. */
  val speakingKind: String?
    get() = activeSpeech?.token?.let { kindOf(it) }

  val total: Int
    get() = mission.signals.size

  val speaking: Boolean
    get() = activeSpeech != null

  fun progress() =
      Progress(mission.id, signalIndex, collected.toList(), flags.toMap(), complete = phase == Phase.COMPLETE)

  fun handle(event: GameEvent): List<Effect> {
    val out = mutableListOf<Effect>()
    when (event) {
      is GameEvent.Start -> start(event, out)
      is GameEvent.Position -> onPosition(event.fix, out)
      is GameEvent.Heading -> heading = event.estimate
      is GameEvent.Input -> onInput(event, out)
      is GameEvent.Heard -> onHeard(event, out)
      is GameEvent.ListenEnded -> onListenEnded(event, out)
      is GameEvent.SpeechFinished -> onSpeechFinished(event.token, out)
      is GameEvent.RhythmPlayed -> onRhythmPlayed(event, out)
      is GameEvent.CharacterReplied -> onCharacterReplied(event, out)
      is GameEvent.RecordingFinished -> onRecordingFinished(event, out)
      is GameEvent.Tick -> onTick(event.nowMs, out)
      is GameEvent.Broadcast -> {
        pendingBroadcasts += event
        deliverBroadcast(out)
      }
      GameEvent.ForceLock -> if (phase == Phase.HUNTING && !paused) beginEncounter(out)
      GameEvent.ForceSolve -> if ((phase == Phase.HUNTING || phase == Phase.ENCOUNTER) && !paused) succeed(out)
      is GameEvent.JumpTo -> jumpTo(event.signalIndex, out)
    }
    return out
  }

  // ---------------------------------------------------------------- flow

  private fun start(event: GameEvent.Start, out: MutableList<Effect>) {
    nowMs = event.nowMs
    collected.clear()
    flags.clear()
    transcript.clear()
    histories.clear()
    paused = false
    activeSpeech = null
    interruptedSpeech = null
    listening = null
    val resume = event.resume?.takeIf { it.missionId == mission.id && !it.complete }
    if (resume != null && resume.collected.isNotEmpty()) {
      collected += resume.collected.filter { id -> mission.signal(id) != null }
      flags += resume.flags
      collected.mapNotNull { mission.signal(it)?.stem }.forEach { out += Effect.UnlockStem(it) }
      advance(out)
      return
    }
    phase = Phase.BRIEFING
    if (mission.prologue.isNotEmpty()) speak(mission.prologue, KIND_PROLOGUE, out, interruptible = false)
    else speak(mission.briefing, KIND_BRIEFING, out, interruptible = false)
  }

  private fun activate(index: Int, out: MutableList<Effect>) {
    phase = Phase.HUNTING
    signalIndex = index
    lockAvailable = false
    approachPlayed = false
    lockAnnouncedAtMs = 0
    resetChallengeState()
    val signal = currentSignal ?: return
    lastFix?.let { reading = SignalField.evaluate(signal, it) }
    out += Effect.PlaySfx(Sfx.SIGNAL_ACQUIRED)
    speak(signal.teaser, KIND_TEASER, out, fromSignal = signal.id)
  }

  private fun beginEncounter(out: MutableList<Effect>) {
    val signal = currentSignal ?: return
    stopSpeaking(out)
    stopListening(out)
    phase = Phase.ENCOUNTER
    lockAvailable = false
    resetChallengeState()
    out += Effect.PlaySfx(Sfx.LOCK)
    speak(signal.arrival + signal.challenge.prompt, KIND_PROMPT, out, interruptible = false, fromSignal = signal.id)
  }

  private fun resetChallengeState() {
    challengeAttempts = 0
    questionsAsked = 0
    listenMisses = 0
    rhythmAttempts = 0
    rhythmTaps = null
    rhythmPlaying = false
    recording = false
    tuneNoHeadingWarned = false
  }

  /** Starts (or restarts) the interactive part of the current challenge. */
  private fun openChallenge(out: MutableList<Effect>) {
    val signal = currentSignal ?: return
    when (val c = signal.challenge) {
      is Challenge.Command -> listen(ListenPurpose.CHALLENGE, ListenMode.COMMAND, c.phrases, out)
      is Challenge.Passphrase -> listen(ListenPurpose.CHALLENGE, ListenMode.COMMAND, c.phrases, out)
      is Challenge.Choice -> listen(ListenPurpose.CHALLENGE, ListenMode.COMMAND, c.options.flatMap { it.phrases }, out)
      is Challenge.Ask ->
          if (questionsAsked >= c.maxQuestions) listen(ListenPurpose.ACCEPT, ListenMode.COMMAND, c.acceptPhrases, out)
          else listen(ListenPurpose.CHALLENGE, ListenMode.FREEFORM, emptyList(), out)
      is Challenge.Rhythm -> {
        rhythmPlaying = true
        rhythmTaps = null
        out += Effect.PlayRhythm(c.onsetsBeats, newToken(KIND_RHYTHM))
      }
      is Challenge.Record -> {
        recording = true
        out += Effect.PlaySfx(Sfx.RECORD_START)
        out += Effect.StartRecording(signal.id, c.maxSeconds)
      }
      is Challenge.Tune -> Unit // Waits for a capture press while the runner turns.
    }
  }

  private fun succeed(out: MutableList<Effect>) {
    val signal = currentSignal ?: return
    stopSpeaking(out)
    stopListening(out)
    if (recording) {
      recording = false
      out += Effect.StopRecording
    }
    rhythmTaps = null
    rhythmPlaying = false
    phase = Phase.COLLECTING
    lockAvailable = false
    if (signal.id !in collected) collected += signal.id
    out += Effect.PlaySfx(Sfx.COLLECT)
    out += Effect.UnlockStem(signal.stem)
    val collectedLine = systemLine("collected", fill(mission.system.fragmentCollected))
    speak(signal.success, KIND_SUCCESS, out, interruptible = false, fromSignal = signal.id, extra = listOf(collectedLine))
    out += Effect.Persist(progress())
  }

  private fun advance(out: MutableList<Effect>) {
    val next = mission.signals.indexOfFirst { it.id !in collected }
    if (next < 0) finale(out) else activate(next, out)
  }

  private fun finale(out: MutableList<Effect>) {
    phase = Phase.FINALE
    out += Effect.PlaySfx(Sfx.FINALE)
    speak(mission.finale.lines, KIND_FINALE, out, interruptible = false)
  }

  private fun afterOutro(out: MutableList<Effect>) {
    val reward = select(rewardLines)
    if (reward.isEmpty()) complete(out) else speak(reward, KIND_REWARD, out, interruptible = false)
  }

  private fun complete(out: MutableList<Effect>) {
    phase = Phase.COMPLETE
    out += Effect.Persist(progress())
  }

  private fun jumpTo(index: Int, out: MutableList<Effect>) {
    if (index !in mission.signals.indices) return
    stopSpeaking(out)
    stopListening(out)
    if (recording) out += Effect.StopRecording
    paused = false
    interruptedSpeech = null
    collected.clear()
    mission.signals.take(index).forEach {
      collected += it.id
      out += Effect.UnlockStem(it.stem)
    }
    activate(index, out)
  }

  // ---------------------------------------------------------------- inputs

  private fun onInput(event: GameEvent.Input, out: MutableList<Effect>) {
    nowMs = event.nowMs
    if (event.action == PlayerAction.PAUSE_TOGGLE) return togglePause(out)
    if (paused || phase == Phase.IDLE || phase == Phase.COMPLETE) return

    // While the rhythm tap window is open, taps and presses are rhythm input.
    val taps = rhythmTaps
    if (taps != null && (event.action == PlayerAction.TAP || event.action == PlayerAction.SCAN)) {
      taps += event.deviceTimeMs ?: event.nowMs
      val c = currentSignal?.challenge as? Challenge.Rhythm
      if (c != null && taps.size >= c.onsetsBeats.size) evaluateRhythm(c, out)
      return
    }

    when (event.action) {
      PlayerAction.SCAN, PlayerAction.TAP -> onScan(out)
      PlayerAction.TALK -> onTalk(out)
      PlayerAction.REPEAT -> if (interruptSpeech(out)) repeatLast(out)
      PlayerAction.HINT -> if (interruptSpeech(out)) giveHint(out)
      PlayerAction.STATUS -> if (interruptSpeech(out)) sayStatus(out)
      PlayerAction.PAUSE_TOGGLE -> Unit
    }
  }

  private fun onScan(out: MutableList<Effect>) {
    when (phase) {
      Phase.HUNTING ->
          if (lockAvailable) beginEncounter(out)
          else {
            out += Effect.PlaySfx(Sfx.DENIED)
            if (interruptSpeech(out)) speakSystem("no-lock", fill(mission.system.noLock), out)
          }
      Phase.ENCOUNTER -> {
        val c = currentSignal?.challenge ?: return
        when {
          recording -> out += Effect.StopRecording
          c is Challenge.Tune -> if (interruptSpeech(out)) checkTune(c, out)
          c is Challenge.Rhythm -> if (!speaking && !rhythmPlaying) openChallenge(out)
          else -> onTalk(out)
        }
      }
      else -> Unit
    }
  }

  private fun onTalk(out: MutableList<Effect>) {
    if (listening != null || recording || rhythmPlaying || rhythmTaps != null) return
    if (!interruptSpeech(out)) {
      out += Effect.PlaySfx(Sfx.DENIED)
      return
    }
    when (phase) {
      Phase.ENCOUNTER ->
          when (currentSignal?.challenge) {
            is Challenge.Rhythm, is Challenge.Record, is Challenge.Tune ->
                listen(ListenPurpose.FREE_TALK, ListenMode.FREEFORM, emptyList(), out)
            else -> openChallenge(out)
          }
      Phase.HUNTING, Phase.COLLECTING, Phase.FINALE -> listen(ListenPurpose.FREE_TALK, ListenMode.FREEFORM, emptyList(), out)
      else -> Unit
    }
  }

  private fun togglePause(out: MutableList<Effect>) {
    if (phase == Phase.IDLE || phase == Phase.COMPLETE) return
    if (!paused) {
      paused = true
      // Story lines must not be lost: remember them and replay on resume.
      activeSpeech?.takeIf { !it.interruptible }?.let { interruptedSpeech = it }
      stopSpeaking(out)
      stopListening(out)
      if (recording) {
        recording = false
        out += Effect.StopRecording
      }
      rhythmTaps = null
      rhythmPlaying = false
      out += Effect.PlaySfx(Sfx.PAUSE)
      speakSystem("paused", mission.system.paused, out)
    } else {
      paused = false
      stopSpeaking(out)
      out += Effect.PlaySfx(Sfx.RESUME)
      val resumed = systemLine("resumed", mission.system.resumed)
      val replay = interruptedSpeech
      interruptedSpeech = null
      if (replay != null) {
        emitSpeak(listOf(resumed) + replay.utterances, kindOf(replay.token), out, interruptible = false)
      } else if (phase == Phase.ENCOUNTER && currentSignal?.challenge !is Challenge.Tune) {
        emitSpeak(listOf(resumed), KIND_RETRY, out, interruptible = false)
      } else {
        emitSpeak(listOf(resumed), KIND_SYSTEM, out, interruptible = true)
      }
    }
  }

  /** Stops interruptible speech; returns false if a story line is playing. */
  private fun interruptSpeech(out: MutableList<Effect>): Boolean {
    val speech = activeSpeech ?: return true
    if (!speech.interruptible) return false
    stopSpeaking(out)
    return true
  }

  private fun stopSpeaking(out: MutableList<Effect>) {
    if (activeSpeech != null) {
      activeSpeech = null
      out += Effect.StopSpeaking
    }
  }

  // ---------------------------------------------------------------- position

  private fun onPosition(fix: GeoFix, out: MutableList<Effect>) {
    lastFix = fix
    nowMs = fix.timeMs
    val signal = currentSignal ?: return
    val r = SignalField.evaluate(signal, fix)
    reading = r
    if (phase != Phase.HUNTING || paused) return

    if (!approachPlayed && r.zone != Zone.FAR && !speaking) {
      approachPlayed = true
      speak(signal.approach, KIND_SYSTEM, out, fromSignal = signal.id)
    }
    if (!lockAvailable && r.zone == Zone.LOCK) {
      lockAvailable = true
      out += Effect.PlaySfx(Sfx.LOCK_AVAILABLE)
      lockAnnouncedAtMs = nowMs
      if (!speaking) speakSystem("lock", mission.system.lockAvailable, out)
    } else if (lockAvailable && !SignalField.stillLockable(signal, r)) {
      lockAvailable = false
    }
  }

  // ---------------------------------------------------------------- speech + listening

  private fun onSpeechFinished(token: String, out: MutableList<Effect>) {
    if (token != activeSpeech?.token) return
    activeSpeech = null
    if (paused) return
    when (kindOf(token)) {
      KIND_PROLOGUE -> speak(mission.briefing, KIND_BRIEFING, out, interruptible = false)
      KIND_BRIEFING -> activate(0, out)
      KIND_PROMPT, KIND_RETRY -> if (phase == Phase.ENCOUNTER) openChallenge(out)
      KIND_REPEAT, KIND_HINT -> if (phase == Phase.ENCOUNTER) reopenIfIdle(out)
      KIND_MISSED -> if (phase == Phase.ENCOUNTER && listenMisses < 2) reopenIfIdle(out)
      KIND_CHOICE -> succeed(out)
      KIND_SUCCESS -> advance(out)
      KIND_NPC -> if (phase == Phase.ENCOUNTER && currentSignal?.challenge is Challenge.Ask) afterAskReply(out)
      KIND_FINALE -> {
        val outro = select(mission.finale.outro)
        if (outro.isEmpty()) afterOutro(out) else speak(outro, KIND_OUTRO, out, interruptible = false)
      }
      KIND_OUTRO -> afterOutro(out)
      KIND_REWARD -> complete(out)
    }
  }

  private fun reopenIfIdle(out: MutableList<Effect>) {
    if (listening != null || rhythmTaps != null || rhythmPlaying || recording) return
    when (currentSignal?.challenge) {
      is Challenge.Tune, is Challenge.Record, null -> Unit
      else -> openChallenge(out)
    }
  }

  private fun listen(purpose: ListenPurpose, mode: ListenMode, hints: List<String>, out: MutableList<Effect>) {
    val token = newToken("listen")
    listening = ListenState(token, purpose, mode)
    val timeout = if (mode == ListenMode.FREEFORM) 9_000L else 7_000L
    out += Effect.Listen(mode, timeout, token, hints)
  }

  private fun stopListening(out: MutableList<Effect>) {
    if (listening != null) {
      listening = null
      out += Effect.StopListening
    }
  }

  private fun onHeard(event: GameEvent.Heard, out: MutableList<Effect>) {
    val state = listening ?: return
    if (event.token != state.token || event.text.isBlank()) return
    if (!event.isFinal) {
      // Accept confident command matches early, before the recognizer closes its window.
      if (state.mode == ListenMode.COMMAND && earlyMatch(state, event.text)) {
        stopListening(out)
        heard(state, event.text, out)
      }
      return
    }
    stopListening(out)
    heard(state, event.text, out)
  }

  private fun earlyMatch(state: ListenState, text: String): Boolean {
    val phrases =
        when (state.purpose) {
          ListenPurpose.ACCEPT -> (currentSignal?.challenge as? Challenge.Ask)?.acceptPhrases
          ListenPurpose.CHALLENGE ->
              when (val c = currentSignal?.challenge) {
                is Challenge.Command -> c.phrases
                is Challenge.Passphrase -> c.phrases
                is Challenge.Choice -> c.options.flatMap { it.phrases }
                else -> null
              }
          ListenPurpose.FREE_TALK -> null
        } ?: return false
    return phrases.any { PhraseMatcher.score(text, it) >= 0.93 }
  }

  private fun onListenEnded(event: GameEvent.ListenEnded, out: MutableList<Effect>) {
    val state = listening ?: return
    if (event.token != state.token) return
    listening = null
    if (state.purpose == ListenPurpose.FREE_TALK || paused) return
    listenMisses++
    val text =
        if (listenMisses >= 2) "${mission.system.heardNothing} ${mission.system.talkHint}"
        else mission.system.heardNothing
    emitSpeak(listOf(systemLine("heard-nothing", text)), KIND_MISSED, out, interruptible = true)
  }

  private fun heard(state: ListenState, text: String, out: MutableList<Effect>) {
    record(TranscriptEntry("runner", text, fromPlayer = true))
    listenMisses = 0
    val signal = currentSignal
    val words = PhraseMatcher.normalize(text).split(' ').size

    // Global commands, unless they're more likely part of a question for a character.
    if (state.purpose != ListenPurpose.FREE_TALK || words <= 3) {
      when (GlobalCommand.match(text)) {
        GlobalCommand.REPEAT -> return repeatLast(out)
        GlobalCommand.HINT -> return giveHint(out)
        GlobalCommand.STATUS -> return sayStatus(out)
        GlobalCommand.PAUSE -> return if (!paused) togglePause(out) else Unit
        GlobalCommand.RESUME -> return if (paused) togglePause(out) else Unit
        null -> Unit
      }
    }

    when (state.purpose) {
      ListenPurpose.FREE_TALK -> {
        val speaker = if (phase == Phase.ENCOUNTER && signal != null) signal.speaker else handler
        askCharacter(speaker, text, out)
      }
      ListenPurpose.ACCEPT -> {
        val c = signal?.challenge as? Challenge.Ask ?: return
        if (PhraseMatcher.matchesAny(text, c.acceptPhrases)) succeed(out) else askCharacter(signal.speaker, text, out)
      }
      ListenPurpose.CHALLENGE -> answerChallenge(signal ?: return, text, out)
    }
  }

  private fun answerChallenge(signal: Signal, text: String, out: MutableList<Effect>) {
    when (val c = signal.challenge) {
      is Challenge.Command -> if (PhraseMatcher.matchesAny(text, c.phrases)) succeed(out) else retry(signal, out)
      is Challenge.Passphrase -> {
        if (PhraseMatcher.matchesAny(text, c.phrases)) return succeed(out)
        challengeAttempts++
        when {
          // Never strand a runner on a passphrase.
          challengeAttempts > c.hintAfterAttempts + 2 -> succeed(out)
          challengeAttempts >= c.hintAfterAttempts && signal.hint.isNotEmpty() ->
              speak(signal.hint, KIND_RETRY, out, interruptible = false, fromSignal = signal.id)
          else -> retry(signal, out)
        }
      }
      is Challenge.Choice -> {
        val match = PhraseMatcher.bestAbove(text, c.options.associate { it.id to it.phrases })
        if (match == null) return retry(signal, out)
        flags[signal.id] = match.key
        val option = c.options.first { it.id == match.key }
        if (option.reply.isEmpty()) succeed(out)
        else speak(option.reply, KIND_CHOICE, out, interruptible = false, fromSignal = signal.id)
      }
      is Challenge.Ask -> {
        if (questionsAsked > 0 && PhraseMatcher.matchesAny(text, c.acceptPhrases)) return succeed(out)
        questionsAsked++
        askCharacter(signal.speaker, text, out)
      }
      else -> Unit
    }
  }

  private fun retry(signal: Signal, out: MutableList<Effect>) {
    challengeAttempts++
    val lines = select(signal.retry)
    if (lines.isEmpty()) {
      emitSpeak(listOf(systemLine("not-understood", mission.system.notUnderstood)), KIND_RETRY, out, false)
    } else {
      speak(lines, KIND_RETRY, out, interruptible = false, fromSignal = signal.id)
    }
  }

  private fun afterAskReply(out: MutableList<Effect>) {
    val c = currentSignal?.challenge as? Challenge.Ask ?: return
    if (questionsAsked >= c.maxQuestions && c.acceptPrompt.isNotEmpty()) {
      speak(c.acceptPrompt, KIND_RETRY, out, interruptible = false, fromSignal = currentSignal?.id)
    } else {
      openChallenge(out)
    }
  }

  // ---------------------------------------------------------------- characters

  private fun askCharacter(characterId: String, text: String, out: MutableList<Effect>) {
    val history = histories.getOrPut(characterId) { mutableListOf() }
    val prior = history.takeLast(8)
    history += Turn(true, text)
    requestSeq++
    pendingRequest = PendingRequest(requestSeq, characterId, phase, signalIndex)
    out +=
        Effect.AskCharacter(
            CharacterRequest(
                requestId = requestSeq,
                characterId = characterId,
                signalId = currentSignal?.id,
                utterance = text,
                context = characterContext(),
                history = prior,
            ))
  }

  private fun onCharacterReplied(event: GameEvent.CharacterReplied, out: MutableList<Effect>) {
    val pending = pendingRequest ?: return
    if (event.requestId != pending.id) return
    pendingRequest = null
    // Stale: the story moved on while the agent was thinking.
    if (pending.phase != phase || pending.signalIndex != signalIndex || paused) return
    if (!interruptSpeech(out)) return
    val c = currentSignal?.challenge as? Challenge.Ask
    val fallback = c?.fallbackReplies?.takeIf { it.isNotEmpty() }?.let { it[(questionsAsked - 1).coerceAtLeast(0) % it.size] }
    val text = event.text?.takeIf { it.isNotBlank() } ?: fallback ?: mission.system.npcOffline
    histories.getOrPut(pending.speaker) { mutableListOf() } += Turn(false, text)
    val fromSignal = currentSignal?.id?.takeIf { phase == Phase.ENCOUNTER && pending.speaker == currentSignal?.speaker }
    val reply = Utterance(null, pending.speaker, text, fromSignal)
    // Ask-challenge replies are part of the story flow; free talk can be talked over.
    val partOfChallenge = phase == Phase.ENCOUNTER && c != null
    emitSpeak(listOf(reply), KIND_NPC, out, interruptible = !partOfChallenge)
    lastAuthored = listOf(reply)
  }

  fun characterContext(): CharacterContext {
    val signal = currentSignal
    val r = reading
    return CharacterContext(
        phase = phase,
        objective = objective(),
        signalName = signal?.name,
        place = signal?.place,
        distance = r?.let { Geo.spokenDistance(it.distanceM) },
        direction = r?.let { direction(it.bearingDeg) },
        collected = collected.size,
        total = total,
        flags = flags.toMap(),
        challenge = signal?.challenge?.let { challengeName(it) },
    )
  }

  // ---------------------------------------------------------------- challenge mechanics

  private fun onRhythmPlayed(event: GameEvent.RhythmPlayed, out: MutableList<Effect>) {
    if (!rhythmPlaying || kindOf(event.token) != KIND_RHYTHM) return
    rhythmPlaying = false
    if (phase != Phase.ENCOUNTER || paused) return
    val c = currentSignal?.challenge as? Challenge.Rhythm ?: return
    nowMs = event.nowMs
    rhythmTaps = mutableListOf()
    val span = (c.onsetsBeats.last() - c.onsetsBeats.first()) * mission.music.beatMs
    rhythmWindowEndsMs = event.nowMs + span.toLong() + 5_000L
  }

  private fun evaluateRhythm(c: Challenge.Rhythm, out: MutableList<Effect>) {
    val taps = rhythmTaps ?: return
    rhythmTaps = null
    rhythmAttempts++
    val passed =
        taps.size >= c.onsetsBeats.size &&
            RhythmMatcher.match(c.onsetsBeats, taps, mission.music.beatMs, c.toleranceBeats).passed
    if (passed || rhythmAttempts >= c.maxAttempts) return succeed(out)
    val signal = currentSignal ?: return
    val lines = select(signal.retry)
    if (lines.isEmpty()) emitSpeak(listOf(systemLine("rhythm-miss", mission.system.rhythmMiss)), KIND_RETRY, out, false)
    else speak(lines, KIND_RETRY, out, interruptible = false, fromSignal = signal.id)
  }

  private fun checkTune(c: Challenge.Tune, out: MutableList<Effect>) {
    val delta = tuneDelta(c)
    if (delta == null) {
      if (tuneNoHeadingWarned) return succeed(out)
      tuneNoHeadingWarned = true
      speakSystem("tune-no-heading", mission.system.tuneNoHeading.replace("{target}", c.targetName), out)
      return
    }
    if (abs(delta) <= c.toleranceDeg) return succeed(out)
    challengeAttempts++
    if (challengeAttempts >= 6) return succeed(out)
    out += Effect.PlaySfx(Sfx.DENIED)
    speakSystem("tune-dir", if (delta > 0) mission.system.tuneRight else mission.system.tuneLeft, out)
  }

  /** Degrees the runner must turn (positive = right) to face the tune target; null without heading. */
  fun tuneDelta(c: Challenge.Tune): Double? {
    val h = heading.deg ?: return null
    val from = lastFix?.position ?: currentSignal?.location ?: return null
    return Geo.deltaDeg(h, Geo.bearingDeg(from, c.target))
  }

  private fun onRecordingFinished(event: GameEvent.RecordingFinished, out: MutableList<Effect>) {
    if (!recording) return
    recording = false
    if (event.path != null) record(TranscriptEntry(mission.system.speaker, mission.system.recordingSaved, false))
    succeed(out)
  }

  /** Live operator messages wait until the runner isn't in the middle of something. */
  private fun deliverBroadcast(out: MutableList<Effect>) {
    if (pendingBroadcasts.isEmpty() || paused || speaking || listening != null || recording) return
    if (rhythmPlaying || rhythmTaps != null || pendingRequest != null) return
    if (phase == Phase.IDLE || phase == Phase.COMPLETE) return
    val b = pendingBroadcasts.removeFirst()
    val speaker = if (mission.character(b.speaker) != null) b.speaker else handler
    out += Effect.PlaySfx(Sfx.TRANSMISSION)
    emitSpeak(listOf(Utterance("live", speaker, b.text)), KIND_SYSTEM, out, interruptible = true)
  }

  private fun onTick(now: Long, out: MutableList<Effect>) {
    nowMs = now
    deliverBroadcast(out)
    val c = currentSignal?.challenge as? Challenge.Rhythm
    if (c != null && rhythmTaps != null && now > rhythmWindowEndsMs) evaluateRhythm(c, out)
    // Remind a runner standing on a signal that they can lock it.
    if (phase == Phase.HUNTING && lockAvailable && !paused && !speaking && now - lockAnnouncedAtMs > 25_000) {
      lockAnnouncedAtMs = now
      speakSystem("lock", mission.system.lockAvailable, out)
    }
  }

  // ---------------------------------------------------------------- talking helpers

  private fun repeatLast(out: MutableList<Effect>) {
    if (lastAuthored.isEmpty()) return
    emitSpeak(lastAuthored, KIND_REPEAT, out, interruptible = true)
  }

  private fun giveHint(out: MutableList<Effect>) {
    val signal = currentSignal ?: return
    when (phase) {
      Phase.HUNTING -> {
        val status = systemLine("status", fill(mission.system.status))
        val hints = select(signal.hint).map { it.toUtterance(signal.id) }
        emitSpeak(listOf(status) + hints, KIND_HINT, out, interruptible = true)
      }
      Phase.ENCOUNTER -> {
        val lines = select(signal.hint).ifEmpty { select(signal.challenge.prompt) }
        emitSpeak(lines.map { it.toUtterance(signal.id) }, KIND_HINT, out, interruptible = true)
      }
      else -> sayStatus(out)
    }
  }

  private fun sayStatus(out: MutableList<Effect>) = speakSystem("status", fill(mission.system.status), out)

  /** Spoken direction to a bearing: clock-face when we know the head heading, compass otherwise. */
  fun direction(bearingDeg: Double): String {
    val h = heading.deg ?: return "to the ${Geo.cardinal(bearingDeg)}"
    val clock = Geo.clockPosition(Geo.deltaDeg(h, bearingDeg))
    return if (clock == 12) "straight ahead" else "at $clock o'clock"
  }

  private fun fill(template: String): String {
    val r = reading
    return template
        .replace("{signal}", currentSignal?.name ?: "the signal")
        .replace("{distance}", r?.let { Geo.spokenDistance(it.distanceM) } ?: "somewhere close")
        .replace("{direction}", r?.let { direction(it.bearingDeg) } ?: "but I have no fix on you yet")
        .replace("{count}", collected.size.toString())
        .replace("{total}", total.toString())
  }

  fun objective(): String {
    val signal = currentSignal
    return when (phase) {
      Phase.IDLE -> "Stand by"
      Phase.BRIEFING -> "Listen to the briefing"
      Phase.HUNTING -> "Find ${signal?.name}" + (signal?.place?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
      Phase.ENCOUNTER ->
          when (val c = signal?.challenge) {
            is Challenge.Command -> "Say \"${c.phrases.first()}\""
            is Challenge.Passphrase -> "Speak the passphrase"
            is Challenge.Choice -> "Choose: " + c.options.joinToString(" or ") { "\"${it.phrases.first()}\"" }
            is Challenge.Ask ->
                "Ask ${mission.character(signal.speaker)?.name ?: "them"} a question, then say \"${c.acceptPhrases.first()}\""
            is Challenge.Rhythm -> "Tap the rhythm back on the touchpad"
            is Challenge.Record -> "Record a message · press capture to finish"
            is Challenge.Tune -> "Face ${c.targetName} · press capture"
            null -> ""
          }
      Phase.COLLECTING -> "Fragment ${collected.size} of $total collected"
      Phase.FINALE -> "The final broadcast"
      Phase.COMPLETE -> "Broadcast restored"
    }
  }

  private fun challengeName(c: Challenge) =
      when (c) {
        is Challenge.Command -> "command"
        is Challenge.Passphrase -> "passphrase"
        is Challenge.Choice -> "choice"
        is Challenge.Ask -> "ask"
        is Challenge.Rhythm -> "rhythm"
        is Challenge.Record -> "record"
        is Challenge.Tune -> "tune"
      }

  private fun systemLine(id: String, text: String) = Utterance("system.$id", mission.system.speaker, text)

  private fun speakSystem(id: String, text: String, out: MutableList<Effect>) =
      emitSpeak(listOf(systemLine(id, text)), KIND_SYSTEM, out, interruptible = true)

  private fun Line.toUtterance(fromSignal: String?) =
      Utterance(id, speaker, text, fromSignal.takeIf { speaker != handler && speaker != mission.system.speaker })

  private fun select(lines: List<Line>) = lines.filter { l -> l.requires.all { (k, v) -> flags[k] == v } }

  private fun speak(
      lines: List<Line>,
      kind: String,
      out: MutableList<Effect>,
      interruptible: Boolean = true,
      fromSignal: String? = null,
      extra: List<Utterance> = emptyList(),
  ) {
    val utterances = select(lines).map { it.toUtterance(fromSignal) }
    if (utterances.isNotEmpty() && kind != KIND_SYSTEM) lastAuthored = utterances
    emitSpeak(utterances + extra, kind, out, interruptible)
  }

  private fun emitSpeak(utterances: List<Utterance>, kind: String, out: MutableList<Effect>, interruptible: Boolean) {
    val token = newToken(kind)
    if (utterances.isEmpty()) {
      // Nothing to say: continue the flow as if it had been said.
      activeSpeech = Effect.Speak(emptyList(), token, interruptible)
      onSpeechFinished(token, out)
      return
    }
    utterances.forEach { record(TranscriptEntry(it.speaker, it.text, fromPlayer = false)) }
    val speak = Effect.Speak(utterances, token, interruptible)
    activeSpeech = speak
    out += speak
  }

  private fun newToken(kind: String) = "$kind#${++tokenSeq}"

  private fun kindOf(token: String) = token.substringBefore('#')

  private fun record(entry: TranscriptEntry) {
    transcript.addLast(entry)
    while (transcript.size > 40) transcript.removeFirst()
  }

  companion object {
    const val KIND_PROLOGUE = "prologue"
    const val KIND_BRIEFING = "briefing"
    const val KIND_TEASER = "teaser"
    const val KIND_PROMPT = "prompt"
    const val KIND_RETRY = "retry"
    const val KIND_REPEAT = "repeat"
    const val KIND_HINT = "hint"
    const val KIND_MISSED = "missed"
    const val KIND_CHOICE = "choice"
    const val KIND_SUCCESS = "success"
    const val KIND_NPC = "npc"
    const val KIND_FINALE = "finale"
    const val KIND_OUTRO = "outro"
    const val KIND_REWARD = "reward"
    const val KIND_RHYTHM = "rhythm"
    const val KIND_SYSTEM = "system"
  }
}
