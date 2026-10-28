package com.ghostsignal.app.net

import com.ghostsignal.core.engine.CharacterRequest
import com.ghostsignal.core.model.Mission
import com.ghostsignal.core.model.RouteOverride
import com.ghostsignal.core.model.TrackCatalog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class NpcTurn(val role: String, val text: String)

@Serializable
data class NpcContext(
    val phase: String,
    val objective: String,
    val signalName: String? = null,
    val place: String? = null,
    val distance: String? = null,
    val direction: String? = null,
    val collected: Int,
    val total: Int,
    val flags: Map<String, String> = emptyMap(),
    val challenge: String? = null,
)

@Serializable
data class NpcRequestBody(
    val missionId: String,
    val characterId: String,
    val signalId: String? = null,
    val utterance: String,
    val context: NpcContext,
    val history: List<NpcTurn>,
)

@Serializable data class NpcReply(val reply: String, val characterId: String? = null)

@Serializable data class TtsRequestBody(val missionId: String, val characterId: String, val text: String)

@Serializable data class LiveBroadcast(val id: String, val speaker: String, val text: String, val createdAtMs: Long)

@Serializable data class BroadcastsResponse(val broadcasts: List<LiveBroadcast>)

@Serializable data class BroadcastBody(val speaker: String, val text: String)

@Serializable data class Health(val ok: Boolean, val npc: Boolean = false, val tts: Boolean = false, val model: String? = null)

@Serializable data class AccountCredentials(val email: String, val password: String, val client: String = "app")

@Serializable
data class PremiumView(
    val active: Boolean = false,
    val source: String? = null,
    val sinceMs: Long? = null,
    val untilMs: Long? = null,
    val manageUrl: String? = null,
)

@Serializable
data class AccountView(
    val id: String,
    val email: String,
    val role: String = "player",
    val premium: PremiumView = PremiumView(),
    val completions: Map<String, Int> = emptyMap(),
)

@Serializable data class PaymentView(val url: String? = null, val priceLabel: String = "", val manageUrl: String? = null)

@Serializable data class TitleView(val id: String, val title: String, val description: String = "")

/** Glasses linked to an account. [status]: pending (an admin checks it), approved, rejected or revoked. */
@Serializable
data class GlassesLink(
    val id: String,
    val status: String,
    val deviceType: String = "",
    val name: String = "",
    val firmware: String = "",
    val fingerprint: String = "",
    val requestedAtMs: Long = 0,
)

/** What the app knows about the connected pair: sent when linking or enrolling it. */
@Serializable
data class GlassesEvidence(
    val identifier: String,
    val deviceType: String,
    val name: String,
    val firmware: String,
    val mock: Boolean = false,
    val note: String = "",
)

@Serializable data class GlassesLinkReply(val link: GlassesLink)

/** A signed-in player: who they are, their plan, and where to pay. [token] comes back on sign-in only. */
@Serializable
data class AccountPlan(
    val token: String? = null,
    val account: AccountView,
    val payment: PaymentView = PaymentView(),
    val premiumTracks: List<TitleView> = emptyList(),
    val glasses: List<GlassesLink> = emptyList(),
)

@Serializable data class SttReply(val text: String)

@Serializable data class ProgressBody(val missionId: String, val completions: Int)

@Serializable data class ProgressReply(val completions: Int)

/** Talks to the GhostSignal backend (server/). All calls are optional: the game runs without it. */
class BackendClient(private val baseUrl: () -> String, private val eventToken: () -> String) {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
  private val http =
      OkHttpClient.Builder()
          .connectTimeout(5, TimeUnit.SECONDS)
          .readTimeout(15, TimeUnit.SECONDS)
          .callTimeout(20, TimeUnit.SECONDS)
          .build()
  /** Stems are a few megabytes each: give a slow connection time to finish one. */
  private val downloads = http.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
  private val jsonType = "application/json".toMediaType()

