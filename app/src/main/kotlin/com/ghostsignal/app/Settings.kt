package com.ghostsignal.app

import android.content.Context
import com.ghostsignal.core.heading.HeadingFusion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How spoken answers are captured. AUTO prefers the glasses, then the phone. */
enum class VoiceInputMode(val label: String) {
  AUTO("Auto (glasses first)"),
  GLASSES("Glasses speech (DAT)"),
  PHONE_RECOGNIZER("Phone recognizer, glasses mic"),
  PHONE_MIC("Phone recognizer, phone mic"),
}

/** Where position fixes come from: real GPS, or the operator's simulated runner. */
enum class LocationMode(val label: String) {
  GPS("GPS"),
  SIMULATED("Simulated"),
}

/** Which spatial renderer to use. FMOD exists only in SDK-equipped builds. */
enum class EngineChoice(val label: String) {
  AUTO("Auto"),
  BUILT_IN("Built-in binaural"),
  FMOD("FMOD"),
}

data class AppSettings(
    val backendUrl: String,
    val eventToken: String,
    val operatorToken: String,
    val voiceInput: VoiceInputMode,
    val locationMode: LocationMode,
    val engine: EngineChoice,
    val yawMode: HeadingFusion.YawMode,
    val invertYaw: Boolean,
    val mockGlasses: Boolean,
    val masterVolume: Float,
)

/**
 * Persistent app settings (SharedPreferences). Event-day values may also
 * be baked in at build time via local.properties (see app/build.gradle.kts);
 * anything the operator changes on the phone wins over the baked default.
 */
class Settings(context: Context) {

  private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val mutableFlow = MutableStateFlow(load())

  val flow: StateFlow<AppSettings> = mutableFlow.asStateFlow()

  val value: AppSettings
    get() = mutableFlow.value

  private fun load(): AppSettings = AppSettings(
      backendUrl = preferences.getString(KEY_BACKEND_URL, null) ?: BuildConfig.BACKEND_URL,
      eventToken = preferences.getString(KEY_EVENT_TOKEN, null) ?: BuildConfig.EVENT_TOKEN,
      operatorToken = preferences.getString(KEY_OPERATOR_TOKEN, "") ?: "",
      voiceInput = enumPref(KEY_VOICE_INPUT, VoiceInputMode.AUTO),
      locationMode = enumPref(KEY_LOCATION_MODE, LocationMode.GPS),
      engine = enumPref(KEY_ENGINE, EngineChoice.AUTO),
      yawMode = enumPref(KEY_YAW_MODE, HeadingFusion.YawMode.GYRO),
      invertYaw = preferences.getBoolean(KEY_INVERT_YAW, false),
      mockGlasses = preferences.getBoolean(KEY_MOCK_GLASSES, false),
      masterVolume = preferences.getFloat(KEY_MASTER_VOLUME, 0.9f),
  )

  /** Applies a change, persists it (URLs/tokens trimmed), and republishes. */
  fun update(change: (AppSettings) -> AppSettings) {
    val next = change(mutableFlow.value)
    preferences.edit()
        .putString(KEY_BACKEND_URL, next.backendUrl.trim().trimEnd('/'))
        .putString(KEY_EVENT_TOKEN, next.eventToken.trim())
        .putString(KEY_OPERATOR_TOKEN, next.operatorToken.trim())
        .putString(KEY_VOICE_INPUT, next.voiceInput.name)
        .putString(KEY_LOCATION_MODE, next.locationMode.name)
        .putString(KEY_ENGINE, next.engine.name)
        .putString(KEY_YAW_MODE, next.yawMode.name)
        .putBoolean(KEY_INVERT_YAW, next.invertYaw)
        .putBoolean(KEY_MOCK_GLASSES, next.mockGlasses)
        .putFloat(KEY_MASTER_VOLUME, next.masterVolume)
        .apply()
    mutableFlow.value = load()
  }

  private inline fun <reified E : Enum<E>> enumPref(key: String, fallback: E): E {
    val storedName = preferences.getString(key, null) ?: return fallback
    return enumValues<E>().firstOrNull { it.name == storedName } ?: fallback
  }

  private companion object {
    const val PREFS_NAME = "ghostsignal"
    const val KEY_BACKEND_URL = "backendUrl"
    const val KEY_EVENT_TOKEN = "eventToken"
    const val KEY_OPERATOR_TOKEN = "operatorToken"
    const val KEY_VOICE_INPUT = "voiceInput"
    const val KEY_LOCATION_MODE = "locationMode"
    const val KEY_ENGINE = "engine"
    const val KEY_YAW_MODE = "yawMode"
    const val KEY_INVERT_YAW = "invertYaw"
    const val KEY_MOCK_GLASSES = "mockGlasses"
    const val KEY_MASTER_VOLUME = "masterVolume"
  }
}
