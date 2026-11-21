package com.anilili.playback

import android.content.Context
import com.anilili.data.AppGraph
import com.anilili.diagnostics.DiagnosticsLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A loopback HTTP server that lends this app's request headers to another player.
 *
 * The players this app hands episodes to fetch the stream themselves, and almost none of them can
 * be told to send a header. VLC in particular has no way at all: its whole external contract is
 * `item_location`, `subtitles_location`, `title`, `from_start`, `position`, `opened_position` and
 * `disable_hardware`, so the `Referer` these CDNs gate on cannot reach it and every gated source
 * answered it 403 while the same URL played here. Measured on one: bare and with a user agent alone
 * both 403, with `Referer: https://megaplay.buzz/` 200.
 *
 * So the other player is given a `127.0.0.1` address instead of the CDN's, and this serves it,
 * making the real request with the headers attached. Playlists are rewritten on the way through
 * ([rewriteHlsPlaylist]) so segments and keys come back here too — lending headers to the manifest
 * alone would only move the 403 one request along.
 *
 * **This is not an open proxy.** It binds the loopback interface, so only apps on this device can
 * reach it, and it will only fetch URLs this app has already registered under an unguessable token.
 * A request naming any other URL has nothing to name it with.
 */
internal object LocalStreamProxy {

    /** One upstream URL and the headers to fetch it with. */
    private data class Entry(val url: String, val headers: Map<String, String>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val tokens = ConcurrentHashMap<Entry, String>()
    private val random = SecureRandom()
    private val lastRequestAt = AtomicLong(0L)
    private val openConnections = AtomicInteger(0)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var port: Int = 0
    @Volatile private var appContext: Context? = null

    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "anilili-stream-proxy").apply { isDaemon = true }
    }
    private val writeWatchdogs = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "anilili-stream-proxy-watchdog").apply { isDaemon = true }
    }

    /**
     * Fetches with no call timeout and no cache, unlike [AppGraph.httpClient].
     *
     * An episode is a long read and the shared client's 45-second call timeout would cut a segment
     * off mid-body; the shared cache would keep megabytes of media that only ever passes straight
     * through to the other player.
     */
    private val client: OkHttpClient by lazy {
        AppGraph.httpClient.newBuilder()
            .cache(null)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The address to hand another player for [url], or [url] itself when nothing would be gained.
     *
     * Standing in the middle of a stream that plays perfectly well without it would only add a
     * failure point, so a request with no headers to lend is passed through untouched.
     */
    fun publish(context: Context, url: String, headers: Map<String, String>): String {
        val usable = headers.filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
        if (!needsLocalStreamProxy(usable) || !url.startsWith("http", ignoreCase = true)) return url
        val running = runCatching { ensureRunning() }.getOrElse { error ->
            // Better the original URL than none: without headers it may still be an ungated source.
            DiagnosticsLog.throwable("LocalStreamProxy could not bind, handing over the direct URL", error)
            return url
        }
        lastRequestAt.set(System.currentTimeMillis())
        val published = register(url, usable, running)
        appContext = context.applicationContext
        if (!LocalStreamProxyService.start(context)) {
            DiagnosticsLog.event("LocalStreamProxy foreground lifetime could not start; using direct URL")
            return url
        }
        return published
    }

    /** Registers one URL and returns its loopback address, reusing an identical entry's token. */
    private fun register(url: String, headers: Map<String, String>, port: Int): String {
        val entry = Entry(url, headers)
        val token = tokens.computeIfAbsent(entry) {
            val fresh = ByteArray(16).also(random::nextBytes)
                .joinToString("") { "%02x".format(it) }
            entries[fresh] = entry
            fresh
        }
        return "http://127.0.0.1:$port/$token"
    }

    @Synchronized
    private fun ensureRunning(): Int {
        serverSocket?.takeIf { !it.isClosed }?.let { return port }
        val socket = ServerSocket()
        socket.reuseAddress = true
        // Android commonly returns ::1 from getLoopbackAddress(), while the URL handed to players
        // is deliberately 127.0.0.1. Binding the IPv6 socket and publishing the IPv4 address makes
        // every player fail with ECONNREFUSED even though this thread reports that it is listening.
        val ipv4Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        socket.bind(InetSocketAddress(ipv4Loopback, 0))
        serverSocket = socket
        port = socket.localPort
        lastRequestAt.set(System.currentTimeMillis())
        workers.execute { acceptLoop(socket) }
        workers.execute { reapWhenIdle(socket) }
        DiagnosticsLog.event("LocalStreamProxy listening on 127.0.0.1:$port")
        return port
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val connection = runCatching { socket.accept() }.getOrElse { return }
            openConnections.incrementAndGet()
            workers.execute {
                var watchdog: ScheduledFuture<*>? = null
                try {
                    connection.soTimeout = SOCKET_TIMEOUT_MS
                    val output = ProgressOutputStream(BufferedOutputStream(connection.getOutputStream()))
                    watchdog = writeWatchdogs.scheduleAtFixedRate(
                        {
                            if (System.currentTimeMillis() - output.lastWriteAt.get() >= WRITE_STALL_TIMEOUT_MS) {
                                runCatching { connection.close() }
                            }
                        },
                        WRITE_STALL_TIMEOUT_MS,
                        WRITE_STALL_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS,
                    )
                    serve(connection, output)
                } catch (_: IOException) {
                    // A player that seeks or stops closes the socket mid-body constantly. That is
                    // the normal shape of this traffic, not a fault worth recording.
                } finally {
                    watchdog?.cancel(false)
                    runCatching { connection.close() }
                    // Idleness counts from the end of the last transfer, not its start: one direct
                    // file can be a single connection that streams for the whole episode.
                    lastRequestAt.set(System.currentTimeMillis())
                    openConnections.decrementAndGet()
                }
            }
        }
    }

    /**
     * Closes the server once nothing has asked it for anything in a while.
     *
     * The hand-off has no completion to listen for — the viewer simply stops watching in the other
     * app — so idleness is the only signal that the socket and the URLs behind it can go. It used
     * to wait six hours, and the foreground service that keeps this alive pins the whole process
     * (image cache, WebViews, stores) above the low-memory killer for all of that time, so on a
     * TV stick the launcher or the next app was killed instead. A paused player whose connection
     * the write watchdog has already closed reconnects with a range request on resume, so the
     * wait only has to outlast an ordinary pause, not a whole evening.
     */
    private fun reapWhenIdle(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                Thread.sleep(IDLE_CHECK_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (!localStreamProxyIdle(
                    nowMs = System.currentTimeMillis(),
                    lastActivityMs = lastRequestAt.get(),
                    openConnections = openConnections.get(),
                    idleShutdownMs = IDLE_SHUTDOWN_MS,
                )
            ) {
                continue
            }
            synchronized(this) {
                if (serverSocket !== socket) return
                entries.clear()
                tokens.clear()
                serverSocket = null
                runCatching { socket.close() }
            }
            appContext?.let { context ->
                context.stopService(LocalStreamProxyService.intent(context))
            }
            // Twenty minutes with nothing fetched: the other player is done with the hand-off.
            PlaybackStatus.externalHandOffEnded()
            DiagnosticsLog.event("LocalStreamProxy closed after $IDLE_SHUTDOWN_MS ms idle")
            return
        }
    }

    /**
     * Forgets the previous hand-off's URLs before a new one publishes its own.
     *
     * Only one other player is watched at a time, so a new hand-off replaces the old one. The
     * socket and its foreground service stay up — the new hand-off is about to publish through
     * them, and stopping the service here would let its delayed `onDestroy` close the socket the
     * new hand-off had just been given.
     */
    fun beginHandOff() {
        entries.clear()
        tokens.clear()
        lastRequestAt.set(System.currentTimeMillis())
    }

    /**
     * Shuts the proxy and its foreground service down, when something has made the hand-off moot:
     * this app's own player preparing an item means the viewer is watching here again.
     */
    fun stop(reason: String) {
        val wasRunning = synchronized(this) { serverSocket != null }
        if (!wasRunning) return
        shutdown()
        appContext?.let { context ->
            runCatching { context.stopService(LocalStreamProxyService.intent(context)) }
        }
        DiagnosticsLog.event("LocalStreamProxy stopped: $reason")
    }

    private fun serve(connection: Socket, output: OutputStream) {
        lastRequestAt.set(System.currentTimeMillis())
        val input = connection.getInputStream().buffered()
        val request = readRequest(input) ?: return respond(output, 400, "Bad Request")
        val entry = entries[request.token] ?: return respond(output, 404, "Not Found")
        relay(request, entry, output)
        output.flush()
    }

    private fun relay(request: ProxyRequest, entry: Entry, output: OutputStream) {
        val builder = Request.Builder().url(entry.url)
        entry.headers.forEach { (name, value) -> builder.header(name, value) }
        // The client's own conditional and range headers are the only ones forwarded. Everything
        // else is this app's to state: a Referer echoed back from the other player would overwrite
        // the one that makes the request work.
        request.forwardable.forEach { (name, value) -> builder.header(name, value) }
        if (request.method == "HEAD") builder.head()

        client.newCall(builder.build()).execute().use { response ->
            val contentType = response.header("Content-Type")
            val passthrough = passthroughHeaders(response.headers)
            if (request.method == "HEAD") {
                respondHeaders(output, response.code, contentType, null, passthrough)
                return
            }
            val body = response.body ?: run {
                respondHeaders(output, response.code, contentType, 0L, passthrough)
                return
            }

            // Sniff only a tiny prefix. An ordinary segment or direct file begins streaming after
            // these bytes; only a response that identifies itself as HLS is retained for rewriting.
            val stream = body.byteStream()
            val prefix = stream.readHead(PLAYLIST_SNIFF_BYTES)
            val hls = looksLikeHlsPlaylist(contentType, prefix.bytes)
            val buffered = if (hls && !prefix.complete) {
                stream.readBody(prefix.bytes, MAX_PLAYLIST_BYTES)
            } else {
                prefix
            }
            if (hls && buffered.complete) {
                val rewritten = rewriteHlsPlaylist(
                    playlist = buffered.bytes.decodeToString(),
                    playlistUrl = response.request.url.toString(),
                    register = { register(it, entry.headers, port) },
                ).encodeToByteArray()
                // The rewritten playlist is a different size than the one upstream sent, so its own
                // length is stated and upstream's is dropped.
                respondHeaders(output, response.code, contentType, rewritten.size.toLong(), emptyList())
                output.write(rewritten)
                return
            }

            val length = if (buffered.complete) buffered.bytes.size.toLong() else null
            respondHeaders(output, response.code, contentType, length, passthrough)
            output.write(buffered.bytes)
            if (!buffered.complete) stream.copyTo(output)
        }
    }

    @Synchronized
    fun shutdown() {
        entries.clear()
        tokens.clear()
        serverSocket?.let { runCatching { it.close() } }
        serverSocket = null
        port = 0
    }

    /** The response headers a player needs to seek: the range and length ones, and nothing else. */
    private fun passthroughHeaders(headers: okhttp3.Headers): List<Pair<String, String>> =
        PASSTHROUGH.mapNotNull { name -> headers[name]?.let { name to it } }

    private val PASSTHROUGH = listOf("Accept-Ranges", "Content-Range", "Content-Length")

    private fun respondHeaders(
        output: OutputStream,
        code: Int,
        contentType: String?,
        contentLength: Long?,
        extra: List<Pair<String, String>>,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append(CRLF)
            contentType?.let { append("Content-Type: ").append(it).append(CRLF) }
            // Stated once, from whichever of the two sources is authoritative here: a rewritten
            // playlist is a different length than the one upstream sent.
            contentLength?.let { append("Content-Length: ").append(it).append(CRLF) }
            extra.filterNot { contentLength != null && it.first == "Content-Length" }
                .forEach { (name, value) -> append(name).append(": ").append(value).append(CRLF) }
            // Every response is close-delimited. It costs a TCP connection per segment, which on
            // loopback is nothing, and it removes any need to agree with the player about where a
            // body of unknown length ends.
            append("Connection: close").append(CRLF).append(CRLF)
        }
        output.write(head.toByteArray())
    }

    private fun respond(output: OutputStream, code: Int, reason: String) {
        output.write("HTTP/1.1 $code $reason${CRLF}Content-Length: 0${CRLF}Connection: close$CRLF$CRLF".toByteArray())
        output.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        304 -> "Not Modified"
        else -> "Status $code"
    }

    private const val CRLF = "\r\n"
    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val IDLE_CHECK_MS = 30_000L
    private const val IDLE_SHUTDOWN_MS = 20 * 60 * 1000L
    private const val WRITE_STALL_TIMEOUT_MS = 60_000L
}

