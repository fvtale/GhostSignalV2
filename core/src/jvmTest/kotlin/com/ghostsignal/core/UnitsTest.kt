package com.ghostsignal.core

import com.ghostsignal.core.engine.SignalField
import com.ghostsignal.core.engine.Zone
import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.geo.LatLon
import com.ghostsignal.core.heading.HeadingFusion
import com.ghostsignal.core.heading.HeadingSource
import com.ghostsignal.core.heading.ImuSample
import com.ghostsignal.core.heading.Vec3
import com.ghostsignal.core.model.LineCatalog
import com.ghostsignal.core.model.MissionValidator
import com.ghostsignal.core.model.Soundtracks
import java.io.File
import com.ghostsignal.core.rhythm.RhythmMatcher
import com.ghostsignal.core.voice.GlobalCommand
import com.ghostsignal.core.voice.PhraseMatcher
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTest {
  private val bryantPark = LatLon(40.753742, -73.983559)
  private val chrysler = LatLon(40.75167, -73.97528)

  @Test
  fun distanceAndBearingAcrossMidtown() {
    val d = Geo.distanceM(bryantPark, chrysler)
    assertTrue(d in 700.0..760.0, "got $d")
    val b = Geo.bearingDeg(bryantPark, chrysler)
    assertTrue(b in 105.0..115.0, "Chrysler is east-south-east of the park, got $b")
  }

  @Test
  fun destinationRoundTrips() {
    val p = Geo.destination(bryantPark, 29.0, 120.0)
    assertEquals(120.0, Geo.distanceM(bryantPark, p), 0.5)
    assertEquals(29.0, Geo.bearingDeg(bryantPark, p), 0.5)
  }

  @Test
  fun anglesAndClockFace() {
    assertEquals(20.0, Geo.deltaDeg(350.0, 10.0), 1e-9)
    assertEquals(-20.0, Geo.deltaDeg(10.0, 350.0), 1e-9)
    assertEquals(3, Geo.clockPosition(90.0))
    assertEquals(12, Geo.clockPosition(-10.0))
    assertEquals(9, Geo.clockPosition(-90.0))
    assertEquals("north-east", Geo.cardinal(40.0))
  }
}

class PhraseMatcherTest {
  @Test
  fun forgivesTypicalRecognizerMistakes() {
    assertTrue(PhraseMatcher.score("the code", "decode") >= PhraseMatcher.DEFAULT_THRESHOLD)
    assertTrue(PhraseMatcher.score("Fortytude!", "fortitude") >= PhraseMatcher.DEFAULT_THRESHOLD)
    assertTrue(PhraseMatcher.score("okay jam it now", "jam") >= PhraseMatcher.DEFAULT_THRESHOLD)
    assertTrue(PhraseMatcher.score("broad cast", "broadcast") >= PhraseMatcher.DEFAULT_THRESHOLD)
  }

  @Test
  fun keepsDifferentWordsApart() {
    assertTrue(PhraseMatcher.score("comply", "jam") < 0.5)
    assertTrue(PhraseMatcher.score("patience", "fortitude") < PhraseMatcher.DEFAULT_THRESHOLD)
    assertTrue(PhraseMatcher.score("what is this place", "decode") < PhraseMatcher.DEFAULT_THRESHOLD)
  }

  @Test
  fun globalCommands() {
    assertEquals(GlobalCommand.REPEAT, GlobalCommand.match("say that again"))
    assertEquals(GlobalCommand.STATUS, GlobalCommand.match("how far"))
    assertNull(GlobalCommand.match("fortitude"))
  }
}

class RhythmMatcherTest {
  private val onsets = listOf(0.0, 1.0, 1.5, 2.0, 3.0)
  private val beatMs = 60_000.0 / 118

  private fun taps(scale: Double, jitterMs: List<Long> = List(5) { 0L }) =
      onsets.mapIndexed { i, b -> 10_000L + (b * beatMs * scale).toLong() + jitterMs[i] }

  @Test
  fun exactAndSlightlySlowTapsPass() {
    assertTrue(RhythmMatcher.match(onsets, taps(1.0), beatMs, 0.22).passed)
    assertTrue(RhythmMatcher.match(onsets, taps(1.2, listOf(0, 30, -25, 20, 0)), beatMs, 0.22).passed)
  }

