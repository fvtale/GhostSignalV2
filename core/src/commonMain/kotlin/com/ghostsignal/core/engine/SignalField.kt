package com.ghostsignal.core.engine

import com.ghostsignal.core.geo.Geo
import com.ghostsignal.core.geo.GeoFix
import com.ghostsignal.core.model.Signal
import kotlin.math.max
import kotlin.math.min

/** How the active signal presents at the runner's position. */
enum class Zone { FAR, NEAR, LOCK }

data class SignalReading(
    val distanceM: Double,
    val effectiveDistanceM: Double,
    val bearingDeg: Double,
    val strength: Double,
    val zone: Zone,
)

/**
 * Converts a GPS fix into signal strength and zone.
 *
 * Urban fixes wander by tens of metres, so the runner is credited with half
 * the reported accuracy radius (capped at 15 m of credit). Standing on the
 * spot with a mediocre fix still locks; the trade-off is an occasional lock
 * a few metres early, which playtesting showed nobody notices.
 */
object SignalField {

  private const val CREDIT_FRACTION = 0.5
  private const val CREDIT_CAP_M = 30.0
  private const val FLOOR_STRENGTH = 0.04

  fun evaluate(signal: Signal, fix: GeoFix): SignalReading {
    val rawDistance = Geo.distanceM(fix.position, signal.location)
    val credit = min(fix.accuracyM, CREDIT_CAP_M) * CREDIT_FRACTION
    val effective = max(0.0, rawDistance - credit)

    val strength = strengthAt(signal, effective)
    val zone = when {
      effective <= signal.lockRadiusM -> Zone.LOCK
      effective <= signal.radiusM -> Zone.NEAR
      else -> Zone.FAR
    }
    return SignalReading(
        distanceM = rawDistance,
        effectiveDistanceM = effective,
        bearingDeg = Geo.bearingDeg(fix.position, signal.location),
        strength = strength,
        zone = zone,
    )
  }

  // Full strength inside the lock ring, then a smoothstep fade out to the
  // audible edge, never quite reaching silence so the beacon stays findable.
  private fun strengthAt(signal: Signal, effectiveM: Double): Double {
    if (effectiveM <= signal.lockRadiusM) return 1.0
    val span = audibleRadiusM(signal) - signal.lockRadiusM
    val t = ((effectiveM - signal.lockRadiusM) / span).coerceIn(0.0, 1.0)
    val eased = 1.0 - t * t * (3.0 - 2.0 * t)
    return max(FLOOR_STRENGTH, eased)
  }

  /** Range at which the beacon fades to its floor level. */
  fun audibleRadiusM(signal: Signal): Double = max(signal.radiusM * 5.0, 260.0)

  /** Lock hysteresis: once lockable, stay lockable until 40% past the ring. */
  fun stillLockable(signal: Signal, reading: SignalReading): Boolean =
      reading.effectiveDistanceM <= signal.lockRadiusM * 1.4

  /** Beacon ping period in ms: stately when far, urgent when close. */
  fun pingIntervalMs(strength: Double): Long =
      (3800.0 - 3200.0 * strength.coerceIn(0.0, 1.0)).toLong()
}
