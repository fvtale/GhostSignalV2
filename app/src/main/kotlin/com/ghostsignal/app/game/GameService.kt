package com.ghostsignal.app.game

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ghostsignal.app.GhostSignalApp
import com.ghostsignal.app.MainActivity
import com.ghostsignal.app.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a run alive with the screen off and the phone in a pocket: GPS, audio and (during
 * listening windows) the microphone all need a foreground service on Android 14+.
 */
class GameService : LifecycleService() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Signal run", NotificationManager.IMPORTANCE_LOW))
    var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    startForeground(NOTIFICATION_ID, build("Tuning in…"), types)
    wakeLock =
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GhostSignal:run").apply {
          acquire(3 * 60 * 60 * 1000L)
        }
    val game = (application as GhostSignalApp).graph.game
    lifecycleScope.launch {
      game.ui
          .map { ui -> if (ui.paused) "Paused" else "${ui.collected.size}/${ui.total} · ${ui.objective}" }
          .distinctUntilChanged()
          .collect { text -> nm.notify(NOTIFICATION_ID, build(text)) }
    }
  }

  private fun build(text: String): Notification {
    val open =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("GhostSignal")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .build()
  }

  override fun onBind(intent: Intent): IBinder? {
    super.onBind(intent)
    return null
  }

  override fun onDestroy() {
    wakeLock?.let { if (it.isHeld) it.release() }
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "run"
    private const val NOTIFICATION_ID = 99
  }
}
