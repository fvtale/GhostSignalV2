package com.ghostsignal.core

import com.ghostsignal.core.engine.AudioSceneComposer
import com.ghostsignal.core.engine.Effect
import com.ghostsignal.core.engine.GameEngine
import com.ghostsignal.core.engine.GameEvent
import com.ghostsignal.core.engine.Phase
import com.ghostsignal.core.engine.PlayerAction
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.geo.LatLon
import com.ghostsignal.core.heading.HeadingEstimate
import com.ghostsignal.core.heading.HeadingSource
import com.ghostsignal.core.model.Challenge
import com.ghostsignal.core.model.Mission
import com.ghostsignal.core.model.Soundtracks
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Plays the engine like the Android app does, finishing speech and rhythm playback instantly. */
class Driver(val engine: GameEngine) {
  var now = 1_000_000L
  val log = mutableListOf<Effect>()
  var autoFinishSpeech = true

  fun send(event: GameEvent): List<Effect> {
    val produced = mutableListOf<Effect>()
    val queue = ArrayDeque(engine.handle(event))
    while (queue.isNotEmpty()) {
      val e = queue.removeFirst()
      produced += e
      log += e
      if (autoFinishSpeech) {
        when (e) {
          is Effect.Speak -> queue += engine.handle(GameEvent.SpeechFinished(e.token))
          is Effect.PlayRhythm -> queue += engine.handle(GameEvent.RhythmPlayed(e.token, now))
          else -> Unit
        }
      }
    }
    return produced
  }

  fun at(position: LatLon, accuracy: Double = 6.0) = send(GameEvent.Position(GeoFix(position, accuracy, tick())))

  fun press(action: PlayerAction, deviceTimeMs: Long? = null) = send(GameEvent.Input(action, tick(), deviceTimeMs))

  fun lastListen(): Effect.Listen = log.filterIsInstance<Effect.Listen>().last()

  fun say(text: String): List<Effect> {
    val listen = engine.listening ?: error("not listening (phase ${engine.phase})")
    return send(GameEvent.Heard(listen.token, text, isFinal = true, confidence = 0.9))
  }

  fun tick(ms: Long = 250): Long {
    now += ms
    return now
  }
}

fun loadMission(): Mission {
  val path = System.getProperty("ghostsignal.missionPath") ?: "../app/src/main/assets/mission/ghostsignal.json"
  return Mission.parse(File(path).readText())
}

class GameEngineTest {
  private val mission = loadMission()

  private fun started(): Driver {
    val d = Driver(GameEngine(mission))
    d.send(GameEvent.Start(d.now))
    return d
  }

  @Test
  fun briefingLeadsToFirstSignal() {
    val d = started()
    assertEquals(Phase.HUNTING, d.engine.phase)
    assertEquals("heartbeat", d.engine.currentSignal?.id)
  }

  @Test
  fun scanOutOfRangeGivesDirectionsInsteadOfLocking() {
    val d = started()
    val far = Geo.destination(mission.signals[0].location, 0.0, 300.0)
    d.at(far)
    val effects = d.press(PlayerAction.SCAN)
    assertEquals(Phase.HUNTING, d.engine.phase)
    val spoken = effects.filterIsInstance<Effect.Speak>().single().utterances.single().text
    assertTrue(spoken.startsWith("No soundwave in range."), spoken)
    assertTrue("metres" in spoken, spoken)
  }

  @Test
  fun fullRunThroughAllEightSignals() = playFullRun(mission)

  @Test
  fun hushHoldsTheFrequencyUntilVesperBreaksIn() {
    val d = Driver(GameEngine(mission)).apply { autoFinishSpeech = false }
    val prologue = d.send(GameEvent.Start(d.now)).filterIsInstance<Effect.Speak>().single()
    assertEquals(listOf("hush"), prologue.utterances.map { it.speaker }.distinct())
    assertEquals(1.0, AudioSceneComposer.compose(d.engine).antagonistLevel)

    val briefing = d.send(GameEvent.SpeechFinished(prologue.token)).filterIsInstance<Effect.Speak>().single()
    assertEquals("vesper", briefing.utterances.first().speaker)
    assertEquals(0.0, AudioSceneComposer.compose(d.engine).antagonistLevel)
    assertEquals("vesper", d.engine.handler, "HUSH speaks first, but Vesper answers when the runner talks")
  }

