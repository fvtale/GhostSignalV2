@file:OptIn(ExperimentalJsExport::class)

package com.ghostsignal.core.web

import com.ghostsignal.core.engine.AudioScene
import com.ghostsignal.core.engine.AudioSceneComposer
import com.ghostsignal.core.engine.Effect
import com.ghostsignal.core.engine.GameEngine
import com.ghostsignal.core.engine.GameEvent
import com.ghostsignal.core.engine.PlayerAction
import com.ghostsignal.core.engine.Progress
import com.ghostsignal.core.engine.Sfx
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.geo.LatLon
import com.ghostsignal.core.heading.HeadingFusion
import com.ghostsignal.core.model.Mission
import com.ghostsignal.core.model.MissionValidator
import com.ghostsignal.core.model.RouteOverride
import com.ghostsignal.core.model.Soundtracks
import com.ghostsignal.core.model.TrackCatalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Browser facade over the shared engine. The web player runs exactly the
 * same rules as the Android app; this class only translates at the border.
 * JavaScript passes strings and numbers in, and every event method returns
 * the effects it caused as a JSON array; [scene] and [state] say what to
 * play and what to show. All times are milliseconds (JS numbers).
 *
 * The mission that plays is the bundled one with the server's deployed
 * soundtracks and venue route applied, on the runner's chosen soundtrack
 * when they have unlocked it.
 */
