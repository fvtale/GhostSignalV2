package com.ghostsignal.app.net

import com.ghostsignal.core.engine.CharacterRequest
import com.ghostsignal.core.model.Mission
import com.ghostsignal.core.model.RouteOverride
import com.ghostsignal.core.model.TrackCatalog
import java.io.File
import java.io.IOException
import java.net.URLEncoder
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
import okhttp3.ResponseBody

// Wire shapes for the GhostSignal backend. These mirror server/ exactly;
// field names are the API, so they only change together with the server.

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

@Serializable
data class NpcReply(val reply: String, val characterId: String? = null)

@Serializable
data class TtsRequestBody(val missionId: String, val characterId: String, val text: String)

@Serializable
data class LiveBroadcast(val id: String, val speaker: String, val text: String, val createdAtMs: Long)

@Serializable
data class BroadcastsResponse(val broadcasts: List<LiveBroadcast>)

@Serializable
data class BroadcastBody(val speaker: String, val text: String)

@Serializable
data class Health(
    val ok: Boolean,
    val npc: Boolean = false,
    val tts: Boolean = false,
    val model: String? = null,
)

@Serializable
data class AccountCredentials(val email: String, val password: String, val client: String = "app")

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

@Serializable
data class PaymentView(
    val url: String? = null,
    val priceLabel: String = "",
    val manageUrl: String? = null,
)

@Serializable
data class TitleView(val id: String, val title: String, val description: String = "")

/** Glasses linked to an account. [status]: pending, approved, rejected, revoked. */
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

/** What the app knows about the connected pair: sent when linking/enrolling. */
@Serializable
data class GlassesEvidence(
    val identifier: String,
    val deviceType: String,
    val name: String,
    val firmware: String,
    val mock: Boolean = false,
    val note: String = "",
)

@Serializable
data class GlassesLinkReply(val link: GlassesLink)

/** A signed-in player: who they are, their plan, where to pay. [token] arrives on sign-in only. */
@Serializable
data class AccountPlan(
    val token: String? = null,
    val account: AccountView,
    val payment: PaymentView = PaymentView(),
    val premiumTracks: List<TitleView> = emptyList(),
    val glasses: List<GlassesLink> = emptyList(),
)

@Serializable
data class SttReply(val text: String)

@Serializable
data class ProgressBody(val missionId: String, val completions: Int)

@Serializable
data class ProgressReply(val completions: Int)

/**
 * Talks to the GhostSignal backend (see server/). Every call is optional:
 * the game runs fully offline without it, and each feature degrades to a
 * local fallback. Base URL and event token are read live from settings, so
 * changing them on the phone takes effect without a restart.
 */
