package com.anilili.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.anilili.data.settings.PosterElement
import com.anilili.data.settings.SettingsStore

/**
 * Which of a poster's badges and captions this viewer has left switched on.
 *
 * Read through one helper rather than by collecting the preference at each card, because the same
 * poster is drawn by five different composables — the catalogue grid, the library grid, Continue
 * Watching, the television rails and the offline shelf — and a badge that obeys the setting on one
 * screen but not another reads as a bug in the screen rather than as a setting that missed it.
 */
@Immutable
@JvmInline
value class PosterElementVisibility internal constructor(private val hidden: Set<PosterElement>) {
    /** Asks the positive question, so call sites read as "if this card shows a dub marker". */
    fun shows(element: PosterElement): Boolean = element !in hidden
}

/**
 * The poster preference for a subtree of cards, provided once by a page that draws many of them
 * (#50). Home's rails compose fifty or more cards, and every card a rail prefetches while the
 * viewer scrolls; each used to start its own collector of [SettingsStore.hiddenPosterElements] as
 * it was composed, reading one preference fifty times to get the same answer. Pages that provide
 * this local let their cards share one read; cards that find nothing provided keep collecting for
 * themselves, which is what screens with a handful of cards have always done.
 */
val LocalPosterElements = staticCompositionLocalOf<PosterElementVisibility?> { null }

/** The current poster preference, recomposing every card that reads it when it changes. */
@Composable
fun rememberPosterElements(): PosterElementVisibility {
    // The provided value is the point of the local (#50): cards read the setting through this
    // helper, so a page that provides it once has no card start a collector of its own.
    LocalPosterElements.current?.let { return it }
    val hidden by SettingsStore.hiddenPosterElements.collectAsState()
    return remember(hidden) { PosterElementVisibility(hidden) }
}
