package com.ghostsignal.app.glasses

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.ghostsignal.core.engine.PlayerAction

/**
 * Fallback glasses control when DAT Inputs isn't available: the glasses' touchpad still sends
 * standard Bluetooth media buttons (play/pause, next, previous) to whichever app holds the active
 * media session, which during a run is us.
 */
class MediaButtonInput(context: Context, private val onAction: (PlayerAction, String) -> Unit) {
  private val session = MediaSession(context, "GhostSignal")
  private var lastMs = 0L

  init {
    session.setCallback(
        object : MediaSession.Callback() {
          override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val event =
                (if (Build.VERSION.SDK_INT >= 33) mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                else @Suppress("DEPRECATION") mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)) ?: return false
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
            when (event.keyCode) {
              KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
              KeyEvent.KEYCODE_MEDIA_PLAY,
              KeyEvent.KEYCODE_MEDIA_PAUSE,
              KeyEvent.KEYCODE_HEADSETHOOK -> emit(PlayerAction.SCAN, "media play/pause")
              KeyEvent.KEYCODE_MEDIA_NEXT -> emit(PlayerAction.HINT, "media next")
              KeyEvent.KEYCODE_MEDIA_PREVIOUS -> emit(PlayerAction.REPEAT, "media previous")
              else -> return false
            }
            return true
          }

          override fun onPlay() = emit(PlayerAction.SCAN, "media play")

          override fun onPause() = emit(PlayerAction.SCAN, "media pause")

          override fun onSkipToNext() = emit(PlayerAction.HINT, "media next")

          override fun onSkipToPrevious() = emit(PlayerAction.REPEAT, "media previous")
        })
    session.setPlaybackState(
        PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
            .build())
  }

  /** Key events and transport callbacks can both fire for one press; keep the first. */
  private fun emit(action: PlayerAction, raw: String) {
    val now = SystemClock.elapsedRealtime()
    if (now - lastMs < 300) return
    lastMs = now
    onAction(action, raw)
  }

  fun setActive(active: Boolean) {
    session.isActive = active
  }

  fun release() {
    session.isActive = false
    session.release()
  }
}
