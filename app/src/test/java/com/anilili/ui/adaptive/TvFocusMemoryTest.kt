package com.anilili.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusMemoryTest {

    @Test
    fun `focusing a card remembers it but arms nothing`() {
        val memory = TvFocusMemoryStore()

        memory.focused("continue:home", 3)

        assertEquals(3, memory.indexOf("continue:home"))
        assertNull(memory.consumeRestore("continue:home"))
    }

    @Test
    fun `an armed rail restores once`() {
        val memory = TvFocusMemoryStore()

        memory.arm("media:Trending:1", 4)

        assertEquals(4, memory.consumeRestore("media:Trending:1"))
        assertNull(memory.consumeRestore("media:Trending:1"))
    }

    @Test
    fun `only the armed rail restores`() {
        val memory = TvFocusMemoryStore()
        memory.focused("continue:profile", 2)

        memory.arm("continue:home", 1)

        assertNull(memory.consumeRestore("continue:profile"))
        assertEquals(1, memory.consumeRestore("continue:home"))
    }

    @Test
    fun `focus moving after arming does not re-arm or change the target rail`() {
        val memory = TvFocusMemoryStore()
        memory.arm("continue:home", 1)

        memory.focused("media:Trending:1", 5)

        assertNull(memory.consumeRestore("media:Trending:1"))
        assertEquals(1, memory.consumeRestore("continue:home"))
    }

    @Test
    fun `a later arm replaces the earlier one`() {
        val memory = TvFocusMemoryStore()
        memory.arm("continue:home", 1)

        memory.arm("media:Trending:1", 2)

        assertNull(memory.consumeRestore("continue:home"))
        assertEquals(2, memory.consumeRestore("media:Trending:1"))
    }

    @Test
    fun `an unknown rail starts at its first card`() {
        assertEquals(0, TvFocusMemoryStore().indexOf("never-seen"))
    }
}