@JsExport
class WebGame(
    missionJson: String,
    routeJson: String?,
    catalogJson: String?,
    soundtrackId: String?,
    completions: Int,
) {
  private val mission: Mission
  private val engine: GameEngine
  private val fusion = HeadingFusion()

  init {
    val json = Mission.json
    val route = routeJson?.let { text -> json.decodeFromString(RouteOverride.serializer(), text) }
    val catalog = catalogJson?.let { text -> json.decodeFromString(TrackCatalog.serializer(), text) }
    val prepared = Mission.parse(missionJson).withTracks(catalog).withRoute(route)
    mission = prepared.withSoundtrack(Soundtracks.resolve(prepared, soundtrackId, completions))
    // A finish that unlocks a soundtrack is announced after the outro.
    val rewardLines = Soundtracks.unlockedByNextCompletion(mission, completions)
        .flatMap { track -> track.unlockLines }
    engine = GameEngine(mission, rewardLines)
  }

  /** The mission as this run plays it, as JSON. */
  fun missionJson(): String = Mission.json.encodeToString(Mission.serializer(), mission)

  // -- events in, effects out ------------------------------------------------

  fun start(nowMs: Double, progressJson: String?): String {
    val resume = progressJson?.let { text ->
      Mission.json.decodeFromString(Progress.serializer(), text)
    }
    return dispatch(GameEvent.Start(nowMs.toLong(), resume))
  }

  fun position(
      lat: Double,
      lon: Double,
      accuracyM: Double,
      timeMs: Double,
      speedMps: Double?,
      courseDeg: Double?,
      simulated: Boolean,
  ): String {
    fusion.onCourse(courseDeg, speedMps, timeMs.toLong())
    val fix = GeoFix(LatLon(lat, lon), accuracyM, timeMs.toLong(), speedMps, courseDeg, simulated)
    return dispatch(GameEvent.Position(fix))
  }

  /** Phone compass heading. [inHand] means the screen is up and looked at. */
  fun phoneHeading(deg: Double, nowMs: Double, inHand: Boolean) {
    fusion.onPhoneAzimuth(deg, nowMs.toLong(), inHand)
  }

  /** Fused facing for the audio listener, or null when no source is fresh. */
  fun listenerHeading(nowMs: Double): Double? = fusion.estimate(nowMs.toLong()).deg

  /** Head pitch for the listener (degrees, up positive), or null. */
  fun listenerPitch(nowMs: Double): Double? = fusion.estimate(nowMs.toLong()).pitchDeg

  /** Call ~20x/second: refreshes the fused heading, then advances timers. */
  fun tick(nowMs: Double): String {
    val now = nowMs.toLong()
    val effects = engine.handle(GameEvent.Heading(fusion.estimate(now))) +
        engine.handle(GameEvent.Tick(now))
    return encodeEffects(effects)
  }

  /** One of SCAN, TAP, TALK, REPEAT, HINT, STATUS, PAUSE_TOGGLE. */
  fun input(action: String, nowMs: Double): String {
    val parsed = PlayerAction.entries.firstOrNull { entry -> entry.name == action }
      ?: return "[]"
    return dispatch(GameEvent.Input(parsed, nowMs.toLong()))
  }

  fun heard(token: String, text: String, isFinal: Boolean, confidence: Double): String =
      dispatch(GameEvent.Heard(token, text, isFinal, confidence))

  fun listenEnded(token: String): String = dispatch(GameEvent.ListenEnded(token))

  fun speechFinished(token: String): String = dispatch(GameEvent.SpeechFinished(token))

  fun rhythmPlayed(token: String, nowMs: Double): String =
      dispatch(GameEvent.RhythmPlayed(token, nowMs.toLong()))

  fun characterReplied(requestId: Int, text: String?): String =
      dispatch(GameEvent.CharacterReplied(requestId, text))

  fun recordingFinished(path: String?): String = dispatch(GameEvent.RecordingFinished(path))

  fun broadcast(speaker: String, text: String): String =
      dispatch(GameEvent.Broadcast(speaker, text))

  /** Operator overrides, for desk testing. */
  fun forceLock(): String = dispatch(GameEvent.ForceLock)

  fun forceSolve(): String = dispatch(GameEvent.ForceSolve)

  fun jumpTo(signalIndex: Int): String = dispatch(GameEvent.JumpTo(signalIndex))

  private fun dispatch(event: GameEvent): String = encodeEffects(engine.handle(event))

  // -- what to play and show ---------------------------------------------------

  /** Current stem/static/ping/antagonist playback picture, as JSON. */
  fun scene(): String = encodeScene(AudioSceneComposer.compose(engine)).toString()

  /** Everything the run screen renders, as JSON. */
  fun state(): String {
    val eng = engine
    val signal = eng.currentSignal
    val currentReading = eng.reading
    return buildJsonObject {
      put("phase", eng.phase.name)
      put("objective", eng.objective())
      put("signalIndex", eng.signalIndex)
      put("total", eng.total)
      put("signalId", signal?.id)
      put("signalName", signal?.name)
      put("signalPlace", signal?.place)
      put("distanceM", currentReading?.distanceM)
      put("bearingDeg", currentReading?.bearingDeg)
      put("strength", currentReading?.strength)
      put("zone", currentReading?.zone?.name)
      put("direction", currentReading?.let { r -> eng.direction(r.bearingDeg) })
      put("lockAvailable", eng.lockAvailable)
      put("paused", eng.paused)
      put("speakingKind", eng.speakingKind)
      put("recording", eng.recording)
      val listenState = eng.listening
      if (listenState == null) {
        put("listening", JsonNull)
      } else {
        put("listening", buildJsonObject {
          put("token", listenState.token)
          put("mode", listenState.mode.name)
          put("purpose", listenState.purpose.name)
        })
      }
      put("headingDeg", eng.heading.deg)
      put("pitchDeg", eng.heading.pitchDeg)
      put("rollDeg", eng.heading.rollDeg)
      put("headingSource", eng.heading.source.name)
      putJsonArray("collected") {
        eng.collected.forEach { id -> add(JsonPrimitive(id)) }
      }
      putJsonArray("layers") {
        for (sig in mission.signals) {
          add(buildJsonObject {
            put("signalId", sig.id)
            put("stem", sig.stem)
            put("name", mission.music.stems.firstOrNull { stem -> stem.id == sig.stem }?.name ?: sig.name)
            put("collected", sig.id in eng.collected)
          })
        }
      }
      putJsonArray("transcript") {
        for (entry in eng.transcript.toList().takeLast(12)) {
          add(buildJsonObject {
            put("speaker", entry.speaker)
            put("text", entry.text)
            put("fromPlayer", entry.fromPlayer)
          })
        }
      }
    }.toString()
  }

  /** Run progress as JSON, for the host to persist. */
  fun progressJson(): String =
      Mission.json.encodeToString(Progress.serializer(), engine.progress())

  // -- JSON translation ----------------------------------------------------------

  private fun encodeEffects(effects: List<Effect>): String =
      JsonArray(effects.map { effect -> encodeEffect(effect) }).toString()

  private fun encodeEffect(effect: Effect): JsonElement = when (effect) {
    is Effect.Speak -> buildJsonObject {
      put("type", "speak")
      put("token", effect.token)
      put("interruptible", effect.interruptible)
      putJsonArray("utterances") {
        for (utterance in effect.utterances) {
          val character = mission.character(utterance.speaker)
          val origin = utterance.fromSignal?.let { id -> mission.signal(id) }
          add(buildJsonObject {
            put("lineId", utterance.lineId)
            put("speaker", utterance.speaker)
            put("speakerName", character?.name ?: utterance.speaker)
            put("text", utterance.text)
            put("fromSignal", utterance.fromSignal)
            put("fromLat", origin?.location?.lat)
            put("fromLon", origin?.location?.lon)
            put("radio", character?.radio ?: 0.4)
            put("pitch", character?.voice?.pitch ?: 1.0)
            put("rate", character?.voice?.rate ?: 1.0)
          })
        }
      }
    }
    Effect.StopSpeaking -> typed("stopSpeaking")
    is Effect.PlaySfx -> buildJsonObject {
      put("type", "sfx")
      put("sfx", effect.sfx.name)
      val located = effect.sfx == Sfx.SIGNAL_ACQUIRED || effect.sfx == Sfx.LOCK_AVAILABLE
      put("bearingDeg", if (located) engine.reading?.bearingDeg else null)
    }
    is Effect.Listen -> buildJsonObject {
      put("type", "listen")
      put("mode", effect.mode.name)
      put("timeoutMs", effect.timeoutMs.toDouble())
      put("token", effect.token)
      putJsonArray("hints") {
        effect.hints.forEach { hint -> add(JsonPrimitive(hint)) }
      }
    }
    Effect.StopListening -> typed("stopListening")
    is Effect.AskCharacter -> {
      val request = effect.request
      val context = request.context
      buildJsonObject {
        put("type", "ask")
        put("requestId", request.requestId)
        // Request body for POST /v1/npc, matching the Android client.
        putJsonObject("body") {
          put("missionId", mission.id)
          put("characterId", request.characterId)
          put("signalId", request.signalId)
          put("utterance", request.utterance.take(400))
          putJsonObject("context") {
            put("phase", context.phase.name)
            put("objective", context.objective)
            put("signalName", context.signalName)
            put("place", context.place)
            put("distance", context.distance)
            put("direction", context.direction)
            put("collected", context.collected)
            put("total", context.total)
            putJsonObject("flags") {
              context.flags.forEach { (key, value) -> put(key, value) }
            }
            put("challenge", context.challenge)
          }
          putJsonArray("history") {
            for (turn in request.history) {
              add(buildJsonObject {
                put("role", if (turn.fromPlayer) "runner" else "character")
                put("text", turn.text)
              })
            }
          }
        }
      }
    }
    is Effect.StartRecording -> buildJsonObject {
      put("type", "startRecording")
      put("signalId", effect.signalId)
      put("maxSeconds", effect.maxSeconds)
    }
    Effect.StopRecording -> typed("stopRecording")
    is Effect.PlayRhythm -> buildJsonObject {
      put("type", "rhythm")
      put("token", effect.token)
      putJsonArray("onsetsBeats") {
        effect.onsetsBeats.forEach { onset -> add(JsonPrimitive(onset)) }
      }
    }
    is Effect.UnlockStem -> buildJsonObject {
      put("type", "unlockStem")
      put("stemId", effect.stemId)
    }
    is Effect.Persist -> buildJsonObject {
      put("type", "persist")
      put("progress", Mission.json.encodeToJsonElement(Progress.serializer(), effect.progress))
    }
  }

  private fun typed(name: String): JsonObject = buildJsonObject { put("type", name) }

  private fun encodeScene(scene: AudioScene): JsonObject = buildJsonObject {
    putJsonObject("stems") {
      scene.stems.forEach { (stemId, stem) ->
        putJsonObject(stemId) {
          put("gain", stem.gain)
          put("bearingDeg", stem.worldBearingDeg)
          put("distanceM", stem.distanceM)
          put("clarity", stem.clarity)
          put("elevationDeg", stem.elevationDeg)
        }
      }
    }
    put("staticLevel", scene.staticLevel)
    put("staticBearingDeg", scene.staticBearingDeg)
    put("pingIntervalMs", scene.pingIntervalMs?.toDouble())
    put("pingBearingDeg", scene.pingBearingDeg)
    put("pingElevationDeg", scene.pingElevationDeg)
    put("staticElevationDeg", scene.staticElevationDeg)
    put("paused", scene.paused)
    put("antagonistLevel", scene.antagonistLevel)
  }
}