  @Test
  fun hushClosesInAroundTheSignalItGuardsAndPushesTheMusicDown() {
    val fogIndex = mission.signals.indexOfFirst { it.speaker == "hush" }
    val fog = mission.signals[fogIndex]
    fun sceneAt(m: Mission, distanceM: Double): com.ghostsignal.core.engine.AudioScene {
      val d = Driver(GameEngine(m))
      d.send(GameEvent.Start(d.now))
      d.send(GameEvent.JumpTo(fogIndex))
      d.at(Geo.destination(fog.location, 0.0, distanceM))
      return AudioSceneComposer.compose(d.engine)
    }
    val far = sceneAt(mission, 250.0)
    val near = sceneAt(mission, 35.0)
    assertTrue(far.antagonistLevel >= 0.35, "HUSH is already there while the runner hunts its signal")
    assertTrue(near.antagonistLevel > far.antagonistLevel, "and grows as they close in")

    val free = sceneAt(mission.copy(antagonist = null), 35.0)
    val heartbeat = mission.signals.first().stem
    assertEquals(1.0, free.stems.getValue(heartbeat).gain)
    assertTrue(near.stems.getValue(heartbeat).gain < 0.7, "collected layers drop under HUSH's theme")

    val d = Driver(GameEngine(mission))
    d.send(GameEvent.Start(d.now))
    d.send(GameEvent.JumpTo(fogIndex))
    d.send(GameEvent.ForceSolve)
    assertEquals(0.0, AudioSceneComposer.compose(d.engine).antagonistLevel, "jamming HUSH takes the frequency back")
  }

  @Test
  fun fullRunOnTheDeadChannelSoundtrack() = playFullRun(mission.withSoundtrack("dead-channel"))

  @Test
  fun aFinishThatUnlocksSomethingAnnouncesItBeforeTheRunCompletes() {
    val reward = Soundtracks.unlockedByNextCompletion(mission, completions = 0).flatMap { it.unlockLines }
    assertTrue(reward.isNotEmpty(), "the first finish should unlock a soundtrack")
    val d = Driver(GameEngine(mission, reward))
    d.send(GameEvent.Start(d.now))
    repeat(mission.signals.size) { d.send(GameEvent.ForceSolve) }

    assertEquals(Phase.COMPLETE, d.engine.phase)
    val announcement = d.log.filterIsInstance<Effect.Speak>().last()
    assertEquals(reward.map { it.text }, announcement.utterances.map { it.text })
    val spokenAt = d.log.indexOf(announcement)
    val completedAt = d.log.indexOfLast { it is Effect.Persist && it.progress.complete }
    assertTrue(completedAt > spokenAt, "the run counts as finished only after the announcement")
  }

  @Test
  fun aFinishThatUnlocksNothingEndsAfterTheOutro() {
    val d = started()
    repeat(mission.signals.size) { d.send(GameEvent.ForceSolve) }
    assertEquals(Phase.COMPLETE, d.engine.phase)
    val last = d.log.filterIsInstance<Effect.Speak>().last().utterances.map { it.text }
    assertEquals(mission.finale.outro.map { it.text }, last)
  }

