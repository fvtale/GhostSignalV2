package com.ghostsignal.core

import com.ghostsignal.core.engine.GameEngine
import com.ghostsignal.core.engine.GameEvent
import com.ghostsignal.core.model.Mission
import com.ghostsignal.core.model.MissionValidator
import com.ghostsignal.core.model.RouteOverride
import com.ghostsignal.core.model.SignalLabel
import com.ghostsignal.core.model.Soundtrack
import com.ghostsignal.core.model.Soundtracks
import com.ghostsignal.core.model.TrackCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Soundtracks deployed from the admin page, and signals renamed there. */
class TracksTest {
  private val mission = loadMission()

  /** A deployed track built the way the server writes one: every stem is a downloaded blob. */
  private fun deployed(id: String, unlock: Int = 0): Soundtrack {
    val template = mission.soundtracks.first()
    return Soundtrack(
        id = id,
        title = id.replaceFirstChar { it.uppercase() },
        unlockAfterCompletions = unlock,
        music =
            template.music.copy(
                title = id,
                stems = template.music.stems.mapIndexed { i, st -> st.copy(file = "server:${"%064x".format(i)}.ogg", sha256 = "%064x".format(i)) }),
    )
  }

  private fun catalog(vararg tracks: Soundtrack) = TrackCatalog(mission.id, tracks.toList(), 1)

  @Test
  fun deployedBaseTracksComeBeforeTheEarnedBonus() {
    val m = mission.withTracks(catalog(deployed("night-bus"), deployed("low-tide")))
    val choices = Soundtracks.choices(m, completions = 0)
    assertEquals(listOf(Soundtracks.ORIGINAL, "night-bus", "low-tide", "dead-channel"), choices.map { it.id })
    assertEquals(listOf(true, true, true, false), choices.map { it.unlocked })
    assertEquals("night-bus", Soundtracks.resolve(m, "night-bus", 0))
    assertTrue(MissionValidator.validate(m).isEmpty(), MissionValidator.validate(m).joinToString())
  }

  @Test
  fun aTrackWaitsUntilEveryStemIsOnThePhone() {
    val t = deployed("night-bus")
    val missing = t.music.stems.last()
    val m = mission.withTracks(catalog(t)) { it != missing }
    assertEquals(listOf("dead-channel"), m.soundtracks.map { it.id })
    assertEquals(Soundtracks.ORIGINAL, Soundtracks.resolve(m, "night-bus", 5))
  }

  @Test
  fun aTrackThatLeavesASlotEmptyOrTakesABundledIdIsLeftOut() {
    val short = deployed("short").let { it.copy(music = it.music.copy(stems = it.music.stems.drop(1))) }
    val clash = deployed("dead-channel")
    val original = deployed(Soundtracks.ORIGINAL)
    val m = mission.withTracks(catalog(short, clash, original))
    assertEquals(mission.soundtracks, m.soundtracks)
    assertEquals(mission, mission.withTracks(TrackCatalog("another-mission", listOf(deployed("x")))))
  }

  @Test
  fun aDeployedBonusTrackUnlocksLikeABundledOne() {
    val m = mission.withTracks(catalog(deployed("encore", unlock = 2)))
    assertEquals(listOf("dead-channel"), Soundtracks.unlockedByNextCompletion(m, 0).map { it.id })
    assertEquals(listOf("encore"), Soundtracks.unlockedByNextCompletion(m, 1).map { it.id })
  }

  @Test
  fun aRunPlaysTheDeployedStems() {
    val m = mission.withTracks(catalog(deployed("night-bus"))).withSoundtrack("night-bus")
    assertTrue(m.music.stems.all { TrackCatalog.blobOf(it) != null })
    assertEquals("%064x".format(0) + ".ogg", TrackCatalog.blobOf(m.music.stems.first()))
    assertNull(TrackCatalog.blobOf(mission.music.stems.first()))
    // The engine runs on it unchanged: it only knows stems by id.
    val d = Driver(GameEngine(m))
    assertTrue(d.send(GameEvent.Start(d.now)).isNotEmpty())
  }

  @Test
  fun renamingASignalRenamesItsStemInEverySoundtrack() {
    val route =
        RouteOverride(
            mission.id,
            labels = mapOf("stacks" to SignalLabel(name = "The Card Catalog", place = "the north steps"), "fog" to SignalLabel(place = " ")))
    val m = mission.withTracks(catalog(deployed("night-bus"))).withRoute(route)
    val stacks = m.signal("stacks")!!
    assertEquals("The Card Catalog", stacks.name)
    assertEquals("the north steps", stacks.place)
    assertEquals(mission.signal("stacks")!!.location, stacks.location)
    assertEquals(mission.signal("fog")!!.place, m.signal("fog")!!.place)
    val renamed = { mm: Mission -> mm.music.stems.first { it.id == "hats" }.name }
    assertEquals("The Card Catalog", renamed(m))
    assertEquals(listOf("The Card Catalog", "The Card Catalog"), m.soundtracks.map { t -> t.music.stems.first { it.id == "hats" }.name })
    assertEquals("The Card Catalog", renamed(m.withSoundtrack("night-bus")))
  }

  @Test
  fun readsTheCatalogTheServerWrites() {
    val sha = "ab".repeat(32)
    val stems =
        mission.signals.joinToString(",") {
          """{"id":"${it.stem}","name":"${it.name}","gainDb":0,"file":"server:$sha.ogg","sha256":"$sha","bytes":1024,"format":"ogg","durationS":21.8}"""
        }
    val json =
        """{"missionId":"${mission.id}","updatedAtMs":5,"soundtracks":[{"id":"night-bus","title":"Night Bus","description":"",
          "unlockAfterCompletions":0,"published":true,"updatedAtMs":5,
          "music":{"title":"Night Bus","bpm":92,"beatsPerBar":4,"loopBars":8,"stems":[$stems]}}]}"""
    val c = Mission.json.decodeFromString(TrackCatalog.serializer(), json.replace("\"updatedAtMs\":5,\"soundtracks\"", "\"updatedAtMs\":5,\"locked\":[{\"id\":\"members-mix\",\"title\":\"Members Mix\"}],\"soundtracks\""))
    assertEquals(listOf("Members Mix"), c.locked.map { it.title })
    assertEquals(1024L, c.soundtracks.single().music.stems.first().bytes)
    assertEquals(listOf("night-bus"), Soundtracks.choices(mission.withTracks(c), 0).drop(1).take(1).map { it.id })
  }
}
