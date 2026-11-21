package com.anilili.data.library

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-way migration that gives rows saved before [HistoryEntry.watchedEpisodes] existed the
 * history the old "everything below the current episode" inference implied.
 *
 * The case that matters is the one that shipped broken: a viewer who starts a series at episode 7
 * has an empty set for the same reason a pre-v0.1.56 row does, and marking 1–6 watched for them is
 * exactly the guess the field was added to replace. The two are only distinguishable in the stored
 * JSON, so every test here goes through it rather than constructing entries directly.
 */
class LegacyWatchedEpisodesTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun entry(
        episodeNumber: Double,
        watched: Set<Double> = emptySet(),
    ) = HistoryEntry(
        anilistId = 185874,
        title = "BLEACH: Thousand-Year Blood War - The Calamity",
        cover = null,
        episodeNumber = episodeNumber,
        provider = "bonk",
        category = "sub",
        watchedEpisodes = watched,
    )

    /** What the store does on load: decode the stored string, then seed against that same string. */
    private fun load(raw: String): List<HistoryEntry> = seedLegacyWatchedEpisodes(
        json.decodeFromString(ListSerializer(HistoryEntry.serializer()), raw),
        raw,
    )

    /** A row as an older build wrote it: every field except the one that did not exist yet. */
    private fun withoutWatchedEpisodes(entries: List<HistoryEntry>): String =
        json.encodeToString(ListSerializer(HistoryEntry.serializer()), entries)
            .replace(Regex(""","watchedEpisodes":\[[^\]]*\]"""), "")

    @Test
    fun `a row written before the field existed keeps the old inference`() {
        val raw = withoutWatchedEpisodes(listOf(entry(episodeNumber = 7.0)))

        assertEquals(setOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), load(raw).single().watchedEpisodes)
    }

    @Test
    fun `starting a series at episode 7 does not mark the six below it`() {
        val raw = json.encodeToString(
            ListSerializer(HistoryEntry.serializer()),
            listOf(entry(episodeNumber = 7.0)),
        )

        assertEquals(emptySet<Double>(), load(raw).single().watchedEpisodes)
    }

    @Test
    fun `a row that recorded some episodes is left exactly as it is`() {
        val raw = json.encodeToString(
            ListSerializer(HistoryEntry.serializer()),
            listOf(entry(episodeNumber = 9.0, watched = setOf(1.0, 4.0))),
        )

        assertEquals(setOf(1.0, 4.0), load(raw).single().watchedEpisodes)
    }

    @Test
    fun `a legacy row on episode 1 has nothing below it to seed`() {
        val raw = withoutWatchedEpisodes(listOf(entry(episodeNumber = 1.0)))

        assertEquals(emptySet<Double>(), load(raw).single().watchedEpisodes)
    }

    @Test
    fun `a mix seeds only the rows that predate the field`() {
        val legacy = withoutWatchedEpisodes(listOf(entry(episodeNumber = 4.0)))
            .removeSurrounding("[", "]")
        val current = json.encodeToString(
            ListSerializer(HistoryEntry.serializer()),
            listOf(entry(episodeNumber = 7.0)),
        ).removeSurrounding("[", "]")
        val loaded = load("[$legacy,$current]")

        assertEquals(setOf(1.0, 2.0, 3.0), loaded[0].watchedEpisodes)
        assertEquals(emptySet<Double>(), loaded[1].watchedEpisodes)
    }

    /** Nothing to compare the rows against is not a licence to invent watched episodes. */
    @Test
    fun `entries with no stored json behind them are left alone`() {
        val entries = listOf(entry(episodeNumber = 7.0))

        assertEquals(entries, seedLegacyWatchedEpisodes(entries, null))
        assertEquals(entries, seedLegacyWatchedEpisodes(entries, "not json"))
        assertEquals(entries, seedLegacyWatchedEpisodes(entries, "[]"))
    }

    // ---- the one-shot mark (#86) ----

    private fun decode(raw: String) = json.decodeFromString(ListSerializer(HistoryEntry.serializer()), raw)

    @Test
    fun `not yet seeded, legacy rows are seeded and counted so the store writes them back`() {
        val raw = withoutWatchedEpisodes(listOf(entry(episodeNumber = 4.0), entry(episodeNumber = 1.0)))

        val seed = seedLegacyHistory(decode(raw), raw, alreadySeeded = false)

        assertEquals(setOf(1.0, 2.0, 3.0), seed.entries[0].watchedEpisodes)
        assertEquals(2, seed.legacyRows)
        assertTrue(seed.settled)
    }

    @Test
    fun `not yet seeded, a history with no legacy rows settles without anything to write`() {
        val raw = json.encodeToString(
            ListSerializer(HistoryEntry.serializer()),
            listOf(entry(episodeNumber = 7.0)),
        )

        val seed = seedLegacyHistory(decode(raw), raw, alreadySeeded = false)

        assertEquals(decode(raw), seed.entries)
        assertEquals(0, seed.legacyRows)
        assertTrue(seed.settled)
    }

    /**
     * The mark is what makes the parse skippable, so with it set even a legacy-shaped string is
     * not looked at: the entries come back exactly as decoded.
     */
    @Test
    fun `already seeded, the stored json is not read again`() {
        val raw = withoutWatchedEpisodes(listOf(entry(episodeNumber = 7.0)))
        val decoded = decode(raw)

        val seed = seedLegacyHistory(decoded, raw, alreadySeeded = true)

        assertEquals(decoded, seed.entries)
        assertEquals(0, seed.legacyRows)
        assertTrue(seed.settled)
    }

    /** Seeding, writing back and loading again is a fixed point: the mark cannot hide a row. */
    @Test
    fun `a seeded history written back has nothing left to seed`() {
        val raw = withoutWatchedEpisodes(listOf(entry(episodeNumber = 5.0)))
        val firstLoad = seedLegacyHistory(decode(raw), raw, alreadySeeded = false).entries
        val written = json.encodeToString(ListSerializer(HistoryEntry.serializer()), firstLoad)

        val secondLoad = seedLegacyHistory(decode(written), written, alreadySeeded = false)

        assertEquals(firstLoad, secondLoad.entries)
        assertEquals(0, secondLoad.legacyRows)
        assertEquals(
            secondLoad.entries,
            seedLegacyHistory(decode(written), written, alreadySeeded = true).entries,
        )
    }

    /** A history that could not be checked must be checked again next launch, not marked done. */
    @Test
    fun `a history that does not line up with its json never settles`() {
        val entries = listOf(entry(episodeNumber = 7.0))

        assertFalse(seedLegacyHistory(entries, "not json", alreadySeeded = false).settled)
        assertFalse(seedLegacyHistory(entries, "[]", alreadySeeded = false).settled)
        assertFalse(seedLegacyHistory(emptyList(), "garbage", alreadySeeded = false).settled)
        assertFalse(seedLegacyHistory(entries, null, alreadySeeded = false).settled)
    }

    @Test
    fun `an install with no stored history settles at once`() {
        val seed = seedLegacyHistory(emptyList(), null, alreadySeeded = false)

        assertTrue(seed.settled)
        assertEquals(0, seed.legacyRows)
    }
}
