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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(val file: File, val signalId: String, val createdAtMs: Long, val durationMs: Long)

/**
 * Records runner messages from the glasses mic (or the phone mic as a fallback) into WAV files in
 * app-private storage. Nothing is uploaded; see the privacy notes in the README.
 */
class VoiceRecorder(private val context: Context, private val routing: AudioRouting) {
  private val dir = File(context.filesDir, "recordings").apply { mkdirs() }
  @Volatile private var recording = false
  private var thread: Thread? = null

  fun list(): List<Recording> =
      dir.listFiles { f -> f.extension == "wav" }
          .orEmpty()
          .sortedByDescending { it.lastModified() }
          .map { f ->
            val signal = f.nameWithoutExtension.substringAfter('_', "")
            Recording(f, signal, f.lastModified(), (f.length() - 44) * 1000 / (RATE * 2))
          }

  fun delete(recording: Recording) {
    recording.file.delete()
  }

  /** Records up to [maxSeconds]; [onFinished] gets the file, or null if nothing could be recorded. */
  @SuppressLint("MissingPermission")
  fun start(signalId: String, maxSeconds: Int, onFinished: (File?) -> Unit) {
    if (recording) stop()
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      onFinished(null)
      return
    }
    recording = true
    thread =
        Thread({
              val usingGlasses = routing.useGlassesMic()
              val file = File(dir, "${STAMP.format(Date())}_$signalId.wav")
              var result: File? = null
              try {
                val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec =
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, RATE / 5 * 2))
                if (usingGlasses) routing.glassesMicDevice()?.let { rec.setPreferredDevice(it) }
                val samples = ShortArray(RATE * maxSeconds)
                var n = 0
                rec.startRecording()
                val chunk = ShortArray(RATE / 20)
                while (recording && n < samples.size) {
                  val read = rec.read(chunk, 0, minOf(chunk.size, samples.size - n))
                  if (read <= 0) break
                  System.arraycopy(chunk, 0, samples, n, read)
                  n += read
                }
                rec.stop()
                rec.release()
                if (n > RATE / 2) {
                  WavCodec.write(file, samples.copyOf(n), 1, RATE)
                  result = file
                }
              } catch (e: Exception) {
                Log.e(TAG, "recording failed", e)
              } finally {
                if (usingGlasses) routing.releaseMic()
                recording = false
              }
              onFinished(result)
            },
            "gs-recorder")
            .apply { start() }
  }

  fun stop() {
    recording = false
  }

  companion object {
    private const val TAG = "GhostSignalRecorder"
    const val RATE = 16_000
    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
  }
}
