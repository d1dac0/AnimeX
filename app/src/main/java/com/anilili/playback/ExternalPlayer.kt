package com.anilili.playback

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.report

/**
 * Hands an episode to another video player installed on the device.
 *
 * This is the answer to "this stream will not play here" that does not require diagnosing why. A
 * source that defeats Media3 — an unusual codec, a container it reads badly, audio the device has no
 * decoder for — will often play in mpv or VLC, which carry their own decoders. It matters most on
 * TV, where the hardware is weakest and the ways around a bad stream are fewest.
 *
 * There is no agreed way to pass any of this. Every player invented its own extras and they do not
 * overlap, so the request carries all of the spellings at once and each player reads the ones it
 * knows; unknown extras are ignored by everyone. The spellings below are the documented ones for MX
 * Player, VLC, Just Player, Vimu, mpv-android and Nova.
 *
 * mpvNova needs no spelling of its own: it is a fork of mpv-android and its trampoline copies a
 * fixed allowlist of extras through to the real player, which already contains every mpv-android
 * name sent here — `title`, `position`, `startfrom`, `from_start`, `return_result`, `subs`,
 * `subs.name`, `subs.enable` and `subtitles_location`. Its `headers` is *not* on that list, so a
 * gated source reaches it only through the loopback proxy below; that is the path every player
 * takes anyway, so nothing is lost. Which player receives the episode is [ExternalPlayerTarget].
 */
internal data class ExternalPlaybackRequest(
    val url: String,
    val title: String,
    val streamType: String,
    val resumePositionMs: Long,
    val headers: Map<String, String>,
    val subtitles: List<SubtitleItem>,
)

internal fun externalPlaybackRequest(
    stream: StreamItem,
    seriesTitle: String,
    episodeTitle: String,
    resumePositionMs: Long,
    subtitles: List<SubtitleItem>,
    userAgent: String = NATIVE_PLAYBACK_USER_AGENT,
): ExternalPlaybackRequest = ExternalPlaybackRequest(
    url = stream.url,
    title = externalPlayerTitle(seriesTitle, episodeTitle),
    streamType = stream.type,
    resumePositionMs = resumePositionMs,
    headers = mediaRequestHeaders(
        referer = stream.referer,
        userAgent = userAgent,
        extraHeaders = stream.headers,
    ) + mapOf("User-Agent" to userAgent),
    subtitles = subtitles,
)

/** What the receiving player shows as the title. Blank parts are dropped rather than left dangling. */
internal fun externalPlayerTitle(seriesTitle: String, episodeTitle: String): String =
    listOf(seriesTitle, episodeTitle)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(" · ")

/**
 * The mime type to declare on the intent.
 *
 * Deliberately coarser than [com.anilili.ui.watch.videoMimeType]: that one steers Media3's own
 * extractor choice, whereas this only has to be broad enough that the target player's intent filter
 * matches. The wildcard video type is the safe answer for anything not obviously a named container,
 * because a player that declines an exact type it does not recognise never gets the chance to try.
 *
 * **The type must always be in the `video/` family, never a manifest type.** VLC routes an incoming
 * `ACTION_VIEW` on `intent.type?.startsWith("video")` alone: a `video/…` type reaches
 * `VideoPlayerActivity`, and everything else falls through to a background "open with no UI" path
 * that opens no player and reports no error. So declaring the correct `application/x-mpegURL` for
 * HLS — which is nearly every stream here — meant VLC came to the front showing its library and
 * simply never played, which is exactly what it looked like from the outside. No player needs the
 * manifest type to demux a manifest; they all sniff the stream themselves.
 */
internal fun externalPlayerMimeType(url: String, streamType: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val isManifest = streamType.equals("hls", true) || path.endsWith(".m3u8") ||
        streamType.equals("dash", true) || streamType.equals("mpd", true) || path.endsWith(".mpd")
    return when {
        // Named ahead of the extensions so a manifest is never labelled by whatever its path
        // happens to end in; a manifest is not a container and only the wildcard describes it.
        isManifest -> "video/*"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".webm") -> "video/webm"
        else -> "video/*"
    }
}

/**
 * Headers as the flat name, value, name, value… array MX Player's `headers` extra expects, which
 * the players copying its API read the same way.
 *
 * Not `Name: value` lines. Measured on MX Player 3.3.0 against a server logging what arrived: two
 * lines became one garbage header (the first line as its name, the second as its value), and an
 * odd count — Referer, Origin and User-Agent, the usual three — was dropped entirely.
 */