  @Test
  fun evenTapsFailTheSyncopatedPattern() {
    val even = (0 until 5).map { 10_000L + (it * beatMs * 0.75).toLong() }
    assertFalse(RhythmMatcher.match(onsets, even, beatMs, 0.22).passed)
  }

  @Test
  fun tooFewTapsFail() {
    assertFalse(RhythmMatcher.match(onsets, taps(1.0).take(3), beatMs, 0.22).passed)
  }
}

class SignalFieldTest {
  private val signal = loadMission().signals[1]

  private fun fixAt(distance: Double, accuracy: Double = 5.0) =
      GeoFix(Geo.destination(signal.location, 200.0, distance), accuracy, 0)

  @Test
  fun zonesAndStrength() {
    assertEquals(Zone.LOCK, SignalField.evaluate(signal, fixAt(5.0)).zone)
    assertEquals(Zone.NEAR, SignalField.evaluate(signal, fixAt(35.0)).zone)
    assertEquals(Zone.FAR, SignalField.evaluate(signal, fixAt(120.0)).zone)
    val strengths = listOf(10.0, 60.0, 120.0, 200.0, 600.0).map { SignalField.evaluate(signal, fixAt(it)).strength }
    assertEquals(strengths.sortedDescending(), strengths, "strength falls with distance")
    assertTrue(strengths.last() > 0.0, "the beacon never goes fully silent")
  }

  @Test
  fun poorAccuracyIsCreditedToThePlayer() {
    assertEquals(Zone.NEAR, SignalField.evaluate(signal, fixAt(30.0, accuracy = 4.0)).zone)
    assertEquals(Zone.LOCK, SignalField.evaluate(signal, fixAt(30.0, accuracy = 25.0)).zone)
  }
}

class HeadingFusionTest {
  /** Gravity along +Y (glasses upright), turning right at [rateDegS] about the vertical. */
  private fun feed(f: HeadingFusion, seconds: Double, rateDegS: Double, startNs: Long, wallStart: Long): Pair<Long, Long> {
    var ns = startNs
    var wall = wallStart
    val steps = (seconds * 30).toInt()
    repeat(steps) {
      ns += 33_333_333L
      wall += 33
      val turnRight = -Math.toRadians(rateDegS) // clockwise seen from above = negative about +up
      f.onImu(ImuSample(ns, Vec3(0.0, 9.81, 0.0), Vec3(0.0, turnRight, 0.0), null), wall)
    }
    return ns to wall
  }

  @Test
  fun alignsToPhoneThenTracksHeadTurns() {
    val f = HeadingFusion()
    var (ns, wall) = feed(f, 0.5, 0.0, 0, 10_000)
    f.onPhoneAzimuth(100.0, wall, inHand = true)
    assertTrue(f.calibrateToPhone(wall))
    assertEquals(100.0, f.estimate(wall).deg!!, 1.0)

    val r = feed(f, 1.0, 45.0, ns, wall)
    ns = r.first
    wall = r.second
    f.onPhoneAzimuth(100.0, wall - 5_000, inHand = false) // phone back in the pocket
    val est = f.estimate(wall)
    assertEquals(HeadingSource.GLASSES, est.source)
    assertEquals(145.0, est.deg!!, 4.0)
  }

  @Test
  fun fallsBackToPhoneThenCourseThenNothing() {
    val f = HeadingFusion()
    assertEquals(HeadingSource.NONE, f.estimate(0).source)
    f.onPhoneAzimuth(30.0, 1_000, inHand = true)
    assertEquals(HeadingSource.PHONE, f.estimate(1_200).source)
    f.onCourse(200.0, 1.4, 5_000)
    val est = f.estimate(5_100)
    assertEquals(HeadingSource.COURSE, est.source)
    assertEquals(200.0, est.deg!!, 1e-9)
    assertEquals(HeadingSource.NONE, f.estimate(20_000).source)
  }

