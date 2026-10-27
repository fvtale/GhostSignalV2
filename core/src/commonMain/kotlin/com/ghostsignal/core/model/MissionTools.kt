package com.ghostsignal.core.model

import com.ghostsignal.core.geo.Geo

/** Every authored line in a mission, for pre-rendering voices before the event. */
object LineCatalog {
  fun all(mission: Mission): List<Line> {
    val lines = mutableListOf<Line>()
    lines += mission.prologue
    lines += mission.briefing
    for (s in mission.signals) {
      lines += s.teaser + s.approach + s.arrival + s.challenge.prompt + s.success + s.retry + s.hint
      when (val c = s.challenge) {
        is Challenge.Choice -> c.options.forEach { lines += it.reply }
        is Challenge.Ask -> lines += c.acceptPrompt
        else -> Unit
      }
    }
    lines += mission.finale.lines + mission.finale.outro
    mission.soundtracks.forEach { lines += it.unlockLines }
    return lines.distinctBy { it.id }
  }
}

/**
 * Which soundtracks a runner may choose. The original is always available; each alternative
 * unlocks after a number of finished runs, counted on the phone.
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

  /** The original first, then the rest in the order they unlock: base tracks before rewards. */
  fun choices(mission: Mission, completions: Int): List<Choice> =
      listOf(Choice(ORIGINAL, mission.music.title.ifBlank { "Original" }, "", true, 0)) +
          mission.soundtracks.sortedBy { it.unlockAfterCompletions }.map {
            Choice(it.id, it.title, it.description, completions >= it.unlockAfterCompletions, it.unlockAfterCompletions)
          }

  fun isUnlocked(mission: Mission, id: String, completions: Int): Boolean =
      id == ORIGINAL || mission.soundtracks.any { it.id == id && completions >= it.unlockAfterCompletions }

  /** The soundtrack a run plays: the runner's choice if it is unlocked, otherwise the original. */
  fun resolve(mission: Mission, chosen: String?, completions: Int): String =
      chosen?.takeIf { isUnlocked(mission, it, completions) } ?: ORIGINAL

  /** Soundtracks that the next finished run unlocks, for a runner who has finished [completions] times. */
  fun unlockedByNextCompletion(mission: Mission, completions: Int): List<Soundtrack> =
      mission.soundtracks.filter { it.unlockAfterCompletions == completions + 1 }
}

