package com.ghostsignal.app.audio

import com.ghostsignal.core.engine.Sfx
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Procedural sound effects, so the game needs no SFX assets. Each effect is a short list of
 * segments: a tone sweep and/or filtered noise with an exponential envelope.
 */
class SynthVoice private constructor(private val segments: List<Segment>, val worldBearingDeg: Double?) {
  data class Segment(
      val startS: Double,
      val lengthS: Double,
      val fromHz: Double,
      val toHz: Double,
      val tone: Double,
      val noise: Double,
      val decay: Double,
  )

  val binaural = Binaural()
  private val noiseGen = Noise((System.nanoTime() and 0x7fffffff).toInt() or 1)
  private val noiseLp = OnePoleLp(4_000f)
  private var t = 0
  private val phases = DoubleArray(segments.size)
  private val endFrame = (segments.maxOf { it.startS + it.lengthS } * Pcm.SAMPLE_RATE).toInt()

  val done: Boolean
    get() = t >= endFrame

  fun next(): Float {
    if (done) return 0f
    val time = t.toDouble() / Pcm.SAMPLE_RATE
    var out = 0.0
    var n = Double.NaN
    for ((i, s) in segments.withIndex()) {
      val local = time - s.startS
      if (local < 0 || local >= s.lengthS) continue
      val progress = local / s.lengthS
      val hz = s.fromHz + (s.toHz - s.fromHz) * progress
      phases[i] += 2 * PI * hz / Pcm.SAMPLE_RATE
      val env = exp(-local * s.decay) * (if (local < 0.004) local / 0.004 else 1.0)
      if (s.tone > 0) out += sin(phases[i]) * s.tone * env
      if (s.noise > 0) {
        if (n.isNaN()) n = noiseLp.process(noiseGen.next()).toDouble()
        out += n * s.noise * env
      }
    }
    t++
    return (out * 0.5).toFloat()
  }

  companion object {
    private fun seg(start: Double, len: Double, from: Double, to: Double = from, tone: Double = 1.0, noise: Double = 0.0, decay: Double = 6.0) =
        Segment(start, len, from, to, tone, noise, decay)

    fun forSfx(sfx: Sfx, bearing: Double?): SynthVoice =
        when (sfx) {
          // Radio dial sweeping onto a station.
          Sfx.SIGNAL_ACQUIRED ->
              SynthVoice(
                  listOf(seg(0.0, 0.45, 300.0, 2_400.0, tone = 0.25, noise = 0.6, decay = 2.0), seg(0.42, 0.12, 1_760.0, decay = 12.0)),
                  bearing)
          Sfx.LOCK_AVAILABLE -> SynthVoice(listOf(seg(0.0, 0.08, 880.0, decay = 8.0), seg(0.1, 0.12, 1_320.0, decay = 8.0)), bearing)
          Sfx.LOCK ->
              SynthVoice(listOf(seg(0.0, 0.28, 400.0, 1_600.0, decay = 3.0), seg(0.0, 0.2, 0.0, tone = 0.0, noise = 0.5, decay = 14.0)), null)
          Sfx.DENIED -> SynthVoice(listOf(seg(0.0, 0.12, 190.0, decay = 4.0), seg(0.16, 0.14, 150.0, decay = 4.0)), null)
          Sfx.COLLECT ->
              SynthVoice(
                  listOf(440.0, 523.25, 659.25, 880.0, 1_318.5).mapIndexed { i, hz -> seg(i * 0.07, 0.35, hz, decay = 7.0) } +
                      seg(0.0, 0.6, 0.0, tone = 0.0, noise = 0.25, decay = 5.0),
                  null)
          Sfx.PAUSE -> SynthVoice(listOf(seg(0.0, 0.25, 620.0, 300.0, decay = 5.0)), null)
          Sfx.RESUME -> SynthVoice(listOf(seg(0.0, 0.25, 300.0, 620.0, decay = 5.0)), null)
          Sfx.RECORD_START -> SynthVoice(listOf(seg(0.0, 0.35, 1_000.0, decay = 2.0)), null)
          // Modem-ish burst for a live transmission.
          Sfx.TRANSMISSION ->
              SynthVoice(
                  listOf(1_200.0, 2_200.0, 1_600.0, 2_600.0, 1_200.0, 2_000.0).mapIndexed { i, hz ->
                    seg(i * 0.06, 0.06, hz, tone = 0.6, noise = 0.2, decay = 1.0)
                  },
                  null)
          Sfx.FINALE -> SynthVoice(listOf(seg(0.0, 1.6, 200.0, 3_000.0, tone = 0.2, noise = 0.7, decay = 0.6)), null)
        }

    fun forUi(sfx: UiSfx): SynthVoice =
        when (sfx) {
          // Walkie-talkie squelch on and off.
          UiSfx.MIC_OPEN ->
              SynthVoice(listOf(seg(0.0, 0.07, 0.0, tone = 0.0, noise = 0.7, decay = 20.0), seg(0.06, 0.05, 1_800.0, decay = 10.0)), null)
          UiSfx.MIC_CLOSE ->
              SynthVoice(listOf(seg(0.0, 0.05, 1_200.0, decay = 10.0), seg(0.04, 0.1, 0.0, tone = 0.0, noise = 0.5, decay = 25.0)), null)
          UiSfx.CLICK ->
              SynthVoice(listOf(seg(0.0, 0.05, 1_150.0, decay = 70.0), seg(0.0, 0.01, 0.0, tone = 0.0, noise = 0.6, decay = 200.0)), null)
        }
  }
}