internal fun externalPlayerHeaderPairs(headers: Map<String, String>): Array<String> =
    headers.entries
        .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
        .flatMap { (key, value) -> listOf(key, value) }
        .toTypedArray()

/**
 * Builds the launch intent.
 *
 * The subtitle URIs are also put in [ClipData], which looks redundant beside the extras and is not:
 * `FLAG_GRANT_READ_URI_PERMISSION` only covers the intent's own data URI, never an extra. Without
 * the clip an imported `content:` subtitle reaches the other player as a URI it is not allowed to
 * open, and it silently plays with no subtitles at all.
 */
internal fun externalPlayerIntent(
    context: Context,
    request: ExternalPlaybackRequest,
    /** Launched for a result ([ExternalPlaybackReturns]), which a new task would cancel at once. */
    forResult: Boolean = false,
): Intent = Intent(Intent.ACTION_VIEW).apply {
    // Handed over through the loopback proxy, which makes the real request with this app's headers
    // attached. The header extras below are still sent for the players that read them, but they are
    // no longer what makes a gated source work — VLC reads none of them and used to get a flat 403.
    val uri = externalShareUri(context, LocalStreamProxy.publish(context, request.url, request.headers))
    setDataAndType(uri, externalPlayerMimeType(request.url, request.streamType))
    addCategory(Intent.CATEGORY_DEFAULT)
    if (!forResult) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // An episode saved to a folder the viewer picked is a document this app holds a grant on and
    // the other player does not. Passing the grant along costs nothing for the http URLs that make
    // up every other hand-off, and is the whole difference for that one.
    if (uri.scheme == "content") addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    putExtra(Intent.EXTRA_TITLE, request.title)
    putExtra("title", request.title)
    putExtra("forcename", request.title) // Vimu

    if (request.resumePositionMs > 0L) {
        val positionMs = request.resumePositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        putExtra("position", positionMs) // MX Player, Just Player, mpv-android
        putExtra("startfrom", positionMs) // Vimu
        putExtra("forceresume", true) // Vimu declines to resume a network stream without this
        putExtra("from_start", false) // VLC
    }

    // Asks the player to hand back where it stopped. MX Player needs it; the rest ignore it.
    putExtra("return_result", true)

    val headerPairs = externalPlayerHeaderPairs(request.headers)
    if (headerPairs.isNotEmpty()) {
        // MX Player, mpv-android and Just Player read this array. It still does not make the
        // proxy optional, even for them: MX's FFmpeg prober sends these, but its hardware engine,
        // the one that streams the segments, sends only its User-Agent. VLC reads no header extra
        // at all — its whole external contract is item_location, subtitles_location, title,
        // from_start, position, opened_position and disable_hardware.
        putExtra("headers", headerPairs)
        request.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let {
            putExtra("http-referrer", it.value)
        }
        request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let {
            putExtra("http-user-agent", it.value)
        }
    }

    // Subtitles go through the proxy too. They are fetched by the other player from the same
    // gated hosts as the media, and a sidecar track that 403s fails as quietly as the video did.
    // A subtitle the viewer imported is a `content:` URI, which the proxy passes back untouched.
    val userAgent = request.headers.entries
        .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
        ?.value
        ?: NATIVE_PLAYBACK_USER_AGENT
    val subtitleUris = request.subtitles.map { subtitle ->
        val subtitleHeaders = mediaRequestHeaders(
            referer = subtitle.referer,
            userAgent = userAgent,
            extraHeaders = subtitle.headers,
        ) + mapOf("User-Agent" to userAgent)
        externalShareUri(context, LocalStreamProxy.publish(context, subtitle.url, subtitleHeaders))
    }
    if (subtitleUris.isNotEmpty()) {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData(
            "subtitles",
            arrayOf("application/x-subrip", "text/vtt"),
            ClipData.Item(subtitleUris.first()),
        ).apply { subtitleUris.drop(1).forEach { addItem(ClipData.Item(it)) } }

        val names = request.subtitles.map(SubtitleItem::label).toTypedArray()
        // MX Player, mpv-android, Nova
        putExtra("subs", subtitleUris.toTypedArray())
        putExtra("subs.name", names)
        putExtra("subs.enable", arrayOf(subtitleUris.first()))
        // Just Player
        putExtra("subtitle_uri", subtitleUris.toTypedArray())
        putExtra("subtitle_name", names)
        // VLC and Vimu each take one file only
        putExtra("subtitles_location", subtitleUris.first().toString())
        putExtra("forcedsrt", subtitleUris.first().toString())
    }
}

