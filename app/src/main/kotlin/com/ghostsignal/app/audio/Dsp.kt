package com.ghostsignal.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Small real-time DSP toolkit for the binaural mixer. Everything here is
// allocation-free in the hot path: filters keep their state in fields, and
// per-sample work is a handful of float operations.

/** One-pole low-pass. Cheap and smooth; colours distance and head shadow. */
class OnePoleLp(cutoffHz: Float = 20_000f) {

  private var coefficient: Float = 0f
  private var state: Float = 0f

  init {
    setCutoff(cutoffHz)
  }

  fun setCutoff(hz: Float) {
    val clamped = hz.coerceIn(20f, 22_000f)
    coefficient = exp(-2.0 * PI * clamped / Pcm.SAMPLE_RATE).toFloat()
  }

  fun process(input: Float): Float {
    state = input * (1f - coefficient) + state * coefficient
    return state
  }
}

/** One-pole high-pass, as the complement of a [OnePoleLp]. */
class OnePoleHp(cutoffHz: Float) {

  private val lowPass = OnePoleLp(cutoffHz)

  fun setCutoff(hz: Float) = lowPass.setCutoff(hz)

  fun process(input: Float): Float = input - lowPass.process(input)
}

/** Walkie-talkie band: two poles each side of roughly 300 Hz – 3.4 kHz. */
class RadioBand {

  private val highPassA = OnePoleHp(320f)
  private val highPassB = OnePoleHp(320f)
  private val lowPassA = OnePoleLp(3_400f)
  private val lowPassB = OnePoleLp(3_400f)

  fun process(input: Float): Float =
      lowPassB.process(lowPassA.process(highPassB.process(highPassA.process(input))))
}

/** Gentle saturation that keeps the master bus from clipping hard. */
fun softClip(input: Float): Float {
  val clamped = input.coerceIn(-3f, 3f)
  return clamped * (27f + clamped * clamped) / (27f + 9f * clamped * clamped)
}

/** Linear ramp toward a target across one block, so changes never click. */
class Ramp(initial: Float = 0f) {

  var value: Float = initial
    private set

  private var stepSize: Float = 0f
  private var targetValue: Float = initial

  fun begin(newTarget: Float, frames: Int) {
    targetValue = newTarget
    stepSize = (targetValue - value) / frames
  }

  fun next(): Float {
    value += stepSize
    return value
  }

  fun end() {
    value = targetValue
  }
}

/**
 * Lightweight binaural panner on a spherical-head model: Woodworth
 * interaural time difference, interaural level difference, a head-shadow
 * low-pass on the far ear, and a duller, quieter colour behind the head.
 * Elevation is approximated spectrally (brighter above, duller below)
 * rather than with a measured HRTF. Cheap enough for eight sources.
 */
class Binaural {

  private val delayLineSize = 128
  private val delayLine = FloatArray(delayLineSize)
  private var writeIndex = 0

  private val shadowFilterLeft = OnePoleLp()
  private val shadowFilterRight = OnePoleLp()
  private val elevationFilterLeft = OnePoleLp()
  private val elevationFilterRight = OnePoleLp()
  private val rearFilterLeft = OnePoleLp()
  private val rearFilterRight = OnePoleLp()

  private val delayRampLeft = Ramp()
  private val delayRampRight = Ramp()
  private val gainRampLeft = Ramp(0.7f)
  private val gainRampRight = Ramp(0.7f)

  private var elevationGain: Float = 1f
  private var rearGain: Float = 1f

