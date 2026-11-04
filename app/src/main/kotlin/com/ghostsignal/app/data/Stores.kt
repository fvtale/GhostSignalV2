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
 * The mission as this phone plays it: the bundled mission plus everything
 * the server adds — the venue route (placements from the route editor,
 * names from the admin page) and deployed soundtracks whose stems are
 * downloaded once and kept on the phone.
 */
/** A mission bundled in assets/mission/, as shown in the picker. */
data class BundledMission(
    val id: String,
    val title: String,
    val asset: String,
)

class MissionRepository(
    private val context: Context,
    private val backend: BackendClient,
) {

  private val preferences =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  /** Every parseable mission in assets/mission/, in filename order. */
  val availableMissions: List<BundledMission> by lazy {
    bundledMissions.values.map { BundledMission(it.id, it.title, assetById.getValue(it.id)) }
  }

  /** Every parsed bundled mission (for the opening's nearest-landmark suggestion). */
  val bundled: List<Mission>
    get() = bundledMissions.values.toList()

  private val assetById: Map<String, String> by lazy { discoverBundled().second }

  private val bundledMissions: Map<String, Mission> by lazy { discoverBundled().first }

  private fun discoverBundled(): Pair<Map<String, Mission>, Map<String, String>> {
    val missions = linkedMapOf<String, Mission>()
    val assets = linkedMapOf<String, String>()
    val names = runCatching { context.assets.list(MISSION_DIR)?.toList() }
        .getOrNull().orEmpty()
        .filter { it.endsWith(".json") }
        .sorted()
    for (name in names) {
      val asset = "$MISSION_DIR/$name"
      runCatching {
        val text = context.assets.open(asset).bufferedReader().use { it.readText() }
        Mission.parse(text)
      }.onSuccess { parsed ->
        missions[parsed.id] = parsed
        assets[parsed.id] = asset
      }.onFailure { Log.w(TAG, "skipping unparseable bundled mission $asset", it) }
    }
    return missions to assets
  }

  private val mutableSelectedMissionId = MutableStateFlow(readSelectedMissionId())

  /** Persisted choice of bundled mission; defaults to Bryant Park. */
  val selectedMissionId: StateFlow<String> = mutableSelectedMissionId

  private fun readSelectedMissionId(): String {
    val stored = preferences.getString(KEY_SELECTED_MISSION, null)
    return when {
      stored != null && stored in bundledMissions -> stored
      DEFAULT_MISSION_ID in bundledMissions -> DEFAULT_MISSION_ID
      else -> bundledMissions.keys.firstOrNull() ?: DEFAULT_MISSION_ID
    }
  }

  private val bundledMission: Mission
    get() = bundledMissions[mutableSelectedMissionId.value]
        ?: bundledMissions[DEFAULT_MISSION_ID]
        ?: bundledMissions.values.first()
        ?: error("no bundled missions in assets/$MISSION_DIR")

  /** Switches the active mission, persists the choice, and reloads it. */
  fun selectMission(missionId: String) {
    if (missionId !in bundledMissions) return
    if (missionId == mutableSelectedMissionId.value) return
    preferences.edit().putString(KEY_SELECTED_MISSION, missionId).apply()
    mutableSelectedMissionId.value = missionId
    mutableLocked.value = storedCatalog()?.locked ?: emptyList()
    mutableMission.value = loadMission()
  }

  private val routeFile: File
    get() = File(context.filesDir, "route_${bundledMission.id}.json")

  private val catalogFile: File
    get() = File(context.filesDir, "tracks_${bundledMission.id}.json")

  private val downloadedTracksDir: File
    get() = File(context.filesDir, "tracks").apply { mkdirs() }

  private val syncMutex = Mutex()

  @Volatile
  private var lastSyncAtMs: Long = 0L

  private val mutableMission = MutableStateFlow(loadMission())
  val mission: StateFlow<Mission> = mutableMission

  private val mutableLocked = MutableStateFlow(storedCatalog()?.locked ?: emptyList())

  /** Premium soundtracks this player does not have, as of the last sync. */
  val locked: StateFlow<List<LockedTrack>> = mutableLocked

  val baseMission: Mission
    get() = bundledMission

  val problems: List<String>
    get() = MissionValidator.validate(mutableMission.value)

  fun route(): RouteOverride? = readJsonFile(routeFile) { text ->
    Mission.json.decodeFromString(RouteOverride.serializer(), text)
  }

  fun catalog(): TrackCatalog? = storedCatalog()

  private fun storedCatalog(): TrackCatalog? = readJsonFile(catalogFile) { text ->
    Mission.json.decodeFromString(TrackCatalog.serializer(), text)
  }

  private fun <T> readJsonFile(file: File, decode: (String) -> T): T? =
      runCatching { decode(file.readText()) }.getOrNull()

  /** On-phone file for a deployed stem, or null for a bundled stem. */
  fun localFile(stem: StemSpec): File? =
      TrackCatalog.blobOf(stem)?.let { blobName -> File(downloadedTracksDir, blobName) }

  private fun stemReady(stem: StemSpec): Boolean {
    val file = localFile(stem) ?: return true // Bundled stems are always ready.
    return file.isFile && (stem.bytes <= 0 || file.length() == stem.bytes)
  }

  // Tracks compose before the route, so a renamed signal also renames its
  // layer inside deployed tracks.
  private fun loadMission(): Mission =
      bundledMission.withTracks(storedCatalog(), ::stemReady).withRoute(route())

  fun place(signalId: String, placement: SignalPlacement) {
    val current = route() ?: RouteOverride(bundledMission.id, "Venue route")
    saveRoute(current.copy(
        placements = current.placements + (signalId to placement),
        updatedAtMs = System.currentTimeMillis(),
    ))
  }

  fun placeAll(placements: Map<String, SignalPlacement>) {
    val current = route() ?: RouteOverride(bundledMission.id, "Venue route")
    saveRoute(current.copy(
        placements = current.placements + placements,
        updatedAtMs = System.currentTimeMillis(),
    ))
  }

  fun saveRoute(route: RouteOverride) {
    routeFile.writeText(Mission.json.encodeToString(RouteOverride.serializer(), route))
    mutableMission.value = loadMission()
  }

  fun resetRoute() {
    routeFile.delete()
    mutableMission.value = loadMission()
  }

  /**
   * Pulls the shared route and deployed soundtracks, downloading missing
   * stems and deleting orphaned ones. [manual] (the operator's Pull)
   * always takes the server's route; automatic syncs keep a local route
   * edited more recently than the server's, so mid-placement work is not
   * lost. Returns a one-line status for the operator screen.
   */
  suspend fun sync(manual: Boolean): String = syncMutex.withLock {
    withContext(Dispatchers.IO) {
      if (!backend.configured) return@withContext "No server set"
      val statusParts = mutableListOf<String>()
      syncRoute(manual, statusParts)
      syncTracks(statusParts)
      lastSyncAtMs = System.currentTimeMillis()
      statusParts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
  }

  private suspend fun syncRoute(manual: Boolean, statusParts: MutableList<String>) {
    try {
      val remoteRoute = backend.getRoute(bundledMission.id)
      val localRoute = route()
      when {
        remoteRoute == null -> statusParts += "no shared route yet"
        !manual && localRoute != null && localRoute.updatedAtMs > remoteRoute.updatedAtMs ->
          statusParts += "kept this phone's newer route"
        else -> {
          saveRoute(remoteRoute)
          statusParts += "route '${remoteRoute.name}': " +
            "${remoteRoute.placements.size} placed, ${remoteRoute.labels.size} renamed"
        }
      }
    } catch (error: Exception) {
      Log.w(TAG, "route pull failed", error)
      statusParts += "route failed: ${error.message}"
    }
  }

  private suspend fun syncTracks(statusParts: MutableList<String>) {
    try {
      val remoteCatalog = backend.getTracks(bundledMission.id)
      var downloaded = 0
      var failed = 0
      val uniqueStems = remoteCatalog.soundtracks
          .flatMap { it.music.stems }
          .distinctBy { it.file }
      for (stem in uniqueStems) {
        val targetFile = localFile(stem) ?: continue
        if (stemReady(stem)) continue
        try {
          backend.downloadBlob(targetFile.name, stem.sha256, targetFile)
          downloaded++
        } catch (error: Exception) {
          Log.w(TAG, "stem ${targetFile.name} failed to download", error)
          failed++
        }
      }

      catalogFile.writeText(Mission.json.encodeToString(TrackCatalog.serializer(), remoteCatalog))
      mutableLocked.value = remoteCatalog.locked

      val stillUsed = remoteCatalog.soundtracks
          .flatMap { track -> track.music.stems.mapNotNull { stem -> localFile(stem)?.name } }
          .toSet()
      downloadedTracksDir.listFiles()?.forEach { file ->
        if (file.name !in stillUsed) file.delete()
      }

      mutableMission.value = loadMission()
      val readyCount = remoteCatalog.soundtracks.count { track ->
        mutableMission.value.soundtracks.any { it.id == track.id }
      }
      statusParts += "${remoteCatalog.soundtracks.size} deployed soundtracks, $readyCount ready" +
        (if (downloaded > 0) ", $downloaded stems downloaded" else "") +
        (if (failed > 0) ", $failed failed" else "")
    } catch (error: Exception) {
      Log.w(TAG, "soundtrack sync failed", error)
      statusParts += "soundtracks failed: ${error.message}"
    }
  }

  /** Quiet automatic sync, throttled: at start and when setup opens. */
  fun syncIfStale(scope: CoroutineScope) {
    val stale = System.currentTimeMillis() - lastSyncAtMs >= STALE_MS
    if (!backend.configured || !stale || syncMutex.isLocked) return
    scope.launch { Log.i(TAG, "sync: ${sync(manual = false)}") }
  }

  suspend fun push(operatorToken: String): String {
    val localRoute = route() ?: return "Nothing to push: no venue placements yet"
    return try {
      backend.putRoute(localRoute, operatorToken)
      "Pushed ${localRoute.placements.size} placements"
    } catch (error: Exception) {
      "Push failed: ${error.message}"
    }
  }

  companion object {
    private const val TAG = "GhostSignalMission"
    private const val STALE_MS = 5 * 60 * 1000L
    private const val PREFS_NAME = "ghostsignal_missions"
    private const val KEY_SELECTED_MISSION = "selected_mission_id"
    const val MISSION_DIR = "mission"
    const val DEFAULT_MISSION_ID = "ghostsignal-bryant-park"
    const val MISSION_ASSET = "mission/ghostsignal.json"
  }
}

/** What this phone has earned: finished runs, and the chosen soundtrack. */
data class Rewards(
    val completions: Int = 0,
    val soundtrack: String = Soundtracks.ORIGINAL,
)

/**
 * Earned progress, kept apart from run progress so starting a new run can
 * never take a reward away. The operator can re-lock a shared event phone
 * from Settings.
 */
class RewardsStore(context: Context) {

  private val preferences =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val mutableRewards = MutableStateFlow(readRewards())
  val rewards: StateFlow<Rewards> = mutableRewards

  val value: Rewards
    get() = mutableRewards.value

  private fun readRewards(): Rewards = Rewards(
      completions = preferences.getInt(KEY_COMPLETIONS, 0),
      soundtrack = preferences.getString(KEY_SOUNDTRACK, null) ?: Soundtracks.ORIGINAL,
  )

  private fun writeRewards(rewards: Rewards) {
    preferences.edit()
        .putInt(KEY_COMPLETIONS, rewards.completions)
        .putString(KEY_SOUNDTRACK, rewards.soundtrack)
        .apply()
    mutableRewards.value = rewards
  }

  /** Counts a finished run; its unlock becomes the next run's soundtrack. */
  fun recordCompletion(mission: Mission): List<Soundtrack> {
    val current = mutableRewards.value
    val newlyUnlocked = Soundtracks.unlockedByNextCompletion(mission, current.completions)
    writeRewards(current.copy(
        completions = current.completions + 1,
        soundtrack = newlyUnlocked.lastOrNull()?.id ?: current.soundtrack,
    ))
    return newlyUnlocked
  }

  fun choose(soundtrackId: String) {
    writeRewards(mutableRewards.value.copy(soundtrack = soundtrackId))
  }

  /** Adopts a higher count from the account (signed in on another phone). */
  fun atLeast(completions: Int) {
    val current = mutableRewards.value
    if (completions > current.completions) {
      writeRewards(current.copy(completions = completions))
    }
  }

  /** Back to a first-time runner: nothing unlocked, original soundtrack. */
  fun relock() {
    writeRewards(Rewards())
  }

  private companion object {
    const val PREFS_NAME = "ghostsignal_rewards"
    const val KEY_COMPLETIONS = "completions"
    const val KEY_SOUNDTRACK = "soundtrack"
  }
}

/**
 * Run progress, so a crash, reboot, or dead battery loses nothing.
 * Kept in one file per mission so switching missions never mixes runs.
 */
class ProgressStore(
    private val context: Context,
    private val missionId: () -> String = { MissionRepository.DEFAULT_MISSION_ID },
) {

  private val progressFile: File
    get() = File(context.filesDir, "progress_${missionId()}.json")

  private val legacyFile: File
    get() = File(context.filesDir, "progress.json")

  private val mutableProgress = MutableStateFlow<Progress?>(readProgress())
  val progress: StateFlow<Progress?> = mutableProgress

  private fun decode(file: File): Progress? = runCatching {
    Mission.json.decodeFromString(Progress.serializer(), file.readText())
  }.getOrNull()

  private fun readProgress(): Progress? {
    decode(progressFile)?.let { return it }
    // Pre-selector installs kept a single progress.json; adopt it only
    // when it belongs to the selected mission.
    return decode(legacyFile)?.takeIf { it.missionId == missionId() }
  }

  /** Re-reads progress for the currently selected mission. */
  fun reload() {
    mutableProgress.value = readProgress()
  }

  fun save(progress: Progress) {
    progressFile.writeText(Mission.json.encodeToString(Progress.serializer(), progress))
    mutableProgress.value = progress
  }

  fun clear() {
    progressFile.delete()
    if (decode(legacyFile)?.missionId == missionId()) legacyFile.delete()
    mutableProgress.value = null
  }
}
