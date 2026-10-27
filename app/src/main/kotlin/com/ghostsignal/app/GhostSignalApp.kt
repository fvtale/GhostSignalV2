package com.ghostsignal.app

import android.app.Application
import com.ghostsignal.app.data.AccountRepository
import com.ghostsignal.app.data.AccountStore
import com.ghostsignal.app.data.MissionRepository
import com.ghostsignal.app.data.ProgressStore
import com.ghostsignal.app.data.RewardsStore
import com.ghostsignal.app.game.GameController
import com.ghostsignal.app.glasses.GlassesManager
import com.ghostsignal.app.net.BackendClient
import com.ghostsignal.app.voice.AudioRouting
import com.ghostsignal.app.voice.LineVoices
import com.ghostsignal.app.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GhostSignalApp : Application() {
  lateinit var graph: AppGraph
    private set

  override fun onCreate() {
    super.onCreate()
    graph = AppGraph(this)
  }
}

/** Hand-wired singletons; the app is small enough not to need a DI framework. */
class AppGraph(app: Application) {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  val settings = Settings(app)
  val backend = BackendClient({ settings.value.backendUrl }, { settings.value.eventToken })
  val missions = MissionRepository(app, backend)
  val progress = ProgressStore(app)
  val rewards = RewardsStore(app)
  /** Optional player account: finished runs across phones, and premium soundtracks. */
  val account = AccountRepository(backend, AccountStore(app), rewards, missions, scope)
  val glasses = GlassesManager(app, scope)
  val routing = AudioRouting(app)
  val voices = LineVoices(app, backend)
  val recorder = VoiceRecorder(app, routing)
  val game = GameController(app, settings, missions, progress, rewards, backend, glasses, routing, voices, recorder)

  init {
    // Pick up names, positions and soundtracks published from the admin page since last time,
    // and the player's plan (premium may have started or ended while the app was closed).
    missions.syncIfStale(scope)
    scope.launch { account.refresh() }
  }
}
