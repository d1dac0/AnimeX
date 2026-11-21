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
 * The index is counted in items, not in px, because a rebuilt list has different measurements.
 * Free of Android and Compose types so it can be unit-tested on the JVM.
 */
class TvFocusMemoryStore {

    private var armedRail: String? = null
    private val indices = mutableMapOf<String, Int>()

    /** Records that [index] in [rail] is where focus is now. Does not arm a restore. */
    fun focused(rail: String, index: Int) {
        indices[rail] = index
    }

    /** The last index the viewer was on in [rail], for seeding direction searches. */
    fun indexOf(rail: String): Int = indices[rail] ?: 0

    /**
     * Records [index] in [rail] and arms it to be restored when the viewer comes back. Called just
     * before the screen is left from that card; a later [arm] on another rail replaces it.
     */
    fun arm(rail: String, index: Int) {
        indices[rail] = index
        armedRail = rail
    }

    /**
     * The index to put the cursor back on, or null when [rail] is not the armed one.
     *
     * Consumed on read: a rail restores once per return, and a later recomposition of the same
     * screen must not drag the cursor back to a place the viewer has since left.
     */
    fun consumeRestore(rail: String): Int? {
        if (armedRail != rail) return null
        armedRail = null
        return indices[rail]
    }
}

/** The app's one rail memory; process-level on purpose, see [TvFocusMemoryStore]. */
val TvFocusMemory = TvFocusMemoryStore()
