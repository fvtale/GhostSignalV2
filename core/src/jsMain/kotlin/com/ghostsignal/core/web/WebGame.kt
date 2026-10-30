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
 * The game engine for the web player. Same engine, same rules as the Android app; this class only
 * translates. Everything crosses the boundary as strings and numbers: each event method returns
 * the effects it caused as a JSON array, and [scene] and [state] describe what to play and show.
 *
 * Times are milliseconds as JavaScript numbers. The mission is the bundled one with the server's
 * deployed soundtracks ([catalogJson]) and venue route ([routeJson]) applied, playing whichever
 * soundtrack the runner chose if they've unlocked it.
 */
@JsExport
class WebGame(missionJson: String, routeJson: String?, catalogJson: String?, soundtrackId: String?, completions: Int) {
  private val mission: Mission
  private val engine: GameEngine
  private val fusion = HeadingFusion()

  init {
    val route = routeJson?.let { Mission.json.decodeFromString(RouteOverride.serializer(), it) }
    val catalog = catalogJson?.let { Mission.json.decodeFromString(TrackCatalog.serializer(), it) }
    val full = Mission.parse(missionJson).withTracks(catalog).withRoute(route)
    mission = full.withSoundtrack(Soundtracks.resolve(full, soundtrackId, completions))
    // If finishing this run unlocks a soundtrack, the handler announces it after the outro.
    engine = GameEngine(mission, Soundtracks.unlockedByNextCompletion(mission, completions).flatMap { it.unlockLines })
  }

  /** The mission as this run plays it, as JSON: stems to load, characters' voices, signal positions. */
  fun missionJson(): String = Mission.json.encodeToString(Mission.serializer(), mission)

  // ---------------------------------------------------------------- events in, effects out

  fun start(nowMs: Double, progressJson: String?): String =
      run(GameEvent.Start(nowMs.toLong(), progressJson?.let { Mission.json.decodeFromString(Progress.serializer(), it) }))

  fun position(lat: Double, lon: Double, accuracyM: Double, timeMs: Double, speedMps: Double?, courseDeg: Double?, simulated: Boolean): String {
    fusion.onCourse(courseDeg, speedMps, timeMs.toLong())
    return run(GameEvent.Position(GeoFix(LatLon(lat, lon), accuracyM, timeMs.toLong(), speedMps, courseDeg, simulated)))
  }

  /** The phone's compass heading (degrees from north). [inHand]: the screen is on and being looked at. */
  fun phoneHeading(deg: Double, nowMs: Double, inHand: Boolean) = fusion.onPhoneAzimuth(deg, nowMs.toLong(), inHand)

  /** Where the runner is facing for the audio, or null: the fused phone compass and walking course. */
  fun listenerHeading(nowMs: Double): Double? = fusion.estimate(nowMs.toLong()).deg

  /** Head pitch for the audio (degrees, up positive), or null when no glasses pitch is fresh. */
  fun listenerPitch(nowMs: Double): Double? = fusion.estimate(nowMs.toLong()).pitchDeg

  /** Call about 20 times a second: feeds the fused heading, then advances timers. */
  fun tick(nowMs: Double): String {
    val now = nowMs.toLong()
    val out = engine.handle(GameEvent.Heading(fusion.estimate(now))) + engine.handle(GameEvent.Tick(now))
    return effects(out)
  }

  /** SCAN, TAP, TALK, REPEAT, HINT, STATUS or PAUSE_TOGGLE. */
  fun input(action: String, nowMs: Double): String {
    val a = PlayerAction.entries.firstOrNull { it.name == action } ?: return "[]"
    return run(GameEvent.Input(a, nowMs.toLong()))
  }

  fun heard(token: String, text: String, isFinal: Boolean, confidence: Double): String = run(GameEvent.Heard(token, text, isFinal, confidence))

  fun listenEnded(token: String): String = run(GameEvent.ListenEnded(token))

  fun speechFinished(token: String): String = run(GameEvent.SpeechFinished(token))

  fun rhythmPlayed(token: String, nowMs: Double): String = run(GameEvent.RhythmPlayed(token, nowMs.toLong()))

  fun characterReplied(requestId: Int, text: String?): String = run(GameEvent.CharacterReplied(requestId, text))

  fun recordingFinished(path: String?): String = run(GameEvent.RecordingFinished(path))

  fun broadcast(speaker: String, text: String): String = run(GameEvent.Broadcast(speaker, text))

  /** Operator overrides, for testing at a desk. */
  fun forceLock(): String = run(GameEvent.ForceLock)

