package com.anilili.data.update

import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.raise
import fr.acinq.secp256k1.Secp256k1
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Last-resort update channel that survives the loss of the GitHub account or repo.
 *
 * The latest release is published as a Nostr addressable event (kind 30078, d-tag
 * [MANIFEST_D_TAG]) signed by the project key in [MANIFEST_PUBKEY_HEX]; relays keep
 * only the newest event for that (pubkey, kind, d-tag) tuple. The event content is a
 * JSON manifest: {"version","changelog","apkUrl","sizeBytes"}. If GitHub is gone, a
 * new manifest pointing at any mirror is published with scripts/nostr_update.py and
 * every installed app follows it — the pubkey baked in here is the only "address"
 * the app ever needs.
 *
 * Events are verified before use: the id must match the NIP-01 serialization hash and
 * the BIP-340 Schnorr signature must check out against [MANIFEST_PUBKEY_HEX], so a
 * malicious or compromised relay can at worst withhold the manifest, never forge one.
 * The final guard is unchanged: Android only installs APKs signed by the release key.
 */
object NostrUpdateSource {
    /**
     * This fork's update key, not the upstream project's.
     *
     * The pubkey is the channel's whole address: only manifests signed by it are accepted, so it has
     * to belong to whoever publishes the APKs. Rotating it means every installed build stops hearing
     * updates — the key lives in `nostr-update-key.properties` (gitignored) and in the
     * `NOSTR_UPDATE_SECRET` repository secret the release workflow signs with.
     */
    const val MANIFEST_PUBKEY_HEX = "2b8969c0c4d7947acd4331b89f78a12ff6150831252edb6fa624ddb5c73b0dee"
    const val MANIFEST_KIND = 30078

    /**
     * Where the signed manifest is mirrored as a file; same event, one request instead of a relay
     * scan. `releases/latest/download/` is a stable alias: every release uploads its own
     * `update.json`, so this URL always serves the newest one without the app knowing a version.
     */
    const val MANIFEST_MIRROR_URL =
        "https://github.com/d1dac0/AnimeX/releases/latest/download/update.json"
    const val MANIFEST_D_TAG = "anilili-update"

