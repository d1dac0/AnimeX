package com.anilili.ui.adaptive

/**
 * Where the remote was, per rail, remembered across navigation.
 *
 * A D-pad has no pointer to go back and find the card the viewer left on. Compose lays a screen out
 * again when its navigation entry returns, so a rail that is rebuilt starts from the first card and
 * the viewer has to walk back to where they were — on Home that is a row and a position they chose
 * seconds ago, and the screen reads as having reset itself.
 *
 * Deliberately a process-level map rather than saved state: this is about the next few seconds of
 * one session, not about surviving a restart, and a rail that is gone from the screen should not
 * pin a cursor somewhere else.
 *
 * Remembering and restoring are two separate things. [focused] only remembers, for seeding the
 * direction searches; a restore is armed by [arm], which a rail calls from the clicks that leave
 * the screen (opening a title, resuming an episode). Arming on every focus change pulled the cursor
 * back onto a rail on returns the viewer had not started from a rail at all — the bar's Home tab,
 * Back from a title opened from the hero.
 *
 * A spot is the card's key with its index beside it. The key wins: playback moves the title just
 * watched to the front of Continue watching, so the index alone put the cursor on whichever show
 * had slid into the old position. The index is the fallback for a card that is gone, counted in
 * items rather than px because a rebuilt list has different measurements.
 *
 * Free of Android and Compose types so it can be unit-tested on the JVM.
 */
class TvFocusMemoryStore {

    private class Spot(val index: Int, val key: Any?)

    private var armedRail: String? = null
    private val spots = mutableMapOf<String, Spot>()

    /** Records that the card [key] at [index] in [rail] is where focus is now. Does not arm a restore. */
    fun focused(rail: String, index: Int, key: Any? = null) {
        spots[rail] = Spot(index, key)
    }

    /**
     * The last index the viewer was on in [rail], for seeding direction searches: where that card
     * now sits in [keys] when they are given, otherwise the remembered index.
     */
    fun indexOf(rail: String, keys: List<Any?>? = null): Int {
        val spot = spots[rail] ?: return 0
        if (keys == null) return spot.index
        return resolveRailIndex(spot.index, spot.key, keys) ?: 0
    }

    /**
     * Records the card [key] at [index] in [rail] and arms it to be restored when the viewer comes
     * back. Called just before the screen is left from that card; a later [arm] replaces it.
     */
    fun arm(rail: String, index: Int, key: Any? = null) {
        spots[rail] = Spot(index, key)
        armedRail = rail
    }

    /**
     * The index in [keys] to put the cursor back on, or null when [rail] is not the armed one or has
     * nothing left to land on.
     *
     * Consumed on read: a rail restores once per return, and a later recomposition of the same
     * screen must not drag the cursor back to a place the viewer has since left.
     */
    fun consumeRestore(rail: String, keys: List<Any?>): Int? {
        if (armedRail != rail) return null
        armedRail = null
        val spot = spots[rail] ?: return null
        return resolveRailIndex(spot.index, spot.key, keys)
    }
}

/**
 * Where the card [key] sits in [keys]; when it is gone (or there is no key), [index] clamped to the
 * list, so the cursor lands on the card that took its place. Null for an empty list.
 */
internal fun resolveRailIndex(index: Int, key: Any?, keys: List<Any?>): Int? {
    if (keys.isEmpty()) return null
    if (key != null) {
        val at = keys.indexOf(key)
        if (at >= 0) return at
    }
    return index.coerceIn(0, keys.lastIndex)
}

/** The app's one rail memory; process-level on purpose, see [TvFocusMemoryStore]. */
val TvFocusMemory = TvFocusMemoryStore()
