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
 * Fallback glasses control when DAT Inputs is unavailable: the glasses'
 * touchpad still sends standard Bluetooth media buttons (play/pause, next,
 * previous) to whichever app holds the active media session — which,
 * during a run, is us.
 */
class MediaButtonInput(
    context: Context,
    private val onAction: (PlayerAction, String) -> Unit,
) {

  private val mediaSession = MediaSession(context, "GhostSignal")
  private var lastEmittedAtMs = 0L

  init {
    mediaSession.setCallback(object : MediaSession.Callback() {
      override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        val keyEvent = extractKeyEvent(mediaButtonIntent) ?: return false
        val isFreshPress = keyEvent.action == KeyEvent.ACTION_DOWN && keyEvent.repeatCount == 0
        if (!isFreshPress) return true
        return when (keyEvent.keyCode) {
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
          KeyEvent.KEYCODE_MEDIA_PLAY,
          KeyEvent.KEYCODE_MEDIA_PAUSE,
          KeyEvent.KEYCODE_HEADSETHOOK -> {
            emit(PlayerAction.SCAN, "media play/pause")
            true
          }
          KeyEvent.KEYCODE_MEDIA_NEXT -> {
            emit(PlayerAction.HINT, "media next")
            true
          }
          KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
            emit(PlayerAction.REPEAT, "media previous")
            true
          }
          else -> false
        }
      }

      override fun onPlay() = emit(PlayerAction.SCAN, "media play")
      override fun onPause() = emit(PlayerAction.SCAN, "media pause")
      override fun onSkipToNext() = emit(PlayerAction.HINT, "media next")
      override fun onSkipToPrevious() = emit(PlayerAction.REPEAT, "media previous")

      private fun extractKeyEvent(intent: Intent): KeyEvent? {
        return if (Build.VERSION.SDK_INT >= 33) {
          intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        }
      }
    })

    mediaSession.setPlaybackState(
        PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                  PlaybackState.ACTION_PAUSE or
                  PlaybackState.ACTION_PLAY_PAUSE or
                  PlaybackState.ACTION_SKIP_TO_NEXT or
                  PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            .setState(PlaybackState.STATE_PLAYING, 0, 1f)
            .build(),
    )
  }

  /** Key events and transport callbacks can both fire for one press; keep the first. */
  private fun emit(action: PlayerAction, rawDescription: String) {
    val nowMs = SystemClock.elapsedRealtime()
    if (nowMs - lastEmittedAtMs < DEBOUNCE_MS) return
    lastEmittedAtMs = nowMs
    onAction(action, rawDescription)
  }

  fun setActive(active: Boolean) {
    mediaSession.isActive = active
  }

  fun release() {
    mediaSession.isActive = false
    mediaSession.release()
  }

  private companion object {
    const val DEBOUNCE_MS = 300L
  }
}
