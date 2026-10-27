package com.ghostsignal.core.model

import com.ghostsignal.core.geo.LatLon
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.floor

/**
 * A complete authored mission: characters, route, script and soundtrack.
 *
 * Missions are plain JSON (see `app/src/main/assets/mission/ghostsignal.json`) so writers and the
 * story agent can edit them without touching code. Positions can be overridden at the venue with a
 * [RouteOverride] produced by the in-app route editor.
 */
@Serializable
data class Mission(
    val id: String,
    val title: String,
    val version: Int = 1,
    /** Shared world description handed to the character agent with every request. */
    val world: String = "",
    val characters: List<Character>,
    val music: MusicManifest,
    /** Spoken before the briefing, under the [antagonist]'s theme: the threat is there first. */
    val prologue: List<Line> = emptyList(),
    val briefing: List<Line>,
    val signals: List<Signal>,
    val finale: Finale,
    val system: SystemLines = SystemLines(),
    /** Alternative soundtracks a runner can unlock. [music] is always the original. */
    val soundtracks: List<Soundtrack> = emptyList(),
    /** The character who answers free talk while hunting. Blank = the first briefing speaker. */
    val handler: String = "",
    val antagonist: Antagonist? = null,
) {
  fun character(id: String): Character? = characters.firstOrNull { it.id == id }

  fun signal(id: String): Signal? = signals.firstOrNull { it.id == id }

  /** This mission playing [soundtrackId]'s music, or unchanged if that isn't one of [soundtracks]. */
  fun withSoundtrack(soundtrackId: String?): Mission =
      soundtracks.firstOrNull { it.id == soundtrackId }?.let { copy(music = it.music) } ?: this

  /**
   * Venue placements and admin renames applied. A renamed signal renames its stem in every
   * soundtrack too, since the runner knows the layer by the signal's name.
   */
  fun withRoute(route: RouteOverride?): Mission {
    if (route == null || route.missionId != id) return this
    val placed =
        signals.map { s ->
          val p = route.placements[s.id]
          val label = route.labels[s.id]
          s.copy(
              location = p?.location ?: s.location,
              radiusM = p?.radiusM ?: s.radiusM,
              lockRadiusM = p?.lockRadiusM ?: s.lockRadiusM,
              name = label?.name?.trim()?.ifBlank { null } ?: s.name,
              place = label?.place?.trim()?.ifBlank { null } ?: s.place,
          )
        }
    val stemNames = signals.mapNotNull { s -> route.labels[s.id]?.name?.trim()?.ifBlank { null }?.let { s.stem to it } }.toMap()
    if (stemNames.isEmpty()) return copy(signals = placed)
    fun MusicManifest.renamed() = copy(stems = stems.map { st -> stemNames[st.id]?.let { st.copy(name = it) } ?: st })
    return copy(
        signals = placed,
        music = music.renamed(),
        soundtracks = soundtracks.map { it.copy(music = it.music.renamed()) },
    )
  }

  /**
   * Adds the soundtracks deployed from the admin page. A deployed track is left out until every
   * one of its stems is [ready] on the phone, if it doesn't fill every slot a signal unlocks, or if
   * its id is already taken by a bundled one.
   */
  fun withTracks(catalog: TrackCatalog?, ready: (StemSpec) -> Boolean = { true }): Mission {
    if (catalog == null || catalog.missionId != id) return this
    val taken = soundtracks.map { it.id }.toSet() + Soundtracks.ORIGINAL
    val slots = signals.map { it.stem }.toSet()
    val added =
        catalog.soundtracks
            .filter { t -> t.id !in taken && slots.all { slot -> t.music.stems.any { it.id == slot } } && t.music.stems.all(ready) }
            .distinctBy { it.id }
    return if (added.isEmpty()) this else copy(soundtracks = soundtracks + added)
  }

  companion object {
    val json = Json {
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
    /** Personality, speech style and boundaries for the character agent. */
    val persona: String = "",
    val voice: VoiceSpec = VoiceSpec(),
    /** 0 = clean studio voice, 1 = crushed walkie-talkie. */
    val radio: Double = 0.5,
)

@Serializable
data class VoiceSpec(
    /** Voice id for the cloud TTS provider (e.g. an ElevenLabs voice id). Blank = server default. */
    val ttsVoice: String = "",
    /** Pitch and rate for the on-phone Android TTS fallback. */
    val pitch: Double = 1.0,
    val rate: Double = 1.0,
)

/**
 * One spoken line. [requires] gates variants on story flags, e.g. `{"hush": "comply"}` only plays
 * if the player chose "comply" at the HUSH signal.
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
    /** Short spoken description of the real place, for hints. */
    val place: String = "",
    val location: LatLon,
    /** Within this radius the player is "near" and hears the approach line. */
    val radiusM: Double = 45.0,
    /** Within this radius a capture press locks the signal. */
    val lockRadiusM: Double = 28.0,
    /** Music stem this signal unlocks. */
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

  /** Say one of [phrases] ("decode", "broadcast"). */
  @Serializable
  @SerialName("command")
  data class Command(override val prompt: List<Line>, val phrases: List<String>) : Challenge()

  /** Speak a passphrase learned earlier in the story. */
  @Serializable
  @SerialName("passphrase")
  data class Passphrase(
      override val prompt: List<Line>,
      val phrases: List<String>,
      val hintAfterAttempts: Int = 2,
  ) : Challenge()

  /** Pick a story branch aloud. The chosen option id is stored as flag `<signalId>`. */
  @Serializable
  @SerialName("choice")
  data class Choice(override val prompt: List<Line>, val options: List<ChoiceOption>) : Challenge()

  /** Ask the character free-form questions (answered by the character agent), then "accept". */
  @Serializable
  @SerialName("ask")
  data class Ask(
      override val prompt: List<Line>,
      val maxQuestions: Int = 2,
      val acceptPhrases: List<String> = listOf("accept", "deal", "i accept"),
      val acceptPrompt: List<Line> = emptyList(),
      /** Used when the character agent is offline. */
      val fallbackReplies: List<String> = emptyList(),
  ) : Challenge()

  /** Hear a rhythm, then tap it back on the glasses touchpad. Onsets are in beats. */
  @Serializable
  @SerialName("rhythm")
  data class Rhythm(
      override val prompt: List<Line>,
      val onsetsBeats: List<Double>,
      val toleranceBeats: Double = 0.2,
      val maxAttempts: Int = 3,
  ) : Challenge()

  /** Record a message for the next runner. Stored on the phone only. */
  @Serializable
  @SerialName("record")
  data class Record(override val prompt: List<Line>, val maxSeconds: Int = 15) : Challenge()

  /** Turn to face a real landmark until the static clears, then press capture. */
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
data class ChoiceOption(val id: String, val phrases: List<String>, val reply: List<Line> = emptyList())

@Serializable
data class Finale(val lines: List<Line>, val outro: List<Line> = emptyList())

@Serializable
data class MusicManifest(
    /** What the runner sees when choosing a soundtrack. */
    val title: String = "",
    val bpm: Double,
    val beatsPerBar: Int = 4,
    /**
     * Loop length in bars. Every stem is trimmed or padded to exactly this length so the parts
     * stay sample-locked forever, even if an encoder padded one file by a few samples.
     */
    val loopBars: Int = 8,
    val stems: List<StemSpec>,
) {
  val beatMs: Double
    get() = 60_000.0 / bpm

  val barMs: Double
    get() = beatMs * beatsPerBar

  /** Exact loop length in frames at [sampleRate]. */
  fun loopFrames(sampleRate: Int): Int = floor(barMs * loopBars * sampleRate / 1000.0 + 0.5).toInt() // rounds half up, like Math.round
}

/**
 * Another recording of the mission's music. It fills the same slots as the original (a stem for
 * every id a signal unlocks), so the story still matches whichever one is playing. It becomes
 * available once the runner has finished the mission [unlockAfterCompletions] times; 0 means from
 * the start.
 */
@Serializable
data class Soundtrack(
    val id: String,
    val title: String,
    val description: String = "",
    val unlockAfterCompletions: Int = 1,
    /** Spoken once, after the outro of the run that unlocks it. */
    val unlockLines: List<Line> = emptyList(),
    val music: MusicManifest,
)

/**
 * The force the runner is up against. Its theme takes the frequency during the prologue and around
 * any signal it guards (one whose speaker is [speaker]), pushing the pirate music down by [duck].
 */
@Serializable
data class Antagonist(
    /** Character id. */
    val speaker: String,
    /** Looping theme in the app's assets, e.g. `music/hush/hush_theme.ogg`. */
    val theme: String,
    val gainDb: Double = 0.0,
    /** How far the pirate music drops while the theme holds: 0 = not at all, 1 = silenced. */
    val duck: Double = 0.85,
)

@Serializable
data class StemSpec(
    val id: String,
    /**
     * Path inside the app's assets, e.g. `music/01_kick.ogg`, or `server:<sha256>.<ext>` for a stem
     * deployed from the admin page (see [TrackCatalog]).
     */
    val file: String,
    val name: String,
    val gainDb: Double = 0.0,
    /** Checksum and size of a deployed stem, so the phone can verify its download. */
    val sha256: String = "",
    val bytes: Long = 0,
)

/**
 * Soundtracks deployed from the admin page rather than bundled with the app. The phone downloads
 * each stem once, checks it against its [StemSpec.sha256], and plays it from its own storage.
 * [Soundtrack.unlockAfterCompletions] 0 makes a track part of the base game. Premium tracks come
 * only to premium players; everyone else gets their titles in [locked], so the app can offer them.
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

    /** The stored file name (`<sha256>.<ext>`) of a deployed stem, or null for a bundled one. */
    fun blobOf(stem: StemSpec): String? = stem.file.takeIf { it.startsWith(SERVER_PREFIX) }?.removePrefix(SERVER_PREFIX)
  }
}

/**
 * Templates for the runner's SmartSpecs (the system voice). Placeholders: {signal} {distance}
 * {direction} {count} {total} {target}.
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

/** Placement of one signal at the venue, captured with the route editor. */
@Serializable
data class SignalPlacement(val location: LatLon, val radiusM: Double, val lockRadiusM: Double)

/** A premium soundtrack this player doesn't have: shown, not playable. */
@Serializable data class LockedTrack(val id: String, val title: String, val description: String = "")

/** An admin's rename of a signal. Blank keeps the authored text. Scripted dialogue is unchanged. */
@Serializable data class SignalLabel(val name: String = "", val place: String = "")

@Serializable
data class RouteOverride(
    val missionId: String,
    val name: String = "",
    val placements: Map<String, SignalPlacement> = emptyMap(),
    val updatedAtMs: Long = 0,
    /** Signal names and place descriptions set from the admin page, by signal id. */
    val labels: Map<String, SignalLabel> = emptyMap(),
)