/**
 * Whether the proxy has nothing left to serve: no connection open, and none closed or opened
 * within [idleShutdownMs]. An open connection is a player still reading, however long ago it asked.
 */
internal fun localStreamProxyIdle(
    nowMs: Long,
    lastActivityMs: Long,
    openConnections: Int,
    idleShutdownMs: Long,
): Boolean = openConnections <= 0 && nowMs - lastActivityMs >= idleShutdownMs

/** A User-Agent alone works directly; only identity/gating headers justify a local proxy. */
internal fun needsLocalStreamProxy(headers: Map<String, String>): Boolean = headers.any { (name, value) ->
    name.isNotBlank() && value.isNotBlank() && !name.equals("User-Agent", ignoreCase = true)
}

/** One parsed request: which registered URL it wants, and what to carry upstream with it. */
internal data class ProxyRequest(
    val method: String,
    val token: String,
    val forwardable: List<Pair<String, String>>,
)

/** The request headers that describe *which bytes* are wanted rather than who is asking. */
private val FORWARDABLE = setOf("range", "if-range", "if-none-match", "if-modified-since")

/**
 * Reads a request head, or null when it is not one this can answer.
 *
 * Only the head is read: this never has a request body to consume, and a player that sent one
 * would be doing something no player does.
 */
internal fun readRequest(input: InputStream): ProxyRequest? {
    val requestLine = readLine(input) ?: return null
    val parts = requestLine.split(' ')
    if (parts.size < 2) return null
    val method = parts[0].uppercase()
    if (method != "GET" && method != "HEAD") return null
    val token = parts[1].substringAfter('/').substringBefore('?').substringBefore('#')
    if (token.isEmpty()) return null

    val forwardable = buildList {
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':', missingDelimiterValue = "")
            if (name.lowercase() in FORWARDABLE) add(name to line.substringAfter(':').trim())
        }
    }
    return ProxyRequest(method, token, forwardable)
}