    private val RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band",
        "wss://relay.primal.net",
    )
    private const val SUBSCRIPTION_ID = "anilili-update"
    private val PER_RELAY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10)
    private val OVERALL_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(15)

    /**
     * The same signed event, served as a file from the APK mirror.
     *
     * Relays are the durable copy but they are slow and flaky — several time out on every publish,
     * and the scan waits up to 15 s for the slowest. A CDN answers in one request, so this is
     * tried first and the relays become the fallback rather than the front door.
     *
     * Security is unchanged. What is fetched is the identical signed Nostr event, not a plain
     * JSON of convenience, so it goes through the same [NostrEvent.isValidManifest] check: project
     * pubkey, kind, d-tag, id hash and BIP-340 signature. Whoever controls the mirror can withhold
     * an update or serve an older signed one, but cannot forge one, and cannot point the app at an
     * APK the project did not sign — Android refuses an install signed with another key.
     */
    fun fetchManifestFromMirror(client: OkHttpClient, json: Json): UpdateManager.UpdateInfo {
        val request = Request.Builder()
            .url(MANIFEST_MIRROR_URL)
            .header("Accept", "application/json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Manifest mirror HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val event = json.parseToJsonElement(body).jsonObject.toNostrEvent()
        if (!event.isValidManifest()) error("Manifest mirror returned an event that failed verification")
        DiagnosticsLog.event("Update manifest read from mirror createdAt=${event.createdAt}")
        return event.parseManifest(json, isTv = com.anilili.data.AppGraph.isTv)
    }

    /** Queries all relays in parallel and returns the newest validly signed manifest. */
    fun fetchManifest(client: OkHttpClient, json: Json): UpdateManager.UpdateInfo {
        val verified = scanRelays(client, json, MANIFEST_D_TAG, SUBSCRIPTION_ID)
        // UPD-02 rather than UPD-01: the relays answered and held nothing this app would accept,
        // which is a different thing from not reaching them — and worth telling apart, because two
        // of the four relays refuse most publishes and a manifest that reached none of them is a
        // publishing fault rather than a viewer's network.
        val newest = verified.maxByOrNull { it.createdAt }
            ?: AnililiError.UPDATE_NO_MANIFEST.raise(
                detail = "No validly signed manifest was found on any relay.",
            )
        return newest.parseManifest(json, isTv = com.anilili.data.AppGraph.isTv)
    }

    /**
     * Every validly signed event the relays hold for one d-tag under the project key.
     *
     * Shared with the announcement channel rather than copied for it: this is where the parallel
     * scan, the two timeouts and — the part worth having exactly one of — the decision about what
     * counts as genuine all live. A second copy is a second place for the signature check to be
     * quietly wrong in, which is the failure mode this channel has already had once (see the R8
     * note on [NostrEvent.isValidSignedBy]).
     */
    internal fun scanRelays(
        client: OkHttpClient,
        json: Json,
        dTag: String,
        subscriptionId: String,
    ): List<NostrEvent> {
        val events = ConcurrentLinkedQueue<JsonObject>()
        val remaining = CountDownLatch(RELAYS.size)
        RELAYS.forEach { relay ->
            thread(name = "nostr-$subscriptionId", isDaemon = true) {
                try {
                    queryRelay(client, relay, dTag, subscriptionId, events, json)
                } catch (error: Exception) {
                    DiagnosticsLog.event("Nostr relay failed relay=$relay dTag=$dTag reason=${error.message}")
                } finally {
                    remaining.countDown()
                }
            }
        }
        remaining.await(OVERALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val candidates = events.mapNotNull { raw ->
            runCatching { raw.toNostrEvent() }.getOrNull()
        }
        val verified = candidates.filter { it.isValidSignedBy(MANIFEST_PUBKEY_HEX, MANIFEST_KIND, dTag) }
        DiagnosticsLog.event(
            "Nostr scan complete dTag=$dTag relays=${RELAYS.size} events=${candidates.size} " +
                "verified=${verified.size}",
        )
        return verified
    }

    private fun queryRelay(
        client: OkHttpClient,
        relay: String,
        dTag: String,
        subscriptionId: String,
        sink: ConcurrentLinkedQueue<JsonObject>,
        json: Json,
    ) {
        val request = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            add(
                JsonObject(
                    mapOf(
                        "kinds" to buildJsonArray { add(JsonPrimitive(MANIFEST_KIND)) },
                        "authors" to buildJsonArray { add(JsonPrimitive(MANIFEST_PUBKEY_HEX)) },
                        "#d" to buildJsonArray { add(JsonPrimitive(dTag)) },
                        "limit" to JsonPrimitive(3),
                    ),
                ),
            )
        }
        val done = CountDownLatch(1)
        val webSocket = client.newWebSocket(
            Request.Builder().url(relay).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(json.encodeToString(JsonElement.serializer(), request))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val message = json.parseToJsonElement(text).jsonArray
                        when (message[0].jsonPrimitive.content) {
                            "EVENT" -> sink.add(message[2].jsonObject)
                            "EOSE" -> {
                                webSocket.close(1000, null)
                                done.countDown()
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    done.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    done.countDown()
                }
            },
        )
        done.await(PER_RELAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        runCatching { webSocket.close(1000, null) }
    }
}

internal data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: JsonArray,
    val content: String,
    val sig: String,
) {
    /** Full NIP-01 verification of the update manifest: shape, d-tag, id hash, Schnorr signature. */
    fun isValidManifest(): Boolean = isValidSignedBy(
        NostrUpdateSource.MANIFEST_PUBKEY_HEX,
        NostrUpdateSource.MANIFEST_KIND,
        NostrUpdateSource.MANIFEST_D_TAG,
    )

    /**
     * The same verification for any of the project's addressable events.
     *
     * The d-tag is what separates one channel from another under a single key — the update
     * manifest and the announcement live at the same kind and pubkey — so it is checked here
     * rather than by the caller. An event that verifies is one this project signed; nothing about
     * a relay is trusted, and a relay can therefore only ever withhold, never forge.
     */
    fun isValidSignedBy(expectedPubkeyHex: String, expectedKind: Int, expectedDTag: String): Boolean {
        if (!pubkey.equals(expectedPubkeyHex, ignoreCase = true)) return false
        if (kind != expectedKind) return false
        val hasDTag = tags.filterIsInstance<JsonArray>().any { tag ->
            tag.size >= 2 &&
                tag[0].jsonPrimitive.content == "d" &&
                tag[1].jsonPrimitive.content == expectedDTag
        }
        if (!hasDTag) return false
        val expectedId = computeEventIdHex()
        if (!expectedId.equals(id, ignoreCase = true)) {
            DiagnosticsLog.event("Nostr event rejected dTag=$expectedDTag reason=id-mismatch")
            return false
        }
        val sigBytes = runCatching { sig.hexToBytes() }.getOrNull() ?: return false
        val idBytes = expectedId.hexToBytes()
        val pubBytes = pubkey.hexToBytes()
        if (sigBytes.size != 64 || idBytes.size != 32 || pubBytes.size != 32) return false
        // Distinguish "this event is not genuine" from "this build cannot check signatures at
        // all". They are the same `false` to the caller, and conflating them hid a real defect
        // for an entire release: R8 shrank the secp256k1 JNI backend away, the verifier threw
        // ExceptionInInitializerError on first touch, and every manifest silently looked forged.
        return runCatching { Secp256k1.verifySchnorr(sigBytes, idBytes, pubBytes) }
            .onFailure { DiagnosticsLog.throwable("Nostr signature check unavailable", it) }
            .onSuccess { if (!it) DiagnosticsLog.event("Nostr event rejected dTag=$expectedDTag reason=bad-signature") }
            .getOrDefault(false)
    }

    /** NIP-01 id: sha256 of the compact JSON array [0, pubkey, created_at, kind, tags, content]. */
    fun computeEventIdHex(): String {
        val preimage = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(pubkey))
            add(JsonPrimitive(createdAt))
            add(JsonPrimitive(kind))
            add(tags)
            add(JsonPrimitive(content))
        }
        val serialized = NostrJson.encodeToString(JsonElement.serializer(), preimage)
        val digest = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
        return digest.toHexString()
    }

    /**
     * Reads the manifest, preferring this device's form factor.
     *
     * Since the mobile/TV flavor split a release ships two different APKs, but the manifest's
     * top-level `apkUrl` can only name one — and it names the phone build, which is what every
     * pre-split install is already following. A `variants` object carries the rest:
     *
     *     {"version":…, "apkUrl":…, "variants":{"tv":{"url":…,"sizeBytes":…}}}
     *
     * A TV takes `variants.tv` when the publisher provided it and otherwise falls back to
     * `apkUrl`, so an older manifest still updates a TV (with the phone build, exactly as it did
     * before the split) rather than leaving it stranded. Apps older than this simply ignore
     * `variants`, which is why the top-level fields stay put.
     */
    fun parseManifest(json: Json, isTv: Boolean = false): UpdateManager.UpdateInfo {
        val manifest = json.parseToJsonElement(content).jsonObject
        val versionText = manifest["version"]?.jsonPrimitive?.content
            ?: error("Nostr update manifest is missing a version")
        val version = parseVersion(versionText)
            ?: error("Nostr update manifest version is not recognizable: $versionText")
        val variant = (manifest["variants"] as? JsonObject)
            ?.get(if (isTv) "tv" else "mobile") as? JsonObject
        val apkUrl = variant?.get("url")?.jsonPrimitive?.content
            ?: manifest["apkUrl"]?.jsonPrimitive?.content
            ?: error("Nostr update manifest is missing an APK URL")
        val sizeBytes = variant?.get("sizeBytes")?.jsonPrimitive?.content?.toLongOrNull()
            ?: manifest["sizeBytes"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: -1L
        DiagnosticsLog.event(
            "Nostr manifest selected version=$versionText tv=$isTv " +
                "variant=${if (variant != null) (if (isTv) "tv" else "mobile") else "legacy-apkUrl"}",
        )
        // Mirrors ride beside the URL they back up, so a variant keeps its own list:
        //   {"variants":{"tv":{"url":…,"sizeBytes":…,"mirrors":["https://…"]}}}
        // A manifest without them parses exactly as before, and an older install ignores the
        // field entirely — which is what lets a host be added or replaced without a new build.
        val mirrors = (variant?.get("mirrors") ?: manifest["mirrors"])
            ?.let { it as? JsonArray }
            ?.mapNotNull { entry -> entry.jsonPrimitive.content.takeIf(String::isNotBlank) }
            .orEmpty()
        // The internal build number (versionCode), when the publisher wrote one. Older manifests
        // have none and are compared by version name alone, as they always were.
        val build = (variant?.get("versionCode") ?: manifest["versionCode"])
            ?.jsonPrimitive?.content?.toIntOrNull()
        // A rebuild worth interrupting for, per form factor first: {"variants":{"tv":{"important":true}}}
        // pops the TV build up while phones keep it quiet. Absent or unreadable means not important.
        val important = (variant?.get("important") ?: manifest["important"])
            ?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        return UpdateManager.UpdateInfo(
            version = version,
            changelog = manifest["changelog"]?.jsonPrimitive?.content.orEmpty(),
            apkUrl = apkUrl,
            sizeBytes = sizeBytes,
            mirrors = mirrors,
            build = build,
            important = important,
        )
    }
}

internal fun JsonObject.toNostrEvent(): NostrEvent = NostrEvent(
    id = this["id"]?.jsonPrimitive?.content ?: error("event missing id"),
    pubkey = this["pubkey"]?.jsonPrimitive?.content ?: error("event missing pubkey"),
    createdAt = this["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: error("event missing created_at"),
    kind = this["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: error("event missing kind"),
    tags = this["tags"]?.jsonArray ?: JsonArray(emptyList()),
    content = this["content"]?.jsonPrimitive?.content ?: "",
    sig = this["sig"]?.jsonPrimitive?.content ?: "",
)

private val NostrJson = Json

internal fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
