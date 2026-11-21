package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of the id-map load (#84): how a parsed row contributes to the three tables, and
 * what a lookup answers before any row has landed. `android.util.JsonReader` is a null-returning
 * stub in the JVM unit tests, so the streaming walk itself ([KonohaClient.parseIdMap]) is
 * exercised only on a device.
 */
class IdMapTablesTest {
    private val tables = IdMapTables()

    @Test
    fun aFreshInstanceAnswersNullToEveryLookup() {
        // The state a lookup sees while the load is still running: null, never a block.
        assertNull(tables.tmdbRef(1))
        assertNull(tables.tvdbRef(1))
        assertNull(tables.anilistForMal(1))
        assertEquals(0, tables.entries)
    }

    @Test
    fun aFullRowFeedsAllThreeTables() {
        tables.addRow(anilistId = 1, tmdbId = 30991, tmdbType = "tv", tvdbId = 76885, tvdbSeason = 1, malId = 1)

        assertEquals(TmdbRef(id = 30991, type = "tv"), tables.tmdbRef(1))
        assertEquals(TvdbRef(id = 76885, season = 1), tables.tvdbRef(1))
        assertEquals(1, tables.anilistForMal(1))
        assertEquals(1, tables.entries)
    }

    @Test
    fun aRowWithoutMalStillFeedsTheIdTables() {
        // The old walk recorded the TMDB and TVDB placements before it returned on a missing MAL
        // id; addRow keeps that order, because about a fifth of the rows have no MAL mapping.
        tables.addRow(anilistId = 21, tmdbId = 30991, tmdbType = "tv", tvdbId = 76885, tvdbSeason = 1, malId = null)

        assertEquals(TmdbRef(id = 30991, type = "tv"), tables.tmdbRef(21))
        assertEquals(TvdbRef(id = 76885, season = 1), tables.tvdbRef(21))
        assertNull(tables.anilistForMal(21))
    }

    @Test
    fun missingSubfieldsComeBackAsNullOnTheRef() {
        tables.addRow(anilistId = 5, tmdbId = 26453, tmdbType = null, tvdbId = 77680, tvdbSeason = null, malId = 6)

        assertEquals(TmdbRef(id = 26453, type = null), tables.tmdbRef(5))
        assertEquals(TvdbRef(id = 77680, season = null), tables.tvdbRef(5))
    }

    @Test
    fun aRowWithNoneOfTheUsedFieldsIsStillCountedButAnswersNull() {
        // A row carrying only the fields this build never reads (kitsu, confidence, …) parses to
        // nothing, and the walk still counts it as consumed.
        tables.addRow(anilistId = 9, tmdbId = null, tmdbType = null, tvdbId = null, tvdbSeason = null, malId = null)

        assertEquals(1, tables.entries)
        assertNull(tables.tmdbRef(9))
        assertNull(tables.tvdbRef(9))
        assertNull(tables.anilistForMal(9))
    }

    @Test
    fun aLaterRowOverwritesAnEarlierOneOnTheSameAnilistId() {
        tables.addRow(anilistId = 1, tmdbId = 1, tmdbType = "movie", tvdbId = 2, tvdbSeason = 3, malId = 10)
        tables.addRow(anilistId = 1, tmdbId = 99, tmdbType = "tv", tvdbId = 98, tvdbSeason = 0, malId = 11)

        assertEquals(TmdbRef(id = 99, type = "tv"), tables.tmdbRef(1))
        assertEquals(TvdbRef(id = 98, season = 0), tables.tvdbRef(1))
        assertEquals(2, tables.entries)
    }

    @Test
    fun aLaterRowOverwritesAnEarlierOneOnTheSameMalId() {
        tables.addRow(anilistId = 1, tmdbId = null, tmdbType = null, tvdbId = null, tvdbSeason = null, malId = 1)
        tables.addRow(anilistId = 5, tmdbId = null, tmdbType = null, tvdbId = null, tvdbSeason = null, malId = 1)

        assertEquals(5, tables.anilistForMal(1))
    }

    @Test
    fun entriesCountsEveryRowTheWalkConsumed() {
        tables.addRow(anilistId = 1, tmdbId = 30991, tmdbType = "tv", tvdbId = 76885, tvdbSeason = 1, malId = 1)
        tables.addRow(anilistId = 5, tmdbId = 26453, tmdbType = "tv", tvdbId = 77680, tvdbSeason = 0, malId = 5)
        tables.addRow(anilistId = 6, tmdbId = null, tmdbType = null, tvdbId = null, tvdbSeason = null, malId = 6)

        assertEquals(3, tables.entries)
    }
}
