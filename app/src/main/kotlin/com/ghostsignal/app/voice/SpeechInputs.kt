package com.ghostsignal.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.ghostsignal.app.glasses.GlassesManager
import com.ghostsignal.core.engine.ListenMode
import com.ghostsignal.core.voice.PhraseMatcher
import com.meta.wearable.dat.speech.Speech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Callbacks for one listening window. [onEnded] fires exactly once. */
interface ListenCallbacks {
  fun onText(text: String, isFinal: Boolean, confidence: Double)
  fun onEnded(reason: String)
}

/** One way of hearing the runner: glasses, phone recognizer, or server. */
interface SpeechInput {
  val name: String

  fun isAvailable(): Boolean

  fun start(mode: ListenMode, timeoutMs: Long, hints: List<String>, callbacks: ListenCallbacks)

  fun stop()
}

/**
 * On-glasses recognition through the DAT Speech capability. The mic stays
 * on the glasses' own channel, so the music keeps its quality while the
 * runner talks.
 */
class DatSpeechInput(
    private val glasses: GlassesManager,
    private val scope: CoroutineScope,
) : SpeechInput {

  override val name = "Glasses speech (DAT)"

  private var collectJob: Job? = null
  private var activeSpeech: Speech? = null
  private var activeCallbacks: ListenCallbacks? = null

  override fun isAvailable(): Boolean = glasses.speech != null

  @Synchronized
  override fun start(
      mode: ListenMode,
      timeoutMs: Long,
      hints: List<String>,
      callbacks: ListenCallbacks,
  ) {
    closeWindow("restarted")
    val speech = glasses.speech
    if (speech == null) {
      callbacks.onEnded("glasses speech unavailable")
      return
    }
    activeCallbacks = callbacks
    activeSpeech = speech

    // Results already in the flows belong to an earlier window; ignore them.
    val staleTranscription = speech.transcriptions.value
    val staleError = speech.errors.value

    collectJob = scope.launch {
      launch {
        speech.transcriptions.filterNotNull().collect { result ->
          if (result === staleTranscription || result.text.isBlank()) return@collect
          callbacks.onText(result.text, result.isFinal, result.confidence.toDouble())
          if (result.isFinal) finish("final")
        }
      }
      launch {
        speech.errors.filterNotNull().collect { error ->
          if (error !== staleError) finish("speech error: ${error.description}")
        }
      }
      delay(timeoutMs)
      finish("timeout")
    }
    speech.start().onFailure { error, _ -> finish("start failed: ${error.description}") }
  }

  @Synchronized
  private fun finish(reason: String) {
    closeWindow(reason)
  }

  override fun stop() = finish("stopped")

  private fun closeWindow(reason: String) {
    val callbacks = activeCallbacks ?: return
    activeCallbacks = null
    runCatching { activeSpeech?.stop() }
    activeSpeech = null
    collectJob?.cancel()
    collectJob = null
    callbacks.onEnded(reason)
  }
}

/**
 * Android's SpeechRecognizer (network recognition on most phones),
 * optionally fed by the glasses mic over Bluetooth SCO. The fallback when
 * DAT Speech is not enabled for the app.
 */
class AndroidSpeechInput(
    private val context: Context,
    private val routing: AudioRouting,
    private val useGlassesMic: Boolean,
) : SpeechInput {

  override val name: String =
      if (useGlassesMic) "Phone recognizer, glasses mic" else "Phone recognizer, phone mic"

  private val mainHandler = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var activeCallbacks: ListenCallbacks? = null
  private var activeHints: List<String> = emptyList()
  private val timeoutRunnable = Runnable { closeWindow("timeout") }

  override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

  override fun start(
      mode: ListenMode,
      timeoutMs: Long,
      hints: List<String>,
      callbacks: ListenCallbacks,
  ) {
    mainHandler.post {
      closeWindow("restarted")
      activeCallbacks = callbacks
      activeHints = hints
      if (useGlassesMic && !routing.useGlassesMic()) {
        Log.w(TAG, "glasses mic unavailable, using phone mic")
      }
      val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
      recognizer = newRecognizer
      newRecognizer.setRecognitionListener(recognitionListener)
      newRecognizer.startListening(recognizerIntent(hints))
      mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }
  }

  override fun stop() {
    mainHandler.post { closeWindow("stopped") }
  }

  private fun recognizerIntent(hints: List<String>): Intent =
      Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= 33 && hints.isNotEmpty()) {
          putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints))
        }
      }

  private fun closeWindow(reason: String) {
    mainHandler.removeCallbacks(timeoutRunnable)
    recognizer?.let { existing ->
      runCatching { existing.cancel() }
      existing.destroy()
    }
    recognizer = null
    if (useGlassesMic) routing.releaseMic()
    val callbacks = activeCallbacks ?: return
    activeCallbacks = null
    callbacks.onEnded(reason)
  }

  /** Picks the best alternative: hint-matched when hints exist, else first. */
  private fun bestAlternative(results: Bundle?): Pair<String, Double>? {
    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
      ?: return null
    if (texts.isEmpty()) return null
    val confidenceScores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

    if (activeHints.isEmpty()) {
      return texts[0] to (confidenceScores?.getOrNull(0)?.toDouble() ?: -1.0)
    }
    val bestIndex = texts.indices.maxBy { index ->
      activeHints.maxOf { hint -> PhraseMatcher.score(texts[index], hint) }
    }
    return texts[bestIndex] to (confidenceScores?.getOrNull(bestIndex)?.toDouble() ?: -1.0)
  }

  private val recognitionListener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) = closeWindow("recognizer error $error")

    override fun onResults(results: Bundle?) {
      bestAlternative(results)?.let { (text, confidence) ->
        activeCallbacks?.onText(text, true, confidence)
      }
      closeWindow("final")
    }

    override fun onPartialResults(partialResults: Bundle?) {
      val firstText = partialResults
          ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          ?.firstOrNull()
      if (!firstText.isNullOrBlank()) {
        activeCallbacks?.onText(firstText, false, -1.0)
      }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
  }

  private companion object {
    const val TAG = "GhostSignalSpeech"
  }
}
