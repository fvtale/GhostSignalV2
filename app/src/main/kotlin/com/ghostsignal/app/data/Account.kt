package com.ghostsignal.app.data

import android.content.Context
import android.util.Log
import com.ghostsignal.app.net.AccountPlan
import com.ghostsignal.app.net.BackendClient
import com.ghostsignal.app.net.GlassesEvidence
import com.ghostsignal.core.model.Mission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The signed-in player's session token and the last plan the server sent, kept in app-private storage. */
class AccountStore(context: Context) {
  private val prefs = context.getSharedPreferences("ghostsignal_account", Context.MODE_PRIVATE)
  private val _plan = MutableStateFlow(readPlan())
  val plan: StateFlow<AccountPlan?> = _plan

  val token: String
    get() = prefs.getString("token", "") ?: ""

  private fun readPlan(): AccountPlan? =
      prefs.getString("plan", null)?.let { runCatching { Mission.json.decodeFromString(AccountPlan.serializer(), it) }.getOrNull() }

  fun save(token: String?, plan: AccountPlan) {
    prefs
        .edit()
        .apply { if (token != null) putString("token", token) }
        .putString("plan", Mission.json.encodeToString(AccountPlan.serializer(), plan.copy(token = null)))
        .apply()
    _plan.value = plan.copy(token = null)
  }

  fun clear() {
    prefs.edit().clear().apply()
    _plan.value = null
  }
}

/**
 * Optional player accounts. Signing in keeps finished runs across phones (the higher count wins,
 * both ways) and brings premium soundtracks to premium players. The game plays the same signed out.
 */
class AccountRepository(
    private val backend: BackendClient,
    private val store: AccountStore,
    private val rewards: RewardsStore,
    private val missions: MissionRepository,
    private val scope: CoroutineScope,
) {
  val plan: StateFlow<AccountPlan?> = store.plan
  private val missionId: String
    get() = missions.baseMission.id

  init {
    backend.accountToken = { store.token }
    // Report each finished run while signed in.
    scope.launch {
      rewards.rewards.collect { r ->
        val p = store.plan.value ?: return@collect
        if (r.completions > (p.account.completions[missionId] ?: 0)) report(r.completions)
      }
    }
  }

  /** Signs in, or creates the account; returns an error to show, or null. */
  suspend fun signIn(email: String, password: String, create: Boolean): String? =
      try {
        val p = if (create) backend.signUp(email, password) else backend.signIn(email, password)
        val token = p.token ?: return "The server didn't send a session"
        store.save(token, p)
        merge(p)
        missions.sync(manual = false)
        null
      } catch (e: Exception) {
        Log.w(TAG, "sign-in failed", e)
        e.message ?: "Couldn't reach the server"
      }

  /** Fetches the plan again: after paying, or when setup opens. Signs out if the server has. */
  suspend fun refresh() {
    if (store.token.isBlank() || !backend.configured) return
    try {
      val before = store.plan.value?.account?.premium?.active
      val p = backend.account()
      store.save(null, p)
      merge(p)
      if (p.account.premium.active != before) missions.sync(manual = false)
    } catch (e: BackendClient.HttpException) {
      if (e.code == 401) {
        store.clear()
        missions.sync(manual = false)
      }
    } catch (e: Exception) {
      Log.w(TAG, "account refresh failed", e)
    }
  }

  /**
   * Links the connected glasses to this account. An admin approves each pair (the app can't prove
   * it's real glasses), unless the pair was enrolled for an event. Returns what to tell the runner.
   */
  suspend fun linkGlasses(evidence: GlassesEvidence): String =
      try {
        val link = backend.linkGlasses(evidence)
        refresh()
        if (link.status == "approved") "Linked. Premium is on." else "Sent for approval. Premium switches on once an admin approves this pair."
      } catch (e: Exception) {
        Log.w(TAG, "glasses link failed", e)
        e.message ?: "Couldn't reach the server"
      }

  suspend fun unlinkGlasses(id: String): String? =
      try {
        backend.unlinkGlasses(id)
        refresh()
        null
      } catch (e: Exception) {
        e.message ?: "Couldn't reach the server"
      }

  suspend fun signOut() {
    runCatching { backend.signOut() }
    store.clear()
    missions.sync(manual = false) // drop premium soundtracks
  }

  private suspend fun merge(p: AccountPlan) {
    val remote = p.account.completions[missionId] ?: 0
    val local = rewards.value.completions
    if (remote > local) rewards.atLeast(remote) else if (local > remote) report(local)
  }

  private suspend fun report(completions: Int) {
    try {
      val n = backend.reportCompletions(missionId, completions)
      store.plan.value?.let { p ->
        store.save(null, p.copy(account = p.account.copy(completions = p.account.completions + (missionId to n))))
      }
    } catch (e: Exception) {
      Log.w(TAG, "couldn't record finished runs", e)
    }
  }

  companion object {
    private const val TAG = "GhostSignalAccount"
  }
}
