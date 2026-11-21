package com.anilili.ui.detail

import com.anilili.data.model.EpisodeItem

/**
 * One episode row of the season download dialog, derived without Compose so the lazy list's keys
 * and a row's copy are checkable in the JVM suite (issue #59).
 *
 * [pipeId] is the lazy row's key, [title] and [subtitle] are the row's copy, and [selected] and
 * [enabled] are the checkbox's state: a filler row is skipped — disabled and unchecked, whatever
 * the viewer picked before — whenever the batch is set to skip fillers.
 */
internal data class SeasonEpisodeRowModel(
    val pipeId: String,
    val title: String,
    val subtitle: String?,
    val selected: Boolean,
    val enabled: Boolean,
)

/** Derives the season download dialog's row for [episode]. */
internal fun seasonEpisodeRow(
    episode: EpisodeItem,
    skipFillers: Boolean,
    selectedEpisodeIds: Set<String>,
): SeasonEpisodeRowModel {
    val skipped = skipFillers && episode.filler
    val subtitle = listOfNotNull(
        episode.distinctTitle,
        when {
            skipped -> "Filler · skipped"
            episode.filler -> "Filler"
            else -> null
        },
    ).joinToString(" · ").takeIf(String::isNotEmpty)
    return SeasonEpisodeRowModel(
        pipeId = episode.pipeId,
        title = "Episode ${episode.displayNumber}",
        subtitle = subtitle,
        selected = !skipped && episode.pipeId in selectedEpisodeIds,
        enabled = !skipped,
    )
}

/** What the dialog counts, walked once per input change instead of on every recomposition (#59). */
internal data class SeasonDownloadSelection(
    val selectedCount: Int,
    val fillerCount: Int,
)

/** Counts the batch's selection and the list's fillers, without changing the list's episode order. */
internal fun seasonDownloadSelection(
    episodes: List<EpisodeItem>,
    selectedEpisodeIds: Set<String>,
    skipFillers: Boolean,
): SeasonDownloadSelection = SeasonDownloadSelection(
    selectedCount = selectedDownloadEpisodes(episodes, selectedEpisodeIds, skipFillers).size,
    fillerCount = episodes.count(EpisodeItem::filler),
)

/** The content type every episode row shares, so the lazy list reuses their composition (#59). */
internal const val SEASON_EPISODE_ROW_CONTENT_TYPE = "season-episode-row"
