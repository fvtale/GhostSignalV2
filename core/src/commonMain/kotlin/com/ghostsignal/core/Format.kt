package com.ghostsignal.core

import kotlin.math.abs

// Deterministic decimal rendering for values that cross the Kotlin/JS boundary.
// The browser and the JVM disagree on how a Double prints, so anything the engine
// puts into speech text or a saved flag goes through here instead of toString().

/**
 * Renders [value] with exactly [places] digits after the point, rounding half
 * away from zero. Negative zero renders as plain zero.
 */
fun fixed(value: Double, places: Int): String {
  if (value.isNaN()) return "NaN"
  if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"

  var factor = 1L
  repeat(places.coerceAtLeast(0)) { factor *= 10L }

  val scaled = kotlin.math.floor(abs(value) * factor + 0.5).toLong()
  val negative = value < 0 && scaled != 0L

  val whole = scaled / factor
  val fraction = (scaled % factor).toString().padStart(places, '0')

  return buildString {
    if (negative) append('-')
    append(whole)
    if (places > 0) {
      append('.')
      append(fraction)
    }
  }
}
