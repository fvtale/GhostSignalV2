package com.ghostsignal.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * [x] rounded half-up to [decimals] places, like `"%.2f".format(x)` on the JVM, but the same on
 * every platform the engine runs on (a browser prints a Double differently from Java).
 */
fun fixed(x: Double, decimals: Int): String {
  if (x.isNaN()) return "NaN"
  if (x.isInfinite()) return if (x > 0) "Infinity" else "-Infinity"
  val scale = 10.0.pow(decimals)
  val n = floor(abs(x) * scale + 0.5).toLong()
  val unit = scale.toLong()
  val sign = if (x < 0 && n != 0L) "-" else ""
  if (decimals <= 0) return "$sign$n"
  return "$sign${n / unit}.${(n % unit).toString().padStart(decimals, '0')}"
}
