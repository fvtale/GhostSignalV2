package com.ghostsignal.core.model

import com.ghostsignal.core.geo.LatLon
import kotlin.math.floor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Mission data model. A mission is authored entirely as JSON (bundled at
// app/src/main/assets/mission/ghostsignal.json, or edited from the admin
// page) so writers can change story, route, and soundtrack without a rebuild.
// Field names and defaults below are the file format: do not rename them
// without migrating every authored mission.

@Serializable
data class Mission(
    val id: String,
    val title: String,
    val version: Int = 1,
    /** Shared world brief passed to the character agent on every request. */
    val world: String = "",
    val characters: List<Character>,
    val music: MusicManifest,
    /** Played before the briefing under the antagonist theme: threat first. */
    val prologue: List<Line> = emptyList(),
    val briefing: List<Line>,
    val signals: List<Signal>,
    val finale: Finale,
    val system: SystemLines = SystemLines(),
    /** Unlockable alternative recordings; [music] is always the original. */
    val soundtracks: List<Soundtrack> = emptyList(),
    /** Character who fields free talk while hunting. Blank: first briefer. */
    val handler: String = "",
    val antagonist: Antagonist? = null,
    /** The place this experience is anchored to; the opening uses it to suggest the nearest mission. */
    val landmark: Landmark? = null,
) {
  fun character(id: String): Character? = characters.firstOrNull { it.id == id }

  fun signal(id: String): Signal? = signals.firstOrNull { it.id == id }

  /** A copy of this mission playing [soundtrackId], or `this` if unknown. */
  fun withSoundtrack(soundtrackId: String?): Mission {
    val chosen = soundtracks.firstOrNull { it.id == soundtrackId } ?: return this
    return copy(music = chosen.music)
  }

  /**
   * Applies venue placements and admin renames from [route]. Renaming a
   * signal also renames the stem it unlocks in every soundtrack, because
   * runners know each layer by the signal's name.
   */
  fun withRoute(route: RouteOverride?): Mission {
    if (route == null || route.missionId != id) return this

    fun SignalLabel.cleanName(): String? = name.trim().ifBlank { null }
    fun SignalLabel.cleanPlace(): String? = place.trim().ifBlank { null }

    val movedSignals = signals.map { signal ->
      val placement = route.placements[signal.id]
      val label = route.labels[signal.id]
      signal.copy(
          location = placement?.location ?: signal.location,
          radiusM = placement?.radiusM ?: signal.radiusM,
          lockRadiusM = placement?.lockRadiusM ?: signal.lockRadiusM,
          name = label?.cleanName() ?: signal.name,
          place = label?.cleanPlace() ?: signal.place,
      )
    }

    val renamedStems: Map<String, String> = signals.mapNotNull { signal ->
      route.labels[signal.id]?.cleanName()?.let { newName -> signal.stem to newName }
    }.toMap()

    if (renamedStems.isEmpty()) return copy(signals = movedSignals)

    fun MusicManifest.withStemNames(): MusicManifest = copy(
        stems = stems.map { stem ->
          renamedStems[stem.id]?.let { stem.copy(name = it) } ?: stem
        },
    )
    return copy(
        signals = movedSignals,
        music = music.withStemNames(),
        soundtracks = soundtracks.map { track -> track.copy(music = track.music.withStemNames()) },
    )
  }

  /**
   * Merges in soundtracks deployed from the admin page. A deployed track is
   * admitted only when every stem is [ready] on the device, it fills every
   * slot the signals unlock, and its id is not already taken.
   */
  fun withTracks(catalog: TrackCatalog?, ready: (StemSpec) -> Boolean = { true }): Mission {
    if (catalog == null || catalog.missionId != id) return this
    val takenIds = soundtracks.map { it.id }.toSet() + Soundtracks.ORIGINAL
    val requiredSlots = signals.map { it.stem }.toSet()

    val admitted = catalog.soundtracks
        .filter { track -> track.id !in takenIds }
        .filter { track -> requiredSlots.all { slot -> track.music.stems.any { it.id == slot } } }
        .filter { track -> track.music.stems.all(ready) }
        .distinctBy { it.id }

    return if (admitted.isEmpty()) this else copy(soundtracks = soundtracks + admitted)
  }

  companion object {
    val json: Json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      prettyPrint = true
      classDiscriminator = "type"
    }

    fun parse(text: String): Mission = json.decodeFromString(serializer(), text)
  }
}

@Serializable
data class Character(
    val id: String,
    val name: String,
    val role: String = "",
    /** Persona, speech style, and boundaries for the character agent. */
    val persona: String = "",
    val voice: VoiceSpec = VoiceSpec(),
    /** Radio treatment: 0 = clean studio voice, 1 = crushed walkie-talkie. */
    val radio: Double = 0.5,
)

