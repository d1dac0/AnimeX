package com.anilili.data.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The login tokens a process start has to decrypt, and where each one is decrypted (#87).
 *
 * Reading a stored token costs a Keystore round-trip — `KeyStore.load` plus `getKey` plus a cipher
 * init are binder calls — and five managers used to pay it on the main thread inside
 * Application.onCreate. Only the AniList token is needed for the first Home frame: the profile row
 * and every authenticated AniList request read it immediately. The MyAnimeList, Simkl,
 * OpenSubtitles and Discord tokens are first consulted when a settings row draws, a tracker sync
 * runs or a subtitle search starts, seconds after launch, so their decrypts run in the app's
 * startup scope instead and publish through the managers' StateFlows when they land — the UI
 * observes the late publication exactly as it observes a sign-in made while the app is open.
 */
enum class StartupTokenService {
    ANILIST,
    MAL,
    SIMKL,
    OPEN_SUBTITLES,
    DISCORD,
}

object StartupTokenPolicy {
    /** True when [service]'s stored token is decrypted on the startup main thread. */
    fun decryptsOnStartupMainThread(service: StartupTokenService): Boolean = when (service) {
        StartupTokenService.ANILIST -> true
        StartupTokenService.MAL,
        StartupTokenService.SIMKL,
        StartupTokenService.OPEN_SUBTITLES,
        StartupTokenService.DISCORD -> false
    }
}

/**
 * Restores [restore]'s stored session where [StartupTokenPolicy] puts it: on the calling thread
 * for the AniList token, or in [startupScope] — MiruroApp's, which is already Dispatchers.IO —
 * for the rest. [startupScope] is written on Main before this runs and read only by the launched
 * coroutine, so the hand-off is ordered.
 */
internal fun restoreOnStartup(
    service: StartupTokenService,
    startupScope: CoroutineScope,
    restore: () -> Unit,
) {
    if (StartupTokenPolicy.decryptsOnStartupMainThread(service)) restore() else startupScope.launch { restore() }
}
