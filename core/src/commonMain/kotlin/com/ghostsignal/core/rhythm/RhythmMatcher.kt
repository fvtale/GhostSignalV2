package com.ghostsignal.core.rhythm

import com.ghostsignal.core.fixed
import kotlin.math.abs

/**
 * Compares a tapped rhythm with a target pattern.
 *
 * Taps arrive with device timestamps but no shared clock with the music, so matching is
 * tempo-relative: the tapped inter-onset intervals are rescaled to the target's total length and
 * compared interval by interval, in beats. Players may tap a little faster or slower than the
 * track as long as the shape is right.
 */
object RhythmMatcher {
  data class Result(val passed: Boolean, val meanErrorBeats: Double, val tempoRatio: Double, val reason: String)

  fun match(
      onsetsBeats: List<Double>,
      tapTimesMs: List<Long>,
      beatMs: Double,
      toleranceBeats: Double,
  ): Result {
    require(onsetsBeats.size >= 2) { "A rhythm needs at least two onsets" }
    if (tapTimesMs.size < onsetsBeats.size) {
      return Result(false, Double.NaN, Double.NaN, "too few taps (${tapTimesMs.size}/${onsetsBeats.size})")
    }
    // Extra taps usually mean a false start; keep the most recent ones.
    val taps = tapTimesMs.sorted().takeLast(onsetsBeats.size)
    val targetIoi = onsetsBeats.zipWithNext { a, b -> b - a }
    val tapIoiBeats = taps.zipWithNext { a, b -> (b - a) / beatMs }
    val targetSpan = targetIoi.sum()
    val tapSpan = tapIoiBeats.sum()
    if (tapSpan <= 0.0) return Result(false, Double.NaN, Double.NaN, "taps had no duration")
    val tempoRatio = targetSpan / tapSpan
    if (tempoRatio !in 0.55..1.8) {
      return Result(false, Double.NaN, tempoRatio, "tempo too far off (x${fixed(tempoRatio, 2)})")
    }
    val errors = targetIoi.indices.map { abs(tapIoiBeats[it] * tempoRatio - targetIoi[it]) }
    val mean = errors.average()
    val worst = errors.max()
    val passed = mean <= toleranceBeats && worst <= toleranceBeats * 2.2
    return Result(passed, mean, tempoRatio, if (passed) "ok" else "off by ${fixed(mean, 2)} beats")
  }
}
