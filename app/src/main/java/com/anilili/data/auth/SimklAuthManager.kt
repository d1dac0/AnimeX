package com.anilili.data.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.anilili.BuildConfig
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.AnililiFailure
import com.anilili.errors.toFailure
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
internal data class SimklTokens(
    val accessToken: String,
    val refreshToken: String,
    /** Epoch millis when the access token stops being usable. */
    val expiresAtMs: Long,
    /** The Simkl account name, for the settings row; null until the first lookup lands. */
    val username: String? = null,
)

@Serializable
internal data class SimklPendingAuth(val state: String, val verifier: String)

/** A device-flow code to show on a television, and what it polls with. */
data class SimklDeviceCode(
    /** `XXXX-YYYY`, shown exactly as Simkl returned it. */
    val userCode: String,
    val verificationUri: String,
    /** The same page with the code filled in — what the QR code carries. */
    val verificationUriComplete: String,
    internal val deviceCode: String,
    val expiresAtMs: Long,
    internal val intervalSeconds: Int,
)

/** Where a sign-in started from the browser stands, for the settings row to show. */
sealed interface SimklSignIn {
    data object Idle : SimklSignIn
    data object Working : SimklSignIn
    data class Connected(val username: String?) : SimklSignIn
    data class Failed(val failure: AnililiFailure) : SimklSignIn
}

/**
 * Simkl sign-in (AUTH V2) for the tracker that runs alongside AniList, MyAnimeList or neither.
 *
 * Simkl is not one of the app's list services — [AccountService] still allows exactly one of
 * those. It only *receives* what the viewer does here: watched episodes, saves and status moves. So
 * nothing about signing in or out of it touches the local library.
 *
 * The app is registered as a "Mobile, desktop & browser" client: there is no secret, and both
 * flows run from the one client id. A phone uses the browser with PKCE `S256` and comes back
 * through `anilili://auth/simkl`; a television shows a code for simkl.com/pin instead, because a
 * TV browser is usually missing or a WebView shell, and Simkl says embedded WebViews fail its
 * Google and e-mail sign-ins.
 *
 * Access tokens last 7 days. Refresh tokens last 180, slide forward on every use and do *not*
 * rotate — but a refresh kills the previous access token at once, so refreshes are serialised.
 */
object SimklAuthManager {
    const val CLIENT_ID = "6cb9e1ceb75ce68500b9c2a673a92a24c0fbbde1fe524c1b55b7540352cb090f"

    /** Registered on the app; the token exchange must present it byte for byte. */
    const val REDIRECT_URI = "anilili://auth/simkl"

    /** The `app-name` every API call carries, so Simkl can tell this app's traffic apart. */
    const val APP_NAME = "anilili"

    private const val AUTHORIZE_ENDPOINT = "https://simkl.com/oauth2/authorize"
    private const val TOKEN_ENDPOINT = "https://api.simkl.com/oauth2/token"
    private const val DEVICE_ENDPOINT = "https://api.simkl.com/oauth2/device"
    private const val REVOKE_ENDPOINT = "https://api.simkl.com/oauth2/revoke"

    /** The callback's `iss`; anything else is refused, as Simkl's docs require. */
    private const val ISSUER = "https://simkl.com"

    /**
     * Without a scope Simkl grants `media:read` only, and an unrecognised scope string is
     * downgraded to read-only rather than refused — so the granted scope is checked on the way back.
     */
    private const val SCOPE = "media:read media:write"
    private const val WRITE_SCOPE = "media:write"

    /** Refresh this long before the reported expiry so in-flight calls never race it. */
    private const val EXPIRY_MARGIN_MS = 10L * 60 * 1000

    /**
     * A declined device code is never reported: Simkl keeps answering `authorization_pending`
     * until the code dies at 15 minutes. Its docs ask for a shorter timeout of our own.
     */
    private const val DEVICE_TIMEOUT_MS = 10L * 60 * 1000

    val userAgent: String get() = "anilili/${BuildConfig.VERSION_NAME}"

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var pendingStore: SecureTokenStore
    private var httpClient: OkHttpClient? = null
    private val refreshMutex = Mutex()

    private val _tokens = MutableStateFlow<SimklTokens?>(null)

