package com.ghostsignal.app.data

import android.content.Context
import android.util.Log
import com.ghostsignal.app.net.BackendClient
import com.ghostsignal.core.engine.Progress
import com.ghostsignal.core.model.LockedTrack
import com.ghostsignal.core.model.Mission
import com.ghostsignal.core.model.MissionValidator
import com.ghostsignal.core.model.RouteOverride
import com.ghostsignal.core.model.SignalPlacement
import com.ghostsignal.core.model.Soundtrack
import com.ghostsignal.core.model.Soundtracks
import com.ghostsignal.core.model.StemSpec
import com.ghostsignal.core.model.TrackCatalog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The bundled mission, plus what the server adds to it: the venue route (positions captured with
 * the route editor, names set on the admin page) and the soundtracks deployed from the admin page,
 * whose stems are downloaded once and kept on the phone.
 */
class MissionRepository(private val context: Context, private val backend: BackendClient) {
  private val base: Mission by lazy {
    Mission.parse(context.assets.open(MISSION_ASSET).bufferedReader().use { it.readText() })
  }
  private val routeFile: File
    get() = File(context.filesDir, "route_${base.id}.json")
  private val catalogFile: File
    get() = File(context.filesDir, "tracks_${base.id}.json")
  private val tracksDir: File
    get() = File(context.filesDir, "tracks").apply { mkdirs() }
  private val syncLock = Mutex()
  @Volatile private var lastSyncMs = 0L

  private val _mission = MutableStateFlow(load())
  val mission: StateFlow<Mission> = _mission
  private val _locked = MutableStateFlow(catalog()?.locked ?: emptyList())
  /** Premium soundtracks this player doesn't have, as of the last sync. */
  val locked: StateFlow<List<LockedTrack>> = _locked

  val baseMission: Mission
    get() = base

  val problems: List<String>
    get() = MissionValidator.validate(_mission.value)

  fun route(): RouteOverride? =
      runCatching { Mission.json.decodeFromString(RouteOverride.serializer(), routeFile.readText()) }.getOrNull()

  fun catalog(): TrackCatalog? =
      runCatching { Mission.json.decodeFromString(TrackCatalog.serializer(), catalogFile.readText()) }.getOrNull()

  /** Where a deployed stem is kept on this phone, or null for one bundled with the app. */
  fun localFile(stem: StemSpec): File? = TrackCatalog.blobOf(stem)?.let { File(tracksDir, it) }

  private fun onPhone(stem: StemSpec): Boolean =
      localFile(stem)?.let { it.isFile && (stem.bytes <= 0 || it.length() == stem.bytes) } ?: true

  // Tracks first, then the route, so a renamed signal renames its layer in deployed tracks too.
  private fun load(): Mission = base.withTracks(catalog(), ::onPhone).withRoute(route())

  fun place(signalId: String, placement: SignalPlacement) {
    val current = route() ?: RouteOverride(base.id, "Venue route")
    save(current.copy(placements = current.placements + (signalId to placement), updatedAtMs = System.currentTimeMillis()))
  }

  fun placeAll(placements: Map<String, SignalPlacement>) {
    val current = route() ?: RouteOverride(base.id, "Venue route")
    save(current.copy(placements = current.placements + placements, updatedAtMs = System.currentTimeMillis()))
  }

  fun save(route: RouteOverride) {
    routeFile.writeText(Mission.json.encodeToString(RouteOverride.serializer(), route))
    _mission.value = load()
  }

  fun resetRoute() {
    routeFile.delete()
    _mission.value = load()
  }