/** One CRLF-terminated line, or null at end of stream. Header lines are short by definition. */
private fun readLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
        val next = input.read()
        if (next == -1) return line.takeIf { it.isNotEmpty() }?.toString()
        if (next == '\n'.code) return line.toString().removeSuffix("\r")
        if (line.length > MAX_HEADER_LINE) return null
        line.append(next.toChar())
    }
}

private const val MAX_HEADER_LINE = 8 * 1024

/** The start of a body, and whether that turned out to be all of it. */
internal class BodyHead(val bytes: ByteArray, val complete: Boolean)

/**
 * Reads up to [limit] bytes, reporting whether the stream ended within them.
 *
 * [BodyHead.complete] is what decides whether the caller may treat this as a playlist: rewriting a
 * body it has only part of would hand the other player a truncated one, so anything longer is
 * streamed through as media instead — and the bytes already read are still returned, because the
 * stream cannot be rewound to send them again.
 */
internal fun InputStream.readHead(limit: Int): BodyHead {
    val buffer = ByteArray(limit)
    var filled = 0
    while (filled < limit) {
        val read = read(buffer, filled, limit - filled)
        if (read == -1) return BodyHead(buffer.copyOf(filled), complete = true)
        if (read == 0) continue
        filled += read
    }
    return BodyHead(buffer, complete = false)
}

/** Continues an already-sniffed body, retaining at most [limit] bytes plus one overflow byte. */
internal fun InputStream.readBody(prefix: ByteArray, limit: Int): BodyHead {
    require(prefix.size <= limit)
    val output = ByteArrayOutputStream(minOf(limit, prefix.size + DEFAULT_BUFFER_SIZE))
    output.write(prefix)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (output.size() <= limit) {
        val remaining = limit + 1 - output.size()
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read == -1) return BodyHead(output.toByteArray(), complete = true)
        if (read == 0) continue
        output.write(buffer, 0, read)
    }
    return BodyHead(output.toByteArray(), complete = false)
}

/** Lets a watchdog close a socket whose receiver has stopped consuming bytes. */
private class ProgressOutputStream(output: OutputStream) : FilterOutputStream(output) {
    val lastWriteAt = AtomicLong(System.currentTimeMillis())

    override fun write(value: Int) = track { out.write(value) }

    override fun write(buffer: ByteArray, offset: Int, length: Int) =
        track { out.write(buffer, offset, length) }

    override fun flush() = track { out.flush() }

    private inline fun track(block: () -> Unit) {
        block()
        lastWriteAt.set(System.currentTimeMillis())
    }
}
