package com.ghostsignal.app.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RouteInfo(
    val outputName: String,
    val bluetoothOutput: Boolean,
    val glassesMicAvailable: Boolean,
    val glassesMicActive: Boolean,
)

/**
 * Where sound goes and where the mic comes from.
 *
 * Music and voices play as normal media, which Android sends to the glasses over A2DP. The
 * glasses' microphones are only reachable through the Bluetooth call profile (SCO/HFP); switching
 * to it usually drops the link to mono, call-quality audio for as long as it's open. The game hides
 * that behind the walkie-talkie "comms" effect and only opens the glasses mic for short windows.
 */
class AudioRouting(context: Context) {
  private val audio = context.getSystemService(AudioManager::class.java)
  private val _route = MutableStateFlow(read(false))
  val route: StateFlow<RouteInfo> = _route
  private var scoActive = false

  private val callback =
      object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
      }

  init {
    audio.registerAudioDeviceCallback(callback, null)
  }

  fun refresh() {
    _route.value = read(scoActive)
  }

  private fun read(sco: Boolean): RouteInfo {
    val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val bt = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    val name = bt?.productName?.toString() ?: if (outputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }) "Wired headphones" else "Phone speaker"
    return RouteInfo(name, bt != null, scoDevice() != null, sco)
  }

  private fun scoDevice(): AudioDeviceInfo? =
      audio.availableCommunicationDevices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
      }

  /** Routes capture to the glasses mic. Returns false if no Bluetooth mic is available. */
  @Synchronized
  fun useGlassesMic(): Boolean {
    val device = scoDevice() ?: return false
    return try {
      audio.mode = AudioManager.MODE_IN_COMMUNICATION
      scoActive = audio.setCommunicationDevice(device)
      refresh()
      scoActive
    } catch (e: Exception) {
      Log.w(TAG, "could not route to glasses mic", e)
      false
    }
  }

  @Synchronized
  fun releaseMic() {
    if (!scoActive) return
    runCatching { audio.clearCommunicationDevice() }
    audio.mode = AudioManager.MODE_NORMAL
    scoActive = false
    refresh()
  }

  fun glassesMicDevice(): AudioDeviceInfo? =
      audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
      }

  companion object {
    private const val TAG = "GhostSignalRouting"
  }
}
