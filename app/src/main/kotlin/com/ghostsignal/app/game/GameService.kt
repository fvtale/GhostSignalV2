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
 * Keeps a run alive with the screen off and the phone pocketed: GPS, audio,
 * and (during listening windows) the microphone all require a foreground
 * service on Android 14+. The notification mirrors run progress.
 */
class GameService : LifecycleService() {

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Signal run", NotificationManager.IMPORTANCE_LOW),
    )

    var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
    if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
      serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    }
    if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
      serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    }
    startForeground(NOTIFICATION_ID, buildNotification("Tuning in…"), serviceTypes)

    wakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GhostSignal:run")
        .apply { acquire(MAX_RUN_MS) }

    val gameController = (application as GhostSignalApp).graph.game
    lifecycleScope.launch {
      gameController.ui
          .map { ui ->
            if (ui.paused) "Paused" else "${ui.collected.size}/${ui.total} · ${ui.objective}"
          }
          .distinctUntilChanged()
          .collect { text -> notificationManager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }
  }

  private fun hasPermission(permission: String): Boolean =
      checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  private fun buildNotification(text: String): Notification {
    val openAppIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("GhostSignal")
        .setContentText(text)
        .setContentIntent(openAppIntent)
        .setOngoing(true)
        .build()
  }

  override fun onBind(intent: Intent): IBinder? {
    super.onBind(intent)
    return null
  }

  override fun onDestroy() {
    wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
    super.onDestroy()
  }

  private companion object {
    const val CHANNEL_ID = "run"
    const val NOTIFICATION_ID = 99
    const val MAX_RUN_MS = 3 * 60 * 60 * 1000L // Three hours.
  }
}
