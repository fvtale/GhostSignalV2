package com.ghostsignal.core.rhythm

import com.ghostsignal.core.fixed
import kotlin.math.abs

/**
 * Judges a tapped rhythm against the pattern the game played.
 *
 * The tap clock and the music clock never meet, so comparison happens in
 * beat space: both sides become lists of gaps between hits, the tapped gaps
 * are stretched onto the target's total length, and each gap is checked.
 * Overall tempo may drift (within a generous window); the shape may not.
 */
object RhythmMatcher {

  data class Result(
      val passed: Boolean,
      val meanErrorBeats: Double,
      val tempoRatio: Double,
      val reason: String,
  )

  private const val MIN_TEMPO_RATIO = 0.55
  private const val MAX_TEMPO_RATIO = 1.8
  private const val WORST_GAP_FACTOR = 2.2

  fun match(
      onsetsBeats: List<Double>,
      tapTimesMs: List<Long>,
      beatMs: Double,
      toleranceBeats: Double,
  ): Result {
    require(onsetsBeats.size >= 2) { "A rhythm needs at least two onsets" }

    if (tapTimesMs.size < onsetsBeats.size) {
      return Result(
          passed = false,
          meanErrorBeats = Double.NaN,
          tempoRatio = Double.NaN,
          reason = "too few taps (${tapTimesMs.size}/${onsetsBeats.size})",
      )
    }

    // Stray early taps (a flinch before the pattern starts) are discarded;
    // the attempt is the final run of taps.
    val attempt = tapTimesMs.sorted().takeLast(onsetsBeats.size)

    val targetGaps = onsetsBeats.zipWithNext { a, b -> b - a }
    val tappedGaps = attempt.zipWithNext { a, b -> (b - a) / beatMs }

    val targetTotal = targetGaps.sum()
    val tappedTotal = tappedGaps.sum()
    if (tappedTotal <= 0.0) {
      return Result(false, Double.NaN, Double.NaN, "taps had no duration")
    }

    val tempoRatio = targetTotal / tappedTotal
    if (tempoRatio < MIN_TEMPO_RATIO || tempoRatio > MAX_TEMPO_RATIO) {
      return Result(
          passed = false,
          meanErrorBeats = Double.NaN,
          tempoRatio = tempoRatio,
          reason = "tempo too far off (x${fixed(tempoRatio, 2)})",
      )
    }

    val gapErrors = targetGaps.mapIndexed { i, gap -> abs(tappedGaps[i] * tempoRatio - gap) }
    val meanError = gapErrors.average()
    val worstError = gapErrors.maxOrNull() ?: 0.0
    val passed = meanError <= toleranceBeats && worstError <= toleranceBeats * WORST_GAP_FACTOR

    return Result(
        passed = passed,
        meanErrorBeats = meanError,
        tempoRatio = tempoRatio,
        reason = if (passed) "ok" else "off by ${fixed(meanError, 2)} beats",
    )
  }
}
