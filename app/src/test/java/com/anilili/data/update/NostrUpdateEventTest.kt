package com.anilili.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture event is a real Nostr manifest signed by the project update key
 * (see nostr-update-key.properties / scripts/nostr_update.py) with a fixed
 * created_at so the expected id stays stable.
 */
class NostrUpdateEventTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val fixtureJson = """
        {
          "id": "b3ada8db07c1b20f9fa02cadf4577cf99a4d372e8917fa4740ce1812d3cbb51e",
          "pubkey": "2b8969c0c4d7947acd4331b89f78a12ff6150831252edb6fa624ddb5c73b0dee",
          "created_at": 1754000000,
          "kind": 30078,
          "tags": [["d", "anilili-update"]],
          "content": "{\"version\":\"0.1.54\",\"changelog\":\"Fixture release for unit tests\",\"apkUrl\":\"https://example.com/Anilili.apk\",\"sizeBytes\":12345}",
          "sig": "4d1759a9e9a2d5720d38b1832e4c5d0e0f7409367b77f9581b19224a420590f2a4438c321d360be669c5db7010deca4d7e22faf9b6ab491144b12f30b84261ba"
        }
    """.trimIndent()

    private fun fixture(): NostrEvent =
        json.parseToJsonElement(fixtureJson).jsonObject.toNostrEvent()

    @Test
    fun eventIdMatchesNip01Serialization() {
        assertEquals(
            "b3ada8db07c1b20f9fa02cadf4577cf99a4d372e8917fa4740ce1812d3cbb51e",
            fixture().computeEventIdHex(),
        )
    }

    @Test
    fun correctlySignedManifestIsAccepted() {
        assertTrue(fixture().isValidManifest())
    }

    @Test
    fun tamperedContentIsRejected() {
        val tampered = fixture().copy(content = fixture().content.replace("0.1.54", "9.9.9"))
        assertFalse(tampered.isValidManifest())
    }

    @Test
    fun tamperedDTagIsRejected() {
        val tags = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("d"))
                add(kotlinx.serialization.json.JsonPrimitive("other-app"))
            })
        }
        assertFalse(fixture().copy(tags = tags).isValidManifest())
    }

    @Test
    fun foreignPubkeyIsRejected() {
        assertFalse(
            fixture().copy(pubkey = "0".repeat(64)).isValidManifest(),
        )
    }

    @Test
    fun garbageSignatureIsRejected() {
        assertFalse(fixture().copy(sig = "f".repeat(128)).isValidManifest())
    }

    @Test
    fun manifestParsesIntoUpdateInfo() {
        val info = fixture().parseManifest(json)
        assertEquals("0.1.54", info.version)
        assertEquals("Fixture release for unit tests", info.changelog)
        assertEquals("https://example.com/Anilili.apk", info.apkUrl)
        assertEquals(12345L, info.sizeBytes)
    }

    @Test
    fun compareVersionsHandlesNostrVersionFormat() {
        assertTrue(compareAppVersions("0.1.54", "0.1.53") > 0)
        assertEquals(0, compareAppVersions("v0.1.54", "0.1.54"))
    }
}

/**
 * A release otherwise lives or dies with one host, and the host is the part an outsider can take
 * away. Mirrors ride in the manifest rather than the APK so a host can be added, moved or dropped
 * without shipping a build — and a manifest written before the field existed must still parse.
 */
class NostrManifestMirrorTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Only `content` matters here; parseManifest never looks at the envelope, and signature
    // verification is covered by NostrManifestVerificationTest.
    private fun manifest(content: String) = NostrEvent(
        id = "",
        pubkey = "",
        createdAt = 0,
        kind = NostrUpdateSource.MANIFEST_KIND,
        tags = JsonArray(emptyList()),
        content = content,
        sig = "",
    )

    @Test
    fun `mirrors are read for the variant that was selected`() {
        val info = manifest(
            """
            {"version":"0.1.60","apkUrl":"https://primary/a.apk",
             "variants":{
               "mobile":{"url":"https://primary/a.apk","sizeBytes":10,"mirrors":["https://m1/a.apk"]},
               "tv":{"url":"https://primary/tv.apk","sizeBytes":20,"mirrors":["https://m1/tv.apk"]}}}
            """.trimIndent(),
        )
        assertEquals(listOf("https://m1/tv.apk"), info.parseManifest(json, isTv = true).mirrors)
        assertEquals(listOf("https://m1/a.apk"), info.parseManifest(json, isTv = false).mirrors)
    }

    @Test
    fun `important is read for the variant that was selected`() {
        val info = manifest(
            """
            {"version":"0.1.84","apkUrl":"https://primary/a.apk","versionCode":97,
             "variants":{
               "mobile":{"url":"https://primary/a.apk","versionCode":97},
               "tv":{"url":"https://primary/tv.apk","versionCode":97,"important":true}}}
            """.trimIndent(),
        )
        assertTrue(info.parseManifest(json, isTv = true).important)
        assertFalse(info.parseManifest(json, isTv = false).important)
        // Top level applies to both, and a manifest without the field is not important.
        assertTrue(manifest("""{"version":"0.1.84","apkUrl":"https://p/a.apk","important":true}""").parseManifest(json).important)
        assertFalse(manifest("""{"version":"0.1.84","apkUrl":"https://p/a.apk"}""").parseManifest(json).important)
    }

    @Test
    fun `a manifest without mirrors still parses`() {
        val info = manifest("""{"version":"0.1.60","apkUrl":"https://primary/a.apk","sizeBytes":10}""")
            .parseManifest(json)
        assertTrue(info.mirrors.isEmpty())
        assertEquals("https://primary/a.apk", info.apkUrl)
    }

    @Test
    fun `the primary is tried before any mirror and never twice`() {
        val info = UpdateManager.UpdateInfo(
            version = "0.1.60",
            changelog = "",
            apkUrl = "https://primary/a.apk",
            sizeBytes = 10,
            // A publisher repeating the primary in the mirror list must not make it retry itself.
            mirrors = listOf("https://primary/a.apk", "https://m1/a.apk"),
        )
        assertEquals(listOf("https://primary/a.apk", "https://m1/a.apk"), info.downloadUrls)
    }
}
