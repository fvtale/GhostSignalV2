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

interface SpeechInput {
  val name: String

  fun isAvailable(): Boolean

  fun start(mode: ListenMode, timeoutMs: Long, hints: List<String>, callbacks: ListenCallbacks)

  fun stop()
}

/**
 * On-glasses recognition through the DAT Speech capability (experimental in DAT 1.0). The mic
 * stays on the glasses' own channel, so the music keeps its quality while the runner talks.
 */
class DatSpeechInput(private val glasses: GlassesManager, private val scope: CoroutineScope) : SpeechInput {
  override val name = "Glasses speech (DAT)"
  private var job: Job? = null
  private var active: Speech? = null
  private var callbacks: ListenCallbacks? = null

  override fun isAvailable() = glasses.speech != null

  @Synchronized
  override fun start(mode: ListenMode, timeoutMs: Long, hints: List<String>, callbacks: ListenCallbacks) {
    stopLocked("restarted")
    val speech = glasses.speech
    if (speech == null) {
      callbacks.onEnded("glasses speech unavailable")
      return
    }
    this.callbacks = callbacks
    active = speech
    val stale = speech.transcriptions.value
    val staleError = speech.errors.value
    job =
        scope.launch {
          launch {
            speech.transcriptions.filterNotNull().collect { r ->
              if (r === stale || r.text.isBlank()) return@collect
              callbacks.onText(r.text, r.isFinal, r.confidence.toDouble())
              if (r.isFinal) finish("final")
            }
          }
          launch {
            speech.errors.filterNotNull().collect { e ->
              if (e !== staleError) finish("speech error: ${e.description}")
            }
          }
          delay(timeoutMs)
          finish("timeout")
        }
    speech.start().onFailure { error, _ -> finish("start failed: ${error.description}") }
  }

  @Synchronized
  private fun finish(reason: String) {
    stopLocked(reason)
  }

  override fun stop() = finish("stopped")

  private fun stopLocked(reason: String) {
    val cb = callbacks ?: return
    callbacks = null
    runCatching { active?.stop() }
    active = null
    job?.cancel()
    job = null
    cb.onEnded(reason)
  }
}

/**
 * Android's SpeechRecognizer (network recognition on most phones), optionally fed by the glasses
 * mic over Bluetooth SCO. This is the fallback when DAT Speech isn't enabled for the app.
 */
class AndroidSpeechInput(
    private val context: Context,
    private val routing: AudioRouting,
    private val useGlassesMic: Boolean,
) : SpeechInput {
  override val name = if (useGlassesMic) "Phone recognizer, glasses mic" else "Phone recognizer, phone mic"
  private val main = Handler(Looper.getMainLooper())
  private var recognizer: SpeechRecognizer? = null
  private var callbacks: ListenCallbacks? = null
  private var hints: List<String> = emptyList()
  private val timeout = Runnable { end("timeout") }

  override fun isAvailable() = SpeechRecognizer.isRecognitionAvailable(context)

  override fun start(mode: ListenMode, timeoutMs: Long, hints: List<String>, callbacks: ListenCallbacks) {
    main.post {
      end("restarted")
      this.callbacks = callbacks
      this.hints = hints
      if (useGlassesMic && !routing.useGlassesMic()) Log.w(TAG, "glasses mic unavailable, using phone mic")
      val r = SpeechRecognizer.createSpeechRecognizer(context)
      recognizer = r
      r.setRecognitionListener(listener)
      val intent =
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
      r.startListening(intent)
      main.postDelayed(timeout, timeoutMs)
    }
  }

  override fun stop() {
    main.post { end("stopped") }
  }

  private fun end(reason: String) {
    main.removeCallbacks(timeout)
    recognizer?.let {
      runCatching { it.cancel() }
      it.destroy()
    }
    recognizer = null
    if (useGlassesMic) routing.releaseMic()
    val cb = callbacks ?: return
    callbacks = null
    cb.onEnded(reason)
  }

  /** With command hints, prefer the alternative that best matches an expected phrase. */
  private fun best(results: Bundle?): Pair<String, Double>? {
    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return null
    if (texts.isEmpty()) return null
    val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
    if (hints.isEmpty()) return texts[0] to (scores?.getOrNull(0)?.toDouble() ?: -1.0)
    val i = texts.indices.maxBy { idx -> hints.maxOf { PhraseMatcher.score(texts[idx], it) } }
    return texts[i] to (scores?.getOrNull(i)?.toDouble() ?: -1.0)
  }

  private val listener =
      object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) = end("recognizer error $error")

        override fun onResults(results: Bundle?) {
          best(results)?.let { (text, conf) -> callbacks?.onText(text, true, conf) }
          end("final")
        }

        override fun onPartialResults(partialResults: Bundle?) {
          val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
          if (!text.isNullOrBlank()) callbacks?.onText(text, false, -1.0)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
      }

  companion object {
    private const val TAG = "GhostSignalSpeech"
  }
}