  /** Prepares the next block for a source [azimuthDeg] right of ahead. */
  fun prepare(azimuthDeg: Double, frames: Int, elevationDeg: Double = 0.0) {
    val azimuthRad = azimuthDeg * PI / 180.0
    val lateralRad = asin(sin(azimuthRad)) // -PI/2..PI/2, positive = right
    val absLateral = abs(lateralRad)

    val itdSamples =
        (HEAD_RADIUS_M / SPEED_OF_SOUND * (absLateral + sin(absLateral)) * Pcm.SAMPLE_RATE)
            .toFloat()
    val sourceOnRight = lateralRad >= 0
    delayRampLeft.begin(if (sourceOnRight) itdSamples else 0f, frames)
    delayRampRight.begin(if (sourceOnRight) 0f else itdSamples, frames)

    val lateralSine = sin(absLateral).toFloat()
    val nearEarGain = 0.72f + 0.2f * lateralSine
    val farEarGain = 0.72f - 0.42f * lateralSine
    gainRampLeft.begin(if (sourceOnRight) farEarGain else nearEarGain, frames)
    gainRampRight.begin(if (sourceOnRight) nearEarGain else farEarGain, frames)

    val shadowCutoff = max(1_600f, 18_000f * (1f - 0.88f * lateralSine))
    shadowFilterLeft.setCutoff(if (sourceOnRight) shadowCutoff else 18_000f)
    shadowFilterRight.setCutoff(if (sourceOnRight) 18_000f else shadowCutoff)

    // Behind the head: duller and a little quieter.
    val behindAmount = max(0f, -cos(azimuthRad).toFloat())
    val rearCutoff = 18_000f - 12_500f * behindAmount
    rearFilterLeft.setCutoff(rearCutoff)
    rearFilterRight.setCutoff(rearCutoff)
    rearGain = 1f - 0.22f * behindAmount

    // Elevation approximation: brighter above, duller and quieter below.
    val clampedElevation = elevationDeg.coerceIn(-60.0, 60.0).toFloat()
    val elevationCutoff = (9_000f + clampedElevation * 150f).coerceIn(2_500f, 18_000f)
    elevationFilterLeft.setCutoff(elevationCutoff)
    elevationFilterRight.setCutoff(elevationCutoff)
    elevationGain = 1f - 0.15f * (abs(clampedElevation) / 60f)
  }

  /** Adds the processed sample into [out] at stereo frame [frameIndex]. */
  fun process(input: Float, out: FloatArray, frameIndex: Int) {
    delayLine[writeIndex] = input
    val leftSample = readDelayed(delayRampLeft.next())
    val rightSample = readDelayed(delayRampRight.next())
    writeIndex = (writeIndex + 1) % delayLineSize

    val leftChain = elevationFilterLeft.process(
        rearFilterLeft.process(shadowFilterLeft.process(leftSample)),
    ) * gainRampLeft.next() * rearGain * elevationGain
    val rightChain = elevationFilterRight.process(
        rearFilterRight.process(shadowFilterRight.process(rightSample)),
    ) * gainRampRight.next() * rearGain * elevationGain

    out[2 * frameIndex] += leftChain
    out[2 * frameIndex + 1] += rightChain
  }

  fun finishBlock() {
    delayRampLeft.end()
    delayRampRight.end()
    gainRampLeft.end()
    gainRampRight.end()
  }

  private fun readDelayed(delaySamples: Float): Float {
    val clampedDelay = min(delaySamples, (delayLineSize - 2).toFloat())
    val readPosition = writeIndex - clampedDelay
    val lowerIndex = floor(readPosition).toInt()
    val fraction = readPosition - lowerIndex
    val sampleA = delayLine[(lowerIndex % delayLineSize + delayLineSize) % delayLineSize]
    val sampleB = delayLine[((lowerIndex + 1) % delayLineSize + delayLineSize) % delayLineSize]
    return sampleA + (sampleB - sampleA) * fraction
  }

  companion object {
    const val HEAD_RADIUS_M = 0.0875
    const val SPEED_OF_SOUND = 343.0
  }
}

/** Deterministic xorshift noise: same hiss on every run, no allocation. */
class Noise(seed: Int = 0x2545F491) {

  private var state: Int = seed

  fun next(): Float {
    state = state xor (state shl 13)
    state = state xor (state ushr 17)
    state = state xor (state shl 5)
    return (state and 0xffffff) / 8388608f - 1f
  }
}
