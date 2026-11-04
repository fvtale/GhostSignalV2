package com.ghostsignal.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.ghostsignal.app.audio.WavCodec
import com.ghostsignal.app.net.BackendClient
import com.ghostsignal.core.engine.ListenMode
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking

/**
 * Runner speech through Muse Voice on the backend: records a push-to-talk
 * clip (16 kHz mono, ending on a short silence after speech, on the
 * timeout, or on [stop]) and posts it to /v1/stt. Used when the backend is
 * configured; the on-device recognizers remain the fallback elsewhere.
 */
class ServerSpeechInput(
    private val context: Context,
    private val backend: BackendClient,
) : SpeechInput {

  override val name = "Muse Voice (server)"

  @Volatile
  private var isRecording = false

  private var activeCallbacks: ListenCallbacks? = null

  override fun isAvailable(): Boolean =
      backend.configured &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
          PackageManager.PERMISSION_GRANTED

  override fun start(
      mode: ListenMode,
      timeoutMs: Long,
      hints: List<String>,
      callbacks: ListenCallbacks,
  ) {
    stop()
    activeCallbacks = callbacks
    if (!isAvailable()) {
      endWindow(callbacks, "server speech unavailable")
      return
    }

    isRecording = true
    Thread({
      var transcript: String? = null
      try {
        val samples = recordClip(timeoutMs)
        if (samples != null && activeCallbacks === callbacks) {
          val wavFile = File(context.cacheDir, "stt.wav")
          WavCodec.write(wavFile, samples, channels = 1, sampleRate = SAMPLE_RATE)
          transcript = runBlocking { backend.transcribe(wavFile.readBytes(), hints) }
        }
      } catch (error: Exception) {
        Log.w(TAG, "server speech failed", error)
      }

      if (activeCallbacks !== callbacks) return@Thread // Stopped meanwhile.
      activeCallbacks = null
      if (!transcript.isNullOrBlank()) callbacks.onText(transcript, true, 0.9)
      callbacks.onEnded("final")
    }, "gs-stt").start()
  }

  override fun stop() {
    isRecording = false
    val callbacks = activeCallbacks ?: return
    activeCallbacks = null
    callbacks.onEnded("stopped")
  }

  private fun endWindow(callbacks: ListenCallbacks, reason: String) {
    if (activeCallbacks === callbacks) activeCallbacks = null
    callbacks.onEnded(reason)
  }

  /** Records until trailing silence, timeout, or [stop]; null if the mic never opened. */
  @SuppressLint("MissingPermission")
  private fun recordClip(timeoutMs: Long): ShortArray? {
    val minimumBuffer = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        maxOf(minimumBuffer, SAMPLE_RATE / 5 * 2),
    )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) {
      recorder.release()
      return null
    }

    val capacityFrames = (SAMPLE_RATE * timeoutMs / 1000).toInt().coerceAtLeast(SAMPLE_RATE)
    val captured = ShortArray(capacityFrames)
    val chunk = ShortArray(SAMPLE_RATE / 20) // 50 ms chunks.
    var capturedCount = 0
    var heardSpeech = false
    var trailingSilenceMs = 0L

    try {
      recorder.startRecording()
      while (isRecording && capturedCount < captured.size) {
        val readCount = recorder.read(
            chunk, 0, minOf(chunk.size, captured.size - capturedCount),
        )
        if (readCount <= 0) break
        System.arraycopy(chunk, 0, captured, capturedCount, readCount)
        capturedCount += readCount

        var energySum = 0.0
        for (index in 0 until readCount) {
          energySum += chunk[index].toDouble() * chunk[index]
        }
        val rms = sqrt(energySum / readCount)
        if (rms > SPEECH_RMS_THRESHOLD) {
          heardSpeech = true
          trailingSilenceMs = 0
        } else if (heardSpeech) {
          trailingSilenceMs += 50
          if (trailingSilenceMs >= SILENCE_END_MS) break
        }
      }
    } finally {
      runCatching { recorder.stop() }
      recorder.release()
    }

    // A quarter-second minimum keeps accidental taps from becoming uploads.
    return if (capturedCount > SAMPLE_RATE / 4) captured.copyOf(capturedCount) else null
  }

  private companion object {
    const val TAG = "GhostSignalStt"
    const val SAMPLE_RATE = 16_000
    const val SPEECH_RMS_THRESHOLD = 500.0
    const val SILENCE_END_MS = 1_200L
  }
}
