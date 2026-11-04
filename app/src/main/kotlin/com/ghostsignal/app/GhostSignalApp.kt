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

/**
 * The app's object graph, wired by hand. The app is small enough that a
 * DI framework would cost more than it saves; every collaborator is
 * constructed exactly once here, in dependency order.
 */
class AppGraph(app: Application) {

  val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  val settings: Settings = Settings(app)

  val backend: BackendClient = BackendClient(
      baseUrl = { settings.value.backendUrl },
      eventToken = { settings.value.eventToken },
  )

  val missions: MissionRepository = MissionRepository(app, backend)
  val progress: ProgressStore = ProgressStore(app)
  val rewards: RewardsStore = RewardsStore(app)

  /** Optional player account: cross-phone progress and premium. */
  val account: AccountRepository = AccountRepository(
      backend = backend,
      store = AccountStore(app),
      rewards = rewards,
      missions = missions,
      scope = scope,
  )

  val glasses: GlassesManager = GlassesManager(app, scope)
  val routing: AudioRouting = AudioRouting(app)
  val voices: LineVoices = LineVoices(app, backend)
  val recorder: VoiceRecorder = VoiceRecorder(app, routing)

  val game: GameController = GameController(
      app, settings, missions, progress, rewards,
      backend, glasses, routing, voices, recorder,
  )

  init {
    // Pick up route/track changes published from the admin page, and
    // refresh the player's plan (premium may have changed while away).
    missions.syncIfStale(scope)
    scope.launch { account.refresh() }
  }
}