/** Playable soundtracks as JSON: id, title, description, unlocked, unlockAfterCompletions. */
@JsExport
fun soundtrackChoices(missionJson: String, catalogJson: String?, completions: Int): String {
  val catalog = decodeCatalog(catalogJson)
  val parsed = Mission.parse(missionJson).withTracks(catalog)
  return buildJsonArray {
    for (choice in Soundtracks.choices(parsed, completions)) {
      add(buildJsonObject {
        put("id", choice.id)
        put("title", choice.title)
        put("description", choice.description)
        put("unlocked", choice.unlocked)
        put("unlockAfterCompletions", choice.unlockAfterCompletions)
      })
    }
  }.toString()
}

/** Soundtracks the next finish unlocks, as JSON [{ id, title }]. */
@JsExport
fun unlockedByNextCompletion(missionJson: String, catalogJson: String?, completions: Int): String {
  val parsed = Mission.parse(missionJson).withTracks(decodeCatalog(catalogJson))
  val unlocking = Soundtracks.unlockedByNextCompletion(parsed, completions).map { track ->
    buildJsonObject {
      put("id", track.id)
      put("title", track.title)
    }
  }
  return JsonArray(unlocking).toString()
}

/** Authoring problems found in a mission, as a JSON string array. */
@JsExport
fun validateMission(missionJson: String): String {
  val problems = MissionValidator.validate(Mission.parse(missionJson))
  return JsonArray(problems.map { problem -> JsonPrimitive(problem) }).toString()
}

/** [distanceM, bearingDeg] between two coordinates. */
@JsExport
fun distanceAndBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Array<Double> {
  val from = LatLon(lat1, lon1)
  val to = LatLon(lat2, lon2)
  return arrayOf(Geo.distanceM(from, to), Geo.bearingDeg(from, to))
}

private fun decodeCatalog(catalogJson: String?): TrackCatalog? =
    catalogJson?.let { text -> Mission.json.decodeFromString(TrackCatalog.serializer(), text) }