class BackendClient(
    private val baseUrl: () -> String,
    private val eventToken: () -> String,
) {

  class HttpException(val code: Int, message: String) : IOException(message)

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
      .connectTimeout(5, TimeUnit.SECONDS)
      .readTimeout(15, TimeUnit.SECONDS)
      .callTimeout(20, TimeUnit.SECONDS)
      .build()

  /** Stems are megabytes each: downloads get a patient timeout. */
  private val downloadClient: OkHttpClient =
      httpClient.newBuilder()
          .readTimeout(30, TimeUnit.SECONDS)
          .callTimeout(180, TimeUnit.SECONDS)
          .build()

  private val jsonMediaType = "application/json".toMediaType()

  /** Signed-in session, sent as X-Account-Token so premium stems come through. */
  @Volatile
  var accountToken: () -> String = { "" }

  val configured: Boolean
    get() = baseUrl().isNotBlank()

  // -- accounts -----------------------------------------------------------------

  suspend fun signUp(email: String, password: String): AccountPlan =
      postAccount("/v1/account/signup", AccountCredentials(email.trim(), password))

  suspend fun signIn(email: String, password: String): AccountPlan =
      postAccount("/v1/account/login", AccountCredentials(email.trim(), password))

  suspend fun account(): AccountPlan =
      get("/v1/account/me") { body -> decode(AccountPlan.serializer(), body) }

  suspend fun signOut() {
    post("/v1/account/logout", "{}") { }
  }

  /** Asks to link the connected glasses. Pending until an admin approves. */
  suspend fun linkGlasses(evidence: GlassesEvidence): GlassesLink =
      post("/v1/account/glasses", encode(GlassesEvidence.serializer(), evidence)) { body ->
        decode(GlassesLinkReply.serializer(), body).link
      }

  suspend fun unlinkGlasses(id: String) {
    val request = Request.Builder().url(fullUrl("/v1/account/glasses/$id")).delete()
    execute(request, eventToken()) { }
  }

  /** Admin: vouch for the connected pair so linking it grants premium at once. */
  suspend fun enrollGlasses(evidence: GlassesEvidence, operatorToken: String) {
    post(
        "/v1/admin/glasses/enroll",
        encode(GlassesEvidence.serializer(), evidence),
        token = operatorToken,
    ) { }
  }

  /** Records finished runs; returns the account's count, which never decreases. */
  suspend fun reportCompletions(missionId: String, completions: Int): Int {
    val body = encode(ProgressBody.serializer(), ProgressBody(missionId, completions))
    return post("/v1/account/progress", body) { responseBody ->
      decode(ProgressReply.serializer(), responseBody).completions
    }
  }

  private suspend fun postAccount(path: String, credentials: AccountCredentials): AccountPlan =
      post(path, encode(AccountCredentials.serializer(), credentials)) { body ->
        decode(AccountPlan.serializer(), body)
      }

  // -- game services ----------------------------------------------------------------

  suspend fun health(): Health =
      get("/health") { body -> decode(Health.serializer(), body) }

  suspend fun askCharacter(mission: Mission, request: CharacterRequest): String {
    val context = request.context
    val body = NpcRequestBody(
        missionId = mission.id,
        characterId = request.characterId,
        signalId = request.signalId,
        utterance = request.utterance.take(400),
        context = NpcContext(
            phase = context.phase.name,
            objective = context.objective,
            signalName = context.signalName,
            place = context.place,
            distance = context.distance,
            direction = context.direction,
            collected = context.collected,
            total = context.total,
            flags = context.flags,
            challenge = context.challenge,
        ),
        history = request.history.map { turn ->
          NpcTurn(if (turn.fromPlayer) "runner" else "character", turn.text)
        },
    )
    val replyText = post("/v1/npc", encode(NpcRequestBody.serializer(), body)) { it.string() }
    return json.decodeFromString(NpcReply.serializer(), replyText).reply
  }

  /** Transcribes a recorded utterance (mono 16 kHz WAV) with Muse Voice. */
  suspend fun transcribe(wav: ByteArray, keywords: List<String>): String {
    val query = if (keywords.isEmpty()) {
      ""
    } else {
      "?keywords=" + keywords.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
    }
    val request = Request.Builder()
        .url(fullUrl("/v1/stt$query"))
        .post(wav.toRequestBody("audio/wav".toMediaType()))
    return execute(request, eventToken()) { body ->
      decode(SttReply.serializer(), body).text
    }
  }

  /** Character voice audio. The live server answers 501; clients fall back locally. */
  suspend fun tts(missionId: String, characterId: String, text: String): ByteArray {
    val body = encode(TtsRequestBody.serializer(), TtsRequestBody(missionId, characterId, text))
    return post("/v1/tts", body) { it.bytes() }
  }

  // -- shared content -----------------------------------------------------------------

  suspend fun getRoute(missionId: String): RouteOverride? {
    return try {
      get("/v1/routes/$missionId") { body ->
        Mission.json.decodeFromString(RouteOverride.serializer(), body.string())
      }
    } catch (error: HttpException) {
      if (error.code == 404) null else throw error
    }
  }

  /**
   * Publishes this phone's placements. Labels are deliberately left out:
   * names are set on the admin page, and a phone with an older copy would
   * otherwise undo a rename. The server merges signal by signal.
   */
  suspend fun putRoute(route: RouteOverride, operatorToken: String) {
    val body = Mission.json.encodeToString(
        RouteOverride.serializer(), route.copy(labels = emptyMap()),
    )
    post("/v1/routes/${route.missionId}", body, method = "PUT", token = operatorToken) { }
  }

  /** The soundtracks published from the admin page. */
  suspend fun getTracks(missionId: String): TrackCatalog =
      get("/v1/tracks/$missionId") { body ->
        Mission.json.decodeFromString(TrackCatalog.serializer(), body.string())
      }

  /** Downloads a deployed stem; it only lands at [dest] if the checksum matches. */
  suspend fun downloadBlob(name: String, sha256: String, dest: File) {
    withContext(Dispatchers.IO) {
      val requestBuilder = Request.Builder().url(fullUrl("/v1/blobs/$name")).get()
      eventToken().takeIf { it.isNotBlank() }
          ?.let { requestBuilder.header("Authorization", "Bearer $it") }
      accountToken().takeIf { it.isNotBlank() }
          ?.let { requestBuilder.header("X-Account-Token", it) }

      val partialFile = File(dest.parentFile, "${dest.name}.part")
      downloadClient.newCall(requestBuilder.build()).execute().use { response ->
        if (!response.isSuccessful) {
          throw HttpException(response.code, "HTTP ${response.code} ${response.message}")
        }
        val body = response.body ?: throw IOException("empty body")
        val digest = MessageDigest.getInstance("SHA-256")
        body.byteStream().use { input ->
          partialFile.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val readCount = input.read(buffer)
              if (readCount < 0) break
              digest.update(buffer, 0, readCount)
              output.write(buffer, 0, readCount)
            }
          }
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualSha.equals(sha256, ignoreCase = true)) {
          partialFile.delete()
          throw IOException("$name didn't match its checksum")
        }
        if (!partialFile.renameTo(dest)) throw IOException("couldn't store $name")
      }
    }
  }

  suspend fun broadcasts(sinceMs: Long): List<LiveBroadcast> =
      get("/v1/broadcasts?since=$sinceMs") { body ->
        decode(BroadcastsResponse.serializer(), body).broadcasts
      }

  suspend fun sendBroadcast(speaker: String, text: String, operatorToken: String) {
    val body = encode(BroadcastBody.serializer(), BroadcastBody(speaker, text))
    post("/v1/broadcasts", body, token = operatorToken) { }
  }

  // -- request plumbing -----------------------------------------------------------------

  private fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
      json.encodeToString(serializer, value)

  private fun <T> decode(
      serializer: kotlinx.serialization.KSerializer<T>,
      body: ResponseBody,
  ): T = json.decodeFromString(serializer, body.string())

  private suspend fun <T> get(path: String, read: (ResponseBody) -> T): T =
      execute(Request.Builder().url(fullUrl(path)).get(), eventToken(), read)

  private suspend fun <T> post(
      path: String,
      body: String,
      method: String = "POST",
      token: String = eventToken(),
      read: (ResponseBody) -> T,
  ): T {
    val request = Request.Builder()
        .url(fullUrl(path))
        .method(method, body.toRequestBody(jsonMediaType))
    return execute(request, token, read)
  }

  private suspend fun <T> execute(
      requestBuilder: Request.Builder,
      token: String,
      read: (ResponseBody) -> T,
  ): T = withContext(Dispatchers.IO) {
    if (token.isNotBlank()) requestBuilder.header("Authorization", "Bearer $token")
    accountToken().takeIf { it.isNotBlank() }
        ?.let { requestBuilder.header("X-Account-Token", it) }

    httpClient.newCall(requestBuilder.build()).execute().use { response ->
      if (!response.isSuccessful) {
        // The server explains itself as {"error": "..."}; prefer that text.
        val serverMessage = runCatching {
          val errorBody = response.body?.string() ?: ""
          json.parseToJsonElement(errorBody).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
        throw HttpException(response.code, serverMessage ?: "HTTP ${response.code} ${response.message}")
      }
      read(response.body ?: throw IOException("empty body"))
    }
  }

  private fun fullUrl(path: String): String {
    val base = baseUrl().trimEnd('/')
    if (base.isBlank()) throw IOException("backend not configured")
    return base + path
  }
}
