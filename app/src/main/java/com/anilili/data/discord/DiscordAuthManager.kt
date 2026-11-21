package com.anilili.data.discord

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.anilili.data.auth.SecureTokenStore
import com.anilili.data.auth.StartupTokenService
import com.anilili.data.auth.restoreOnStartup
import com.anilili.diagnostics.DiagnosticsLog
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
internal data class DiscordTokens(
    val accessToken: String,
    val refreshToken: String?,
    /** Epoch millis when the access token stops being usable; 0 when Discord reported no expiry. */
    val expiresAtMs: Long,
)

@Serializable
internal data class DiscordPendingAuth(
    val state: String,
    val verifier: String,
    val redirectUri: String,
)

/** Who the token belongs to, for the settings row to name. */
data class DiscordAccount(
    val id: String,
    val username: String,
    val displayName: String,
)

/**
 * Where Discord is told to send the viewer back to. Both are registered on the application, and
 * which one an authorization uses decides which surface can complete it — so the redirect a
 * session started with has to be the one its token exchange presents, or Discord rejects the code.
 */
enum class DiscordRedirect(val uri: String) {
    /**
     * Discord's documented mobile pattern (one slash, not two — the path is `/authorize/callback`).
     * Handed to the system browser, where the viewer is usually already signed in to Discord and
     * only has to press Authorize. [DiscordOAuthCallbackActivity] catches the answer.
     */
    BROWSER("discord-${DiscordAuthManager.APPLICATION_ID}:/authorize/callback"),

    /**
     * A loopback URL nothing ever really opens: the in-app login WebView recognises it and reads
     * the code straight out of the address, exactly as the AniList and MyAnimeList logins do. This
     * is the only path that works on a television, where there is frequently no browser at all.
     *
     * `127.0.0.1` rather than `localhost` because that is the form Discord documents and the one
     * its portal is known to accept; [DiscordAuthManager] recognises either coming back, so a
     * redirect registered under the other name still completes.
     */
    LOOPBACK("http://127.0.0.1/discord/callback"),
}

/**
 * Discord OAuth (Authorization Code + PKCE `S256`) for rich presence.
 *
 * There is no client secret: the application is registered as a *public client*, which is what
 * lets a shipped app exchange its own codes. That is also the whole reason this is worth doing —
 * the widespread alternative is to ask a viewer for their Discord *account token*, which is a
 * password equivalent that grants read and write access to every message and server they are in.
 * A presence scope grants the ability to set a status and nothing else.
 *
 * The token is stored through [SecureTokenStore], encrypted by a Keystore key, in its own slot.
 */
object DiscordAuthManager {
    /**
     * The Discord application. Not a secret — it is broadcast inside every presence payload, and
     * sits in the authorize URL — so it lives here beside the AniList and MyAnimeList client ids
     * rather than in a private Gradle property.
     */
    const val APPLICATION_ID = "1545324837306634250"

    private const val AUTHORIZE_ENDPOINT = "https://discord.com/oauth2/authorize"
    private const val TOKEN_ENDPOINT = "https://discord.com/api/v10/oauth2/token"
    private const val REVOKE_ENDPOINT = "https://discord.com/api/v10/oauth2/token/revoke"
    private const val CURRENT_USER_ENDPOINT = "https://discord.com/api/v10/users/@me"

    /**
     * `sdk.social_layer_presence` is an umbrella scope: Discord expands it to the set the Social
     * SDK needs, which includes `gateway.connect` and `activities.write` — the two the presence
     * connection in [DiscordGateway] actually rides on. It is the exact string the Social SDK's own
     * `Client::GetDefaultPresenceScopes` returns.
     *
     * The umbrella is wider than this feature is: it puts six lines on the consent screen, among
     * them *Manage your Discord friends list and blocked users*, *Access your Friends list and
     * their online status* and *Send and receive game invites*, none of which this app has code to
     * use. Asking instead for the two that are honestly needed — `identify activities.write`, which
     * renders exactly *Access your username, avatar, and banner* and *Update your current activity*
     * — has been tried, and the redirect comes back `invalid_scope` once Authorize is pressed.
     *
     * That refusal is an entitlement, not a law. `activities.write` is approval-gated: Discord
     * grants it through app verification, to applications that name the scope and justify it, and
     * it leans towards game integrations. Social SDK access licenses the umbrella rather than its
     * members, so this application does not hold it. Seeking verification was considered and
     * deliberately not pursued — the umbrella works today, approval is not assured for an anime
     * client, and even with the scope the activity would still be published over the gateway,
     * whose own `gateway.connect` puts a third line back on the screen.
     *
     * That a consent screen renders is no evidence a scope set can be granted — the authorize
     * endpoint validates scope *names*, and checks entitlement only at Authorize.
     */
    private val SCOPES = listOf("openid", "sdk.social_layer_presence")

    /** Refresh this long before the reported expiry so an in-flight presence update never races it. */
    private const val EXPIRY_MARGIN_MS = 5L * 60 * 1000