    /** UI-observable login state; the token itself never leaves this object and its client. */
    val loggedIn = MutableStateFlow(false)

    private val _username = MutableStateFlow<String?>(null)
    val username = _username.asStateFlow()

    private val _signIn = MutableStateFlow<SimklSignIn>(SimklSignIn.Idle)

    /**
     * The browser sign-in's progress. It is finished by [AnililiLinkActivity][com.anilili.AnililiLinkActivity]
     * rather than by a screen, because the browser may come back after this process was killed and
     * Settings is not necessarily what is on screen when it does.
     */
    val signIn = _signIn.asStateFlow()

    fun init(context: Context, client: OkHttpClient, startupScope: CoroutineScope) {
        tokenStore = SecureTokenStore(context.applicationContext, SecureTokenStore.KEY_SIMKL_TOKENS)
        pendingStore = SecureTokenStore(context.applicationContext, SecureTokenStore.KEY_SIMKL_PENDING_AUTH)
        httpClient = client
        // #87: the Keystore decrypt moves off Main; the StateFlows below publish whatever it finds.
        restoreOnStartup(StartupTokenService.SIMKL, startupScope, ::restoreStoredTokens)
    }

    private fun restoreStoredTokens() {
        _tokens.value = tokenStore.load()?.let { raw ->
            runCatching { json.decodeFromString(SimklTokens.serializer(), raw) }.getOrNull()
        }
        loggedIn.value = _tokens.value != null
        _username.value = _tokens.value?.username
    }

    val isLoggedIn: Boolean get() = _tokens.value != null

    // ---- browser (phone) ----

    /**
     * Starts a browser sign-in and returns the URL to open. The PKCE material goes to disk, not a
     * field: Android may kill this process while the viewer is away in the browser.
     */
    fun authorizeUrl(): String {
        val state = randomUrlSafe(24)
        val verifier = randomUrlSafe(48)
        pendingStore.save(
            json.encodeToString(SimklPendingAuth.serializer(), SimklPendingAuth(state, verifier)),
        )
        _signIn.value = SimklSignIn.Idle
        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", sha256Challenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /** True for `anilili://auth/simkl…` — the callback, answered or not. */
    fun isRedirect(url: String): Boolean = isSimklRedirect(url)

    /** Finishes a browser sign-in in the background; [signIn] reports how it went. */
    fun handleRedirect(url: String) {
        _signIn.value = SimklSignIn.Working
        scope.launch {
            _signIn.value = runCatching { completeAuthorization(url) }
                .fold(
                    onSuccess = { SimklSignIn.Connected(it) },
                    onFailure = { error ->
                        SimklSignIn.Failed(error.toFailure(AnililiError.ACCOUNT_TRACKER_CONNECT_FAILED, "Simkl"))
                    },
                )
        }
    }

    /** Clears a finished sign-in's message once it has been shown. */
    fun acknowledgeSignIn() {
        if (_signIn.value !is SimklSignIn.Working) _signIn.value = SimklSignIn.Idle
    }

    /**
     * The pending PKCE material is spent whether or not this succeeds, so a stale callback replayed
     * later cannot mint a second session.
     */
    private suspend fun completeAuthorization(url: String): String? {
        val pending = requireNotNull(loadPending()) { "No Simkl sign-in was in progress." }
        pendingStore.clear()
        val uri = Uri.parse(url)
        require(uri.getQueryParameter("state") == pending.state) { "Simkl sign-in state mismatch." }
        uri.getQueryParameter("error")?.let { error ->
            error(if (error == "access_denied") "Simkl access was not approved." else "Simkl refused the sign-in ($error).")
        }
        require(uri.getQueryParameter("iss") == ISSUER) { "The sign-in did not come from Simkl." }
        val code = requireNotNull(uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }) {
            "Simkl returned no authorization code."
        }
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", pending.verifier)
            .build()
        adopt(requestTokens(body))
        DiagnosticsLog.event(category = "auth", name = "simkl.token_exchange", attributes = mapOf("flow" to "browser"))
        return refreshUsername()
    }

    // ---- device (television) ----

