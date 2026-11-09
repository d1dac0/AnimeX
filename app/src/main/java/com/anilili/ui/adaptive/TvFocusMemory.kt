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
 * pin a cursor somewhere else. Only the rail that last held focus restores; everything else keeps
 * its own last position and stays out of the way.
 *
 * The index is counted in items, not in px, because a rebuilt list has different measurements.
 */
object TvFocusMemory {

    private var focusedRail: String? = null
    private val indices = mutableMapOf<String, Int>()

    /** Records that [index] in [rail] is where focus is now. */
    fun focused(rail: String, index: Int) {
        focusedRail = rail
        indices[rail] = index
    }

    /** The last index the viewer was on in [rail], for seeding direction searches. */
    fun indexOf(rail: String): Int = indices[rail] ?: 0

    /**
     * The index to put the cursor back on, or null when [rail] is not the one that had it.
     *
     * Consumed on read: a rail restores once per return, and a later recomposition of the same
     * screen must not drag the cursor back to a place the viewer has since left.
     */
    fun consumeRestore(rail: String): Int? {
        if (focusedRail != rail) return null
        focusedRail = null
        return indices[rail]
    }
}