    /**
     * Both spellings of the loopback are recognised on the way back. The token exchange presents
     * whichever was registered — it is read from the pending session, not assumed — so this only
     * has to be permissive enough to notice the redirect landing.
     */
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost")

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var pendingStore: SecureTokenStore
    private var httpClient: OkHttpClient? = null
    private val refreshMutex = Mutex()

    private val _tokens = MutableStateFlow<DiscordTokens?>(null)

    /** UI-observable login state; the token value itself never leaves this object. */
    val loggedIn = MutableStateFlow(false)

    private val _account = MutableStateFlow<DiscordAccount?>(null)
    val account = _account.asStateFlow()

    fun init(context: Context, client: OkHttpClient, startupScope: CoroutineScope) {
        tokenStore = SecureTokenStore(context.applicationContext, SecureTokenStore.KEY_DISCORD_TOKENS)
        pendingStore = SecureTokenStore(context.applicationContext, SecureTokenStore.KEY_DISCORD_PENDING_AUTH)
        httpClient = client
        // #87: the Keystore decrypt moves off Main; the StateFlows below publish what it finds.
        // DiscordPresence collects `loggedIn`, so a late publication reaches the presence
        // connection the same way a mid-session sign-in does.
        restoreOnStartup(StartupTokenService.DISCORD, startupScope, ::restoreStoredTokens)
    }

    private fun restoreStoredTokens() {
        _tokens.value = tokenStore.load()?.let { raw ->
            runCatching { json.decodeFromString(DiscordTokens.serializer(), raw) }.getOrNull()
        }
        loggedIn.value = _tokens.value != null
    }

    val isLoggedIn: Boolean get() = _tokens.value != null

