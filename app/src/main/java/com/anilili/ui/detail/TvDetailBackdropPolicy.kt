package com.anilili.ui.detail

/**
 * The decisions behind the TV title page's backdrop (#58).
 *
 * The backdrop is the still of the episode the cursor is on, drawn full-screen under two
 * gradients. Every step of a held Right used to recompose it with a new model: a new request and
 * the shared ImageLoader's 150ms crossfade, per press, on a picture that takes the whole screen to
 * decode. Home's hero already solves the same shape by waiting for the cursor to settle before it
 * looks anything up (`TV_HERO_BACKDROP_SETTLE_MS`); these are that page's decisions, restated for
 * this one. They are plain functions so the JVM suite can pin them — Compose does not run there.
 */

/** How long the cursor must rest on an episode before the low-cost backdrop starts its load. */
internal const val TV_DETAIL_BACKDROP_SETTLE_MS = 600L

/**
 * What the backdrop draws: the episode's own still while the viewer has asked to see episode art
 * at all, the season's artwork standing in otherwise. Blurring a full-screen backdrop would leave
 * the page looking broken rather than spoiler-free, so the season's own artwork stands in while
 * episode images are hidden; and a season with no stills must not open on a black page.
 */
internal fun tvDetailBackdropSource(
    episodeImage: Any?,
    seasonArtwork: Any?,
    showEpisodeImages: Boolean,
): Any? = episodeImage?.takeIf { showEpisodeImages } ?: seasonArtwork

/**
 * Whether the backdrop follows the cursor step by step. Full-cost devices keep today's behaviour —
 * a new model per press, the shared loader's crossfade on it — and only the low-cost TV path
 * settles first and cuts instead.
 */
internal fun tvDetailBackdropFollowsCursorLive(useLowCostTvEffects: Boolean): Boolean =
    !useLowCostTvEffects