/** Internal playback may use file URIs; another app needs a scoped, grantable URI. */
internal fun externalShareUri(context: Context, url: String): Uri {
    val uri = Uri.parse(url)
    if (!uri.scheme.equals("file", ignoreCase = true)) return uri
    require(uri.authority.isNullOrEmpty()) { "Cannot share a file with a remote authority." }
    val file = File(requireNotNull(uri.path) { "The shared file has no path." }).canonicalFile
    require(file.isFile) { "The shared file is no longer available." }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Where the other player stopped, from whatever extra it chose to report it in.
 *
 * MX Player, Just Player, mpv-android and Vimu return integers; VLC returns a long under a name of
 * its own. Anything else returns nothing, which is not an error — it just means the watch position
 * cannot be carried back and the episode keeps the position it left with.
 */
internal fun externalPlayerResultPositionMs(result: Intent?): Long? {
    val extras = result?.extras ?: return null
    val intKeys = listOf("position", "extra_position")
    for (key in intKeys) {
        val value = runCatching { extras.getInt(key, -1) }.getOrDefault(-1)
        if (value > 0) return value.toLong()
    }
    val longValue = runCatching { extras.getLong("extra_position", -1L) }.getOrDefault(-1L)
    return longValue.takeIf { it > 0L }
}

/**
 * The episode's length as the other player measured it, under the same per-player spellings as
 * [externalPlayerResultPositionMs]: `duration` for MX Player, Just Player and mpv-android,
 * `extra_duration` (a long) for VLC.
 */
internal fun externalPlayerResultDurationMs(result: Intent?): Long? {
    val extras = result?.extras ?: return null
    for (key in listOf("duration", "extra_duration")) {
        val value = runCatching { extras.getInt(key, -1) }.getOrDefault(-1)
        if (value > 0) return value.toLong()
    }
    val longValue = runCatching { extras.getLong("extra_duration", -1L) }.getOrDefault(-1L)
    return longValue.takeIf { it > 0L }
}

/** MX Player and Just Player name why they closed; running out of episode is the one that counts. */
internal fun externalPlayerResultCompleted(result: Intent?): Boolean =
    result?.getStringExtra("end_by") == "playback_completion"

/**
 * Launches the receiving app and reports the only locally actionable failure.
 *
 * [preferredPackage] is the player the viewer picked in settings, or blank for the system chooser.
 * Naming it skips the chooser, which is the point: a television chooser is remembered the instant
 * "Always" is pressed and cannot be un-remembered from in here, so without this the choice is made
 * once by accident and never again. A package that is no longer installed — or no longer accepts
 * this episode — falls back to the chooser rather than failing, because on a device with three
 * players "no other video player is installed" would simply be untrue.
 */
internal fun launchExternalPlayer(
    context: Context,
    request: ExternalPlaybackRequest,
    preferredPackage: String = "",
    /** The episode being handed over, so what the other player reports back can be recorded. */
    session: ExternalPlaybackSession? = null,
): Boolean {
    return runCatching {
        LocalStreamProxy.beginHandOff()
        val intent = externalPlayerIntent(context, request, forResult = session != null)
        val chosen = preferredPackage.takeIf(String::isNotBlank)
            ?.takeIf { canReceiveExternalPlayback(context, intent, it) }
        chosen?.let(intent::setPackage)
        DiagnosticsLog.event(
            "External player handoff type=${request.streamType} headers=${request.headers.size} " +
                "subtitles=${request.subtitles.size} resumeMs=${request.resumePositionMs} " +
                "target=${chosen ?: "chooser"}",
        )
        val forResult = session != null && ExternalPlaybackReturns.launch(intent, session)
        if (!forResult) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        PlaybackStatus.externalHandOffStarted()
        true
    }.getOrElse { error ->
        val failure = if (error is ActivityNotFoundException) {
            AnililiError.EXTERNAL_PLAYER_MISSING.report(cause = error)
        } else {
            AnililiError.EXTERNAL_PLAYER_REMOTE_FILE.report(
                detail = "The other player refused the hand-off. The copy in your library is untouched.",
                cause = error,
                attributes = mapOf("streamType" to request.streamType, "headers" to request.headers.size),
            )
        }
        Toast.makeText(context, failure.withCode(), Toast.LENGTH_LONG).show()
        false
    }
}
