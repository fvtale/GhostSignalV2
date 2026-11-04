package com.ghostsignal.core.engine

import com.ghostsignal.core.model.Challenge
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Desired playback state for one music stem at this instant.
 * [worldBearingDeg] is an absolute bearing (degrees from north); the audio
 * engine subtracts the live head heading itself so turning feels instant
 * instead of waiting for the next engine tick. Null bearing = in-head.
 */
data class StemState(
    val stemId: String,
    val gain: Double,
    val worldBearingDeg: Double?,
    val distanceM: Double,
    /** 0 = buried in static, 1 = studio clean. */
    val clarity: Double,
    /** Authored source elevation in degrees; 0 = horizontal plane. */
    val elevationDeg: Double = 0.0,
)

/** A complete audio picture for one engine tick. */
data class AudioScene(
    val stems: Map<String, StemState>,
    val staticLevel: Double,
    val staticBearingDeg: Double?,
    /** Sonar ping period in ms, or null for silence. */
    val pingIntervalMs: Long?,
    val pingBearingDeg: Double?,
    val paused: Boolean,
    /** Antagonist theme presence: 0 = absent, 1 = holds the frequency. */
    val antagonistLevel: Double = 0.0,
    val pingElevationDeg: Double = 0.0,
    val staticElevationDeg: Double = 0.0,
) {
  companion object {
    val SILENT = AudioScene(emptyMap(), 0.0, null, null, null, paused = false)
  }
}

/**
 * Derives the audio scene from engine state. The governing idea of the game:
 * the signal being hunted IS the next layer of the track. It sounds from the
 * signal's real direction, crushed when far and clearing as the runner
 * closes in; once collected it folds into the head mix on the next bar.
 */
object AudioSceneComposer {

  fun compose(engine: GameEngine): AudioScene {
    val mission = engine.mission
    val collectedStems = engine.collected
        .mapNotNull { id -> mission.signal(id)?.stem }
        .toSet()
    val runFinished = engine.phase == Phase.FINALE || engine.phase == Phase.COMPLETE

    val stems = mutableMapOf<String, StemState>()

    // Collected layers (and everything, once the run is complete) play
    // clean and centred: they are the runner's own track now.
    for (spec in mission.music.stems) {
      if (spec.id in collectedStems || runFinished) {
        stems[spec.id] = StemState(spec.id, gain = 1.0, worldBearingDeg = null, distanceM = 0.0, clarity = 1.0)
      }
    }

    var staticLevel = 0.0
    var staticBearing: Double? = null
    var pingInterval: Long? = null
    var pingBearing: Double? = null

    val signal = engine.currentSignal
    val reading = engine.reading
    val targetAudible = signal != null && signal.stem !in collectedStems && !runFinished

    if (targetAudible) {
      when (engine.phase) {
        Phase.HUNTING -> {
          if (reading != null) {
            val clarity = reading.strength.pow(0.8)
            stems[signal.stem] = StemState(
                stemId = signal.stem,
                gain = 0.35 + 0.65 * reading.strength,
                worldBearingDeg = reading.bearingDeg,
                distanceM = reading.distanceM,
                clarity = clarity,
                elevationDeg = signal.elevationDeg,
            )
            staticLevel = (1.0 - clarity) * 0.5
            staticBearing = reading.bearingDeg
            pingInterval = SignalField.pingIntervalMs(reading.strength)
            pingBearing = reading.bearingDeg
          } else {
            // No fix yet: a faint centred murmur so the layer exists.
            stems[signal.stem] = StemState(signal.stem, 0.25, null, 0.0, clarity = 0.1)
            staticLevel = 0.3
          }
        }
        Phase.ENCOUNTER -> {
          var clarity = 1.0
          val challenge = signal.challenge
          if (challenge is Challenge.Tune) {
            val offBy = engine.tuneDelta(challenge)
            clarity = if (offBy == null) {
              0.5
            } else {
              (1.0 - min(abs(offBy), 120.0) / 120.0).pow(2)
            }
            staticLevel = (1.0 - clarity) * 0.6
            staticBearing = null
          }
          stems[signal.stem] = StemState(
              stemId = signal.stem,
              gain = 0.85,
              worldBearingDeg = reading?.bearingDeg,
              distanceM = min(reading?.distanceM ?: 6.0, 8.0),
              clarity = clarity,
              elevationDeg = signal.elevationDeg,
          )
        }
        Phase.BRIEFING -> staticLevel = 0.12
        else -> Unit
      }
    } else if (engine.phase == Phase.BRIEFING) {
      staticLevel = 0.12
    }

    // Antagonist: full hold during its prologue and its encounters, ramping
    // with signal strength while hunting one of its guarded signals.
    val antagonist = mission.antagonist
    var antagonistLevel = 0.0
    if (antagonist != null && !runFinished) {
      val guarding = signal != null &&
          signal.speaker == antagonist.speaker &&
          signal.stem !in collectedStems
      antagonistLevel = when {
        engine.speakingKind == GameEngine.KIND_PROLOGUE -> 1.0
        guarding && engine.phase == Phase.ENCOUNTER -> 1.0
        guarding && engine.phase == Phase.HUNTING -> 0.35 + 0.65 * (reading?.strength ?: 0.0)
        else -> 0.0
      }
    }

    val duckFactor = 1.0 - (antagonist?.duck ?: 0.0) * antagonistLevel
    val audibleStems = if (antagonistLevel > 0.0) {
      stems.mapValues { entry -> entry.value.copy(gain = entry.value.gain * duckFactor) }
    } else {
      stems
    }

    return AudioScene(
        stems = audibleStems,
        staticLevel = staticLevel,
        staticBearingDeg = staticBearing,
        pingIntervalMs = pingInterval,
        pingBearingDeg = pingBearing,
        paused = engine.paused,
        antagonistLevel = antagonistLevel,
        pingElevationDeg = signal?.elevationDeg ?: 0.0,
        staticElevationDeg = if (staticBearing != null) signal?.elevationDeg ?: 0.0 else 0.0,
    )
  }
}