  private fun playFullRun(mission: Mission) {
    val d = Driver(GameEngine(mission))
    d.send(GameEvent.Start(d.now))
    d.send(GameEvent.Heading(HeadingEstimate(90.0, HeadingSource.GLASSES, true)))
    val unlocked = mutableListOf<String>()

    for ((i, signal) in mission.signals.withIndex()) {
      assertEquals(i, d.engine.signalIndex, "expected to be hunting ${signal.id}")
      assertEquals(Phase.HUNTING, d.engine.phase)
      val effects = d.at(signal.location)
      assertTrue(d.engine.lockAvailable, "standing on ${signal.id} should allow a lock")
      assertTrue(effects.any { it is Effect.PlaySfx })
      d.press(PlayerAction.SCAN)
      assertEquals(Phase.ENCOUNTER, d.engine.phase, "scan should start the ${signal.id} encounter")

      when (val c = signal.challenge) {
        is Challenge.Command -> d.say("okay ${c.phrases.first()}")
        is Challenge.Passphrase -> d.say(c.phrases.first())
        is Challenge.Choice -> d.say("jam it")
        is Challenge.Ask -> {
          val ask = d.say("what's the oldest sound you keep?")
          val request = ask.filterIsInstance<Effect.AskCharacter>().single().request
          assertEquals(signal.speaker, request.characterId)
          d.send(GameEvent.CharacterReplied(request.requestId, "A ship's horn from 1890, runner."))
          assertNotNull(d.engine.listening, "after the reply the runner can ask again or accept")
          d.say("accept")
        }
        is Challenge.Rhythm -> {
          val beat = mission.music.beatMs
          c.onsetsBeats.forEach { b -> d.press(PlayerAction.TAP, deviceTimeMs = 50_000L + (b * beat).toLong()) }
        }
        is Challenge.Record -> {
          assertTrue(d.log.last { it is Effect.StartRecording } is Effect.StartRecording)
          d.send(GameEvent.RecordingFinished("/tmp/message.wav"))
        }
        is Challenge.Tune -> {
          val bearing = Geo.bearingDeg(signal.location, c.target)
          d.send(GameEvent.Heading(HeadingEstimate(bearing + 120, HeadingSource.GLASSES, true)))
          d.press(PlayerAction.SCAN)
          assertEquals(Phase.ENCOUNTER, d.engine.phase, "facing away should not tune")
          d.send(GameEvent.Heading(HeadingEstimate(bearing + 5, HeadingSource.GLASSES, true)))
          d.press(PlayerAction.SCAN)
        }
      }
      unlocked += d.log.filterIsInstance<Effect.UnlockStem>().last().stemId
      assertTrue(signal.id in d.engine.collected, "${signal.id} should be collected")
    }

    assertEquals(mission.signals.map { it.stem }, unlocked)
    assertEquals(Phase.COMPLETE, d.engine.phase)
    assertEquals("jam", d.engine.flags["fog"])
    val finaleTexts = d.log.filterIsInstance<Effect.Speak>().flatMap { it.utterances }.map { it.text }
    assertTrue(finaleTexts.any { "still rebooting" in it }, "jam branch line should play")
    assertFalse(finaleTexts.any { "came back licensed" in it }, "comply branch line should not play")
    assertTrue(d.log.filterIsInstance<Effect.Persist>().last().progress.complete)
  }

  @Test
  fun wrongPassphraseHintsThenLetsTheRunnerThrough() {
    val d = started()
    val index = mission.signals.indexOfFirst { it.challenge is Challenge.Passphrase }
    d.send(GameEvent.JumpTo(index))
    d.at(mission.signals[index].location)
    d.press(PlayerAction.SCAN)
    val hintIds = mutableListOf<String?>()
    repeat(5) {
      if (d.engine.phase == Phase.ENCOUNTER) {
        val effects = d.say("banana")
        hintIds += effects.filterIsInstance<Effect.Speak>().flatMap { it.utterances }.map { it.lineId }
      }
    }
    assertTrue(hintIds.contains("${mission.signals[index].id}.hint"), "hint should play after repeated misses")
    assertTrue(mission.signals[index].id in d.engine.collected, "runner must never be stuck")
  }

  @Test
  fun pauseDuringStoryLineReplaysItOnResume() {
    val d = Driver(GameEngine(mission))
    d.autoFinishSpeech = false
    d.send(GameEvent.Start(d.now))
    val opening = d.log.filterIsInstance<Effect.Speak>().single()
    d.press(PlayerAction.PAUSE_TOGGLE)
    assertTrue(d.engine.paused)
    // The opening's completion must not advance the game while paused.
    d.send(GameEvent.SpeechFinished(opening.token))
    assertEquals(Phase.BRIEFING, d.engine.phase)

    val resumed = d.press(PlayerAction.PAUSE_TOGGLE).filterIsInstance<Effect.Speak>().single()
    assertEquals(opening.utterances, resumed.utterances.drop(1))
    d.send(GameEvent.SpeechFinished(resumed.token))
    val briefing = d.log.filterIsInstance<Effect.Speak>().last()
    assertEquals("vesper", briefing.utterances.first().speaker, "the briefing follows the replayed opening")
    d.send(GameEvent.SpeechFinished(briefing.token))
    assertEquals(Phase.HUNTING, d.engine.phase)
  }

