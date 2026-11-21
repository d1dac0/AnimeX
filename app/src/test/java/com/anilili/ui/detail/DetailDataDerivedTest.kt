package com.anilili.ui.detail

import com.anilili.data.model.Category
import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import com.anilili.data.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [DetailData]'s season chain, labels, watch order and suggestions used to be getters recomputed on
 * every read; they are now computed once per value and held. These pin that the held values are
 * exactly what the getters answered — the old logic is restated here against the same inputs —
 * and that a second read hands back the same instance, which is what lets the TV rows skip.
 */
class DetailDataDerivedTest {
    private fun media(id: Int, format: String, title: String = "Entry $id") = Media(
        id = id,
        title = MediaTitle(english = title),
        format = format,
        episodes = 12,
    )

    private val season1 = media(10, "TV", "Season 1")
    private val season2 = media(20, "TV", "Season 2")
    private val movie = media(30, "MOVIE", "The Movie")
    private val ova = media(40, "OVA", "An OVA")
    private val recommendations = listOf(
        media(20, "TV"), // a season of this series: not a suggestion
        media(10, "TV"), // the page itself: not a suggestion
        media(500, "TV", "Something else"),
        media(600, "MOVIE", "Another film"),
    )

    private fun data() = DetailData(
        info = season1,
        episodes = emptyList(),
        preferredCategory = Category.SUB,
        series = listOf(season1, movie, season2, ova),
        recommendations = recommendations,
    )

    @Test
    fun `held values match the getters they replaced`() {
        val page = data()
        val hideAdult = SettingsStore.hideAdultContent.value

        val seasons = seasonChain(page.info, page.series)
        val labels = seasonChainLabels(seasons)
        val watchOrder = buildWatchOrder(
            info = page.info,
            series = page.series,
            seasons = seasons,
            hideAdult = hideAdult,
        )
        val franchise = watchOrder.mapTo(mutableSetOf()) { it.media.id }
        val suggestions = page.recommendations.filter { it.id != page.info.id && it.id !in franchise }

        assertEquals(seasons, page.seasons)
        assertEquals(labels, page.seasonLabels)
        assertEquals(watchOrder, page.watchOrder)
        assertEquals(suggestions, page.suggestions)
        assertEquals(listOf(500, 600), page.suggestions.map(Media::id))
    }

    @Test
    fun `a second read is the same instance`() {
        val page = data()

        assertSame(page.seasons, page.seasons)
        assertSame(page.seasonLabels, page.seasonLabels)
        assertSame(page.watchOrder, page.watchOrder)
        assertSame(page.suggestions, page.suggestions)
    }

    @Test
    fun `a copy derives its own values from its own inputs`() {
        val page = data()
        page.seasons // fill the cache before copying

        val shorter = page.copy(series = listOf(season1, season2))

        assertEquals(seasonChain(shorter.info, shorter.series), shorter.seasons)
        assertEquals(listOf(10, 20), shorter.seasons.map(Media::id))
        assertEquals(page, page.copy())
    }
}
