package com.ghostsignal.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One-pole low-pass. Cheap, smooth, good enough for distance and head-shadow colouring. */
class OnePoleLp(cutoffHz: Float = 20_000f) {
  private var a = 0f
  private var z = 0f

  init {
    setCutoff(cutoffHz)
  }

  fun setCutoff(hz: Float) {
    val c = hz.coerceIn(20f, 22_000f)
    a = exp(-2.0 * PI * c / Pcm.SAMPLE_RATE).toFloat()
  }

  fun process(x: Float): Float {
    z = x * (1 - a) + z * a
    return z
  }
}

/** One-pole high-pass (the complement of [OnePoleLp]). */
class OnePoleHp(cutoffHz: Float) {
  private val lp = OnePoleLp(cutoffHz)

  fun setCutoff(hz: Float) = lp.setCutoff(hz)

  fun process(x: Float) = x - lp.process(x)
}

/** Walkie-talkie band (roughly 300 Hz - 3.4 kHz). */
class RadioBand {
  private val hp1 = OnePoleHp(320f)
  private val hp2 = OnePoleHp(320f)
  private val lp1 = OnePoleLp(3_400f)
  private val lp2 = OnePoleLp(3_400f)

  fun process(x: Float) = lp2.process(lp1.process(hp2.process(hp1.process(x))))
}

/** Gentle saturation that keeps the master bus from clipping. */
fun softClip(x: Float): Float {
  val c = x.coerceIn(-3f, 3f)
  return c * (27f + c * c) / (27f + 9f * c * c)
}

/** Linear ramp from the last value to a new target over one block, so changes never click. */
class Ramp(initial: Float = 0f) {
  var value = initial
    private set

  private var step = 0f
  private var target = initial

  fun begin(newTarget: Float, frames: Int) {
    target = newTarget
    step = (target - value) / frames
  }

  fun next(): Float {
    value += step
    return value
  }

  fun end() {
    value = target
  }
}

/**
 * Lightweight binaural panner using a spherical-head model.
 *
 * Interaural time difference (Woodworth), interaural level difference, a head-shadow low-pass on
 * the far ear and a dull, quieter "behind you" colour. It is not a measured HRTF, but over
 * open-ear speakers it reads clearly as left/right/front/back and is cheap enough for eight
 * sources on a budget phone. FMOD + Resonance Audio is the higher-fidelity option.
 */
class Binaural {
  private val size = 128
  private val delay = FloatArray(size)
  private var write = 0
  private val shadowL = OnePoleLp()
  private val shadowR = OnePoleLp()
  private val rearL = OnePoleLp()
  private val rearR = OnePoleLp()

  private val delayL = Ramp()
  private val delayR = Ramp()
  private val gainL = Ramp(0.7f)
  private val gainR = Ramp(0.7f)

  /** Sets up the next block for a source [azimuthDeg] to the right of straight ahead. */
  fun prepare(azimuthDeg: Double, frames: Int) {
    val az = azimuthDeg * PI / 180.0
    val lateral = asin(sin(az)) // -pi/2 .. pi/2, positive = right
    val absLat = abs(lateral)
    val itdSamples = (HEAD_RADIUS_M / SPEED_OF_SOUND * (absLat + sin(absLat)) * Pcm.SAMPLE_RATE).toFloat()
    val right = lateral >= 0
    delayL.begin(if (right) itdSamples else 0f, frames)
    delayR.begin(if (right) 0f else itdSamples, frames)

    val s = sin(absLat).toFloat()
    val near = 0.72f + 0.2f * s
    val far = 0.72f - 0.42f * s
    gainL.begin(if (right) far else near, frames)
    gainR.begin(if (right) near else far, frames)
    val shadowCut = max(1_600f, 18_000f * (1f - 0.88f * s))
    shadowL.setCutoff(if (right) shadowCut else 18_000f)
    shadowR.setCutoff(if (right) 18_000f else shadowCut)

    // Behind the head: duller and a little quieter.
    val behind = max(0f, -cos(az).toFloat())
    val rearCut = 18_000f - 12_500f * behind
    rearL.setCutoff(rearCut)
    rearR.setCutoff(rearCut)
    rearGain = 1f - 0.22f * behind
  }

  private var rearGain = 1f

  /** Adds the processed sample to [out] at stereo frame [i]. */
  fun process(x: Float, out: FloatArray, i: Int) {
    delay[write] = x
    val l = read(delayL.next())
    val r = read(delayR.next())
    write = (write + 1) % size
    out[2 * i] += rearL.process(shadowL.process(l)) * gainL.next() * rearGain
    out[2 * i + 1] += rearR.process(shadowR.process(r)) * gainR.next() * rearGain
  }

  fun finishBlock() {
    delayL.end()
    delayR.end()
    gainL.end()
    gainR.end()
  }

  private fun read(delaySamples: Float): Float {
    val d = min(delaySamples, (size - 2).toFloat())
    val pos = write - d
    val i0 = kotlin.math.floor(pos).toInt()
    val t = pos - i0
    val a = delay[(i0 % size + size) % size]
    val b = delay[((i0 + 1) % size + size) % size]
    return a + (b - a) * t
  }

  companion object {
    const val HEAD_RADIUS_M = 0.0875
    const val SPEED_OF_SOUND = 343.0
  }
}

/** Deterministic, allocation-free noise source. */
class Noise(seed: Int = 0x2545F491) {
  private var state = seed

  fun next(): Float {
    state = state xor (state shl 13)
    state = state xor (state ushr 17)
    state = state xor (state shl 5)
    return (state and 0xffffff) / 8388608f - 1f
  }
}
