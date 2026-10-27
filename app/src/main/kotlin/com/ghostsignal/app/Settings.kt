package com.ghostsignal.app

import android.content.Context
import com.ghostsignal.core.heading.HeadingFusion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceInputMode(val label: String) {
  AUTO("Auto (glasses first)"),
  GLASSES("Glasses speech (DAT)"),
  PHONE_RECOGNIZER("Phone recognizer, glasses mic"),
  PHONE_MIC("Phone recognizer, phone mic"),
}

enum class LocationMode(val label: String) {
  GPS("GPS"),
  SIMULATED("Simulated"),
}

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

/** Small persistent settings store. Event-day values can also be baked in via local.properties. */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("ghostsignal", Context.MODE_PRIVATE)
  private val _flow = MutableStateFlow(read())
  val flow: StateFlow<AppSettings> = _flow.asStateFlow()
  val value: AppSettings
    get() = _flow.value

  private fun read() =
      AppSettings(
          backendUrl = prefs.getString("backendUrl", null) ?: BuildConfig.BACKEND_URL,
          eventToken = prefs.getString("eventToken", null) ?: BuildConfig.EVENT_TOKEN,
          operatorToken = prefs.getString("operatorToken", "") ?: "",
          voiceInput = enumOr(prefs.getString("voiceInput", null), VoiceInputMode.AUTO),
          locationMode = enumOr(prefs.getString("locationMode", null), LocationMode.GPS),
          engine = enumOr(prefs.getString("engine", null), EngineChoice.AUTO),
          yawMode = enumOr(prefs.getString("yawMode", null), HeadingFusion.YawMode.GYRO),
          invertYaw = prefs.getBoolean("invertYaw", false),
          mockGlasses = prefs.getBoolean("mockGlasses", false),
          masterVolume = prefs.getFloat("masterVolume", 0.9f),
      )

  fun update(change: (AppSettings) -> AppSettings) {
    val next = change(_flow.value)
    prefs
        .edit()
        .putString("backendUrl", next.backendUrl.trim().trimEnd('/'))
        .putString("eventToken", next.eventToken.trim())
        .putString("operatorToken", next.operatorToken.trim())
        .putString("voiceInput", next.voiceInput.name)
        .putString("locationMode", next.locationMode.name)
        .putString("engine", next.engine.name)
        .putString("yawMode", next.yawMode.name)
        .putBoolean("invertYaw", next.invertYaw)
        .putBoolean("mockGlasses", next.mockGlasses)
        .putFloat("masterVolume", next.masterVolume)
        .apply()
    _flow.value = read()
  }

  private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
      name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