/** Catches authoring mistakes before they reach a runner in the field. */
object MissionValidator {
  fun validate(mission: Mission): List<String> {
    val problems = mutableListOf<String>()
    val characterIds = mission.characters.map { it.id }.toSet() + mission.system.speaker
    val stemIds = mission.music.stems.map { it.id }.toSet()

    fun dupes(ids: List<String>) = ids.groupBy { it }.filterValues { it.size > 1 }.keys

    dupes(mission.signals.map { it.id }).forEach { problems += "duplicate signal id '$it'" }
    dupes(mission.music.stems.map { it.id }).forEach { problems += "duplicate stem id '$it'" }
    val allLines = LineCatalog.all(mission)
    val rawLineIds = buildList {
      addAll(mission.prologue.map { it.id })
      addAll(mission.briefing.map { it.id })
      mission.signals.forEach { s ->
        val c = s.challenge
        addAll((s.teaser + s.approach + s.arrival + c.prompt + s.success + s.retry + s.hint).map { it.id })
        if (c is Challenge.Choice) c.options.forEach { o -> addAll(o.reply.map { it.id }) }
        if (c is Challenge.Ask) addAll(c.acceptPrompt.map { it.id })
      }
      addAll((mission.finale.lines + mission.finale.outro).map { it.id })
      mission.soundtracks.forEach { t -> addAll(t.unlockLines.map { it.id }) }
    }
    dupes(rawLineIds).forEach { problems += "line id '$it' is used more than once" }

    for (line in allLines) {
      if (line.speaker !in characterIds) problems += "line '${line.id}' has unknown speaker '${line.speaker}'"
      if (line.text.isBlank()) problems += "line '${line.id}' is empty"
    }
    if (mission.briefing.isEmpty()) problems += "mission has no briefing"
    if (mission.handler.isNotBlank() && mission.character(mission.handler) == null) problems += "handler '${mission.handler}' is not a character"
    mission.antagonist?.let { a ->
      if (mission.character(a.speaker) == null) problems += "antagonist '${a.speaker}' is not a character"
      if (a.theme.isBlank()) problems += "antagonist '${a.speaker}' has no theme"
      if (a.duck !in 0.0..1.0) problems += "antagonist duck ${a.duck} should be between 0 and 1"
    }
    if (mission.signals.isEmpty()) problems += "mission has no signals"
    if (mission.music.bpm !in 40.0..220.0) problems += "music bpm ${mission.music.bpm} looks wrong"

    // Every alternative soundtrack has to fill the same slots, or a signal would unlock silence.
    val signalStems = mission.signals.map { it.stem }.toSet()
    dupes(mission.soundtracks.map { it.id }).forEach { problems += "duplicate soundtrack id '$it'" }
    for (t in mission.soundtracks) {
      if (t.id == Soundtracks.ORIGINAL) problems += "soundtrack id '${t.id}' is reserved for the mission's own music"
      if (t.title.isBlank()) problems += "soundtrack '${t.id}' has no title"
      if (t.unlockAfterCompletions < 0) problems += "soundtrack '${t.id}' unlock count is negative"
      if (t.music.bpm !in 40.0..220.0) problems += "soundtrack '${t.id}' bpm ${t.music.bpm} looks wrong"
      dupes(t.music.stems.map { it.id }).forEach { problems += "soundtrack '${t.id}' has duplicate stem id '$it'" }
      val ids = t.music.stems.map { it.id }.toSet()
      (signalStems - ids).sorted().forEach { problems += "soundtrack '${t.id}' has no stem '$it'" }
    }

    val usedStems = mutableSetOf<String>()
    for (s in mission.signals) {
      if (s.stem !in stemIds) problems += "signal '${s.id}' unlocks unknown stem '${s.stem}'"
      if (!usedStems.add(s.stem)) problems += "stem '${s.stem}' is unlocked by more than one signal"
      if (s.speaker !in characterIds) problems += "signal '${s.id}' has unknown speaker '${s.speaker}'"
      if (s.lockRadiusM > s.radiusM) problems += "signal '${s.id}' lock radius is larger than its near radius"
      if (s.lockRadiusM < 10) problems += "signal '${s.id}' lock radius under 10 m will be unreliable on phone GPS"
      if (abs(s.location.lat) > 90 || abs(s.location.lon) > 180) problems += "signal '${s.id}' has invalid coordinates"
      if (s.challenge.prompt.isEmpty()) problems += "signal '${s.id}' challenge has no prompt"
      when (val c = s.challenge) {
        is Challenge.Command -> if (c.phrases.isEmpty()) problems += "signal '${s.id}' command has no phrases"
        is Challenge.Passphrase -> if (c.phrases.isEmpty()) problems += "signal '${s.id}' passphrase has no phrases"
        is Challenge.Choice -> {
          if (c.options.size < 2) problems += "signal '${s.id}' choice needs at least two options"
          c.options.forEach { o -> if (o.phrases.isEmpty()) problems += "signal '${s.id}' option '${o.id}' has no phrases" }
        }
        is Challenge.Ask -> if (c.fallbackReplies.isEmpty()) problems += "signal '${s.id}' ask has no offline fallback replies"
        is Challenge.Rhythm -> if (c.onsetsBeats.size < 3) problems += "signal '${s.id}' rhythm needs at least three taps"
        is Challenge.Record -> if (c.maxSeconds !in 3..60) problems += "signal '${s.id}' record length should be 3-60 s"
        is Challenge.Tune -> if (Geo.distanceM(s.location, c.target) < 150) problems += "signal '${s.id}' tune target is too close to aim at"
      }
    }
    // Sequential signals should be far enough apart that GPS can tell them apart.
    mission.signals.zipWithNext { a, b ->
      val d = Geo.distanceM(a.location, b.location)
      if (d < 35) problems += "signals '${a.id}' and '${b.id}' are only ${d.toInt()} m apart"
    }
    return problems
  }

  private fun abs(d: Double) = kotlin.math.abs(d)
}