  /** The signed-in player's session, sent as X-Account-Token so premium soundtracks come through. */
  @Volatile var accountToken: () -> String = { "" }

  val configured: Boolean
    get() = baseUrl().isNotBlank()

  suspend fun signUp(email: String, password: String): AccountPlan = accountPost("/v1/account/signup", AccountCredentials(email.trim(), password))

  suspend fun signIn(email: String, password: String): AccountPlan = accountPost("/v1/account/login", AccountCredentials(email.trim(), password))

  suspend fun account(): AccountPlan = get("/v1/account/me") { json.decodeFromString(AccountPlan.serializer(), it.string()) }

  suspend fun signOut() {
    post("/v1/account/logout", "{}") {}
  }

  /** Asks to link the connected glasses to the signed-in account. Pending until an admin approves. */
  suspend fun linkGlasses(evidence: GlassesEvidence): GlassesLink =
      post("/v1/account/glasses", json.encodeToString(GlassesEvidence.serializer(), evidence)) {
        json.decodeFromString(GlassesLinkReply.serializer(), it.string()).link
      }

  suspend fun unlinkGlasses(id: String) {
    call(Request.Builder().url(url("/v1/account/glasses/$id")).delete(), eventToken()) {}
  }

  /** Admin: vouch for the connected pair (event glasses), so whoever links it gets premium at once. */
  suspend fun enrollGlasses(evidence: GlassesEvidence, operatorToken: String) {
    post("/v1/admin/glasses/enroll", json.encodeToString(GlassesEvidence.serializer(), evidence), token = operatorToken) {}
  }

  /** Records finished runs on the account; returns the account's count, which never goes down. */
  suspend fun reportCompletions(missionId: String, completions: Int): Int =
      post("/v1/account/progress", json.encodeToString(ProgressBody.serializer(), ProgressBody(missionId, completions))) {
        json.decodeFromString(ProgressReply.serializer(), it.string()).completions
      }

  private suspend fun accountPost(path: String, body: AccountCredentials): AccountPlan =
      post(path, json.encodeToString(AccountCredentials.serializer(), body)) { json.decodeFromString(AccountPlan.serializer(), it.string()) }

  suspend fun health(): Health = get("/health") { json.decodeFromString(Health.serializer(), it.string()) }

  suspend fun askCharacter(mission: Mission, request: CharacterRequest): String {
    val c = request.context
    val body =
        NpcRequestBody(
            missionId = mission.id,
            characterId = request.characterId,
            signalId = request.signalId,
            utterance = request.utterance.take(400),
            context =
                NpcContext(
                    c.phase.name, c.objective, c.signalName, c.place, c.distance, c.direction, c.collected, c.total, c.flags, c.challenge),
            history = request.history.map { NpcTurn(if (it.fromPlayer) "runner" else "character", it.text) },
        )
    val text = post("/v1/npc", json.encodeToString(NpcRequestBody.serializer(), body)) { it.string() }
    return json.decodeFromString(NpcReply.serializer(), text).reply
  }

  /** Transcribes a recorded utterance (mono 16 kHz WAV) with Muse Voice on the backend. */
  suspend fun transcribe(wav: ByteArray, keywords: List<String>): String {
    val query =
        if (keywords.isEmpty()) ""
        else "?keywords=" + keywords.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") }
    return call(
        Request.Builder().url(url("/v1/stt$query")).post(wav.toRequestBody("audio/wav".toMediaType())),
        eventToken(),
    ) {
      json.decodeFromString(SttReply.serializer(), it.string()).text
    }
  }

  /** Returns WAV bytes in the character's cloud voice. */
  suspend fun tts(missionId: String, characterId: String, text: String): ByteArray =
      post("/v1/tts", json.encodeToString(TtsRequestBody.serializer(), TtsRequestBody(missionId, characterId, text))) { it.bytes() }

