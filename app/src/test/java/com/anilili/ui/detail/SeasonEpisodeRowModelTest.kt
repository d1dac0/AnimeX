package com.anilili.ui.detail

import com.anilili.data.model.EpisodeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season download dialog's episode rows and counts (issue #59). The rows are composed lazily
 * from these models, so a row's copy, its checkbox state and the lazy keys are checked here rather
 * than in a Compose test.
 */
class SeasonEpisodeRowModelTest {
    private fun episode(
        pipeId: String,
        number: Double = 1.0,
        title: String? = null,
        filler: Boolean = false,
    ) = EpisodeItem(pipeId = pipeId, number = number, title = title, image = null, filler = filler)

    @Test
    fun `an ordinary episode shows its own title and the viewer's pick`() {
        val row = seasonEpisodeRow(
            episode = episode("anilist:21:3", title = "The Beginning"),
            skipFillers = false,
            selectedEpisodeIds = setOf("anilist:21:3"),
        )

        assertEquals("anilist:21:3", row.pipeId)
        assertEquals("Episode 1", row.title)
        assertEquals("The Beginning", row.subtitle)
        assertTrue(row.selected)
        assertTrue(row.enabled)
    }

    @Test
    fun `a filler row is labelled and skipped when the batch skips fillers`() {
        val row = seasonEpisodeRow(
            episode = episode("f", number = 6.0, title = "Recap", filler = true),
            skipFillers = true,
            selectedEpisodeIds = setOf("f"),
        )

        assertEquals("Episode 6", row.title)
        assertEquals("Recap · Filler · skipped", row.subtitle)
        assertFalse(row.selected)
        assertFalse(row.enabled)
    }

    @Test
    fun `a filler row stays choosable when the batch does not skip fillers`() {
        val row = seasonEpisodeRow(
            episode = episode("f", filler = true),
            skipFillers = false,
            selectedEpisodeIds = emptySet(),
        )

        assertEquals("Filler", row.subtitle)
        assertTrue(row.enabled)
        assertFalse(row.selected)
    }

    @Test
    fun `a title that only echoes the number is not printed twice`() {
        val row = seasonEpisodeRow(
            episode = episode("e", number = 6.0, title = "EP 6", filler = true),
            skipFillers = true,
            selectedEpisodeIds = emptySet(),
        )

        assertEquals("Filler · skipped", row.subtitle)
    }

    @Test
    fun `an episode with nothing to say has no subtitle`() {
        val row = seasonEpisodeRow(
            episode = episode("e", title = null),
            skipFillers = false,
            selectedEpisodeIds = emptySet(),
        )

        assertNull(row.subtitle)
    }

    @Test
    fun `a picked episode stays picked and a skipped one does not, at any order of toggles`() {
        val picked = seasonEpisodeRow(
            episode = episode("e", filler = true),
            skipFillers = false,
            selectedEpisodeIds = setOf("e"),
        )
        val skipped = seasonEpisodeRow(
            episode = episode("e", filler = true),
            skipFillers = true,
            selectedEpisodeIds = setOf("e"),
        )

        assertTrue(picked.selected)
        assertFalse(skipped.selected)
    }

    @Test
    fun `the lazy row key is the pipe id`() {
        val episodes = listOf(
            episode("a", number = 1.0),
            episode("b", number = 2.0),
            episode("c", number = 3.0),
        )

        assertEquals(
            listOf("a", "b", "c"),
            episodes.map { seasonEpisodeRow(it, skipFillers = false, selectedEpisodeIds = emptySet()).pipeId },
        )
    }

    @Test
    fun `the selection counts only what would download`() {
        val episodes = listOf(
            episode("a", filler = false),
            episode("b", filler = true),
            episode("c", filler = false),
        )

        val keepingFillers = seasonDownloadSelection(episodes, setOf("a", "b"), skipFillers = false)
        assertEquals(2, keepingFillers.selectedCount)
        assertEquals(1, keepingFillers.fillerCount)

        val skippingFillers = seasonDownloadSelection(episodes, setOf("a", "b"), skipFillers = true)
        assertEquals(1, skippingFillers.selectedCount)
        assertEquals(1, skippingFillers.fillerCount)
    }

    @Test
    fun `an empty selection counts zero`() {
        val counts = seasonDownloadSelection(listOf(episode("a")), emptySet(), skipFillers = false)

        assertEquals(0, counts.selectedCount)
    }
}
