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

data class Recording(
    val file: File,
    val signalId: String,
    val createdAtMs: Long,
    val durationMs: Long,
)

/**
 * Records the runner's Voice-challenge messages into app-private WAV
 * files. Nothing is uploaded; recordings are played back from the
 * Messages screen on this phone only.
 */
class VoiceRecorder(
    private val context: Context,
    private val routing: AudioRouting,
) {

  private val recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }

  @Volatile
  private var isRecording = false

  private var recordingThread: Thread? = null

  fun list(): List<Recording> {
    val waveFiles = recordingsDir.listFiles { file -> file.extension == "wav" }
      ?: emptyArray()
    return waveFiles
        .sortedByDescending { it.lastModified() }
        .map { file -> file.toRecording() }
  }

  private fun File.toRecording(): Recording {
    val signalId = nameWithoutExtension.substringAfter('_', "")
    val durationMs = (length() - WAV_HEADER_BYTES) * 1000 / (SAMPLE_RATE * 2)
    return Recording(this, signalId, lastModified(), durationMs)
  }

  fun delete(recording: Recording) {
    recording.file.delete()
  }

  /**
   * Records up to [maxSeconds] from the glasses mic when available.
   * [onFinished] receives the saved file, or null when nothing usable
   * was recorded (no permission, mic failure, or near-silence).
   */
  @SuppressLint("MissingPermission")
  fun start(signalId: String, maxSeconds: Int, onFinished: (File?) -> Unit) {
    if (isRecording) stop()
    val hasMicPermission = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
    if (!hasMicPermission) {
      onFinished(null)
      return
    }

    isRecording = true
    recordingThread = Thread({
      val usingGlassesMic = routing.useGlassesMic()
      val outputFile = File(recordingsDir, "${FILE_STAMP.format(Date())}_$signalId.wav")
      var savedFile: File? = null
      try {
        savedFile = captureToFile(outputFile, maxSeconds, usingGlassesMic)
      } catch (error: Exception) {
        Log.e(TAG, "recording failed", error)
      } finally {
        if (usingGlassesMic) routing.releaseMic()
        isRecording = false
      }
      onFinished(savedFile)
    }, "gs-recorder").apply { start() }
  }

  @SuppressLint("MissingPermission")
  private fun captureToFile(outputFile: File, maxSeconds: Int, preferGlassesMic: Boolean): File? {
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
    if (preferGlassesMic) {
      routing.glassesMicDevice()?.let { device -> recorder.setPreferredDevice(device) }
    }

    val captured = ShortArray(SAMPLE_RATE * maxSeconds)
    val chunk = ShortArray(SAMPLE_RATE / 20)
    var capturedCount = 0
    try {
      recorder.startRecording()
      while (isRecording && capturedCount < captured.size) {
        val readCount = recorder.read(chunk, 0, minOf(chunk.size, captured.size - capturedCount))
        if (readCount <= 0) break
        System.arraycopy(chunk, 0, captured, capturedCount, readCount)
        capturedCount += readCount
      }
    } finally {
      runCatching { recorder.stop() }
      recorder.release()
    }

    // Half a second minimum: shorter is a tap, not a message.
    if (capturedCount <= SAMPLE_RATE / 2) return null
    WavCodec.write(outputFile, captured.copyOf(capturedCount), channels = 1, sampleRate = SAMPLE_RATE)
    return outputFile
  }

  fun stop() {
    isRecording = false
  }

  companion object {
    private const val TAG = "GhostSignalRecorder"
    private const val WAV_HEADER_BYTES = 44L
    const val SAMPLE_RATE = 16_000
    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
  }
}
