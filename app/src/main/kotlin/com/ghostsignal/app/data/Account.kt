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

/**
 * Local account state: the session token and the last plan the server
 * sent, kept in app-private storage. The token is never written into the
 * plan JSON that gets persisted alongside it.
 */
class AccountStore(context: Context) {

  private val preferences =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val mutablePlan = MutableStateFlow<AccountPlan?>(readStoredPlan())
  val plan: StateFlow<AccountPlan?> = mutablePlan

  val token: String
    get() = preferences.getString(KEY_TOKEN, "") ?: ""

  private fun readStoredPlan(): AccountPlan? {
    val storedJson = preferences.getString(KEY_PLAN, null) ?: return null
    return runCatching {
      Mission.json.decodeFromString(AccountPlan.serializer(), storedJson)
    }.getOrNull()
  }

  /** Persists a plan; [token] replaces the stored session when given. */
  fun save(token: String?, plan: AccountPlan) {
    val tokenlessPlan = plan.copy(token = null)
    preferences.edit()
        .apply { if (token != null) putString(KEY_TOKEN, token) }
        .putString(KEY_PLAN, Mission.json.encodeToString(AccountPlan.serializer(), tokenlessPlan))
        .apply()
    mutablePlan.value = tokenlessPlan
  }

  fun clear() {
    preferences.edit().clear().apply()
    mutablePlan.value = null
  }

  private companion object {
    const val PREFS_NAME = "ghostsignal_account"
    const val KEY_TOKEN = "token"
    const val KEY_PLAN = "plan"
  }
}

/**
 * Optional player accounts. Signing in syncs finished-run counts across
 * phones (the higher count wins, in both directions) and brings premium
 * soundtracks to premium players. The game plays identically signed out.
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
    // Report each newly finished run while signed in.
    scope.launch {
      rewards.rewards.collect { current ->
        val currentPlan = store.plan.value ?: return@collect
        val reported = currentPlan.account.completions[missionId] ?: 0
        if (current.completions > reported) reportCompletions(current.completions)
      }
    }
  }

  /** Signs in (or creates the account). Returns an error to show, or null. */
  suspend fun signIn(email: String, password: String, create: Boolean): String? {
    return try {
      val newPlan = if (create) {
        backend.signUp(email, password)
      } else {
        backend.signIn(email, password)
      }
      val sessionToken = newPlan.token ?: return "The server didn't send a session"
      store.save(sessionToken, newPlan)
      mergeCompletions(newPlan)
      missions.sync(manual = false)
      null
    } catch (error: Exception) {
      Log.w(TAG, "sign-in failed", error)
      error.message ?: "Couldn't reach the server"
    }
  }

  /**
   * Refetches the plan (after paying, or when setup opens). A 401 from the
   * server means the session is gone: sign out locally and resync.
   */
  suspend fun refresh() {
    if (store.token.isBlank() || !backend.configured) return
    try {
      val premiumBefore = store.plan.value?.account?.premium?.active
      val freshPlan = backend.account()
      store.save(null, freshPlan)
      mergeCompletions(freshPlan)
      if (freshPlan.account.premium.active != premiumBefore) {
        missions.sync(manual = false)
      }
    } catch (error: BackendClient.HttpException) {
      if (error.code == 401) {
        store.clear()
        missions.sync(manual = false)
      }
    } catch (error: Exception) {
      Log.w(TAG, "account refresh failed", error)
    }
  }

  /**
   * Links the connected glasses to this account. An admin approves each
   * pair (the app cannot prove it is real glasses), unless the pair was
   * enrolled for an event. Returns the message to show the runner.
   */
  suspend fun linkGlasses(evidence: GlassesEvidence): String {
    return try {
      val link = backend.linkGlasses(evidence)
      refresh()
      if (link.status == "approved") {
        "Linked. Premium is on."
      } else {
        "Sent for approval. Premium switches on once an admin approves this pair."
      }
    } catch (error: Exception) {
      Log.w(TAG, "glasses link failed", error)
      error.message ?: "Couldn't reach the server"
    }
  }

  suspend fun unlinkGlasses(id: String): String? {
    return try {
      backend.unlinkGlasses(id)
      refresh()
      null
    } catch (error: Exception) {
      error.message ?: "Couldn't reach the server"
    }
  }

  suspend fun signOut() {
    runCatching { backend.signOut() }
    store.clear()
    missions.sync(manual = false) // Drop premium soundtracks from the mission.
  }

  /** Reconciles local and account completion counts; higher wins. */
  private suspend fun mergeCompletions(plan: AccountPlan) {
    val remoteCount = plan.account.completions[missionId] ?: 0
    val localCount = rewards.value.completions
    when {
      remoteCount > localCount -> rewards.atLeast(remoteCount)
      localCount > remoteCount -> reportCompletions(localCount)
    }
  }

  private suspend fun reportCompletions(completions: Int) {
    try {
      val recorded = backend.reportCompletions(missionId, completions)
      store.plan.value?.let { currentPlan ->
        val updatedCompletions = currentPlan.account.completions + (missionId to recorded)
        store.save(null, currentPlan.copy(account = currentPlan.account.copy(completions = updatedCompletions)))
      }
    } catch (error: Exception) {
      Log.w(TAG, "couldn't record finished runs", error)
    }
  }

  private companion object {
    const val TAG = "GhostSignalAccount"
  }
}
