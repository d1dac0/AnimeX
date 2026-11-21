package com.anilili.ui.home

import com.anilili.data.model.Media
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The settle window between a rail and the hero (#47): what the promotion effect in
 * [TvHomeContent] asks, asked here with an injected clock so the window's semantics are
 * decided without a coroutine. The rendering side of the swap — the recomposition scope and
 * the banner decode — is not coverable in the unit JVM.
 */
class TvHeroPreviewGateTest {
    private class Clock(var nowMs: Long = 0L) {
        operator fun invoke(): Long = nowMs
    }

    @Test
    fun aPreviewIsHeldUntilTheSettleWindowHasPassed() {
        val clock = Clock()
        val gate = TvHeroPreviewGate(settleMs = 600, elapsedNow = clock::invoke)
        val card = Media(id = 1)

        gate.onCardFocused(card, useLowCostTvEffects = false)
        clock.nowMs = 599
        assertNull(gate.settledPromotion())

        clock.nowMs = 600
        assertSame(card, gate.settledPromotion())
    }

    @Test
    fun aNewCardRestartsTheWindowAndTheOlderOneIsNeverPromoted() {
        // Holding Right: the first card's own window expires while the cursor is already on the
        // second, and only the second — the one the cursor settled on — may take the hero.
        val clock = Clock()
        val gate = TvHeroPreviewGate(settleMs = 600, elapsedNow = clock::invoke)
        val first = Media(id = 1)
        val second = Media(id = 2)

        gate.onCardFocused(first, useLowCostTvEffects = false)
        clock.nowMs = 500
        gate.onCardFocused(second, useLowCostTvEffects = false)

        clock.nowMs = 600
        assertNull(gate.settledPromotion())

        clock.nowMs = 1100
        assertSame(second, gate.settledPromotion())
    }

    @Test
    fun returningToTheCardAlreadyWaitingDoesNotMakeItWaitAgain() {
        // Walking back and forth over one card must not push its preview further away every
        // time: the window belongs to the card, not to the press.
        val clock = Clock()
        val gate = TvHeroPreviewGate(settleMs = 600, elapsedNow = clock::invoke)
        val card = Media(id = 1)

        gate.onCardFocused(card, useLowCostTvEffects = false)
        clock.nowMs = 300
        gate.onCardFocused(card, useLowCostTvEffects = false)

        clock.nowMs = 600
        assertSame(card, gate.settledPromotion())
    }

    @Test
    fun aLowCostBoxIsOfferedNoPreviewAtAll() {
        // #47: even one hero swap is a full-screen recomposition and a ~1920x600 decode the
        // low-cost hardware cannot carry, so the gate refuses rather than debounces.
        val clock = Clock()
        val gate = TvHeroPreviewGate(settleMs = 600, elapsedNow = clock::invoke)

        gate.onCardFocused(Media(id = 1), useLowCostTvEffects = true)
        clock.nowMs = 10_000

        assertNull(gate.pending)
        assertNull(gate.settledPromotion())
    }

    @Test
    fun focusReturningToTheHeroDropsTheWaitingPreview() {
        val clock = Clock()
        val gate = TvHeroPreviewGate(settleMs = 600, elapsedNow = clock::invoke)
        val card = Media(id = 1)

        gate.onCardFocused(card, useLowCostTvEffects = false)
        clock.nowMs = 300
        gate.onHeroFocused()

        assertNull(gate.pending)
        clock.nowMs = 10_000
        assertNull(gate.settledPromotion())
    }

    @Test
    fun aNullPreviewFromTheRailChangesNothing() {
        val clock = Clock()
        val gate = TvHeroPreviewGate(settleMs = 600, elapsedNow = clock::invoke)

        gate.onCardFocused(null, useLowCostTvEffects = false)

        assertNull(gate.pending)
        assertNull(gate.settledPromotion())
    }
}