  /**
   * Pulls the shared route and the deployed soundtracks, downloading stems this phone doesn't have
   * yet and deleting ones no track uses any more. [manual] is the operator's Pull and always takes
   * the server's route; an automatic sync keeps a local route edited more recently than the
   * server's, so an operator mid-placement doesn't lose work. Returns a short status line.
   */
  suspend fun sync(manual: Boolean): String =
      syncLock.withLock {
        withContext(Dispatchers.IO) {
          if (!backend.configured) return@withContext "No server set"
          val parts = mutableListOf<String>()
          try {
            val remote = backend.getRoute(base.id)
            val local = route()
            when {
              remote == null -> parts += "no shared route yet"
              !manual && local != null && local.updatedAtMs > remote.updatedAtMs -> parts += "kept this phone's newer route"
              else -> {
                save(remote)
                parts += "route '${remote.name}': ${remote.placements.size} placed, ${remote.labels.size} renamed"
              }
            }
          } catch (e: Exception) {
            Log.w(TAG, "route pull failed", e)
            parts += "route failed: ${e.message}"
          }
          try {
            val catalog = backend.getTracks(base.id)
            var fetched = 0
            var failed = 0
            for (stem in catalog.soundtracks.flatMap { it.music.stems }.distinctBy { it.file }) {
              val file = localFile(stem) ?: continue
              if (onPhone(stem)) continue
              try {
                backend.downloadBlob(file.name, stem.sha256, file)
                fetched++
              } catch (e: Exception) {
                Log.w(TAG, "stem ${file.name} failed to download", e)
                failed++
              }
            }
            catalogFile.writeText(Mission.json.encodeToString(TrackCatalog.serializer(), catalog))
            _locked.value = catalog.locked
            val keep = catalog.soundtracks.flatMap { t -> t.music.stems.mapNotNull { localFile(it)?.name } }.toSet()
            tracksDir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            _mission.value = load()
            val ready = catalog.soundtracks.count { t -> _mission.value.soundtracks.any { it.id == t.id } }
            parts +=
                "${catalog.soundtracks.size} deployed soundtracks, $ready ready" +
                    (if (fetched > 0) ", $fetched stems downloaded" else "") +
                    (if (failed > 0) ", $failed failed" else "")
          } catch (e: Exception) {
            Log.w(TAG, "soundtrack sync failed", e)
            parts += "soundtracks failed: ${e.message}"
          }
          lastSyncMs = System.currentTimeMillis()
          parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
        }
      }

  /** A quiet automatic sync, at most every few minutes: on start and whenever setup opens. */
  fun syncIfStale(scope: CoroutineScope) {
    if (!backend.configured || System.currentTimeMillis() - lastSyncMs < STALE_MS || syncLock.isLocked) return
    scope.launch { Log.i(TAG, "sync: ${sync(manual = false)}") }
  }

  suspend fun push(operatorToken: String): String {
    val r = route() ?: return "Nothing to push: no venue placements yet"
    return try {
      backend.putRoute(r, operatorToken)
      "Pushed ${r.placements.size} placements"
    } catch (e: Exception) {
      "Push failed: ${e.message}"
    }
  }

  companion object {
    private const val TAG = "GhostSignalMission"
    private const val STALE_MS = 5 * 60 * 1000L
    const val MISSION_ASSET = "mission/ghostsignal.json"
  }
}

/** What this phone has earned: how many runs it has finished, and the soundtrack picked for the next one. */
data class Rewards(val completions: Int = 0, val soundtrack: String = Soundtracks.ORIGINAL)

/**
 * Finished runs unlock alternative soundtracks (see [Soundtracks]). Kept apart from [ProgressStore]
 * so starting a new run never takes a reward away; the operator can re-lock a shared event phone
 * from Settings.
 */
class RewardsStore(context: Context) {
  private val prefs = context.getSharedPreferences("ghostsignal_rewards", Context.MODE_PRIVATE)
  private val _rewards = MutableStateFlow(read())
  val rewards: StateFlow<Rewards> = _rewards
  val value: Rewards
    get() = _rewards.value

  private fun read() =
      Rewards(prefs.getInt("completions", 0), prefs.getString("soundtrack", null) ?: Soundtracks.ORIGINAL)

  private fun write(r: Rewards) {
    prefs.edit().putInt("completions", r.completions).putString("soundtrack", r.soundtrack).apply()
    _rewards.value = r
  }

  /** Counts a finished run. Whatever it unlocks becomes the soundtrack for the next run. */
  fun recordCompletion(mission: Mission): List<Soundtrack> {
    val r = _rewards.value
    val unlocked = Soundtracks.unlockedByNextCompletion(mission, r.completions)
    write(r.copy(completions = r.completions + 1, soundtrack = unlocked.lastOrNull()?.id ?: r.soundtrack))
    return unlocked
  }

  fun choose(soundtrackId: String) = write(_rewards.value.copy(soundtrack = soundtrackId))

  /** Takes a higher count from the runner's account (signing in on another phone). Keeps the soundtrack choice. */
  fun atLeast(completions: Int) {
    val r = _rewards.value
    if (completions > r.completions) write(r.copy(completions = completions))
  }

  /** Back to a first-time runner: nothing unlocked, the original soundtrack. */
  fun relock() = write(Rewards())
}

/** Saves progress so a crash, reboot or dead battery doesn't cost a runner their fragments. */
class ProgressStore(context: Context) {
  private val file = File(context.filesDir, "progress.json")
  private val _progress = MutableStateFlow(read())
  val progress: StateFlow<Progress?> = _progress

  private fun read(): Progress? =
      runCatching { Mission.json.decodeFromString(Progress.serializer(), file.readText()) }.getOrNull()

  fun save(p: Progress) {
    file.writeText(Mission.json.encodeToString(Progress.serializer(), p))
    _progress.value = p
  }

  fun clear() {
    file.delete()
    _progress.value = null
  }
}
