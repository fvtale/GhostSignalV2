package com.ghostsignal.core.model

import com.ghostsignal.core.geo.Geo
import kotlin.math.abs

/**
 * Every spoken line authored in a mission, de-duplicated by line id.
 * Used to pre-render voices before an event so nothing waits on synthesis.
 */
object LineCatalog {

  fun all(mission: Mission): List<Line> {
    val collected = mutableListOf<Line>()
    fun add(lines: List<Line>) { collected += lines }

    add(mission.prologue)
    add(mission.briefing)
    for (signal in mission.signals) {
      add(signal.teaser)
      add(signal.approach)
      add(signal.arrival)
      add(signal.challenge.prompt)
      add(signal.success)
      add(signal.retry)
      add(signal.hint)
      when (val challenge = signal.challenge) {
        is Challenge.Choice -> challenge.options.forEach { add(it.reply) }
        is Challenge.Ask -> add(challenge.acceptPrompt)
        else -> Unit
      }
    }
    add(mission.finale.lines)
    add(mission.finale.outro)
    for (track in mission.soundtracks) add(track.unlockLines)

    return collected.distinctBy { it.id }
  }

  /** Every line id occurrence, duplicates included, for validation. */
  fun allIds(mission: Mission): List<String> {
    val ids = mutableListOf<String>()
    fun add(lines: List<Line>) { lines.forEach { ids += it.id } }

    add(mission.prologue)
    add(mission.briefing)
    for (signal in mission.signals) {
      add(signal.teaser)
      add(signal.approach)
      add(signal.arrival)
      add(signal.challenge.prompt)
      add(signal.success)
      add(signal.retry)
      add(signal.hint)
      when (val challenge = signal.challenge) {
        is Challenge.Choice -> challenge.options.forEach { add(it.reply) }
        is Challenge.Ask -> add(challenge.acceptPrompt)
        else -> Unit
      }
    }
    add(mission.finale.lines)
    add(mission.finale.outro)
    for (track in mission.soundtracks) add(track.unlockLines)
    return ids
  }
}

/**
 * Soundtrack availability. The original recording is always playable; each
 * alternative unlocks after a counted number of completed runs.
 */
object Soundtracks {

  const val ORIGINAL = "original"

  data class Choice(
      val id: String,
      val title: String,
      val description: String,
      val unlocked: Boolean,
      val unlockAfterCompletions: Int,
  )

  /** Original first, then alternatives in unlock order (base before rewards). */
  fun choices(mission: Mission, completions: Int): List<Choice> {
    val original = Choice(ORIGINAL, mission.music.title.ifBlank { "Original" }, "", true, 0)
    val alternatives = mission.soundtracks
        .sortedBy { it.unlockAfterCompletions }
        .map { track ->
          Choice(
              id = track.id,
              title = track.title,
              description = track.description,
              unlocked = completions >= track.unlockAfterCompletions,
              unlockAfterCompletions = track.unlockAfterCompletions,
          )
        }
    return listOf(original) + alternatives
  }

  fun isUnlocked(mission: Mission, id: String, completions: Int): Boolean =
      id == ORIGINAL || mission.soundtracks.any { track ->
        track.id == id && completions >= track.unlockAfterCompletions
      }

  /** The track a run actually plays: the choice if earned, else original. */
  fun resolve(mission: Mission, chosen: String?, completions: Int): String =
      chosen?.takeIf { isUnlocked(mission, it, completions) } ?: ORIGINAL

  /** Tracks the next completed run will unlock for a runner at [completions]. */
  fun unlockedByNextCompletion(mission: Mission, completions: Int): List<Soundtrack> =
      mission.soundtracks.filter { it.unlockAfterCompletions == completions + 1 }
}

/**
 * Authoring checks that keep a broken mission from reaching a runner's
 * phone. Rendered as plain strings for the admin page and the test suite.
 */
object MissionValidator {