  @Test
  fun lateCharacterReplyIsDroppedAfterTheStoryMovesOn() {
    val d = started()
    d.send(GameEvent.Input(PlayerAction.TALK, d.tick()))
    val ask = d.say("Vesper, what happened to your broadcast last night?")
    val request = ask.filterIsInstance<Effect.AskCharacter>().single().request
    assertEquals("vesper", request.characterId)
    d.send(GameEvent.ForceLock)
    val effects = d.send(GameEvent.CharacterReplied(request.requestId, "Left at the lions."))
    assertTrue(effects.none { it is Effect.Speak }, "stale reply must not talk over the encounter")
  }

  @Test
  fun listenTimeoutsReopenOnceThenWaitForTalk() {
    val d = started()
    d.send(GameEvent.ForceLock)
    val first = d.engine.listening
    assertNotNull(first)
    d.send(GameEvent.ListenEnded(first.token))
    val second = d.engine.listening
    assertNotNull(second, "first miss reopens the mic")
    d.send(GameEvent.ListenEnded(second.token))
    assertNull(d.engine.listening, "second miss waits for the runner to hold capture")
    d.press(PlayerAction.TALK)
    assertNotNull(d.engine.listening)
  }

  @Test
  fun huntingSceneTracksTheBeaconAndCollectedStemsMoveIntoTheHead() {
    val d = started()
    val s = mission.signals[0]
    d.at(Geo.destination(s.location, 270.0, 150.0))
    val far = AudioSceneComposer.compose(d.engine).stems.getValue(s.stem)
    d.at(Geo.destination(s.location, 270.0, 40.0))
    val near = AudioSceneComposer.compose(d.engine).stems.getValue(s.stem)
    assertTrue(near.clarity > far.clarity)
    assertTrue(far.worldBearingDeg!! in 80.0..100.0, "signal is east of the runner")

    d.send(GameEvent.ForceSolve)
    val scene = AudioSceneComposer.compose(d.engine)
    assertNull(scene.stems.getValue(s.stem).worldBearingDeg, "collected stem plays in the head")
    assertEquals(1.0, scene.stems.getValue(s.stem).clarity)
  }

  @Test
  fun resumeFromSavedProgressSkipsTheBriefing() {
    val d = Driver(GameEngine(mission))
    val progress = com.ghostsignal.core.engine.Progress(mission.id, 2, listOf("heartbeat", "stacks"), emptyMap())
    val effects = d.send(GameEvent.Start(d.now, progress))
    assertEquals(Phase.HUNTING, d.engine.phase)
    assertEquals(2, d.engine.signalIndex)
    assertEquals(listOf("kick", "hats"), effects.filterIsInstance<Effect.UnlockStem>().map { it.stemId })
  }

  @Test
  fun liveBroadcastWaitsForTheRunnerToBeFree() {
    val d = Driver(GameEngine(mission))
    d.autoFinishSpeech = false
    d.send(GameEvent.Start(d.now))
    val opening = d.log.filterIsInstance<Effect.Speak>().single()
    val during = d.send(GameEvent.Broadcast("vesper", "Runners, HUSH patrols near the fountain."))
    assertTrue(during.none { it is Effect.Speak }, "must not talk over the opening")
    d.send(GameEvent.SpeechFinished(opening.token))
    val briefing = d.log.filterIsInstance<Effect.Speak>().last()
    d.send(GameEvent.SpeechFinished(briefing.token))
    val teaser = d.log.filterIsInstance<Effect.Speak>().last()
    d.send(GameEvent.SpeechFinished(teaser.token))
    val delivered = d.send(GameEvent.Tick(d.tick()))
    val live = delivered.filterIsInstance<Effect.Speak>().single().utterances.single()
    assertEquals("Runners, HUSH patrols near the fountain.", live.text)
  }
}
