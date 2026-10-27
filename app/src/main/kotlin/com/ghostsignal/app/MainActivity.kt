package com.ghostsignal.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ghostsignal.app.ui.AppRoot
import com.ghostsignal.app.ui.Permissions
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val graph by lazy { (application as GhostSignalApp).graph }
  private val permissions = MutableStateFlow(Permissions())

  private val runtimePermissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        onBluetoothReady()
      }

  private val glassesMicPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        graph.glasses.onMicPermissionResult(result.getOrDefault(PermissionStatus.Denied))
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    refreshPermissions()
    if (permissions.value.bluetooth) onBluetoothReady() else requestPermissions()
    handle(intent)
    lifecycleScope.launch { graph.glasses.launchRequests.collect { startFromVoice() } }
    setContent {
      AppRoot(
          graph = graph,
          permissions = permissions,
          onRequestPermissions = ::requestPermissions,
          onRegister = { graph.glasses.startRegistration(this) },
          onUnregister = { graph.glasses.startUnregistration(this) },
          onRequestGlassesMic = { glassesMicPermission.launch(Permission.MICROPHONE) },
      )
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handle(intent)
  }

  override fun onResume() {
    super.onResume()
    graph.game.phoneInHand = true
    refreshPermissions()
    lifecycleScope.launch { graph.glasses.refreshMicPermission() }
  }

  override fun onPause() {
    graph.game.phoneInHand = false
    super.onPause()
  }

  private fun handle(intent: Intent?) {
    intent ?: return
    if (graph.glasses.state.value.initialized) graph.glasses.handleIntent(this, intent)
    if (isVoiceInvocationsIntent(intent)) startFromVoice()
  }

  /** "Hey Meta, start GhostSignal": resume the run if there is one, else start fresh. */
  private fun startFromVoice() {
    val game = graph.game
    if (game.running) return
    val p = graph.progress.progress.value
    game.start(resume = p != null && !p.complete)
  }

  private fun requestPermissions() {
    runtimePermissions.launch(
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        ))
  }

  private fun onBluetoothReady() {
    if (!permissions.value.bluetooth) return
    val glasses = graph.glasses
    if (graph.settings.value.mockGlasses) glasses.enableMock() else glasses.initialize()
    glasses.startVoiceInvocations()
    handle(intent)
  }

  private fun refreshPermissions() {
    fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    permissions.value =
        Permissions(
            bluetooth = granted(Manifest.permission.BLUETOOTH_CONNECT),
            location = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            microphone = granted(Manifest.permission.RECORD_AUDIO),
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
        )
  }
}