  fun validate(mission: Mission): List<String> {
    val problems = mutableListOf<String>()
    fun report(message: String) { problems += message }
    fun duplicatesOf(ids: List<String>): Set<String> =
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    val knownSpeakers = mission.characters.map { it.id }.toSet() + mission.system.speaker
    val bundledStemIds = mission.music.stems.map { it.id }.toSet()

    // Cast and script basics. An empty cast is fatal in the field: the
    // engine falls back to the first character for free talk.
    if (mission.characters.isEmpty()) report("mission has no characters")
    duplicatesOf(mission.characters.map { it.id }).forEach { report("duplicate character id '$it'") }
    duplicatesOf(mission.signals.map { it.id }).forEach { report("duplicate signal id '$it'") }
    duplicatesOf(mission.music.stems.map { it.id }).forEach { report("duplicate stem id '$it'") }
    duplicatesOf(LineCatalog.allIds(mission)).forEach { report("line id '$it' is used more than once") }

    for (line in LineCatalog.all(mission)) {
      if (line.speaker !in knownSpeakers) {
        report("line '${line.id}' has unknown speaker '${line.speaker}'")
      }
      if (line.text.isBlank()) report("line '${line.id}' is empty")
    }
    if (mission.briefing.isEmpty()) report("mission has no briefing")
    if (mission.handler.isNotBlank() && mission.character(mission.handler) == null) {
      report("handler '${mission.handler}' is not a character")
    }
    mission.antagonist?.let { antagonist ->
      if (mission.character(antagonist.speaker) == null) {
        report("antagonist '${antagonist.speaker}' is not a character")
      }
      if (antagonist.theme.isBlank()) report("antagonist '${antagonist.speaker}' has no theme")
      if (antagonist.duck !in 0.0..1.0) {
        report("antagonist duck ${antagonist.duck} should be between 0 and 1")
      }
    }
    if (mission.signals.isEmpty()) report("mission has no signals")
    if (mission.music.bpm !in 40.0..220.0) report("music bpm ${mission.music.bpm} looks wrong")

    // Alternative soundtracks must fill every slot the signals unlock.
    val requiredStems = mission.signals.map { it.stem }.toSet()
    duplicatesOf(mission.soundtracks.map { it.id }).forEach { report("duplicate soundtrack id '$it'") }
    for (track in mission.soundtracks) {
      if (track.id == Soundtracks.ORIGINAL) {
        report("soundtrack id '${track.id}' is reserved for the mission's own music")
      }
      if (track.title.isBlank()) report("soundtrack '${track.id}' has no title")
      if (track.unlockAfterCompletions < 0) report("soundtrack '${track.id}' unlock count is negative")
      if (track.music.bpm !in 40.0..220.0) {
        report("soundtrack '${track.id}' bpm ${track.music.bpm} looks wrong")
      }
      duplicatesOf(track.music.stems.map { it.id }).forEach {
        report("soundtrack '${track.id}' has duplicate stem id '$it'")
      }
      val offered = track.music.stems.map { it.id }.toSet()
      (requiredStems - offered).sorted().forEach {
        report("soundtrack '${track.id}' has no stem '$it'")
      }
    }

    // Per-signal geometry, casting, and challenge sanity.
    val claimedStems = mutableSetOf<String>()
    for (signal in mission.signals) {
      if (signal.stem !in bundledStemIds) {
        report("signal '${signal.id}' unlocks unknown stem '${signal.stem}'")
      }
      if (!claimedStems.add(signal.stem)) {
        report("stem '${signal.stem}' is unlocked by more than one signal")
      }
      if (signal.speaker !in knownSpeakers) {
        report("signal '${signal.id}' has unknown speaker '${signal.speaker}'")
      }
      if (signal.lockRadiusM > signal.radiusM) {
        report("signal '${signal.id}' lock radius is larger than its near radius")
      }
      if (signal.lockRadiusM < 10) {
        report("signal '${signal.id}' lock radius under 10 m will be unreliable on phone GPS")
      }
      if (signal.elevationDeg !in -60.0..60.0) {
        report("signal '${signal.id}' elevation ${signal.elevationDeg} should be between -60 and 60 degrees")
      }
      if (abs(signal.location.lat) > 90 || abs(signal.location.lon) > 180) {
        report("signal '${signal.id}' has invalid coordinates")
      }
      if (signal.challenge.prompt.isEmpty()) {
        report("signal '${signal.id}' challenge has no prompt")
      }
      checkChallenge(signal, ::report)
    }

    // Neighbouring signals must be separable by GPS.
    mission.signals.zipWithNext { first, second ->
      val gap = Geo.distanceM(first.location, second.location)
      if (gap < 35) {
        report("signals '${first.id}' and '${second.id}' are only ${gap.toInt()} m apart")
      }
    }
    return problems
  }

  private fun checkChallenge(signal: Signal, report: (String) -> Unit) {
    when (val challenge = signal.challenge) {
      is Challenge.Command ->
          if (challenge.phrases.isEmpty()) report("signal '${signal.id}' command has no phrases")
      is Challenge.Passphrase ->
          if (challenge.phrases.isEmpty()) report("signal '${signal.id}' passphrase has no phrases")
      is Challenge.Choice -> {
        if (challenge.options.size < 2) {
          report("signal '${signal.id}' choice needs at least two options")
        }
        for (option in challenge.options) {
          if (option.phrases.isEmpty()) {
            report("signal '${signal.id}' option '${option.id}' has no phrases")
          }
        }
      }
      is Challenge.Ask ->
          if (challenge.fallbackReplies.isEmpty()) {
            report("signal '${signal.id}' ask has no offline fallback replies")
          }
      is Challenge.Rhythm ->
          if (challenge.onsetsBeats.size < 3) {
            report("signal '${signal.id}' rhythm needs at least three taps")
          }
      is Challenge.Record ->
          if (challenge.maxSeconds !in 3..60) {
            report("signal '${signal.id}' record length should be 3-60 s")
          }
      is Challenge.Tune ->
          if (Geo.distanceM(signal.location, challenge.target) < 150) {
            report("signal '${signal.id}' tune target is too close to aim at")
          }
    }
  }
}
