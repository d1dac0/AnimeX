package com.anilili.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusMemoryTest {

    private val rail = listOf(10, 20, 30, 40, 50)

    @Test
    fun `focusing a card remembers it but arms nothing`() {
        val memory = TvFocusMemoryStore()

        memory.focused("continue:home", 3, 40)

        assertEquals(3, memory.indexOf("continue:home"))
        assertNull(memory.consumeRestore("continue:home", rail))
    }

    @Test
    fun `an armed rail restores once`() {
        val memory = TvFocusMemoryStore()

        memory.arm("media:Trending:1", 4, 50)

        assertEquals(4, memory.consumeRestore("media:Trending:1", rail))
        assertNull(memory.consumeRestore("media:Trending:1", rail))
    }

    @Test
    fun `only the armed rail restores`() {
        val memory = TvFocusMemoryStore()
        memory.focused("continue:profile", 2, 30)

        memory.arm("continue:home", 1, 20)

        assertNull(memory.consumeRestore("continue:profile", rail))
        assertEquals(1, memory.consumeRestore("continue:home", rail))
    }

    @Test
    fun `focus moving after arming does not re-arm or change the target rail`() {
        val memory = TvFocusMemoryStore()
        memory.arm("continue:home", 1, 20)

        memory.focused("media:Trending:1", 4, 50)

        assertNull(memory.consumeRestore("media:Trending:1", rail))
        assertEquals(1, memory.consumeRestore("continue:home", rail))
    }

    @Test
    fun `a later arm replaces the earlier one`() {
        val memory = TvFocusMemoryStore()
        memory.arm("continue:home", 1, 20)

        memory.arm("media:Trending:1", 2, 30)

        assertNull(memory.consumeRestore("continue:home", rail))
        assertEquals(2, memory.consumeRestore("media:Trending:1", rail))
    }

    @Test
    fun `an unknown rail starts at its first card`() {
        assertEquals(0, TvFocusMemoryStore().indexOf("never-seen"))
        assertEquals(0, TvFocusMemoryStore().indexOf("never-seen", rail))
    }

    /** Resume the fourth card, watch, Back: that show is now first and the cursor follows it. */
    @Test
    fun `a restore follows the card to its new position`() {
        val memory = TvFocusMemoryStore()
        memory.arm("continue:home", 3, 40)

        assertEquals(0, memory.consumeRestore("continue:home", listOf(40, 10, 20, 30, 50)))
    }

    @Test
    fun `the remembered index follows the card too`() {
        val memory = TvFocusMemoryStore()
        memory.focused("continue:home", 3, 40)

        assertEquals(0, memory.indexOf("continue:home", listOf(40, 10, 20, 30, 50)))
    }

    /** The title was dropped from the row while away: land on the card that took its place. */
    @Test
    fun `a restore whose card is gone falls back to the stored index`() {
        val memory = TvFocusMemoryStore()
        memory.arm("continue:home", 3, 40)

        assertEquals(3, memory.consumeRestore("continue:home", listOf(10, 20, 30, 50)))
    }

    @Test
    fun `a restore whose card is gone from the end clamps to the last card`() {
        val memory = TvFocusMemoryStore()
        memory.arm("continue:home", 4, 50)

        assertEquals(2, memory.consumeRestore("continue:home", listOf(10, 20, 30)))
    }

    @Test
    fun `a restore into an empty rail has nowhere to land and is still consumed`() {
        val memory = TvFocusMemoryStore()
        memory.arm("continue:home", 1, 20)

        assertNull(memory.consumeRestore("continue:home", emptyList()))
        assertNull(memory.consumeRestore("continue:home", rail))
    }

    @Test
    fun `resolution prefers the key and falls back to the clamped index`() {
        assertEquals(1, resolveRailIndex(index = 4, key = 20, keys = rail))
        assertEquals(4, resolveRailIndex(index = 4, key = 99, keys = rail))
        assertEquals(4, resolveRailIndex(index = 9, key = null, keys = rail))
        assertEquals(0, resolveRailIndex(index = -1, key = null, keys = rail))
        assertNull(resolveRailIndex(index = 0, key = 20, keys = emptyList()))
    }
}