@Serializable
data class VoiceSpec(
    /** Legacy cloud voice id, retained so old missions still parse. */
    val ttsVoice: String = "",
    /** Pitch/rate for the on-device speech fallback. */
    val pitch: Double = 1.0,
    val rate: Double = 1.0,
)

/**
 * One line of authored dialogue. [requires] gates a line on story flags:
 * `{"hush": "comply"}` plays only if that flag holds that value.
 */
@Serializable
data class Line(
    val id: String,
    val speaker: String,
    val text: String,
    val requires: Map<String, String> = emptyMap(),
)

@Serializable
data class Signal(
    val id: String,
    val name: String,
    /** Short spoken description of the real place; used by hints. */
    val place: String = "",
    val location: LatLon,
    /** Inside this radius the runner is "near" and hears the approach. */
    val radiusM: Double = 45.0,
    /** Inside this radius a capture press locks the signal. */
    val lockRadiusM: Double = 28.0,
    /** Authored source height for spatial audio, degrees in -60..60. */
    val elevationDeg: Double = 0.0,
    /** Music stem collected at this signal. */
    val stem: String,
    val speaker: String,
    val teaser: List<Line> = emptyList(),
    val approach: List<Line> = emptyList(),
    val arrival: List<Line> = emptyList(),
    val challenge: Challenge,
    val success: List<Line> = emptyList(),
    val retry: List<Line> = emptyList(),
    val hint: List<Line> = emptyList(),
)

@Serializable
sealed class Challenge {
  abstract val prompt: List<Line>

  /** Say one of [phrases], e.g. "decode" or "broadcast". */
  @Serializable
  @SerialName("command")
  data class Command(override val prompt: List<Line>, val phrases: List<String>) : Challenge()

  /** Speak a passphrase learned earlier in the run. */
  @Serializable
  @SerialName("passphrase")
  data class Passphrase(
      override val prompt: List<Line>,
      val phrases: List<String>,
      val hintAfterAttempts: Int = 2,
  ) : Challenge()

  /** Choose a story branch aloud; the option id is stored as a flag. */
  @Serializable
  @SerialName("choice")
  data class Choice(override val prompt: List<Line>, val options: List<ChoiceOption>) : Challenge()

  /** Free-form questions to the character agent, ended by accepting. */
  @Serializable
  @SerialName("ask")
  data class Ask(
      override val prompt: List<Line>,
      val maxQuestions: Int = 2,
      val acceptPhrases: List<String> = listOf("accept", "deal", "i accept"),
      val acceptPrompt: List<Line> = emptyList(),
      /** Replies used when the character agent cannot be reached. */
      val fallbackReplies: List<String> = emptyList(),
  ) : Challenge()

  /** Hear a rhythm, then tap it back. [onsetsBeats] are beat positions. */
  @Serializable
  @SerialName("rhythm")
  data class Rhythm(
      override val prompt: List<Line>,
      val onsetsBeats: List<Double>,
      val toleranceBeats: Double = 0.2,
      val maxAttempts: Int = 3,
  ) : Challenge()

  /** Record a message for the next runner. Stored on-device only. */
  @Serializable
  @SerialName("record")
  data class Record(override val prompt: List<Line>, val maxSeconds: Int = 15) : Challenge()

  /** Face a real landmark until the static clears, then capture. */
  @Serializable
  @SerialName("tune")
  data class Tune(
      override val prompt: List<Line>,
      val target: LatLon,
      val targetName: String,
      val toleranceDeg: Double = 22.0,
  ) : Challenge()
}

@Serializable
data class ChoiceOption(
    val id: String,
    val phrases: List<String>,
    val reply: List<Line> = emptyList(),
)

@Serializable
data class Finale(val lines: List<Line>, val outro: List<Line> = emptyList())

@Serializable
data class MusicManifest(
    /** Display title shown when choosing a soundtrack. */
    val title: String = "",
    val bpm: Double,
    val beatsPerBar: Int = 4,
    /**
     * Loop length in bars. Every stem is conformed to exactly this length
     * so the parts stay sample-locked indefinitely.
     */
    val loopBars: Int = 8,
    val stems: List<StemSpec>,
) {
  val beatMs: Double get() = 60_000.0 / bpm

  val barMs: Double get() = beatMs * beatsPerBar

  /** Exact loop length in sample frames at [sampleRate] (rounds half up). */
  fun loopFrames(sampleRate: Int): Int =
      floor(barMs * loopBars * sampleRate / 1000.0 + 0.5).toInt()
}