  fun forceSolve(): String = run(GameEvent.ForceSolve)

  fun jumpTo(signalIndex: Int): String = run(GameEvent.JumpTo(signalIndex))

  private fun run(event: GameEvent): String = effects(engine.handle(event))

  // ---------------------------------------------------------------- what to play and show

  /** What each stem, the static, the ping and the antagonist's theme should sound like now. */
  fun scene(): String = sceneJson(AudioSceneComposer.compose(engine)).toString()

  /** Everything the screen shows. */
  fun state(): String {
    val e = engine
    val signal = e.currentSignal
    val r = e.reading
    return buildJsonObject {
      put("phase", e.phase.name)
      put("objective", e.objective())
      put("signalIndex", e.signalIndex)
      put("total", e.total)
      put("signalId", signal?.id)
      put("signalName", signal?.name)
      put("signalPlace", signal?.place)
      put("distanceM", r?.distanceM)
      put("bearingDeg", r?.bearingDeg)
      put("strength", r?.strength)
      put("zone", r?.zone?.name)
      put("direction", r?.let { e.direction(it.bearingDeg) })
      put("lockAvailable", e.lockAvailable)
      put("paused", e.paused)
      put("speakingKind", e.speakingKind)
      put("recording", e.recording)
      put("listening", e.listening?.let { l -> buildJsonObject { put("token", l.token); put("mode", l.mode.name); put("purpose", l.purpose.name) } } ?: JsonNull)
      put("headingDeg", e.heading.deg)
      put("pitchDeg", e.heading.pitchDeg)
      put("rollDeg", e.heading.rollDeg)
      put("headingSource", e.heading.source.name)
      putJsonArray("collected") { e.collected.forEach { add(JsonPrimitive(it)) } }
      putJsonArray("layers") {
        mission.signals.forEach { s ->
          add(buildJsonObject {
            put("signalId", s.id)
            put("stem", s.stem)
            put("name", mission.music.stems.firstOrNull { it.id == s.stem }?.name ?: s.name)
            put("collected", s.id in e.collected)
          })
        }
      }
      putJsonArray("transcript") {
        e.transcript.toList().takeLast(12).forEach { t ->
          add(buildJsonObject { put("speaker", t.speaker); put("text", t.text); put("fromPlayer", t.fromPlayer) })
        }
      }
    }.toString()
  }

  /** Where this run's progress stands, for saving. */
  fun progressJson(): String = Mission.json.encodeToString(Progress.serializer(), engine.progress())

  // ---------------------------------------------------------------- translation

  private fun effects(list: List<Effect>): String = JsonArray(list.map(::effectJson)).toString()

  private fun effectJson(effect: Effect): JsonElement =
      when (effect) {
        is Effect.Speak ->
            buildJsonObject {
              put("type", "speak")
              put("token", effect.token)
              put("interruptible", effect.interruptible)
              putJsonArray("utterances") {
                effect.utterances.forEach { u ->
                  val character = mission.character(u.speaker)
                  val from = u.fromSignal?.let { mission.signal(it) }
                  add(buildJsonObject {
                    put("lineId", u.lineId)
                    put("speaker", u.speaker)
                    put("speakerName", character?.name ?: u.speaker)
                    put("text", u.text)
                    put("fromSignal", u.fromSignal)
                    put("fromLat", from?.location?.lat)
                    put("fromLon", from?.location?.lon)
                    put("radio", character?.radio ?: 0.4)
                    put("pitch", character?.voice?.pitch ?: 1.0)
                    put("rate", character?.voice?.rate ?: 1.0)
                  })
                }
              }
            }
        Effect.StopSpeaking -> type("stopSpeaking")
        is Effect.PlaySfx ->
            buildJsonObject {
              put("type", "sfx")
              put("sfx", effect.sfx.name)
              val located = effect.sfx == Sfx.SIGNAL_ACQUIRED || effect.sfx == Sfx.LOCK_AVAILABLE
              put("bearingDeg", if (located) engine.reading?.bearingDeg else null)
            }
        is Effect.Listen ->
            buildJsonObject {
              put("type", "listen")
              put("mode", effect.mode.name)
              put("timeoutMs", effect.timeoutMs.toDouble())
              put("token", effect.token)
              putJsonArray("hints") { effect.hints.forEach { add(JsonPrimitive(it)) } }
            }
        Effect.StopListening -> type("stopListening")
        is Effect.AskCharacter -> {
          val r = effect.request
          val c = r.context
          buildJsonObject {
            put("type", "ask")
            put("requestId", r.requestId)
            // The body POST /v1/npc expects, as the Android app sends it.
            putJsonObject("body") {
              put("missionId", mission.id)
              put("characterId", r.characterId)
              put("signalId", r.signalId)
              put("utterance", r.utterance.take(400))
              putJsonObject("context") {
                put("phase", c.phase.name)
                put("objective", c.objective)
                put("signalName", c.signalName)
                put("place", c.place)
                put("distance", c.distance)
                put("direction", c.direction)
                put("collected", c.collected)
                put("total", c.total)
                putJsonObject("flags") { c.flags.forEach { (k, v) -> put(k, v) } }
                put("challenge", c.challenge)
              }
              putJsonArray("history") {
                r.history.forEach { t -> add(buildJsonObject { put("role", if (t.fromPlayer) "runner" else "character"); put("text", t.text) }) }
              }
            }
          }
        }
        is Effect.StartRecording ->
            buildJsonObject {
              put("type", "startRecording")
              put("signalId", effect.signalId)
              put("maxSeconds", effect.maxSeconds)
            }
        Effect.StopRecording -> type("stopRecording")
        is Effect.PlayRhythm ->
            buildJsonObject {
              put("type", "rhythm")
              put("token", effect.token)
              putJsonArray("onsetsBeats") { effect.onsetsBeats.forEach { add(JsonPrimitive(it)) } }
            }
        is Effect.UnlockStem ->
            buildJsonObject {
              put("type", "unlockStem")
              put("stemId", effect.stemId)
            }
        is Effect.Persist ->
            buildJsonObject {
              put("type", "persist")
              put("progress", Mission.json.encodeToJsonElement(Progress.serializer(), effect.progress))
            }
      }

