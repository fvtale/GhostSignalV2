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

data class PrepareProgress(val done: Int, val total: Int, val cloud: Int, val local: Int, val failed: Int)

/**
 * Turns lines into audio. Authored lines and character replies use the character's cloud voice
 * from the backend when one is configured; dynamic SmartSpecs lines (distances, directions) use the
 * phone's own TTS so they're instant. Everything is cached on disk, and [prepareAll] renders the
 * whole script ahead of the event so the game never waits on the network for a story line.
 */
class LineVoices(context: Context, private val backend: BackendClient) {
  private val dir = File(context.filesDir, "voices").apply { mkdirs() }
  private val tts = AndroidTts(context)

  suspend fun render(mission: Mission, utterance: Utterance): Pcm {
    val character = mission.character(utterance.speaker)
    val cloudFile = file(mission, utterance, "cloud")
    if (cloudFile.exists()) return PcmDecoder.decodeFile(cloudFile)
    if (prefersCloud(utterance) && backend.configured) {
      try {
        val bytes = backend.tts(mission.id, utterance.speaker, utterance.text)
        cloudFile.writeBytes(bytes)
        return PcmDecoder.decodeFile(cloudFile)
      } catch (e: Exception) {
        cloudFile.delete()
        Log.w(TAG, "cloud voice failed for ${utterance.speaker}: ${e.message}")
      }
    }
    val localFile = file(mission, utterance, "local")
    if (!localFile.exists()) {
      val voice = character?.voice
      val ok = tts.synthesize(utterance.text, (voice?.pitch ?: 1.0).toFloat(), (voice?.rate ?: 1.0).toFloat(), localFile)
      if (!ok) {
        localFile.delete()
        error("no voice available for '${utterance.text.take(40)}'")
      }
    }
    return PcmDecoder.decodeFile(localFile)
  }

  /** Renders every authored line so the run never waits on TTS. */
  suspend fun prepareAll(mission: Mission, onProgress: (PrepareProgress) -> Unit): PrepareProgress {
    val lines = LineCatalog.all(mission)
    var cloud = 0
    var local = 0
    var failed = 0
    lines.forEachIndexed { i, line ->
      val u = Utterance(line.id, line.speaker, line.text)
      try {
        render(mission, u)
        if (file(mission, u, "cloud").exists()) cloud++ else local++
      } catch (e: Exception) {
        failed++
      }
      onProgress(PrepareProgress(i + 1, lines.size, cloud, local, failed))
    }
    return PrepareProgress(lines.size, lines.size, cloud, local, failed)
  }

  fun clearCache() {
    dir.listFiles()?.forEach { it.delete() }
  }

  private fun prefersCloud(u: Utterance) = u.lineId?.startsWith("system.") != true

  private fun file(mission: Mission, u: Utterance, source: String): File {
    val c = mission.character(u.speaker)
    val key = "${mission.id}|${u.speaker}|${c?.voice}|${u.text}|$source"
    val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
    return File(dir, "${source}_" + digest.joinToString("") { "%02x".format(it) } + ".wav")
  }

  companion object {
    private const val TAG = "GhostSignalVoices"
  }
}

/** Coroutine wrapper around Android's TextToSpeech, rendering to WAV files. */
class AndroidTts(context: Context) {
  private val ready = CompletableDeferred<Boolean>()
  private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
  private val mutex = Mutex()
  private var languageSet = false
  private val tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

  init {
    tts.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String) = Unit

          override fun onDone(utteranceId: String) {
            pending.remove(utteranceId)?.complete(true)
          }

          @Suppress("OVERRIDE_DEPRECATION")
          override fun onError(utteranceId: String) {
            pending.remove(utteranceId)?.complete(false)
          }

          override fun onError(utteranceId: String, errorCode: Int) {
            pending.remove(utteranceId)?.complete(false)
          }
        })
  }

  suspend fun synthesize(text: String, pitch: Float, rate: Float, out: File): Boolean {
    return mutex.withLock {
        if (withTimeoutOrNull(10_000) { ready.await() } != true) return false
        if (!languageSet) {
          tts.setLanguage(Locale.US)
          languageSet = true
        }
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        if (tts.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) {
          pending.remove(id)
          return false
        }
        withTimeoutOrNull(20_000) { done.await() } == true && out.length() > 44
      }
  }
}
