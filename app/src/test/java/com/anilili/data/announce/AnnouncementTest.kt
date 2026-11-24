package com.anilili.data.announce

import com.anilili.data.update.NostrUpdateSource
import com.anilili.data.update.forkSignedManifestFixture
import com.anilili.data.update.toNostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast channel's rules, all of them decided off-device.
 *
 * This channel can put a notification on every install in the world, so the interesting tests are
 * the ones about what it *refuses*: an event signed for the other channel, a link that is not a web
 * page, a message aimed at versions this build is not.
 */
class AnnouncementTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The d-tag is the whole separation between the two channels, since they share a key and a
     * kind. If this ever passed, a signed update manifest would be replayable as an announcement.
     */
    @Test
    fun `a genuinely signed update manifest is not an announcement`() {
        val event = forkSignedManifestFixture()
        assertTrue("control: this event really is validly signed", event.isValidManifest())
        assertFalse(
            "the update d-tag must not satisfy the announcement channel",
            event.isValidSignedBy(
                NostrUpdateSource.MANIFEST_PUBKEY_HEX,
                NostrUpdateSource.MANIFEST_KIND,
                AnnouncementSource.D_TAG,
            ),
        )
    }

    /**
     * A real announcement, signed by the upstream key with `scripts/nostr_update.py announce`.
     *
     * The publisher and the app are two independent implementations of NIP-01 — one in Python
     * against coincurve, one in Kotlin against secp256k1 — and nothing but a test like this notices
     * when they stop agreeing. That is not hypothetical for this channel: the update manifest it
     * shares a key with spent an entire release silently rejecting every event, because the
     * verifier could not run at all and a `runCatching` turned that into "not genuine".
     */
    private val liveAnnouncementEventJson = """{"id":"1a3e007da72179bb5cae37be96c334c06b58e1c9670541a9bf528a823e705d65","pubkey":"f1abd1ac685aed1f5145271283cd9418bf13aa92ed26399df191ae0c08b22207","created_at":1788559744,"kind":30078,"tags":[["d","anilili-announcement"]],"content":"{\"id\":\"test-signing-loop\",\"title\":\"Sources are down\",\"message\":\"Not your device - the provider is having an outage.\",\"url\":\"https://example.test/status\",\"urlLabel\":\"Status page\",\"severity\":\"emergency\",\"maxVersion\":\"0.1.99\"}","sig":"701f8435a36433c0543abd0c9dc1889c954b91b3691cee7f7d2e5493f6c37c3264ea29714a0ad71b4d36b32ce4bf4b1102a4c18f08fa177638beb8cbf8b694d9"}"""

    @Test
    fun `an event the publish script signed passes the app's verification and parses`() {
        val event = json.parseToJsonElement(liveAnnouncementEventJson).jsonObject.toNostrEvent()
        assertEquals("NIP-01 id must re-serialize identically", event.id, event.computeEventIdHex())
        assertTrue(
            "the script and the app must agree on what a valid announcement is",
            event.isValidSignedBy(
                event.pubkey,
                NostrUpdateSource.MANIFEST_KIND,
                AnnouncementSource.D_TAG,
            ),
        )
        assertFalse(event.isValidSignedBy(NostrUpdateSource.MANIFEST_PUBKEY_HEX, event.kind, AnnouncementSource.D_TAG))
        val parsed = parseAnnouncement(json, event.content)
        assertEquals("test-signing-loop", parsed?.id)
        assertEquals("Sources are down", parsed?.title)
        assertTrue("--emergency must reach the loud channel", parsed?.urgent == true)
        assertEquals("example.test", parsed?.urlHost)
        assertTrue(announcementApplies(parsed!!, "0.1.76", nowSec = 1_788_600_000L))
    }

    /** The reverse direction of the same guard: it must not verify against the update channel. */
    @Test
    fun `a signed announcement is not an update manifest`() {
        val event = json.parseToJsonElement(liveAnnouncementEventJson).jsonObject.toNostrEvent()
        assertFalse(event.isValidSignedBy(event.pubkey, NostrUpdateSource.MANIFEST_KIND, NostrUpdateSource.MANIFEST_D_TAG))
    }

    @Test
    fun `a full announcement parses`() {
        val parsed = parseAnnouncement(
            json,
            """{"id":"outage-2026-09","title":"Sources are down","message":"Not your device.",
               "url":"https://example.test/status","urlLabel":"Status page","severity":"emergency",
               "expiresAt":1790000000,"minVersion":"0.1.70","maxVersion":"0.1.76"}""",
        )
        assertEquals("outage-2026-09", parsed?.id)
        assertEquals("Sources are down", parsed?.title)
        assertTrue("emergency routes to the loud channel", parsed?.urgent == true)
        assertEquals("Status page", parsed?.urlLabel)
        assertEquals("example.test", parsed?.urlHost)
        assertEquals(1790000000L, parsed?.expiresAt)
    }

    @Test
    fun `an announcement without an id or a message is not shown`() {
        assertNull(parseAnnouncement(json, """{"title":"Hi","message":"No id here"}"""))
        assertNull(parseAnnouncement(json, """{"id":"x","title":"Hi"}"""))
        assertNull(parseAnnouncement(json, """{"id":"  ","message":"blank id"}"""))
        assertNull(parseAnnouncement(json, "not json at all"))
    }

    /** Absent severity is the quiet channel: an ordinary notice must not buzz like an emergency. */
    @Test
    fun `severity defaults to not urgent`() {
        val parsed = parseAnnouncement(json, """{"id":"a","message":"Just so you know"}""")
        assertFalse(parsed?.urgent == true)
        assertEquals("Anilili", parsed?.title)
    }

    /**
     * The link is the one part of a broadcast that reaches outside the app, so anything that is
     * not plainly a web page is dropped rather than passed to `Intent.parseUri`.
     */
    @Test
    fun `only https links survive`() {
        assertEquals("https://example.test/x", sanitizeAnnouncementUrl("https://example.test/x"))
        assertNull("http would be a downgrade in a signed message", sanitizeAnnouncementUrl("http://example.test"))
        assertNull("intent urls can address another app", sanitizeAnnouncementUrl("intent://scan#Intent;end"))
        assertNull(sanitizeAnnouncementUrl("javascript:alert(1)"))
        assertNull(sanitizeAnnouncementUrl("file:///sdcard/x.apk"))
        assertNull("a scheme with no host is not a page", sanitizeAnnouncementUrl("https:///nohost"))
        assertNull(sanitizeAnnouncementUrl(null))
        assertNull(sanitizeAnnouncementUrl("   "))
    }

    @Test
    fun `a link that failed sanitising leaves the announcement without one`() {
        val parsed = parseAnnouncement(json, """{"id":"a","message":"m","url":"intent://x#Intent;end"}""")
        assertEquals("a", parsed?.id)
        assertNull("the message still shows, just without a button", parsed?.url)
        assertNull(parsed?.urlHost)
    }

    @Test
    fun `an expired announcement is not shown`() {
        val announcement = Announcement(id = "a", title = "t", message = "m", expiresAt = 1_000L)
        assertFalse(announcementApplies(announcement, "0.1.76", nowSec = 1_001L))
        assertTrue(announcementApplies(announcement, "0.1.76", nowSec = 999L))
    }

    /** Zero is the "no expiry" sentinel, and must not read as "expired in 1970". */
    @Test
    fun `an announcement with no expiry never expires`() {
        val announcement = Announcement(id = "a", title = "t", message = "m", expiresAt = 0L)
        assertTrue(announcementApplies(announcement, "0.1.76", nowSec = 9_999_999_999L))
    }

    /** "0.1.76 is broken, stay on 0.1.75" must reach 0.1.76 and nobody else. */
    @Test
    fun `version bounds are inclusive on both ends`() {
        val announcement = Announcement(
            id = "bad-release",
            title = "t",
            message = "m",
            minVersion = "0.1.74",
            maxVersion = "0.1.76",
        )
        assertFalse(announcementApplies(announcement, "0.1.73", nowSec = 1L))
        assertTrue(announcementApplies(announcement, "0.1.74", nowSec = 1L))
        assertTrue(announcementApplies(announcement, "0.1.75", nowSec = 1L))
        assertTrue(announcementApplies(announcement, "0.1.76", nowSec = 1L))
        assertFalse(announcementApplies(announcement, "0.1.77", nowSec = 1L))
    }

    @Test
    fun `an unbounded announcement reaches every version`() {
        val announcement = Announcement(id = "a", title = "t", message = "m")
        assertTrue(announcementApplies(announcement, "0.0.1", nowSec = 1L))
        assertTrue(announcementApplies(announcement, "9.9.9", nowSec = 1L))
    }
}
