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
 * The GhostSignal game rules as a pure state machine.
 *
 * Hosts feed in [GameEvent]s (taps, GPS fixes, transcripts, completions) and
 * perform the [Effect]s that come back (speak this, listen now, unlock that
 * stem). Nothing here touches Android, audio, or the network, which keeps a
 * full run reproducible in a unit test.
 *
 * Live-event rule above all: a runner can never get stuck. Missed phrases
 * degrade to hints and then to a pass, pause replays whatever it cut off,
 * broadcasts wait for a quiet moment, and the operator can force any step.
 *
 * [rewardLines] are voiced after the outro and before the run completes;
 * hosts use them to announce whatever this finish unlocked.
 */
class GameEngine(
    val mission: Mission,
    private val rewardLines: List<Line> = emptyList(),
) {

  var phase: Phase = Phase.IDLE
    private set

  var signalIndex: Int = 0
    private set

  var paused: Boolean = false
    private set

  var lockAvailable: Boolean = false
    private set

  var lastFix: GeoFix? = null
    private set

  var reading: SignalReading? = null
    private set

  var heading: HeadingEstimate = HeadingEstimate(null, HeadingSource.NONE, false)
    private set

  val collected: MutableList<String> = mutableListOf()
  val flags: MutableMap<String, String> = mutableMapOf()
  val transcript: ArrayDeque<TranscriptEntry> = ArrayDeque()

  /** Speech currently playing, if any. */
  var activeSpeech: Effect.Speak? = null
    private set

  var listening: ListenState? = null
    private set

  var recording: Boolean = false
    private set

  /** Taps gathered while the rhythm answer window is open; null when shut. */
  var rhythmTaps: MutableList<Long>? = null
    private set

  var rhythmPlaying: Boolean = false
    private set

  // -- transient run state -------------------------------------------------
  private var interruptedSpeech: Effect.Speak? = null
  private var rhythmWindowEndsMs: Long = 0L
  private var rhythmAttempts: Int = 0
  private var challengeAttempts: Int = 0
  private var questionsAsked: Int = 0
  private var listenMisses: Int = 0
  private var approachPlayed: Boolean = false
  private var lockAnnouncedAtMs: Long = 0L
  private var tuneNoHeadingWarned: Boolean = false
  private var requestSeq: Int = 0
  private var pendingRequest: PendingRequest? = null
  private var lastAuthored: List<Utterance> = emptyList()
  private val histories = mutableMapOf<String, MutableList<Turn>>()
  private val pendingBroadcasts = ArrayDeque<GameEvent.Broadcast>()
  private var tokenSeq: Int = 0
  private var nowMs: Long = 0L

  data class ListenState(val token: String, val purpose: ListenPurpose, val mode: ListenMode)

  enum class ListenPurpose { CHALLENGE, ACCEPT, FREE_TALK }

  private data class PendingRequest(
      val id: Int,
      val speaker: String,
      val phase: Phase,
      val signalIndex: Int,
  )

  val currentSignal: Signal?
    get() = mission.signals.getOrNull(signalIndex)

  /**
   * The voice running the mission over the radio: the authored handler, else
   * whoever speaks first in the briefing, else the first character.
   */
  val handler: String
    get() = mission.handler.ifBlank { null }
        ?: mission.briefing.firstOrNull()?.speaker
        ?: mission.characters.first().id

  /** Speech category of the line now playing (a KIND_ constant), or null. */
  val speakingKind: String?
    get() = activeSpeech?.token?.let { kindOf(it) }

  val total: Int
    get() = mission.signals.size

  val speaking: Boolean
    get() = activeSpeech != null

  fun progress(): Progress = Progress(
      missionId = mission.id,
      signalIndex = signalIndex,
      collected = collected.toList(),
      flags = flags.toMap(),
      complete = phase == Phase.COMPLETE,
  )

  /** Applies one event and returns the effects the host must perform. */
  fun handle(event: GameEvent): List<Effect> {
    val effects = mutableListOf<Effect>()
    when (event) {
      is GameEvent.Start -> startRun(event, effects)
      is GameEvent.Position -> onPosition(event.fix, effects)
      is GameEvent.Heading -> heading = event.estimate
      is GameEvent.Input -> onInput(event, effects)
      is GameEvent.Heard -> onHeard(event, effects)
      is GameEvent.ListenEnded -> onListenEnded(event, effects)
      is GameEvent.SpeechFinished -> onSpeechFinished(event.token, effects)
      is GameEvent.RhythmPlayed -> onRhythmPlayed(event, effects)
      is GameEvent.CharacterReplied -> onCharacterReplied(event, effects)
      is GameEvent.RecordingFinished -> onRecordingFinished(event, effects)
      is GameEvent.Tick -> onTick(event.nowMs, effects)
      is GameEvent.Broadcast -> {
        pendingBroadcasts += event
        deliverBroadcast(effects)
      }
      GameEvent.ForceLock ->
          if (phase == Phase.HUNTING && !paused) beginEncounter(effects)
      GameEvent.ForceSolve ->
          if ((phase == Phase.HUNTING || phase == Phase.ENCOUNTER) && !paused) succeed(effects)
      is GameEvent.JumpTo -> jumpTo(event.signalIndex, effects)
    }
    return effects
  }

  // -- run flow --------------------------------------------------------------

  private fun startRun(event: GameEvent.Start, out: MutableList<Effect>) {
    nowMs = event.nowMs
    collected.clear()
    flags.clear()
    transcript.clear()
    histories.clear()
    paused = false
    activeSpeech = null
    interruptedSpeech = null
    listening = null

    val resume = event.resume?.takeIf { saved ->
      saved.missionId == mission.id && !saved.complete
    }
    if (resume != null && resume.collected.isNotEmpty()) {
      collected += resume.collected.filter { id -> mission.signal(id) != null }
      flags += resume.flags
      collected.mapNotNull { id -> mission.signal(id)?.stem }
          .forEach { stem -> out += Effect.UnlockStem(stem) }
      advance(out)
      return
    }

    phase = Phase.BRIEFING
    if (mission.prologue.isNotEmpty()) {
      speak(mission.prologue, KIND_PROLOGUE, out, interruptible = false)
    } else {
      speak(mission.briefing, KIND_BRIEFING, out, interruptible = false)
    }
  }

  private fun activate(index: Int, out: MutableList<Effect>) {
    phase = Phase.HUNTING
    signalIndex = index
    lockAvailable = false
    approachPlayed = false
    lockAnnouncedAtMs = 0L
    resetChallengeState()
    val signal = currentSignal ?: return
    lastFix?.let { fix -> reading = SignalField.evaluate(signal, fix) }
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
    speak(
        signal.arrival + signal.challenge.prompt,
        KIND_PROMPT, out, interruptible = false, fromSignal = signal.id,
    )
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

  /** Opens (or reopens) the interactive half of the current challenge. */
  private fun openChallenge(out: MutableList<Effect>) {
    val signal = currentSignal ?: return
    when (val challenge = signal.challenge) {
      is Challenge.Command ->
          listen(ListenPurpose.CHALLENGE, ListenMode.COMMAND, challenge.phrases, out)
      is Challenge.Passphrase ->
          listen(ListenPurpose.CHALLENGE, ListenMode.COMMAND, challenge.phrases, out)
      is Challenge.Choice ->
          listen(ListenPurpose.CHALLENGE, ListenMode.COMMAND, challenge.options.flatMap { it.phrases }, out)
      is Challenge.Ask ->
          if (questionsAsked >= challenge.maxQuestions) {
            listen(ListenPurpose.ACCEPT, ListenMode.COMMAND, challenge.acceptPhrases, out)
          } else {
            listen(ListenPurpose.CHALLENGE, ListenMode.FREEFORM, emptyList(), out)
          }
      is Challenge.Rhythm -> {
        rhythmPlaying = true
        rhythmTaps = null
        out += Effect.PlayRhythm(challenge.onsetsBeats, newToken(KIND_RHYTHM))
      }
      is Challenge.Record -> {
        recording = true
        out += Effect.PlaySfx(Sfx.RECORD_START)
        out += Effect.StartRecording(signal.id, challenge.maxSeconds)
      }
      is Challenge.Tune -> Unit // Resolved by turning, then a capture press.
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
    val nextIndex = mission.signals.indexOfFirst { it.id !in collected }
    if (nextIndex < 0) finale(out) else activate(nextIndex, out)
  }

  private fun finale(out: MutableList<Effect>) {
    phase = Phase.FINALE
    out += Effect.PlaySfx(Sfx.FINALE)
    speak(mission.finale.lines, KIND_FINALE, out, interruptible = false)
  }

  private fun afterOutro(out: MutableList<Effect>) {
    val reward = select(rewardLines)
    if (reward.isEmpty()) complete(out)
    else speak(reward, KIND_REWARD, out, interruptible = false)
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
    mission.signals.take(index).forEach { earlier ->
      collected += earlier.id
      out += Effect.UnlockStem(earlier.stem)
    }
    activate(index, out)
  }

  // -- runner input ------------------------------------------------------------

  private fun onInput(event: GameEvent.Input, out: MutableList<Effect>) {
    nowMs = event.nowMs
    if (event.action == PlayerAction.PAUSE_TOGGLE) return togglePause(out)
    if (paused || phase == Phase.IDLE || phase == Phase.COMPLETE) return

    // With the rhythm answer window open, every tap is rhythm input.
    val taps = rhythmTaps
    if (taps != null && (event.action == PlayerAction.TAP || event.action == PlayerAction.SCAN)) {
      taps += event.deviceTimeMs ?: event.nowMs
      val rhythm = currentSignal?.challenge as? Challenge.Rhythm
      if (rhythm != null && taps.size >= rhythm.onsetsBeats.size) evaluateRhythm(rhythm, out)
      return
    }

    when (event.action) {
      PlayerAction.SCAN, PlayerAction.TAP -> onScan(out)
      PlayerAction.TALK -> onTalk(out)
      PlayerAction.REPEAT -> if (interruptSpeech(out)) repeatLast(out)
      PlayerAction.HINT -> if (interruptSpeech(out)) giveHint(out)
      PlayerAction.STATUS -> if (interruptSpeech(out)) sayStatus(out)
      PlayerAction.PAUSE_TOGGLE -> Unit // Handled above.
    }
  }

  private fun onScan(out: MutableList<Effect>) {
    when (phase) {
      Phase.HUNTING -> {
        if (lockAvailable) {
          beginEncounter(out)
        } else {
          out += Effect.PlaySfx(Sfx.DENIED)
          if (interruptSpeech(out)) speakSystem("no-lock", fill(mission.system.noLock), out)
        }
      }
      Phase.ENCOUNTER -> {
        val challenge = currentSignal?.challenge ?: return
        when {
          recording -> out += Effect.StopRecording
          challenge is Challenge.Tune -> if (interruptSpeech(out)) checkTune(challenge, out)
          challenge is Challenge.Rhythm -> if (!speaking && !rhythmPlaying) openChallenge(out)
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
      Phase.ENCOUNTER -> when (currentSignal?.challenge) {
        is Challenge.Rhythm, is Challenge.Record, is Challenge.Tune ->
            listen(ListenPurpose.FREE_TALK, ListenMode.FREEFORM, emptyList(), out)
        else -> openChallenge(out)
      }
      Phase.HUNTING, Phase.COLLECTING, Phase.FINALE ->
          listen(ListenPurpose.FREE_TALK, ListenMode.FREEFORM, emptyList(), out)
      else -> Unit
    }
  }

  private fun togglePause(out: MutableList<Effect>) {
    if (phase == Phase.IDLE || phase == Phase.COMPLETE) return
    if (!paused) {
      paused = true
      // Uninterruptible story speech is replayed on resume, never dropped.
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
      val resumedLine = systemLine("resumed", mission.system.resumed)
      val replay = interruptedSpeech
      interruptedSpeech = null
      when {
        replay != null ->
            emitSpeak(listOf(resumedLine) + replay.utterances, kindOf(replay.token), out, interruptible = false)
        phase == Phase.ENCOUNTER && currentSignal?.challenge !is Challenge.Tune ->
            emitSpeak(listOf(resumedLine), KIND_RETRY, out, interruptible = false)
        else ->
            emitSpeak(listOf(resumedLine), KIND_SYSTEM, out, interruptible = true)
      }
    }
  }

  /** Stops speech if the runner may talk over it; false while story plays. */
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

  // -- position ------------------------------------------------------------------

  private fun onPosition(fix: GeoFix, out: MutableList<Effect>) {
    lastFix = fix
    nowMs = fix.timeMs
    val signal = currentSignal ?: return
    val newReading = SignalField.evaluate(signal, fix)
    reading = newReading
    if (phase != Phase.HUNTING || paused) return

    if (!approachPlayed && newReading.zone != Zone.FAR && !speaking) {
      approachPlayed = true
      speak(signal.approach, KIND_SYSTEM, out, fromSignal = signal.id)
    }
    if (!lockAvailable && newReading.zone == Zone.LOCK) {
      lockAvailable = true
      out += Effect.PlaySfx(Sfx.LOCK_AVAILABLE)
      lockAnnouncedAtMs = nowMs
      if (!speaking) speakSystem("lock", mission.system.lockAvailable, out)
    } else if (lockAvailable && !SignalField.stillLockable(signal, newReading)) {
      lockAvailable = false
    }
  }

  // -- speech, listening, challenges --------------------------------------------

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
      KIND_NPC ->
          if (phase == Phase.ENCOUNTER && currentSignal?.challenge is Challenge.Ask) afterAskReply(out)
      KIND_FINALE -> {
        val outro = select(mission.finale.outro)
        if (outro.isEmpty()) afterOutro(out)
        else speak(outro, KIND_OUTRO, out, interruptible = false)
      }
      KIND_OUTRO -> afterOutro(out)
      KIND_REWARD -> complete(out)
      else -> Unit
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
    val timeoutMs = if (mode == ListenMode.FREEFORM) 9_000L else 7_000L
    out += Effect.Listen(mode, timeoutMs, token, hints)
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
      // A confident command read ends the window early; no waiting on the
      // recognizer to decide the runner has finished talking.
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
    val phrases: List<String>? = when (state.purpose) {
      ListenPurpose.ACCEPT -> (currentSignal?.challenge as? Challenge.Ask)?.acceptPhrases
      ListenPurpose.CHALLENGE -> when (val challenge = currentSignal?.challenge) {
        is Challenge.Command -> challenge.phrases
        is Challenge.Passphrase -> challenge.phrases
        is Challenge.Choice -> challenge.options.flatMap { it.phrases }
        else -> null
      }
      ListenPurpose.FREE_TALK -> null
    }
    return phrases?.any { phrase -> PhraseMatcher.score(text, phrase) >= 0.93 } ?: false
  }

  private fun onListenEnded(event: GameEvent.ListenEnded, out: MutableList<Effect>) {
    val state = listening ?: return
    if (event.token != state.token) return
    listening = null
    if (state.purpose == ListenPurpose.FREE_TALK || paused) return
    listenMisses++
    val message = if (listenMisses >= 2) {
      "${mission.system.heardNothing} ${mission.system.talkHint}"
    } else {
      mission.system.heardNothing
    }
    emitSpeak(listOf(systemLine("heard-nothing", message)), KIND_MISSED, out, interruptible = true)
  }

  private fun heard(state: ListenState, text: String, out: MutableList<Effect>) {
    record(TranscriptEntry("runner", text, fromPlayer = true))
    listenMisses = 0
    val signal = currentSignal
    val wordCount = PhraseMatcher.normalize(text).split(' ').size

    // Global commands win, except inside a longer question clearly aimed at
    // a character ("Vesper, tell me the status of the old station").
    if (state.purpose != ListenPurpose.FREE_TALK || wordCount <= 3) {
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
        val ask = signal?.challenge as? Challenge.Ask ?: return
        if (PhraseMatcher.matchesAny(text, ask.acceptPhrases)) succeed(out)
        else askCharacter(signal.speaker, text, out)
      }
      ListenPurpose.CHALLENGE -> answerChallenge(signal ?: return, text, out)
    }
  }

  private fun answerChallenge(signal: Signal, text: String, out: MutableList<Effect>) {
    when (val challenge = signal.challenge) {
      is Challenge.Command ->
          if (PhraseMatcher.matchesAny(text, challenge.phrases)) succeed(out) else retry(signal, out)
      is Challenge.Passphrase -> {
        if (PhraseMatcher.matchesAny(text, challenge.phrases)) return succeed(out)
        challengeAttempts++
        when {
          // A forgotten passphrase must never end someone's run.
          challengeAttempts > challenge.hintAfterAttempts + 2 -> succeed(out)
          challengeAttempts >= challenge.hintAfterAttempts && signal.hint.isNotEmpty() ->
              speak(signal.hint, KIND_RETRY, out, interruptible = false, fromSignal = signal.id)
          else -> retry(signal, out)
        }
      }
      is Challenge.Choice -> {
        val byOption = challenge.options.associate { option -> option.id to option.phrases }
        val match = PhraseMatcher.bestAbove(text, byOption) ?: return retry(signal, out)
        flags[signal.id] = match.key
        val chosen = challenge.options.first { it.id == match.key }
        if (chosen.reply.isEmpty()) succeed(out)
        else speak(chosen.reply, KIND_CHOICE, out, interruptible = false, fromSignal = signal.id)
      }
      is Challenge.Ask -> {
        if (questionsAsked > 0 && PhraseMatcher.matchesAny(text, challenge.acceptPhrases)) {
          return succeed(out)
        }
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
      emitSpeak(listOf(systemLine("not-understood", mission.system.notUnderstood)), KIND_RETRY, out, interruptible = false)
    } else {
      speak(lines, KIND_RETRY, out, interruptible = false, fromSignal = signal.id)
    }
  }

  private fun afterAskReply(out: MutableList<Effect>) {
    val ask = currentSignal?.challenge as? Challenge.Ask ?: return
    if (questionsAsked >= ask.maxQuestions && ask.acceptPrompt.isNotEmpty()) {
      speak(ask.acceptPrompt, KIND_RETRY, out, interruptible = false, fromSignal = currentSignal?.id)
    } else {
      openChallenge(out)
    }
  }

  // -- character agent -------------------------------------------------------------

  private fun askCharacter(characterId: String, text: String, out: MutableList<Effect>) {
    val history = histories.getOrPut(characterId) { mutableListOf() }
    val priorTurns = history.takeLast(8)
    history += Turn(fromPlayer = true, text = text)
    requestSeq++
    pendingRequest = PendingRequest(requestSeq, characterId, phase, signalIndex)
    out += Effect.AskCharacter(
        CharacterRequest(
            requestId = requestSeq,
            characterId = characterId,
            signalId = currentSignal?.id,
            utterance = text,
            context = characterContext(),
            history = priorTurns,
        ),
    )
  }

  private fun onCharacterReplied(event: GameEvent.CharacterReplied, out: MutableList<Effect>) {
    val pending = pendingRequest ?: return
    if (event.requestId != pending.id) return
    pendingRequest = null
    // The story moved on while the agent thought; drop the stale reply.
    if (pending.phase != phase || pending.signalIndex != signalIndex || paused) return
    if (!interruptSpeech(out)) return

    val ask = currentSignal?.challenge as? Challenge.Ask
    val fallback = ask?.fallbackReplies
        ?.takeIf { it.isNotEmpty() }
        ?.let { replies -> replies[(questionsAsked - 1).coerceAtLeast(0) % replies.size] }
    val replyText = event.text?.takeIf { it.isNotBlank() } ?: fallback ?: mission.system.npcOffline

    histories.getOrPut(pending.speaker) { mutableListOf() } += Turn(fromPlayer = false, text = replyText)
    val anchor = currentSignal?.id?.takeIf {
      phase == Phase.ENCOUNTER && pending.speaker == currentSignal?.speaker
    }
    val reply = Utterance(null, pending.speaker, replyText, anchor)
    // Challenge replies are story beats; free talk may be talked over.
    val isChallengeBeat = phase == Phase.ENCOUNTER && ask != null
    emitSpeak(listOf(reply), KIND_NPC, out, interruptible = !isChallengeBeat)
    lastAuthored = listOf(reply)
  }

  fun characterContext(): CharacterContext {
    val signal = currentSignal
    val currentReading = reading
    return CharacterContext(
        phase = phase,
        objective = objective(),
        signalName = signal?.name,
        place = signal?.place,
        distance = currentReading?.let { Geo.spokenDistance(it.distanceM) },
        direction = currentReading?.let { direction(it.bearingDeg) },
        collected = collected.size,
        total = total,
        flags = flags.toMap(),
        challenge = signal?.challenge?.let { challenge -> challengeName(challenge) },
    )
  }

  // -- rhythm, tune, record ----------------------------------------------------------

  private fun onRhythmPlayed(event: GameEvent.RhythmPlayed, out: MutableList<Effect>) {
    if (!rhythmPlaying || kindOf(event.token) != KIND_RHYTHM) return
    rhythmPlaying = false
    if (phase != Phase.ENCOUNTER || paused) return
    val rhythm = currentSignal?.challenge as? Challenge.Rhythm ?: return
    nowMs = event.nowMs
    rhythmTaps = mutableListOf()
    val patternSpanMs = (rhythm.onsetsBeats.last() - rhythm.onsetsBeats.first()) * mission.music.beatMs
    rhythmWindowEndsMs = event.nowMs + patternSpanMs.toLong() + 5_000L
  }

  private fun evaluateRhythm(challenge: Challenge.Rhythm, out: MutableList<Effect>) {
    val taps = rhythmTaps ?: return
    rhythmTaps = null
    rhythmAttempts++
    val passed = taps.size >= challenge.onsetsBeats.size &&
        RhythmMatcher.match(challenge.onsetsBeats, taps, mission.music.beatMs, challenge.toleranceBeats).passed
    if (passed || rhythmAttempts >= challenge.maxAttempts) return succeed(out)
    val signal = currentSignal ?: return
    val lines = select(signal.retry)
    if (lines.isEmpty()) {
      emitSpeak(listOf(systemLine("rhythm-miss", mission.system.rhythmMiss)), KIND_RETRY, out, interruptible = false)
    } else {
      speak(lines, KIND_RETRY, out, interruptible = false, fromSignal = signal.id)
    }
  }

  private fun checkTune(challenge: Challenge.Tune, out: MutableList<Effect>) {
    val delta = tuneDelta(challenge)
    if (delta == null) {
      // No heading: warn once, then trust the runner's capture press.
      if (tuneNoHeadingWarned) return succeed(out)
      tuneNoHeadingWarned = true
      speakSystem("tune-no-heading", mission.system.tuneNoHeading.replace("{target}", challenge.targetName), out)
      return
    }
    if (abs(delta) <= challenge.toleranceDeg) return succeed(out)
    challengeAttempts++
    if (challengeAttempts >= 6) return succeed(out)
    out += Effect.PlaySfx(Sfx.DENIED)
    speakSystem("tune-dir", if (delta > 0) mission.system.tuneRight else mission.system.tuneLeft, out)
  }

  /** Degrees to turn (positive = right) to face the tune target; null with no heading. */
  fun tuneDelta(challenge: Challenge.Tune): Double? {
    val headDeg = heading.deg ?: return null
    val origin = lastFix?.position ?: currentSignal?.location ?: return null
    return Geo.deltaDeg(headDeg, Geo.bearingDeg(origin, challenge.target))
  }

  private fun onRecordingFinished(event: GameEvent.RecordingFinished, out: MutableList<Effect>) {
    if (!recording) return
    recording = false
    if (event.path != null) {
      record(TranscriptEntry(mission.system.speaker, mission.system.recordingSaved, fromPlayer = false))
    }
    succeed(out)
  }

  /** Operator traffic is held until the runner is between activities. */
  private fun deliverBroadcast(out: MutableList<Effect>) {
    if (pendingBroadcasts.isEmpty() || paused || speaking || listening != null || recording) return
    if (rhythmPlaying || rhythmTaps != null || pendingRequest != null) return
    if (phase == Phase.IDLE || phase == Phase.COMPLETE) return
    val broadcast = pendingBroadcasts.removeFirst()
    val speaker = if (mission.character(broadcast.speaker) != null) broadcast.speaker else handler
    out += Effect.PlaySfx(Sfx.TRANSMISSION)
    emitSpeak(listOf(Utterance("live", speaker, broadcast.text)), KIND_SYSTEM, out, interruptible = true)
  }

  private fun onTick(now: Long, out: MutableList<Effect>) {
    nowMs = now
    deliverBroadcast(out)
    val rhythm = currentSignal?.challenge as? Challenge.Rhythm
    if (rhythm != null && rhythmTaps != null && now > rhythmWindowEndsMs) {
      evaluateRhythm(rhythm, out)
    }
    // Gentle reminder while standing on a lockable signal.
    if (phase == Phase.HUNTING && lockAvailable && !paused && !speaking && now - lockAnnouncedAtMs > 25_000L) {
      lockAnnouncedAtMs = now
      speakSystem("lock", mission.system.lockAvailable, out)
    }
  }

  // -- speaking helpers ----------------------------------------------------------------

  private fun repeatLast(out: MutableList<Effect>) {
    if (lastAuthored.isEmpty()) return
    emitSpeak(lastAuthored, KIND_REPEAT, out, interruptible = true)
  }

  private fun giveHint(out: MutableList<Effect>) {
    val signal = currentSignal ?: return
    when (phase) {
      Phase.HUNTING -> {
        val statusLine = systemLine("status", fill(mission.system.status))
        val hintLines = select(signal.hint).map { line -> line.toUtterance(signal.id) }
        emitSpeak(listOf(statusLine) + hintLines, KIND_HINT, out, interruptible = true)
      }
      Phase.ENCOUNTER -> {
        val lines = select(signal.hint).ifEmpty { select(signal.challenge.prompt) }
        emitSpeak(lines.map { line -> line.toUtterance(signal.id) }, KIND_HINT, out, interruptible = true)
      }
      else -> sayStatus(out)
    }
  }

  private fun sayStatus(out: MutableList<Effect>) {
    speakSystem("status", fill(mission.system.status), out)
  }

  /** Spoken direction: clock-face with a head heading, compass points without. */
  fun direction(bearingDeg: Double): String {
    val headDeg = heading.deg ?: return "to the ${Geo.cardinal(bearingDeg)}"
    val clock = Geo.clockPosition(Geo.deltaDeg(headDeg, bearingDeg))
    return if (clock == 12) "straight ahead" else "at $clock o'clock"
  }

  private fun fill(template: String): String {
    val currentReading = reading
    return template
        .replace("{signal}", currentSignal?.name ?: "the signal")
        .replace("{distance}", currentReading?.let { Geo.spokenDistance(it.distanceM) } ?: "somewhere close")
        .replace("{direction}", currentReading?.let { direction(it.bearingDeg) } ?: "but I have no fix on you yet")
        .replace("{count}", collected.size.toString())
        .replace("{total}", total.toString())
  }

  fun objective(): String {
    val signal = currentSignal
    return when (phase) {
      Phase.IDLE -> "Stand by"
      Phase.BRIEFING -> "Listen to the briefing"
      Phase.HUNTING ->
          "Find ${signal?.name}" + (signal?.place?.takeIf { it.isNotBlank() }?.let { place -> " · $place" } ?: "")
      Phase.ENCOUNTER -> when (val challenge = signal?.challenge) {
        is Challenge.Command -> "Say \"${challenge.phrases.first()}\""
        is Challenge.Passphrase -> "Speak the passphrase"
        is Challenge.Choice ->
            "Choose: " + challenge.options.joinToString(" or ") { option -> "\"${option.phrases.first()}\"" }
        is Challenge.Ask ->
            "Ask ${mission.character(signal.speaker)?.name ?: "them"} a question, then say \"${challenge.acceptPhrases.first()}\""
        is Challenge.Rhythm -> "Tap the rhythm back on the touchpad"
        is Challenge.Record -> "Record a message · press capture to finish"
        is Challenge.Tune -> "Face ${challenge.targetName} · press capture"
        null -> ""
      }
      Phase.COLLECTING -> "Fragment ${collected.size} of $total collected"
      Phase.FINALE -> "The final broadcast"
      Phase.COMPLETE -> "Broadcast restored"
    }
  }

  private fun challengeName(challenge: Challenge): String = when (challenge) {
    is Challenge.Command -> "command"
    is Challenge.Passphrase -> "passphrase"
    is Challenge.Choice -> "choice"
    is Challenge.Ask -> "ask"
    is Challenge.Rhythm -> "rhythm"
    is Challenge.Record -> "record"
    is Challenge.Tune -> "tune"
  }

  private fun systemLine(id: String, text: String): Utterance =
      Utterance("system.$id", mission.system.speaker, text)

  private fun speakSystem(id: String, text: String, out: MutableList<Effect>) {
    emitSpeak(listOf(systemLine(id, text)), KIND_SYSTEM, out, interruptible = true)
  }

  private fun Line.toUtterance(anchorSignalId: String?): Utterance = Utterance(
      lineId = id,
      speaker = speaker,
      text = text,
      fromSignal = anchorSignalId.takeIf {
        speaker != handler && speaker != mission.system.speaker
      },
  )

  /** Lines whose flag requirements the run currently satisfies. */
  private fun select(lines: List<Line>): List<Line> =
      lines.filter { line -> line.requires.all { (key, value) -> flags[key] == value } }

  private fun speak(
      lines: List<Line>,
      kind: String,
      out: MutableList<Effect>,
      interruptible: Boolean = true,
      fromSignal: String? = null,
      extra: List<Utterance> = emptyList(),
  ) {
    val utterances = select(lines).map { line -> line.toUtterance(fromSignal) }
    if (utterances.isNotEmpty() && kind != KIND_SYSTEM) lastAuthored = utterances
    emitSpeak(utterances + extra, kind, out, interruptible)
  }

  private fun emitSpeak(
      utterances: List<Utterance>,
      kind: String,
      out: MutableList<Effect>,
      interruptible: Boolean,
  ) {
    val token = newToken(kind)
    if (utterances.isEmpty()) {
      // Nothing selected to say: flow on as if it had been spoken.
      activeSpeech = Effect.Speak(emptyList(), token, interruptible)
      onSpeechFinished(token, out)
      return
    }
    utterances.forEach { utterance ->
      record(TranscriptEntry(utterance.speaker, utterance.text, fromPlayer = false))
    }
    val speech = Effect.Speak(utterances, token, interruptible)
    activeSpeech = speech
    out += speech
  }

  private fun newToken(kind: String): String = "$kind#${++tokenSeq}"

  private fun kindOf(token: String): String = token.substringBefore('#')

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
