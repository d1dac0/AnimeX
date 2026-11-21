package com.anilili.ui.components

import com.anilili.data.settings.PosterElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derivation every card's captions and badges follow (#50): whether [PosterElementVisibility]
 * shows an element. The collector count around it — one for a page that provides
 * [LocalPosterElements], one per card where nothing is provided — is a composition property, not
 * reachable from the unit JVM.
 */
class PosterElementsVisibilityTest {
    @Test
    fun nothingHiddenShowsEveryElement() {
        val poster = PosterElementVisibility(emptySet())

        PosterElement.entries.forEach { element ->
            assertTrue("expected $element shown", poster.shows(element))
        }
    }

    @Test
    fun onlyTheHiddenElementsAreSwitchedOff() {
        val poster = PosterElementVisibility(setOf(PosterElement.TITLE, PosterElement.DUB))

        assertFalse(poster.shows(PosterElement.TITLE))
        assertFalse(poster.shows(PosterElement.DUB))
        assertTrue(poster.shows(PosterElement.SCORE))
        assertTrue(poster.shows(PosterElement.NEW_EPISODES))
        assertTrue(poster.shows(PosterElement.PROGRESS))
        assertTrue(poster.shows(PosterElement.ADULT))
        assertTrue(poster.shows(PosterElement.DETAILS))
    }

    @Test
    fun hidingEverythingLeavesTheCardWithoutCaptions() {
        val poster = PosterElementVisibility(PosterElement.entries.toSet())

        PosterElement.entries.forEach { element ->
            assertFalse("expected $element hidden", poster.shows(element))
        }
    }
}
