package com.ghostsignal.app.audio

import com.ghostsignal.core.engine.Sfx
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Procedural sound effects, so the game needs no SFX assets. Each effect is
 * a short list of segments — a tone sweep and/or filtered noise under an
 * exponential envelope — mixed sample by sample on the audio thread.
 */
class SynthVoice private constructor(
    private val segments: List<Segment>,
    val worldBearingDeg: Double?,
) {

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

  private val noiseSource = Noise((System.nanoTime() and 0x7fffffff).toInt() or 1)
  private val noiseFilter = OnePoleLp(4_000f)
  private var sampleIndex = 0
  private val phaseBySegment = DoubleArray(segments.size)
  private val totalFrames =
      (segments.maxOf { it.startS + it.lengthS } * Pcm.SAMPLE_RATE).toInt()

  val done: Boolean
    get() = sampleIndex >= totalFrames

  /** Next output sample (0 once the effect has played out). */
  fun next(): Float {
    if (done) return 0f
    val timeS = sampleIndex.toDouble() / Pcm.SAMPLE_RATE
    var mixed = 0.0
    var sharedNoise = Double.NaN

    segments.forEachIndexed { index, segment ->
      val localTime = timeS - segment.startS
      if (localTime < 0 || localTime >= segment.lengthS) return@forEachIndexed

      val progress = localTime / segment.lengthS
      val frequency = segment.fromHz + (segment.toHz - segment.fromHz) * progress
      phaseBySegment[index] += 2 * PI * frequency / Pcm.SAMPLE_RATE
      val attackFade = if (localTime < 0.004) localTime / 0.004 else 1.0
      val envelope = exp(-localTime * segment.decay) * attackFade

      if (segment.tone > 0) {
        mixed += sin(phaseBySegment[index]) * segment.tone * envelope
      }
      if (segment.noise > 0) {
        if (sharedNoise.isNaN()) {
          sharedNoise = noiseFilter.process(noiseSource.next()).toDouble()
        }
        mixed += sharedNoise * segment.noise * envelope
      }
    }

    sampleIndex++
    return (mixed * 0.5).toFloat()
  }

  companion object {

    private fun segment(
        start: Double,
        length: Double,
        from: Double,
        to: Double = from,
        tone: Double = 1.0,
        noise: Double = 0.0,
        decay: Double = 6.0,
    ): Segment = Segment(start, length, from, to, tone, noise, decay)

    fun forSfx(sfx: Sfx, bearingDeg: Double?): SynthVoice = when (sfx) {
      // Radio dial sweeping onto a station.
      Sfx.SIGNAL_ACQUIRED -> SynthVoice(
          listOf(
              segment(0.0, 0.45, 300.0, 2_400.0, tone = 0.25, noise = 0.6, decay = 2.0),
              segment(0.42, 0.12, 1_760.0, decay = 12.0),
          ),
          bearingDeg,
      )
      Sfx.LOCK_AVAILABLE -> SynthVoice(
          listOf(
              segment(0.0, 0.08, 880.0, decay = 8.0),
              segment(0.1, 0.12, 1_320.0, decay = 8.0),
          ),
          bearingDeg,
      )
      Sfx.LOCK -> SynthVoice(
          listOf(
              segment(0.0, 0.28, 400.0, 1_600.0, decay = 3.0),
              segment(0.0, 0.2, 0.0, tone = 0.0, noise = 0.5, decay = 14.0),
          ),
          null,
      )
      Sfx.DENIED -> SynthVoice(
          listOf(
              segment(0.0, 0.12, 190.0, decay = 4.0),
              segment(0.16, 0.14, 150.0, decay = 4.0),
          ),
          null,
      )
      Sfx.COLLECT -> SynthVoice(
          listOf(440.0, 523.25, 659.25, 880.0, 1_318.5)
              .mapIndexed { index, hz -> segment(index * 0.07, 0.35, hz, decay = 7.0) } +
              segment(0.0, 0.6, 0.0, tone = 0.0, noise = 0.25, decay = 5.0),
          null,
      )
      Sfx.PAUSE -> SynthVoice(
          listOf(segment(0.0, 0.25, 620.0, 300.0, decay = 5.0)),
          null,
      )
      Sfx.RESUME -> SynthVoice(
          listOf(segment(0.0, 0.25, 300.0, 620.0, decay = 5.0)),
          null,
      )
      Sfx.RECORD_START -> SynthVoice(
          listOf(segment(0.0, 0.35, 1_000.0, decay = 2.0)),
          null,
      )
      // Modem-like burst announcing a live transmission.
      Sfx.TRANSMISSION -> SynthVoice(
          listOf(1_200.0, 2_200.0, 1_600.0, 2_600.0, 1_200.0, 2_000.0)
              .mapIndexed { index, hz ->
                segment(index * 0.06, 0.06, hz, tone = 0.6, noise = 0.2, decay = 1.0)
              },
          null,
      )
      Sfx.FINALE -> SynthVoice(
          listOf(segment(0.0, 1.6, 200.0, 3_000.0, tone = 0.2, noise = 0.7, decay = 0.6)),
          null,
      )
    }

    fun forUi(sfx: UiSfx): SynthVoice = when (sfx) {
      // Walkie-talkie squelch, open and close.
      UiSfx.MIC_OPEN -> SynthVoice(
          listOf(
              segment(0.0, 0.07, 0.0, tone = 0.0, noise = 0.7, decay = 20.0),
              segment(0.06, 0.05, 1_800.0, decay = 10.0),
          ),
          null,
      )
      UiSfx.MIC_CLOSE -> SynthVoice(
          listOf(
              segment(0.0, 0.05, 1_200.0, decay = 10.0),
              segment(0.04, 0.1, 0.0, tone = 0.0, noise = 0.5, decay = 25.0),
          ),
          null,
      )
      UiSfx.CLICK -> SynthVoice(
          listOf(
              segment(0.0, 0.05, 1_150.0, decay = 70.0),
              segment(0.0, 0.01, 0.0, tone = 0.0, noise = 0.6, decay = 200.0),
          ),
          null,
      )
    }
  }
}