  suspend fun getRoute(missionId: String): RouteOverride? =
      try {
        get("/v1/routes/$missionId") { Mission.json.decodeFromString(RouteOverride.serializer(), it.string()) }
      } catch (e: HttpException) {
        if (e.code == 404) null else throw e
      }

  /**
   * Publishes this phone's placements. Names are left out: they're set on the admin page, and a
   * phone holding an older copy would otherwise undo a rename. The server merges signal by signal.
   */
  suspend fun putRoute(route: RouteOverride, operatorToken: String) {
    val body = Mission.json.encodeToString(RouteOverride.serializer(), route.copy(labels = emptyMap()))
    post("/v1/routes/${route.missionId}", body, method = "PUT", token = operatorToken) {}
  }

  /** The soundtracks published from the admin page. */
  suspend fun getTracks(missionId: String): TrackCatalog =
      get("/v1/tracks/$missionId") { Mission.json.decodeFromString(TrackCatalog.serializer(), it.string()) }

  /** Downloads a deployed stem to [dest]. It only lands there if it matches [sha256]. */
  suspend fun downloadBlob(name: String, sha256: String, dest: File) =
      withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("/v1/blobs/$name")).get()
        eventToken().takeIf { it.isNotBlank() }?.let { request.header("Authorization", "Bearer $it") }
        accountToken().takeIf { it.isNotBlank() }?.let { request.header("X-Account-Token", it) }
        val part = File(dest.parentFile, "${dest.name}.part")
        downloads.newCall(request.build()).execute().use { response ->
          if (!response.isSuccessful) throw HttpException(response.code, "HTTP ${response.code} ${response.message}")
          val digest = MessageDigest.getInstance("SHA-256")
          (response.body ?: throw IOException("empty body")).byteStream().use { input ->
            part.outputStream().use { out ->
              val buf = ByteArray(64 * 1024)
              while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
              }
            }
          }
          val got = digest.digest().joinToString("") { "%02x".format(it) }
          if (!got.equals(sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("$name didn't match its checksum")
          }
          if (!part.renameTo(dest)) throw IOException("couldn't store $name")
        }
      }

  suspend fun broadcasts(sinceMs: Long): List<LiveBroadcast> =
      get("/v1/broadcasts?since=$sinceMs") { json.decodeFromString(BroadcastsResponse.serializer(), it.string()).broadcasts }

  suspend fun sendBroadcast(speaker: String, text: String, operatorToken: String) {
    post("/v1/broadcasts", json.encodeToString(BroadcastBody.serializer(), BroadcastBody(speaker, text)), token = operatorToken) {}
  }

  class HttpException(val code: Int, message: String) : IOException(message)

  private suspend fun <T> get(path: String, read: (okhttp3.ResponseBody) -> T): T =
      call(Request.Builder().url(url(path)).get(), eventToken(), read)

  private suspend fun <T> post(
      path: String,
      body: String,
      method: String = "POST",
      token: String = eventToken(),
      read: (okhttp3.ResponseBody) -> T,
  ): T = call(Request.Builder().url(url(path)).method(method, body.toRequestBody(jsonType)), token, read)

  private suspend fun <T> call(builder: Request.Builder, token: String, read: (okhttp3.ResponseBody) -> T): T =
      withContext(Dispatchers.IO) {
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        accountToken().takeIf { it.isNotBlank() }?.let { builder.header("X-Account-Token", it) }
        http.newCall(builder.build()).execute().use { response ->
          if (!response.isSuccessful) {
            // The server explains itself in {"error": "..."}; show that rather than a status line.
            val said = runCatching { json.parseToJsonElement(response.body?.string() ?: "").jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw HttpException(response.code, said ?: "HTTP ${response.code} ${response.message}")
          }
          read(response.body ?: throw IOException("empty body"))
        }
      }

  private fun url(path: String): String {
    val base = baseUrl().trimEnd('/')
    if (base.isBlank()) throw IOException("backend not configured")
    return base + path
  }
}