    /**
     * Starts an authorization and returns the URL to open.
     *
     * The PKCE material is written to disk rather than held in a field: a browser hand-off can
     * outlive this process — Android is free to kill an app that is no longer on screen — and a
     * verifier lost that way would strand the viewer on a code that can never be exchanged.
     */
    fun authorizeUrl(redirect: DiscordRedirect): String {
        val state = randomUrlSafe(24)
        val verifier = randomUrlSafe(48)
        pendingStore.save(
            json.encodeToString(
                DiscordPendingAuth.serializer(),
                DiscordPendingAuth(state = state, verifier = verifier, redirectUri = redirect.uri),
            ),
        )
        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", APPLICATION_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirect.uri)
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", sha256Challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /** True once a URL is one of this application's registered redirects carrying an answer. */
    fun isRedirect(url: String): Boolean = parseRedirect(url) != null

    /**
     * Completes an authorization from the redirect URL. Returns the signed-in account, or fails
     * with the reason — a mismatched state, Discord's own `error_description`, or a rejected
     * exchange. The pending PKCE material is spent either way, so a stale redirect replayed later
     * cannot mint a second session.
     */
    suspend fun completeAuthorization(url: String): Result<DiscordAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val redirect = requireNotNull(parseRedirect(url)) { "Not a Discord redirect" }
            val pending = requireNotNull(loadPending()) { "No Discord login in progress" }
            pendingStore.clear()

            require(redirect.getQueryParameter("state") == pending.state) {
                "Discord authorization state mismatch"
            }
            redirect.getQueryParameter("error")?.let { error ->
                error(redirect.getQueryParameter("error_description") ?: error)
            }
            val code = requireNotNull(redirect.getQueryParameter("code")?.takeIf { it.isNotBlank() }) {
                "Discord returned no authorization code"
            }

            val body = FormBody.Builder()
                .add("client_id", APPLICATION_ID)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", pending.redirectUri)
                .add("code_verifier", pending.verifier)
                .build()
            store(requestTokens(body))
            DiagnosticsLog.event(
                category = "auth",
                name = "discord.token_exchange",
                attributes = mapOf("redirect" to pending.redirectUri.substringBefore(':')),
            )
            val signedIn = runCatching { fetchAccount() }.getOrNull()
            _account.value = signedIn
            signedIn ?: DiscordAccount(id = "", username = "", displayName = "Connected")
        }.onFailure { error ->
            DiagnosticsLog.throwable("Discord authorization failed", error)
        }
    }

    /**
     * A currently valid access token, refreshed when the stored one is within [EXPIRY_MARGIN_MS]
     * of expiry. Null when signed out. A refresh that fails only ends the session once the old
     * token is genuinely dead — a presence update attempted on a flaky network should not sign
     * anybody out.
     */
    suspend fun freshAccessToken(): String? {
        val current = _tokens.value ?: return null
        val now = System.currentTimeMillis()
        if (current.expiresAtMs == 0L || now < current.expiresAtMs - EXPIRY_MARGIN_MS) {
            return current.accessToken
        }
        return refreshMutex.withLock {
            val latest = _tokens.value ?: return@withLock null
            if (latest.expiresAtMs != 0L && System.currentTimeMillis() < latest.expiresAtMs - EXPIRY_MARGIN_MS) {
                return@withLock latest.accessToken
            }
            val refreshToken = latest.refreshToken ?: return@withLock latest.accessToken
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder()
                    .add("client_id", APPLICATION_ID)
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build()
                runCatching { requestTokens(body) }
                    .onSuccess { store(it) }
                    .onFailure { error ->
                        DiagnosticsLog.throwable("Discord token refresh failed", error)
                        if (System.currentTimeMillis() >= latest.expiresAtMs) logout()
                    }
                _tokens.value?.accessToken
            }
        }
    }

    /** The signed-in account, fetched on demand so the settings row can name who is connected. */
    suspend fun fetchAccount(): DiscordAccount? = withContext(Dispatchers.IO) {
        val token = freshAccessToken() ?: return@withContext null
        val client = httpClient ?: return@withContext null
        val request = Request.Builder()
            .url(CURRENT_USER_ENDPOINT)
            .header("Authorization", "Bearer $token")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Discord users/@me HTTP ${response.code}: ${text.take(200)}")
                }
                val parsed = json.decodeFromString(DiscordUserResponse.serializer(), text)
                val id = parsed.id ?: parsed.sub.orEmpty()
                val username = parsed.username ?: parsed.preferredUsername ?: id
                DiscordAccount(
                    id = id,
                    username = username,
                    displayName = parsed.globalName ?: parsed.nickname ?: parsed.name ?: username,
                )
            }
        }.onSuccess { _account.value = it }
            .onFailure { DiagnosticsLog.throwable("Discord account lookup failed", it) }
            .getOrNull()
    }

    /**
     * Signs out and asks Discord to invalidate the token as well, so revoking here is not merely
     * this device forgetting it. The local state is dropped whether or not that call lands.
     */
    suspend fun logout() {
        val revoked = _tokens.value?.accessToken
        tokenStore.clear()
        pendingStore.clear()
        _tokens.value = null
        _account.value = null
        loggedIn.value = false
        if (revoked == null) return
        withContext(Dispatchers.IO) {
            val client = httpClient ?: return@withContext
            val body = FormBody.Builder()
                .add("client_id", APPLICATION_ID)
                .add("token", revoked)
                .add("token_type_hint", "access_token")
                .build()
            runCatching {
                client.newCall(Request.Builder().url(REVOKE_ENDPOINT).post(body).build())
                    .execute()
                    .close()
            }.onFailure { DiagnosticsLog.throwable("Discord token revocation failed", it) }
        }
    }

    /** Abandons a login the viewer backed out of, so its verifier cannot be spent later. */
    fun cancelPendingAuthorization() {
        if (::pendingStore.isInitialized) pendingStore.clear()
    }

    private fun loadPending(): DiscordPendingAuth? = pendingStore.load()?.let { raw ->
        runCatching { json.decodeFromString(DiscordPendingAuth.serializer(), raw) }.getOrNull()
    }

    /**
     * The redirect as a URI, or null when the URL is some other page.
     *
     * Both registered forms are recognised because either surface may be the one that finishes:
     * the custom scheme carries its answer on a single-slash path, so `Uri` reports the whole
     * `/authorize/callback` as the path with no authority at all.
     */
    private fun parseRedirect(url: String): Uri? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val answered = uri.getQueryParameter("code") != null || uri.getQueryParameter("error") != null
        if (!answered) return null
        val browser = uri.scheme == "discord-$APPLICATION_ID" && uri.path == "/authorize/callback"
        val loopback = (uri.scheme == "http" || uri.scheme == "https") &&
            uri.host in LOOPBACK_HOSTS && uri.path == "/discord/callback"
        return uri.takeIf { browser || loopback }
    }

    private fun requestTokens(body: FormBody): DiscordTokens {
        val client = requireNotNull(httpClient) { "DiscordAuthManager not initialised" }
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("Discord token endpoint HTTP ${response.code}: ${text.take(200)}")
            }
            val parsed = json.decodeFromString(DiscordTokenResponse.serializer(), text)
            return DiscordTokens(
                accessToken = parsed.accessToken,
                refreshToken = parsed.refreshToken,
                expiresAtMs = if (parsed.expiresIn > 0) {
                    System.currentTimeMillis() + parsed.expiresIn * 1000
                } else {
                    0L
                },
            )
        }
    }

    private fun store(fresh: DiscordTokens) {
        // Discord does not always return a refresh token on a refresh; keeping the previous one is
        // what stops a single silent response from making the session unrenewable.
        val merged = fresh.copy(refreshToken = fresh.refreshToken ?: _tokens.value?.refreshToken)
        tokenStore.save(json.encodeToString(DiscordTokens.serializer(), merged))
        _tokens.value = merged
        loggedIn.value = true
    }

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes)
        .also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    /**
     * `android.util.Base64` rather than `java.util.Base64`, which is API 26 and would throw on
     * every Fire TV stick this app still supports.
     */
    internal fun sha256Challenge(verifier: String): String = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(Charsets.US_ASCII))
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
}

@Serializable
private data class DiscordTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 0,
)

@Serializable
private data class DiscordUserResponse(
    @SerialName("id") val id: String? = null,
    @SerialName("sub") val sub: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("preferred_username") val preferredUsername: String? = null,
    @SerialName("global_name") val globalName: String? = null,
    @SerialName("nickname") val nickname: String? = null,
    @SerialName("name") val name: String? = null,
)
