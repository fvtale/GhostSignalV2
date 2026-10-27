package com.ghostsignal.core.engine

import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.model.Signal
import kotlin.math.max
import kotlin.math.min

enum class Zone {
  FAR,
  NEAR,
  LOCK,
}

data class SignalReading(
    val distanceM: Double,
    /** Distance after crediting part of the GPS error radius to the player. */
    val effectiveDistanceM: Double,
    val bearingDeg: Double,
    /** 0 = barely audible, 1 = locked on. Drives beacon clarity and ping rate. */
    val strength: Double,
    val zone: Zone,
)

/**
 * Turns a GPS fix into how strongly the active signal comes through.
 *
 * Midtown GPS routinely drifts 10-30 m between towers, so half the reported accuracy radius
 * (capped) is credited toward the player. That keeps a runner standing at the right spot from being
 * locked out by a bad fix, at the cost of occasionally unlocking a few metres early.
 */
object SignalField {
  private const val ACCURACY_CREDIT = 0.5
  private const val ACCURACY_CAP_M = 30.0
  private const val AUDIBLE_FLOOR = 0.04

  fun evaluate(signal: Signal, fix: GeoFix): SignalReading {
    val distance = Geo.distanceM(fix.position, signal.location)
    val credit = min(fix.accuracyM, ACCURACY_CAP_M) * ACCURACY_CREDIT
    val effective = max(0.0, distance - credit)
    val audible = audibleRadiusM(signal)
    val strength =
        if (effective <= signal.lockRadiusM) 1.0
        else {
          val t = ((effective - signal.lockRadiusM) / (audible - signal.lockRadiusM)).coerceIn(0.0, 1.0)
          val s = 1.0 - t * t * (3 - 2 * t) // smoothstep falloff
          max(AUDIBLE_FLOOR, s)
        }
    val zone =
        when {
          effective <= signal.lockRadiusM -> Zone.LOCK
          effective <= signal.radiusM -> Zone.NEAR
          else -> Zone.FAR
        }
    return SignalReading(distance, effective, Geo.bearingDeg(fix.position, signal.location), strength, zone)
  }

  /** Beyond this the beacon sits at its quiet floor. */
  fun audibleRadiusM(signal: Signal): Double = max(signal.radiusM * 5.0, 260.0)

  /** Leaving the lock zone needs a margin so a jittery fix doesn't flap the lock on and off. */
  fun stillLockable(signal: Signal, reading: SignalReading): Boolean =
      reading.effectiveDistanceM <= signal.lockRadiusM * 1.4

  /** Sonar ping period: slow when far, fast when close. */
  fun pingIntervalMs(strength: Double): Long = (3800 - 3200 * strength.coerceIn(0.0, 1.0)).toLong()
}
