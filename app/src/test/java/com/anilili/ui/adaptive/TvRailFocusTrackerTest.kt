package com.anilili.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvRailFocusTrackerTest {

    @Test
    fun `removing the focused card recovers onto the neighbour that took its place`() {
        val tracker = TvRailFocusTracker()
        tracker.cardFocused(30, 2)
        val after = listOf(10, 20, 40, 50)
        // Compose reports the loss after the row has recomposed without the card.
        tracker.rowLostFocus(after)

        assertEquals(2, tracker.recoveryIndex(after))
    }

    @Test
    fun `removing the focused last card recovers onto the new last card`() {
        val tracker = TvRailFocusTracker()
        tracker.cardFocused(50, 4)

        assertEquals(3, tracker.recoveryIndex(listOf(10, 20, 30, 40)))
    }

    @Test
    fun `nothing is recovered while the focused card is still there`() {
        val tracker = TvRailFocusTracker()
        tracker.cardFocused(30, 2)

        assertNull(tracker.recoveryIndex(listOf(30, 10, 20)))
    }

    @Test
    fun `a row the viewer left does not pull focus back`() {
        val tracker = TvRailFocusTracker()
        tracker.cardFocused(30, 2)
        tracker.rowLostFocus(listOf(10, 20, 30))

        assertNull(tracker.recoveryIndex(listOf(10, 20)))
    }

    @Test
    fun `a loss kept by the rail, such as an open menu, still recovers`() {
        val tracker = TvRailFocusTracker()
        tracker.cardFocused(30, 2)
        tracker.rowLostFocus(listOf(10, 20, 30), keep = true)

        assertEquals(1, tracker.recoveryIndex(listOf(10, 20)))
    }

    @Test
    fun `a row that never held focus recovers nothing`() {
        assertNull(TvRailFocusTracker().recoveryIndex(listOf(10)))
    }

    @Test
    fun `an emptied row is left to its host`() {
        val tracker = TvRailFocusTracker()
        tracker.cardFocused(30, 0)

        assertNull(tracker.recoveryIndex(emptyList()))
        assertNull(tracker.recoveryIndex(listOf(10)))
    }

    @Test
    fun `recovery happens once per removal`() {
        val tracker = TvRailFocusTracker()
        tracker.cardFocused(30, 2)
        val after = listOf(10, 20, 40)

        assertEquals(2, tracker.recoveryIndex(after))
        assertNull(tracker.recoveryIndex(after))
    }
}
