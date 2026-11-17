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

/**
 * Single activity hosting the Compose UI. Owns the Android runtime
 * permission flow, the glasses mic permission, and the voice-invocation
 * entry point ("Hey Meta, start GhostSignal").
 */
class MainActivity : ComponentActivity() {

  private val graph: AppGraph by lazy { (application as GhostSignalApp).graph }
  private val permissionsFlow = MutableStateFlow(Permissions())

  private val runtimePermissionRequest =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
        onBluetoothReady()
      }

  private val glassesMicPermissionRequest =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        graph.glasses.onMicPermissionResult(result.getOrDefault(PermissionStatus.Denied))
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    refreshPermissions()
    if (permissionsFlow.value.bluetooth) onBluetoothReady() else requestRuntimePermissions()
    handleIntent(intent)
    lifecycleScope.launch {
      graph.glasses.launchRequests.collect { startFromVoiceInvocation() }
    }
    setContent {
      AppRoot(
          graph = graph,
          permissions = permissionsFlow,
          onRequestPermissions = ::requestRuntimePermissions,
          onRegister = { graph.glasses.startRegistration(this) },
          onUnregister = { graph.glasses.startUnregistration(this) },
          onRequestGlassesMic = { glassesMicPermissionRequest.launch(Permission.MICROPHONE) },
      )
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
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

  private fun handleIntent(intent: Intent?) {
    if (intent == null) return
    if (graph.glasses.state.value.initialized) {
      graph.glasses.handleIntent(this, intent)
    }
    if (isVoiceInvocationsIntent(intent)) startFromVoiceInvocation()
  }

  /** Voice launch: resume the saved run when there is one, else start fresh. */
  private fun startFromVoiceInvocation() {
    val game = graph.game
    if (game.running) return
    val saved = graph.progress.progress.value
    game.start(resume = saved != null && !saved.complete)
  }

  private fun requestRuntimePermissions() {
    runtimePermissionRequest.launch(
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        ),
    )
  }

  private fun onBluetoothReady() {
    if (!permissionsFlow.value.bluetooth) return
    val glasses = graph.glasses
    if (graph.settings.value.mockGlasses) glasses.enableMock() else glasses.initialize()
    glasses.startVoiceInvocations()
    handleIntent(intent)
  }

  private fun refreshPermissions() {
    fun isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    permissionsFlow.value = Permissions(
        bluetooth = isGranted(Manifest.permission.BLUETOOTH_CONNECT),
        location = isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
        microphone = isGranted(Manifest.permission.RECORD_AUDIO),
        notifications = isGranted(Manifest.permission.POST_NOTIFICATIONS),
    )
  }
}