/**
 * An alternative recording of the mission music. It must fill the same stem
 * slots as the original so the story matches whichever track is playing.
 * Available after [unlockAfterCompletions] finished runs; 0 = from the start.
 */
@Serializable
data class Soundtrack(
    val id: String,
    val title: String,
    val description: String = "",
    val unlockAfterCompletions: Int = 1,
    /** Spoken once, after the outro of the run that unlocks this track. */
    val unlockLines: List<Line> = emptyList(),
    val music: MusicManifest,
)

/**
 * The opposing force. Its theme takes the frequency during the prologue and
 * near signals it guards, pushing the pirate music down by [duck].
 */
@Serializable
data class Antagonist(
    /** Character id of the antagonist. */
    val speaker: String,
    /** Looping theme asset, e.g. `music/hush/hush_theme.ogg`. */
    val theme: String,
    val gainDb: Double = 0.0,
    /** Music suppression while the theme holds: 0 = none, 1 = silence. */
    val duck: Double = 0.85,
)

/** The anchor place of a mission, used by the opening to suggest the nearest experience. */
@Serializable
data class Landmark(
    /** Short place name, e.g. "Bryant Park". */
    val name: String,
    /** Spoken form, e.g. "Bryant Park on 42nd". */
    val label: String,
    val location: LatLon,
)

@Serializable
data class StemSpec(
    val id: String,
    /**
     * Asset path such as `music/01_kick.ogg`, or `server:<sha256>.<ext>`
     * for a stem deployed from the admin page (see [TrackCatalog]).
     */
    val file: String,
    val name: String,
    val gainDb: Double = 0.0,
    /** Checksum/size of a deployed stem so downloads can be verified. */
    val sha256: String = "",
    val bytes: Long = 0,
)

/**
 * Soundtracks deployed from the admin page rather than bundled. Stems are
 * downloaded once, verified against [StemSpec.sha256], and played from local
 * storage. Premium tracks a player has not earned appear in [locked] so the
 * app can show (but not play) them.
 */
@Serializable
data class TrackCatalog(
    val missionId: String,
    val soundtracks: List<Soundtrack> = emptyList(),
    val updatedAtMs: Long = 0,
    val locked: List<LockedTrack> = emptyList(),
) {
  companion object {
    const val SERVER_PREFIX = "server:"

    /** Stored blob name (`<sha256>.<ext>`) of a deployed stem, else null. */
    fun blobOf(stem: StemSpec): String? =
        stem.file.takeIf { it.startsWith(SERVER_PREFIX) }?.removePrefix(SERVER_PREFIX)
  }
}

/**
 * System-voice templates spoken by the runner's SmartSpecs.
 * Placeholders: {signal} {distance} {direction} {count} {total} {target}.
 */
@Serializable
data class SystemLines(
    val speaker: String = "smartspecs",
    val noLock: String = "No soundwave in range. {signal} is {distance}, {direction}.",
    val status: String = "Layer {count} of {total}. {signal} is {distance}, {direction}.",
    val lockAvailable: String = "Soundwave in range. Press capture.",
    val locked: String = "Soundwave captured.",
    val paused: String = "Signal paused. Tap back to resume.",
    val resumed: String = "Signal resumed.",
    val talkHint: String = "Hold capture to talk.",
    val heardNothing: String = "Nothing on the channel.",
    val notUnderstood: String = "Didn't catch that.",
    val tuneLeft: String = "Static's heavier on your right. Turn left.",
    val tuneRight: String = "Static's heavier on your left. Turn right.",
    val tuneNoHeading: String = "No head tracking. Press capture when you are facing {target}.",
    val rhythmMiss: String = "Off the beat. Listen again.",
    val recordingSaved: String = "Message saved to your SmartSpecs.",
    val fragmentCollected: String = "Layer {count} of {total} unlocked.",
    val npcOffline: String = "Their channel is jammed. Try again in a moment.",
)

/** Venue placement for one signal, captured with the route editor. */
@Serializable
data class SignalPlacement(val location: LatLon, val radiusM: Double, val lockRadiusM: Double)

/** A premium soundtrack the player has not earned: shown, not playable. */
@Serializable
data class LockedTrack(val id: String, val title: String, val description: String = "")

/** Admin rename of a signal. Blank fields keep the authored text. */
@Serializable
data class SignalLabel(val name: String = "", val place: String = "")

@Serializable
data class RouteOverride(
    val missionId: String,
    val name: String = "",
    val placements: Map<String, SignalPlacement> = emptyMap(),
    val updatedAtMs: Long = 0,
    /** Admin-set signal names/places, keyed by signal id. */
    val labels: Map<String, SignalLabel> = emptyMap(),
)
