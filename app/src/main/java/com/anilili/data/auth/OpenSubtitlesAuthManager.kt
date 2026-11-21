package com.anilili.data.auth

import android.content.Context
import com.anilili.data.remote.OpenSubtitlesClient
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.raise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OpenSubtitlesCredentials(
    val username: String,
    val password: String,
)

/**
 * Persistent OpenSubtitles account session. OpenSubtitles provides a short-lived login token but
 * no refresh token, so the credentials are encrypted with the same device-bound store used by
 * AniList/MAL and used to renew the API session without asking the viewer to sign in again.
 */
object OpenSubtitlesAuthManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionMutex = Mutex()
    private lateinit var credentialStore: SecureTokenStore
    private lateinit var client: OpenSubtitlesClient
    @Volatile
    private var credentials: OpenSubtitlesCredentials? = null

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn = _loggedIn.asStateFlow()
    private val _username = MutableStateFlow<String?>(null)
    val username = _username.asStateFlow()

    fun init(context: Context, openSubtitlesClient: OpenSubtitlesClient, startupScope: CoroutineScope) {
        client = openSubtitlesClient
        credentialStore = SecureTokenStore(
            context.applicationContext,
            SecureTokenStore.KEY_OPEN_SUBTITLES_CREDENTIALS,
        )
        // #87: the Keystore decrypt moves off Main; the StateFlows below publish what it finds.
        // `credentials` is @Volatile, so the session renewals that read it see the publication.
        restoreOnStartup(StartupTokenService.OPEN_SUBTITLES, startupScope, ::restoreStoredCredentials)
    }

    private fun restoreStoredCredentials() {
        credentials = credentialStore.load()?.let { raw ->
            runCatching { json.decodeFromString(OpenSubtitlesCredentials.serializer(), raw) }
                .getOrNull()
        }
        _username.value = credentials?.username
        _loggedIn.value = credentials != null
    }

    suspend fun signIn(username: String, password: String) {
        val normalizedUsername = username.trim()
        require(normalizedUsername.isNotBlank()) { "Enter your OpenSubtitles username." }
        require(password.isNotBlank()) { "Enter your OpenSubtitles password." }
        withContext(Dispatchers.IO) { client.login(normalizedUsername, password) }
        val saved = OpenSubtitlesCredentials(normalizedUsername, password)
        credentialStore.save(json.encodeToString(OpenSubtitlesCredentials.serializer(), saved))
        credentials = saved
        _username.value = normalizedUsername
        _loggedIn.value = true
        DiagnosticsLog.event("OpenSubtitles account connected")
    }

    /** The stored username and password, for a backup the viewer asked to carry logins. */
    internal fun exportLogin(): Pair<String, String>? =
        credentials?.let { it.username to it.password }

    /**
     * Take on a login restored from a backup, without asking OpenSubtitles about it.
     *
     * Unlike [signIn] there is no network call: these credentials already worked on the device that
     * wrote the backup, and the first search renews the session through [ensureSession] anyway. A
     * password changed since the backup was written therefore surfaces the same way an expired one
     * does — at the search that needs it — rather than failing a restore that has already applied
     * everything else.
     */
    internal fun adoptLogin(username: String, password: String) {
        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank() || password.isBlank()) return
        val saved = OpenSubtitlesCredentials(normalizedUsername, password)
        credentialStore.save(json.encodeToString(OpenSubtitlesCredentials.serializer(), saved))
        client.clearSession()
        credentials = saved
        _username.value = normalizedUsername
        _loggedIn.value = true
        DiagnosticsLog.event("OpenSubtitles account restored from a backup")
    }

    /** Ensures the short-lived REST token exists, renewing it from encrypted credentials. */
    suspend fun ensureSession(): Boolean {
        if (client.isLoggedIn) return true
        return sessionMutex.withLock {
            if (client.isLoggedIn) return@withLock true
            val saved = credentials ?: return@withLock false
            withContext(Dispatchers.IO) {
                runCatching { client.login(saved.username, saved.password) }
                    .onFailure { DiagnosticsLog.throwable("OpenSubtitles session refresh failed", it) }
                    .isSuccess
            }
        }
    }

    /** Runs an authenticated call and transparently renews once if the API rejects an old token. */
    suspend fun <T> withSession(block: () -> T): T {
        // SUB-07 rather than a bare IllegalStateException: this is the one subtitle failure with a
        // different answer — open Settings and sign in — and it used to arrive at the panel as a
        // sentence indistinguishable from "the search didn't come back".
        if (!ensureSession()) {
            AnililiError.SUBTITLE_SIGN_IN_REQUIRED.raise(
                detail = "No OpenSubtitles session could be established for this request.",
            )
        }
        return withContext(Dispatchers.IO) {
            try {
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (client.isLoggedIn || !ensureSession()) throw error
                block()
            }
        }
    }

    fun logout() {
        client.clearSession()
        credentialStore.clear()
        credentials = null
        _username.value = null
        _loggedIn.value = false
        DiagnosticsLog.event("OpenSubtitles account disconnected")
    }
}
