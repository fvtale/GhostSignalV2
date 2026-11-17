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
 * Music and voices travel as normal media, which Android sends to the
 * glasses over A2DP. The glasses' microphones are only reachable through
 * the Bluetooth call profile (SCO/HFP); opening it usually drops the link
 * to mono, call-quality audio for as long as it is open. The game hides
 * that behind the walkie-talkie comms effect and opens the glasses mic
 * only for short windows.
 */
class AudioRouting(context: Context) {

  private val audioManager = context.getSystemService(AudioManager::class.java)

  private val mutableRoute = MutableStateFlow(currentRoute(sco = false))
  val route: StateFlow<RouteInfo> = mutableRoute

  private var scoActive = false

  private val deviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refresh()
    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refresh()
  }

  init {
    audioManager.registerAudioDeviceCallback(deviceCallback, null)
  }

  fun refresh() {
    mutableRoute.value = currentRoute(scoActive)
  }

  private fun currentRoute(sco: Boolean): RouteInfo {
    val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val bluetoothOutput = outputs.firstOrNull { device -> device.isBluetoothOutput() }
    val outputName = when {
      bluetoothOutput != null -> bluetoothOutput.productName.toString()
      outputs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES } -> "Wired headphones"
      else -> "Phone speaker"
    }
    return RouteInfo(
        outputName = outputName,
        bluetoothOutput = bluetoothOutput != null,
        glassesMicAvailable = glassesMicCandidate() != null,
        glassesMicActive = sco,
    )
  }

  private fun AudioDeviceInfo.isBluetoothOutput(): Boolean =
      type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET

  private fun AudioDeviceInfo.isGlassesMicCandidate(): Boolean =
      type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

  private fun glassesMicCandidate(): AudioDeviceInfo? =
      audioManager.availableCommunicationDevices.firstOrNull { it.isGlassesMicCandidate() }

  /** Routes capture to the glasses mic. False when no Bluetooth mic exists. */
  @Synchronized
  fun useGlassesMic(): Boolean {
    val device = glassesMicCandidate() ?: return false
    return try {
      audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
      scoActive = audioManager.setCommunicationDevice(device)
      refresh()
      scoActive
    } catch (error: Exception) {
      Log.w(TAG, "could not route to glasses mic", error)
      false
    }
  }

  @Synchronized
  fun releaseMic() {
    if (!scoActive) return
    runCatching { audioManager.clearCommunicationDevice() }
    audioManager.mode = AudioManager.MODE_NORMAL
    scoActive = false
    refresh()
  }

  /** The glasses mic as an input device, for recorder preference. */
  fun glassesMicDevice(): AudioDeviceInfo? =
      audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
          .firstOrNull { it.isGlassesMicCandidate() }

  private companion object {
    const val TAG = "GhostSignalRouting"
  }
}
