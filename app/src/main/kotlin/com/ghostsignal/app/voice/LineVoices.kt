package com.ghostsignal.app.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.ghostsignal.app.audio.Pcm
import com.ghostsignal.app.audio.PcmDecoder
import com.ghostsignal.app.net.BackendClient
import com.ghostsignal.core.engine.Utterance
import com.ghostsignal.core.model.LineCatalog
import com.ghostsignal.core.model.Mission
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PrepareProgress(
    val done: Int,
    val total: Int,
    val cloud: Int,
    val local: Int,
    val failed: Int,
)

/**
 * Turns text lines into playable audio.
 *
 * Authored story lines prefer a rendered voice from the backend when one
 * is configured; dynamic SmartSpecs lines (distances, directions) always
 * use the phone's own TTS so they are instant. Everything is cached on
 * disk under a hash of its inputs, and [prepareAll] renders the whole
 * script before the event so a run never waits on synthesis or network.
 * The live backend renders voices on-device and answers 501, so in
 * practice lines are spoken by the phone — the cloud path stays for
 * backends that do synthesize.
 */
class LineVoices(context: Context, private val backend: BackendClient) {

  private val cacheDir = File(context.filesDir, "voices").apply { mkdirs() }
  private val localTts = AndroidTts(context)

  /** Renders (or reuses) the audio for one utterance. */
  suspend fun render(mission: Mission, utterance: Utterance): Pcm {
    val cloudFile = cacheFile(mission, utterance, sourceTag = "cloud")
    if (cloudFile.exists()) return PcmDecoder.decodeFile(cloudFile)

    if (prefersCloudVoice(utterance) && backend.configured) {
      try {
        val audioBytes = backend.tts(mission.id, utterance.speaker, utterance.text)
        cloudFile.writeBytes(audioBytes)
        return PcmDecoder.decodeFile(cloudFile)
      } catch (error: Exception) {
        cloudFile.delete()
        Log.w(TAG, "cloud voice failed for ${utterance.speaker}: ${error.message}")
      }
    }

    val localFile = cacheFile(mission, utterance, sourceTag = "local")
    if (!localFile.exists()) {
      val voiceSpec = mission.character(utterance.speaker)?.voice
      val synthesized = localTts.synthesize(
          text = utterance.text,
          pitch = (voiceSpec?.pitch ?: 1.0).toFloat(),
          rate = (voiceSpec?.rate ?: 1.0).toFloat(),
          outputFile = localFile,
      )
      if (!synthesized) {
        localFile.delete()
        error("no voice available for '${utterance.text.take(40)}'")
      }
    }
    return PcmDecoder.decodeFile(localFile)
  }

  /** Renders every authored line, reporting progress as it goes. */
  suspend fun prepareAll(
      mission: Mission,
      onProgress: (PrepareProgress) -> Unit,
  ): PrepareProgress {
    val allLines = LineCatalog.all(mission)
    var cloudCount = 0
    var localCount = 0
    var failedCount = 0

    allLines.forEachIndexed { index, line ->
      val utterance = Utterance(line.id, line.speaker, line.text)
      try {
        render(mission, utterance)
        if (cacheFile(mission, utterance, "cloud").exists()) cloudCount++ else localCount++
      } catch (error: Exception) {
        failedCount++
      }
      onProgress(PrepareProgress(index + 1, allLines.size, cloudCount, localCount, failedCount))
    }
    return PrepareProgress(allLines.size, allLines.size, cloudCount, localCount, failedCount)
  }

  fun clearCache() {
    cacheDir.listFiles()?.forEach { it.delete() }
  }

  /** Story lines may use a cloud voice; system readouts stay on-device. */
  private fun prefersCloudVoice(utterance: Utterance): Boolean =
      utterance.lineId?.startsWith("system.") != true

  private fun cacheFile(mission: Mission, utterance: Utterance, sourceTag: String): File {
    val character = mission.character(utterance.speaker)
    val keyMaterial = "${mission.id}|${utterance.speaker}|${character?.voice}|${utterance.text}|$sourceTag"
    val digest = MessageDigest.getInstance("SHA-1").digest(keyMaterial.toByteArray())
    val hexDigest = digest.joinToString("") { "%02x".format(it) }
    return File(cacheDir, "${sourceTag}_$hexDigest.wav")
  }

  private companion object {
    const val TAG = "GhostSignalVoices"
  }
}

/** Coroutine wrapper around Android TextToSpeech, rendering to WAV files. */
class AndroidTts(context: Context) {

  private val readySignal = CompletableDeferred<Boolean>()
  private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
  private val synthesisMutex = Mutex()
  private var languageConfigured = false

  private val ttsEngine = TextToSpeech(context.applicationContext) { status ->
    readySignal.complete(status == TextToSpeech.SUCCESS)
  }

  init {
    ttsEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String) = Unit

      override fun onDone(utteranceId: String) {
        pendingUtterances.remove(utteranceId)?.complete(true)
      }

      @Suppress("OVERRIDE_DEPRECATION")
      override fun onError(utteranceId: String) {
        pendingUtterances.remove(utteranceId)?.complete(false)
      }

      override fun onError(utteranceId: String, errorCode: Int) {
        pendingUtterances.remove(utteranceId)?.complete(false)
      }
    })
  }

  /** Synthesizes [text] into [outputFile]; false when no voice can speak. */
  suspend fun synthesize(text: String, pitch: Float, rate: Float, outputFile: File): Boolean {
    return synthesisMutex.withLock {
      val engineReady = withTimeoutOrNull(10_000) { readySignal.await() } == true
      if (!engineReady) return false

      if (!languageConfigured) {
        ttsEngine.setLanguage(Locale.US)
        languageConfigured = true
      }
      ttsEngine.setPitch(pitch)
      ttsEngine.setSpeechRate(rate)

      val utteranceId = UUID.randomUUID().toString()
      val completion = CompletableDeferred<Boolean>()
      pendingUtterances[utteranceId] = completion
      val queued = ttsEngine.synthesizeToFile(text, Bundle(), outputFile, utteranceId)
      if (queued != TextToSpeech.SUCCESS) {
        pendingUtterances.remove(utteranceId)
        return false
      }
      val finished = withTimeoutOrNull(20_000) { completion.await() } == true
      finished && outputFile.length() > 44 // Longer than a bare WAV header.
    }
  }
}