  private fun type(name: String): JsonObject = buildJsonObject { put("type", name) }

  private fun sceneJson(s: AudioScene): JsonObject = buildJsonObject {
    putJsonObject("stems") {
      s.stems.forEach { (id, st) ->
        putJsonObject(id) {
          put("gain", st.gain)
          put("bearingDeg", st.worldBearingDeg)
          put("distanceM", st.distanceM)
          put("clarity", st.clarity)
          put("elevationDeg", st.elevationDeg)
        }
      }
    }
    put("staticLevel", s.staticLevel)
    put("staticBearingDeg", s.staticBearingDeg)
    put("pingIntervalMs", s.pingIntervalMs?.toDouble())
    put("pingBearingDeg", s.pingBearingDeg)
    put("pingElevationDeg", s.pingElevationDeg)
    put("staticElevationDeg", s.staticElevationDeg)
    put("paused", s.paused)
    put("antagonistLevel", s.antagonistLevel)
  }
}

/** The soundtracks a runner can pick, as JSON: id, title, description, unlocked, unlockAfterCompletions. */
@JsExport
fun soundtrackChoices(missionJson: String, catalogJson: String?, completions: Int): String {
  val catalog = catalogJson?.let { Mission.json.decodeFromString(TrackCatalog.serializer(), it) }
  val m = Mission.parse(missionJson).withTracks(catalog)
  return buildJsonArray {
    Soundtracks.choices(m, completions).forEach { c ->
      add(buildJsonObject {
        put("id", c.id)
        put("title", c.title)
        put("description", c.description)
        put("unlocked", c.unlocked)
        put("unlockAfterCompletions", c.unlockAfterCompletions)
      })
    }
  }.toString()
}

/** Soundtracks the next finished run unlocks, as a JSON array of { id, title }. */
@JsExport
fun unlockedByNextCompletion(missionJson: String, catalogJson: String?, completions: Int): String {
  val catalog = catalogJson?.let { Mission.json.decodeFromString(TrackCatalog.serializer(), it) }
  val m = Mission.parse(missionJson).withTracks(catalog)
  return JsonArray(Soundtracks.unlockedByNextCompletion(m, completions).map { buildJsonObject { put("id", it.id); put("title", it.title) } }).toString()
}

/** Authoring problems in a mission, as a JSON array of messages. */
@JsExport
fun validateMission(missionJson: String): String = JsonArray(MissionValidator.validate(Mission.parse(missionJson)).map { JsonPrimitive(it) }).toString()

/** Distance in metres and bearing in degrees between two points, as [distance, bearing]. */
@JsExport
fun distanceAndBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Array<Double> {
  val a = LatLon(lat1, lon1)
  val b = LatLon(lat2, lon2)
  return arrayOf(Geo.distanceM(a, b), Geo.bearingDeg(a, b))
}
