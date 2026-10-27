package com.ghostsignal.core.engine

import com.ghostsignal.core.model.Challenge
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * What each music stem should sound like right now. Bearings are world bearings (degrees from
 * north); the audio engine subtracts the live head heading itself so head turns don't wait for the
 * next game tick.
 */
data class StemState(
    val stemId: String,
    val gain: Double,
    /** Null = play inside the head (stereo, centred). */
    val worldBearingDeg: Double?,
    val distanceM: Double,
    /** 0 = buried in radio mush, 1 = clean. */
    val clarity: Double,
)

data class AudioScene(
    val stems: Map<String, StemState>,
    val staticLevel: Double,
    val staticBearingDeg: Double?,
    /** Sonar ping period, or null for no ping. */
    val pingIntervalMs: Long?,
    val pingBearingDeg: Double?,
    val paused: Boolean,
    /** How much the antagonist's theme holds the frequency: 0 = absent, 1 = in full. */
    val antagonistLevel: Double = 0.0,
) {
  companion object {
    val SILENT = AudioScene(emptyMap(), 0.0, null, null, null, paused = false)
  }
}

/**
 * The core audio idea: the signal you are hunting *is* the next part of the track. It plays from
 * the signal's real-world direction, crushed and filtered when far away and clearer as you close
 * in. When you collect it, it moves into your head and joins the mix on the next bar.
 */
object AudioSceneComposer {
  fun compose(engine: GameEngine): AudioScene {
    val mission = engine.mission
    val stems = mutableMapOf<String, StemState>()
    val collectedStems = engine.collected.mapNotNull { mission.signal(it)?.stem }.toSet()
    val finished = engine.phase == Phase.FINALE || engine.phase == Phase.COMPLETE

    for (spec in mission.music.stems) {
      if (spec.id in collectedStems || finished) {
        stems[spec.id] = StemState(spec.id, 1.0, null, 0.0, 1.0)
      }
    }

    var staticLevel = 0.0
    var staticBearing: Double? = null
    var ping: Long? = null
    var pingBearing: Double? = null

    val signal = engine.currentSignal
    val reading = engine.reading
    if (signal != null && signal.stem !in collectedStems && !finished) {
      when (engine.phase) {
        Phase.HUNTING ->
            if (reading != null) {
              val clarity = reading.strength.pow(0.8)
              stems[signal.stem] =
                  StemState(signal.stem, 0.35 + 0.65 * reading.strength, reading.bearingDeg, reading.distanceM, clarity)
              staticLevel = (1 - clarity) * 0.5
              staticBearing = reading.bearingDeg
              ping = SignalField.pingIntervalMs(reading.strength)
              pingBearing = reading.bearingDeg
            } else {
              // No GPS yet: a faint, centred, muffled hint of the part.
              stems[signal.stem] = StemState(signal.stem, 0.25, null, 0.0, 0.1)
              staticLevel = 0.3
            }
        Phase.ENCOUNTER -> {
          var clarity = 1.0
          val c = signal.challenge
          if (c is Challenge.Tune) {
            val delta = engine.tuneDelta(c)
            clarity = if (delta == null) 0.5 else (1 - min(abs(delta), 120.0) / 120.0).pow(2)
            staticLevel = (1 - clarity) * 0.6
            staticBearing = null
          }
          stems[signal.stem] =
              StemState(signal.stem, 0.85, reading?.bearingDeg, min(reading?.distanceM ?: 6.0, 8.0), clarity)
        }
        Phase.BRIEFING -> staticLevel = 0.12
        else -> Unit
      }
    } else if (engine.phase == Phase.BRIEFING) {
      staticLevel = 0.12
    }

    // The antagonist takes the frequency during the prologue and around any signal it guards,
    // growing as the runner closes in and pushing the pirate music down underneath it.
    val antagonist = mission.antagonist
    var level = 0.0
    if (antagonist != null && !finished) {
      val guarded = signal != null && signal.speaker == antagonist.speaker && signal.stem !in collectedStems
      level =
          when {
            engine.speakingKind == GameEngine.KIND_PROLOGUE -> 1.0
            guarded && engine.phase == Phase.ENCOUNTER -> 1.0
            guarded && engine.phase == Phase.HUNTING -> 0.35 + 0.65 * (reading?.strength ?: 0.0)
            else -> 0.0
          }
    }
    val duck = 1.0 - (antagonist?.duck ?: 0.0) * level
    val ducked = if (level > 0) stems.mapValues { (_, st) -> st.copy(gain = st.gain * duck) } else stems

    return AudioScene(ducked, staticLevel, staticBearing, ping, pingBearing, engine.paused, level)
  }
}