    /** Asks Simkl for a code to show. Throws on failure. */
    suspend fun startDeviceSignIn(): SimklDeviceCode = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("scope", SCOPE)
            .build()
        val (code, text) = post(DEVICE_ENDPOINT, body)
        if (code !in 200..299) error("Simkl device code HTTP $code: ${text.take(200)}")
        val parsed = json.decodeFromString(SimklDeviceResponse.serializer(), text)
        DiagnosticsLog.event(category = "auth", name = "simkl.device_code")
        SimklDeviceCode(
            userCode = parsed.userCode,
            verificationUri = parsed.verificationUri,
            verificationUriComplete = parsed.verificationUriComplete ?: parsed.verificationUri,
            deviceCode = parsed.deviceCode,
            expiresAtMs = System.currentTimeMillis() + minOf(parsed.expiresIn * 1000L, DEVICE_TIMEOUT_MS),
            intervalSeconds = parsed.interval.coerceAtLeast(5),
        )
    }

    /**
     * Polls until the code is approved, returning the account name; throws when the code expires or
     * Simkl refuses. Cancel the calling coroutine to give up.
     */
    suspend fun awaitDeviceApproval(device: SimklDeviceCode): String? {
        var interval = device.intervalSeconds
        while (System.currentTimeMillis() < device.expiresAtMs) {
            delay(interval * 1000L)
            val body = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("client_id", CLIENT_ID)
                .add("device_code", device.deviceCode)
                .build()
            val (code, text) = try {
                withContext(Dispatchers.IO) { post(TOKEN_ENDPOINT, body) }
            } catch (error: IOException) {
                // One dropped poll on a flaky connection is not a failed sign-in.
                DiagnosticsLog.throwable("Simkl device poll failed", error)
                continue
            }
            if (code in 200..299) {
                adopt(tokensFrom(text))
                DiagnosticsLog.event(category = "auth", name = "simkl.token_exchange", attributes = mapOf("flow" to "device"))
                return refreshUsername().also { _signIn.value = SimklSignIn.Connected(it) }
            }
            when (oauthError(text)) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "expired_token" -> throw SimklCodeExpiredException()
                else -> error("Simkl refused the code (HTTP $code ${oauthError(text) ?: ""}).")
            }
        }
        throw SimklCodeExpiredException()
    }

    // ---- tokens ----

    /**
     * A currently valid access token, refreshed when the stored one is close to expiry. Null when
     * signed out. A refresh that fails on the network keeps the session; one Simkl refuses means
     * the grant is gone (revoked in Connected Apps, or past 180 days) and signs out.
     */
    suspend fun freshAccessToken(): String? {
        val current = _tokens.value ?: return null
        if (System.currentTimeMillis() < current.expiresAtMs - EXPIRY_MARGIN_MS) return current.accessToken
        return refresh(current.accessToken)
    }

    /**
     * Called after a 401 with the token that was refused. If another call already refreshed past
     * it, the newer token is returned without a second refresh — a refresh kills the access token
     * before it, so two racing refreshes would each strand the other's requests.
     */
    suspend fun refreshAfterRejection(rejected: String): String? = refresh(rejected)

    private suspend fun refresh(stale: String): String? = refreshMutex.withLock {
        val latest = _tokens.value ?: return@withLock null
        if (latest.accessToken != stale &&
            System.currentTimeMillis() < latest.expiresAtMs - EXPIRY_MARGIN_MS
        ) {
            return@withLock latest.accessToken
        }
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("refresh_token", latest.refreshToken)
                .build()
            try {
                val (code, text) = post(TOKEN_ENDPOINT, body)
                if (code in 200..299) {
                    store(tokensFrom(text).copy(username = latest.username))
                    DiagnosticsLog.event(category = "auth", name = "simkl.token_refresh")
                } else if (code in 400..499 && code != 429) {
                    DiagnosticsLog.event(
                        category = "auth",
                        name = "simkl.grant_gone",
                        attributes = mapOf("http" to code, "error" to oauthError(text)),
                    )
                    clearLocal()
                } else {
                    DiagnosticsLog.event("Simkl token refresh HTTP $code; keeping the session")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The network, or an answer that could not be read: neither says the grant is gone.
                DiagnosticsLog.throwable("Simkl token refresh failed; keeping the session", error)
            }
            _tokens.value?.accessToken
        }
    }

    /** Names the account in Settings. Never fails the sign-in it follows. */
    suspend fun refreshUsername(): String? {
        val name = runCatching { com.anilili.data.remote.SimklClient.accountName() }
            .onFailure { DiagnosticsLog.throwable("Simkl account lookup failed", it) }
            .getOrNull()
            ?: return _username.value
        _tokens.value?.let { store(it.copy(username = name)) }
        return name
    }

    /**
     * Signs out here and revokes the grant with Simkl too, so disconnecting is not merely this
     * device forgetting a token. Revoke always answers 200, so there is nothing to check.
     */
    suspend fun logout() {
        val token = _tokens.value?.refreshToken
        clearLocal()
        if (token == null) return
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("token", token)
                .build()
            runCatching { post(REVOKE_ENDPOINT, body) }
                .onFailure { DiagnosticsLog.throwable("Simkl token revocation failed", it) }
        }
    }

    private fun clearLocal() {
        tokenStore.clear()
        pendingStore.clear()
        _tokens.value = null
        _username.value = null
        loggedIn.value = false
        _signIn.value = SimklSignIn.Idle
    }

    /** A new grant simply replaces any earlier one: nothing local depends on which account it is. */
    private fun adopt(fresh: SimklTokens) = store(fresh)

    private fun store(fresh: SimklTokens) {
        tokenStore.save(json.encodeToString(SimklTokens.serializer(), fresh))
        _tokens.value = fresh
        _username.value = fresh.username
        loggedIn.value = true
    }

    private fun tokensFrom(text: String): SimklTokens {
        val parsed = json.decodeFromString(SimklTokenResponse.serializer(), text)
        require(parsed.scope == null || WRITE_SCOPE in parsed.scope.split(' ')) {
            "Simkl granted read-only access, so nothing could be tracked."
        }
        return SimklTokens(
            accessToken = parsed.accessToken,
            refreshToken = parsed.refreshToken,
            expiresAtMs = System.currentTimeMillis() + parsed.expiresIn * 1000,
        )
    }

    private fun requestTokens(body: FormBody): SimklTokens {
        val (code, text) = post(TOKEN_ENDPOINT, body)
        if (code !in 200..299) {
            error("Simkl token endpoint HTTP $code${oauthError(text)?.let { " ($it)" } ?: ""}")
        }
        return tokensFrom(text)
    }

    private fun post(url: String, body: FormBody): Pair<Int, String> {
        val client = requireNotNull(httpClient) { "SimklAuthManager not initialised" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .post(body)
            .build()
        return client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
    }

    private fun oauthError(text: String): String? =
        runCatching { json.decodeFromString(SimklOAuthError.serializer(), text).error }.getOrNull()

    private fun loadPending(): SimklPendingAuth? = pendingStore.load()?.let { raw ->
        runCatching { json.decodeFromString(SimklPendingAuth.serializer(), raw) }.getOrNull()
    }

    private fun randomUrlSafe(bytes: Int): String = ByteArray(bytes)
        .also(SecureRandom()::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    /** `android.util.Base64`: `java.util.Base64` is API 26 and this app still runs on 22. */
    private fun sha256Challenge(verifier: String): String = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(Charsets.US_ASCII))
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
}

/** The device code ran out before anyone approved it — Simkl never reports a decline. */
class SimklCodeExpiredException : IllegalStateException("The Simkl code expired before it was approved")

/**
 * `anilili://auth/simkl`, with or without its query. Plain string work rather than `Uri`, so the
 * link reader's unit tests can use it on the JVM.
 */
internal fun isSimklRedirect(url: String): Boolean {
    val bare = url.substringBefore('?').substringBefore('#').trimEnd('/')
    return bare.equals(SimklAuthManager.REDIRECT_URI, ignoreCase = true)
}

@Serializable
private data class SimklTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 604_800,
    @SerialName("scope") val scope: String? = null,
)

@Serializable
private data class SimklDeviceResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String = "https://simkl.com/pin",
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 900,
    @SerialName("interval") val interval: Int = 5,
)

@Serializable
private data class SimklOAuthError(@SerialName("error") val error: String? = null)
