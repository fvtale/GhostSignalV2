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
import kotlinx.coroutines.runBlocking

/**
 * Runner speech through Muse Voice on the backend: records a push-to-talk clip (16 kHz mono,
 * ending on a short silence after speech, on the timeout, or on [stop]) and posts it to
 * /v1/stt. Used when the backend is configured; the on-device recognizers remain the fallback
 * everywhere else.
 */
class ServerSpeechInput(
    private val context: Context,
    private val backend: BackendClient,
) : SpeechInput {
  override val name = "Muse Voice (server)"

  @Volatile private var recording = false
  private var callbacks: ListenCallbacks? = null

  override fun isAvailable() =
      backend.configured &&
          context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  override fun start(mode: ListenMode, timeoutMs: Long, hints: List<String>, callbacks: ListenCallbacks) {
    stop()
    this.callbacks = callbacks
    if (!isAvailable()) {
      end(callbacks, "server speech unavailable")
      return
    }
    recording = true
    Thread(
        {
          var text: String? = null
          try {
            val samples = record(timeoutMs)
            if (samples != null && this.callbacks === callbacks) {
              val file = File(context.cacheDir, "stt.wav")
              WavCodec.write(file, samples, 1, RATE)
              text = runBlocking { backend.transcribe(file.readBytes(), hints) }
            }
          } catch (e: Exception) {
            Log.w(TAG, "server speech failed", e)
          }
          if (this.callbacks !== callbacks) return@Thread // stopped or restarted meanwhile
          this.callbacks = null
          if (!text.isNullOrBlank()) callbacks.onText(text, true, 0.9)
          callbacks.onEnded("final")
        },
        "gs-stt",
    ).start()
  }

  override fun stop() {
    recording = false
    val cb = callbacks ?: return
    callbacks = null
    cb.onEnded("stopped")
  }

  private fun end(callbacks: ListenCallbacks, reason: String) {
    if (this.callbacks === callbacks) this.callbacks = null
    callbacks.onEnded(reason)
  }

  /** Records until trailing silence, the timeout, or [stop]; null if the mic never opened. */
  @SuppressLint("MissingPermission")
  private fun record(timeoutMs: Long): ShortArray? {
    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec =
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, RATE / 5 * 2),
        )
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      rec.release()
      return null
    }
    val samples = ShortArray((RATE * timeoutMs / 1000).toInt().coerceAtLeast(RATE))
    var n = 0
    var heard = false
    var silentMs = 0L
    val chunk = ShortArray(RATE / 20) // 50 ms
    try {
      rec.startRecording()
      while (recording && n < samples.size) {
        val read = rec.read(chunk, 0, minOf(chunk.size, samples.size - n))
        if (read <= 0) break
        System.arraycopy(chunk, 0, samples, n, read)
        n += read
        var sum = 0.0
        for (i in 0 until read) sum += chunk[i].toDouble() * chunk[i]
        val rms = kotlin.math.sqrt(sum / read)
        if (rms > SPEECH_RMS) {
          heard = true
          silentMs = 0
        } else if (heard) {
          silentMs += 50
          if (silentMs >= SILENCE_MS) break
        }
      }
    } finally {
      runCatching { rec.stop() }
      rec.release()
    }
    return if (n > RATE / 4) samples.copyOf(n) else null
  }

  companion object {
    private const val TAG = "GhostSignalStt"
    private const val RATE = 16_000
    private const val SPEECH_RMS = 500.0
    private const val SILENCE_MS = 1_200L
  }
}
