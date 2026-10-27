package com.ghostsignal.core.geo

import com.ghostsignal.core.fixed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/** One position reading from the phone (or the simulator). */
data class GeoFix(
    val position: LatLon,
    /** Horizontal accuracy radius in metres (68% confidence), as Android reports it. */
    val accuracyM: Double,
    val timeMs: Long,
    val speedMps: Double? = null,
    /** Direction of travel, degrees clockwise from true north. */
    val courseDeg: Double? = null,
    val simulated: Boolean = false,
)

object Geo {
  const val EARTH_RADIUS_M = 6_371_008.8

  fun distanceM(a: LatLon, b: LatLon): Double {
    val lat1 = a.lat.toRad()
    val lat2 = b.lat.toRad()
    val dLat = (b.lat - a.lat).toRad()
    val dLon = (b.lon - a.lon).toRad()
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
  }

  /** Initial great-circle bearing from [from] to [to], degrees clockwise from north in [0, 360). */
  fun bearingDeg(from: LatLon, to: LatLon): Double {
    val lat1 = from.lat.toRad()
    val lat2 = to.lat.toRad()
    val dLon = (to.lon - from.lon).toRad()
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return normalizeDeg(atan2(y, x).toDeg())
  }

  /** The point [distanceM] metres from [from] along [bearingDeg]. */
  fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
    val d = distanceM / EARTH_RADIUS_M
    val b = bearingDeg.toRad()
    val lat1 = from.lat.toRad()
    val lon1 = from.lon.toRad()
    val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
    val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
    return LatLon(lat2.toDeg(), ((lon2.toDeg() + 540) % 360) - 180)
  }

  fun normalizeDeg(deg: Double): Double {
    val r = deg % 360.0
    return if (r < 0) r + 360.0 else r
  }

  /** Signed shortest rotation from [from] to [to], in (-180, 180]. Positive means turn right. */
  fun deltaDeg(from: Double, to: Double): Double {
    var d = normalizeDeg(to) - normalizeDeg(from)
    if (d > 180) d -= 360
    if (d <= -180) d += 360
    return d
  }

  /** Clock-face position (1..12) of something [relativeDeg] to the right of straight ahead. */
  fun clockPosition(relativeDeg: Double): Int {
    val hour = (normalizeDeg(relativeDeg) / 30.0).roundToInt() % 12
    return if (hour == 0) 12 else hour
  }

  private val CARDINALS =
      listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

  fun cardinal(bearingDeg: Double): String =
      CARDINALS[((normalizeDeg(bearingDeg) + 22.5) / 45.0).toInt() % 8]

  /** A distance phrased for speech: "about 140 metres", "right on top of it". */
  fun spokenDistance(distanceM: Double): String =
      when {
        distanceM < 12 -> "right on top of it"
        distanceM < 100 -> "about ${(distanceM / 10).roundToInt() * 10} metres"
        distanceM < 1000 -> "about ${(distanceM / 25).roundToInt() * 25} metres"
        else -> "about ${fixed(distanceM / 1000, 1)} kilometres"
      }

  fun absAngleDeg(from: Double, to: Double): Double = abs(deltaDeg(from, to))

  private fun Double.toRad() = this * PI / 180.0

  private fun Double.toDeg() = this * 180.0 / PI
}