  @Test
  fun walkingCourseCorrectsGyroDrift() {
    val f = HeadingFusion()
    var (ns, wall) = feed(f, 0.5, 0.0, 0, 10_000)
    f.onPhoneAzimuth(0.0, wall, inHand = true)
    f.calibrateToPhone(wall)
    // The runner actually walks due east the whole time; alignment should converge there.
    repeat(150) {
      val r = feed(f, 0.2, 0.0, ns, wall)
      ns = r.first
      wall = r.second
      f.onPhoneAzimuth(0.0, wall - 10_000, inHand = false)
      f.onCourse(50.0, 1.4, wall)
    }
    assertTrue(abs(Geo.deltaDeg(f.estimate(wall).deg!!, 50.0)) < 5.0)
  }
}

class MissionTest {
  private val mission = loadMission()

  @Test
  fun bundledMissionIsValid() {
    val problems = MissionValidator.validate(mission)
    assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    assertEquals(8, mission.signals.size)
    assertEquals(8, mission.music.stems.size)
  }

  @Test
  fun lineCatalogCoversEveryChallengeBranch() {
    val ids = LineCatalog.all(mission).map { it.id }.toSet()
    assertTrue("fog.jam" in ids && "fog.comply" in ids && "stacks.accept" in ids)
    assertNotNull(mission.character("vesper"))
    assertTrue("unlock.deadchannel.1" in ids, "unlock announcements are pre-rendered like any other line")
  }

  @Test
  fun deadChannelUnlocksAfterTheFirstFinish() {
    assertEquals(Soundtracks.ORIGINAL, Soundtracks.resolve(mission, "dead-channel", completions = 0))
    assertEquals("dead-channel", Soundtracks.resolve(mission, "dead-channel", completions = 1))
    assertEquals("dead-channel", Soundtracks.resolve(mission, "dead-channel", completions = 7))
    assertEquals(Soundtracks.ORIGINAL, Soundtracks.resolve(mission, "no-such-mix", completions = 7))
    assertEquals(Soundtracks.ORIGINAL, Soundtracks.resolve(mission, null, completions = 7))
    assertEquals(listOf("dead-channel"), Soundtracks.unlockedByNextCompletion(mission, completions = 0).map { it.id })
    assertTrue(Soundtracks.unlockedByNextCompletion(mission, completions = 1).isEmpty(), "it unlocks once")
    assertEquals(listOf(true, false), Soundtracks.choices(mission, completions = 0).map { it.unlocked })
    assertEquals(listOf(true, true), Soundtracks.choices(mission, completions = 1).map { it.unlocked })
  }

  @Test
  fun aSoundtrackSwapsTheMusicAndKeepsTheStory() {
    val night = mission.withSoundtrack("dead-channel")
    assertEquals(88.0, night.music.bpm)
    assertEquals(mission.signals, night.signals)
    assertEquals(mission.music.stems.map { it.id }, night.music.stems.map { it.id })
    assertTrue(night.music.stems.all { it.file.startsWith("music/dead_channel/") })
    assertEquals(mission, mission.withSoundtrack(Soundtracks.ORIGINAL))
    assertEquals(mission, mission.withSoundtrack("no-such-mix"))
  }

  @Test
  fun everySoundtrackFileIsInTheApp() {
    val assets = File(System.getProperty("ghostsignal.missionPath") ?: "../app/src/main/assets/mission/ghostsignal.json").parentFile.parentFile
    val files = mission.music.stems.map { it.file } + mission.soundtracks.flatMap { t -> t.music.stems.map { it.file } } +
        listOfNotNull(mission.antagonist?.theme)
    files.forEach { assertTrue(File(assets, it).isFile, "missing asset $it") }
  }

  @Test
  fun validatorCatchesASoundtrackThatLeavesASlotEmpty() {
    val t = mission.soundtracks.first()
    val missingVox = t.copy(music = t.music.copy(stems = t.music.stems.filter { it.id != "vox" }))
    assertTrue(MissionValidator.validate(mission.copy(soundtracks = listOf(missingVox))).any { "has no stem 'vox'" in it })
    val reserved = t.copy(id = Soundtracks.ORIGINAL)
    assertTrue(MissionValidator.validate(mission.copy(soundtracks = listOf(reserved))).any { "reserved" in it })
  }
}
