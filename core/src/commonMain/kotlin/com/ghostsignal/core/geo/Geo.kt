package com.ghostsignal.core.geo

import com.ghostsignal.core.fixed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/** A WGS84 coordinate pair, in decimal degrees. */
@Serializable
data class LatLon(val lat: Double, val lon: Double)

/**
 * A single position sample. [accuracyM] is the horizontal error radius the
 * platform reports (roughly a 68% confidence circle on Android); [courseDeg]
 * is the direction of travel when the platform knows it.
 */
data class GeoFix(
    val position: LatLon,
    val accuracyM: Double,
    val timeMs: Long,
    val speedMps: Double? = null,
    val courseDeg: Double? = null,
    val simulated: Boolean = false,
)

/**
 * Spherical-earth navigation maths. A sphere is plenty at city-block scale:
 * the error against an ellipsoid model stays well under the GPS noise floor.
 */
object Geo {

  const val EARTH_RADIUS_M = 6_371_008.8

  private fun rad(deg: Double): Double = deg * PI / 180.0
  private fun deg(rad: Double): Double = rad * 180.0 / PI

  /** Great-circle distance between two points, in metres (haversine form). */
  fun distanceM(a: LatLon, b: LatLon): Double {
    val halfLat = rad(b.lat - a.lat) / 2
    val halfLon = rad(b.lon - a.lon) / 2
    val sinLat = sin(halfLat)
    val sinLon = sin(halfLon)
    val h = (sinLat * sinLat + cos(rad(a.lat)) * cos(rad(b.lat)) * sinLon * sinLon)
        .coerceIn(0.0, 1.0)
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
  }

  /** Initial bearing from [from] to [to]: degrees clockwise from north, [0, 360). */
  fun bearingDeg(from: LatLon, to: LatLon): Double {
    val phiFrom = rad(from.lat)
    val phiTo = rad(to.lat)
    val deltaLon = rad(to.lon - from.lon)
    val east = sin(deltaLon) * cos(phiTo)
    val north = cos(phiFrom) * sin(phiTo) - sin(phiFrom) * cos(phiTo) * cos(deltaLon)
    return normalizeDeg(deg(atan2(east, north)))
  }

  /** The point reached by travelling [distanceM] metres from [from] along [bearingDeg]. */
  fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
    val angular = distanceM / EARTH_RADIUS_M
    val theta = rad(bearingDeg)
    val phi1 = rad(from.lat)
    val lambda1 = rad(from.lon)

    val phi2 = asin(sin(phi1) * cos(angular) + cos(phi1) * sin(angular) * cos(theta))
    val lambda2 = lambda1 + atan2(
        sin(theta) * sin(angular) * cos(phi1),
        cos(angular) - sin(phi1) * sin(phi2),
    )
    return LatLon(lat = deg(phi2), lon = deg(lambda2).let { ((it + 540) % 360) - 180 })
  }

  /** Folds any angle into [0, 360). */
  fun normalizeDeg(deg: Double): Double {
    val turns = floor(deg / 360.0)
    return deg - turns * 360.0
  }

  /**
   * Shortest signed turn from [from] to [to], in (-180, 180].
   * Positive values mean the target lies to the right.
   */
  fun deltaDeg(from: Double, to: Double): Double {
    val raw = normalizeDeg(to) - normalizeDeg(from)
    return when {
      raw > 180.0 -> raw - 360.0
      raw <= -180.0 -> raw + 360.0
      else -> raw
    }
  }

  fun absAngleDeg(from: Double, to: Double): Double = abs(deltaDeg(from, to))

  /** Clock-face hour (1-12) for a bearing relative to straight ahead. */
  fun clockPosition(relativeDeg: Double): Int {
    val hour = (normalizeDeg(relativeDeg) / 30.0).roundToInt() % 12
    return if (hour == 0) 12 else hour
  }

  private val COMPASS_POINTS = listOf(
      "north", "north-east", "east", "south-east",
      "south", "south-west", "west", "north-west",
  )

  /** Eight-point compass name for an absolute bearing. */
  fun cardinal(bearingDeg: Double): String {
    val index = ((normalizeDeg(bearingDeg) + 22.5) / 45.0).toInt() % 8
    return COMPASS_POINTS[index]
  }

  /** Spoken-friendly distance: coarse rounding that grows with range. */
  fun spokenDistance(distanceM: Double): String = when {
    distanceM < 12 -> "right on top of it"
    distanceM < 100 -> "about ${(distanceM / 10).roundToInt() * 10} metres"
    distanceM < 1000 -> "about ${(distanceM / 25).roundToInt() * 25} metres"
    else -> "about ${fixed(distanceM / 1000, 1)} kilometres"
  }
}
