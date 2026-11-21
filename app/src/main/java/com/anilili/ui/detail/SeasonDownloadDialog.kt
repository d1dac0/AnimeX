package com.anilili.ui.detail

import android.text.format.Formatter
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.Category
import com.anilili.data.model.SourceVariant
import com.anilili.data.ProviderCatalog
import com.anilili.data.settings.DEFAULT_PREFERRED_PROVIDER
import com.anilili.data.settings.DownloadDestination
import com.anilili.data.settings.DownloadQuality
import com.anilili.data.settings.SettingsStore
import com.anilili.playback.BatchQualityCheck
import com.anilili.playback.DownloadLocations
import com.anilili.playback.DownloadStorage
import com.anilili.playback.HardSubDownloads
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightChoicePicker
import com.anilili.ui.dialogs.MidnightSettingRow
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightPrimaryButton
import com.anilili.ui.dialogs.MidnightSelectionRow
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightSettingSwitchRow
import com.anilili.ui.dialogs.MidnightStorageStrip
import com.anilili.ui.dialogs.MidnightUnderlineSelector
import com.anilili.ui.watch.DownloadSpaceVolume
import com.anilili.ui.watch.LIBRARY_CACHE_VOLUME_LABEL
import com.anilili.ui.watch.downloadSpacePlan

/**
 * Which subtitle shape a batch asks its server for.
 *
 * [choice] is what the viewer picked, and it is deliberately allowed to go stale: changing the
 * server or the audio changes what is on offer, and a soft sub carried over to a hard-sub-only
 * server would be a request nothing can answer. Falling back to the server's own first published
 * shape is what the queue did implicitly before there was anything to pick.
 */
internal fun effectiveDownloadVariant(
    choice: SourceVariant?,
    options: List<SourceVariant>,
    category: Category,
    /** "Hard subs only" outranks [choice]: a server's hard sub is asked for whenever it has one. */
    hardSubsOnly: Boolean = false,
): SourceVariant = SourceVariant.HARD_SUB.takeIf {
    HardSubDownloads.appliesTo(hardSubsOnly, category) && it in options
}
    ?: choice?.takeIf { it in options }
    ?: options.firstOrNull()
    ?: SourceVariant.defaultFor(category)

/**
 * "Download these episodes" for the detail page's episode list.
 *
 * Deliberately scoped to what the list is currently showing rather than to the season as a whole.
 * This app models One Piece as one season of over a thousand episodes, so a literal "download the
 * season" is a single tap worth hundreds of gigabytes and hours of provider requests — nothing a
 * viewer can supervise, and nothing most devices can hold. The range picker already chunks a long
 * runner into hundreds, and a filtered list is an even smaller deliberate selection; taking the
 * scope from the screen keeps "all" bounded by construction and keeps it meaning what it looks
 * like it means.
 *
 * A batch has no resolved stream to describe and no per-download destination override — where the
 * selected episodes land is whatever the viewer set in Settings, which the dialog states rather
 * than asks.
 */
@Composable
internal fun SeasonDownloadDialog(
    seriesTitle: String,
    /** What "all" covers, e.g. "Season 2 · episodes 1–100" — shown so the scope is never implicit. */
    scopeLabel: String,
    artworkUrl: String?,
    episodes: List<EpisodeItem>,
    selectedEpisodeIds: Set<String>,
    skipFillers: Boolean,
    alreadyHeldCount: Int,
    category: Category,
    onCategoryChange: (Category) -> Unit,
    preferredProvider: String,
    onProviderChange: (String) -> Unit,
    /** The subtitle shape to ask that server for; only ever offered when it publishes more than one. */
    variant: SourceVariant,
    variantOptions: List<SourceVariant>,
    onVariantChange: (SourceVariant) -> Unit,
    /** Only servers with the subtitles burned in; remembered in Settings. See [HardSubDownloads]. */
    hardSubsOnly: Boolean,
    onHardSubsOnlyChange: (Boolean) -> Unit,
    quality: DownloadQuality,
    onQualityChange: (DownloadQuality) -> Unit,
    /** What the chosen server really offers for a sample episode; null when there is nothing to check. */
    qualityCheck: BatchQualityCheck?,
    /** Makes another server the batch's first choice — the check's suggestion. */
    onUseProvider: (String) -> Unit,
    /** Whether each episode may move to another server when its own would exceed [quality]. */
    matchQuality: Boolean,
    onMatchQualityChange: (Boolean) -> Unit,
    onEpisodeSelected: (EpisodeItem, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onSkipFillersChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var serverPickerVisible by remember { mutableStateOf(false) }
    val hideAdult by SettingsStore.hideAdultContent.collectAsState()
    val disabledProviders by SettingsStore.disabledProviders.collectAsState()
    val hardSubFilter = HardSubDownloads.appliesTo(hardSubsOnly, category)
    val servers = remember(hideAdult, disabledProviders, hardSubFilter) {
        listOf(DEFAULT_PREFERRED_PROVIDER) + ProviderCatalog.enabledProviders(
            ProviderCatalog.selectableProviders(hideAdult), disabledProviders,
        ).filter(ProviderCatalog::supportsOfflineDownload)
            // A soft-sub-only server as first choice would be turned away on every episode.
            .filter { !hardSubFilter || HardSubDownloads.mayOffer(it) }
    }
    val serverLabel: (String) -> String = {
        if (it == DEFAULT_PREFERRED_PROVIDER) "Auto" else ProviderCatalog.label(it)
    }
    if (serverPickerVisible) {
        MidnightChoicePicker(
            kicker = "Download episodes",
            title = "First-choice server",
            options = servers,
            selected = preferredProvider,
            label = serverLabel,
            onSelect = onProviderChange,
            onDismiss = { serverPickerVisible = false },
            explanation = "Tried first for each episode. If unavailable, another server may be used for the selected sub or dub.",
        )
        return
    }
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val downloadDestination by SettingsStore.downloadDestination.collectAsState()
    // Measured on the volume the episodes are actually going to, which is not the one the app runs
    // from. This dialog used to stat `filesDir` and call the answer the free space: a phone with
    // 4.5 GB internal and a far larger memory card selected was told a 27-episode season was "4 GB
    // short" and had the button disabled, while the same season at a lower resolution squeezed past
    // the internal figure and then downloaded to the card exactly as asked.
    val saveLocation = remember(context) { DownloadLocations.current(context) }
    val libraryFreeBytes = remember(context) { DownloadStorage.freeBytes(context) }
    val deviceFreeBytes = remember(saveLocation) {
        DownloadLocations.freeBytes(context, saveLocation)
    }
    val deviceSharesLibraryVolume = remember(saveLocation) {
        DownloadLocations.sharesVolume(context, saveLocation, context.filesDir)
    }
    val locationLabel = remember(saveLocation) { DownloadLocations.label(context, saveLocation) }
    val volumeLabel = remember(saveLocation) { DownloadLocations.volumeLabel(context, saveLocation) }
    // Recomputed per chosen resolution: the estimate, and therefore the warning, depends on it.
    val perEpisodeBytes = remember(quality) { DownloadStorage.estimatedEpisodeBytes(quality.maxHeight) }
    // Walked once per input change, not on every recomposition: the quality check's Checking state
    // ticks recompose this dialog several times a second, and filtering every episode each tick is
    // the O(n) recompute issue #59 called out beside the eager rows.
    val selection = remember(episodes, selectedEpisodeIds, skipFillers) {
        seasonDownloadSelection(episodes, selectedEpisodeIds, skipFillers)
    }
    val count = selection.selectedCount
    val fillerCount = selection.fillerCount
    val selectableCount = episodes.size - if (skipFillers) fillerCount else 0
    val allSelected = selectableCount > 0 && count == selectableCount
    val estimatedBytes = perEpisodeBytes * count
    val space = downloadSpacePlan(
        episodeCount = count,
        perEpisodeBytes = perEpisodeBytes,
        keepsLibraryCopy = downloadDestination.includesApp,
        savesToDevice = downloadDestination.includesDevice,
        libraryFreeBytes = libraryFreeBytes,
        deviceFreeBytes = deviceFreeBytes,
        deviceVolumeLabel = volumeLabel,
        deviceSharesLibraryVolume = deviceSharesLibraryVolume,
        headroomBytes = DownloadStorage.HEADROOM_BYTES,
    )
    val wontFit = space.wontFit
    val tight = space.tight
    /** Null when one volume holds everything, so the gauge has no second disk to distinguish it from. */
    val gaugeVolume = space.volumeLabel

    // The opening cursor goes to the body's first control, the audio selector (§31). Rows of a
    // lazy body compose and dispose as it scrolls, and a row that claims the cursor on
    // composition would re-claim it every time it is composed again on the way back (#59), so
    // the claim is withdrawn once the host reports the opening cursor has landed.
    var openingFocusPlaced by remember { mutableStateOf(false) }
    MidnightDialogHost(
        onDismiss = onDismiss,
        // The body is a lazy list: up to a hundred episode rows per range, 270 or more with a Find
        // query, used to compose the moment the dialog opened (#59). The list itself opens at the
        // head, where the claiming control stands.
        bodyListState = rememberLazyListState(),
        onBodyFocusPlaced = { openingFocusPlaced = true },
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Download episodes",
                title = seriesTitle,
                metadata = scopeLabel,
                artworkUrl = artworkUrl,
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightPrimaryButton(
                text = when {
                    wontFit -> "Not enough space"
                    tight -> "Download anyway"
                    count == 1 -> "Download 1 episode"
                    else -> "Download $count episodes"
                },
                enabled = count > 0 && !wontFit,
                onClick = onConfirm,
                modifier = Modifier.padding(top = 16.dp),
            )
            MidnightDialogFooter(quietText = "Cancel", onQuiet = onDismiss)
        },
    ) {
        item(key = "section-audio") {
            MidnightSectionLabel("Audio")
            MidnightUnderlineSelector(
                options = listOf(Category.SUB, Category.DUB),
                selected = category,
                onSelect = onCategoryChange,
                tabLabel = { if (it == Category.DUB) "Dub" else "Sub" },
                claimInitialFocus = !openingFocusPlaced,
            )
            MidnightSettingRow(
                title = "First-choice server",
                value = serverLabel(preferredProvider),
                onClick = { serverPickerVisible = true },
            )
            MidnightHelper("Applies to this batch. If the server fails, another may be tried with the same sub/dub selection.")

            // Dub has no subtitles to burn in, so the switch would be a control that does nothing.
            if (category == Category.SUB) {
                MidnightSettingSwitchRow(
                    title = "Hard subs only",
                    description = "Subtitles burned into the picture, so they show in any player. " +
                        "Soft-sub servers are skipped, and an episode no hard-sub server has fails.",
                    checked = hardSubsOnly,
                    onCheckedChange = onHardSubsOnlyChange,
                )
            }
        }

        // Only bonk and twin publish both today, and a single-shape server has nothing to ask. A
        // selector showing one option would be a control that cannot be wrong and cannot be used.
        // With "Hard subs only" on there is nothing left to choose either.
        if (variantOptions.size > 1 && !hardSubFilter) {
            item(key = "section-subtitles") {
                MidnightSectionLabel("Subtitles")
                MidnightUnderlineSelector(
                    options = variantOptions,
                    selected = variant,
                    onSelect = onVariantChange,
                    tabLabel = { it.label },
                    // Not a claimant. Composing everything eagerly used to let the last claiming
                    // control in body order take the cursor — the quality selector further down,
                    // below the fold on a television — and a lazy body composes claimants only
                    // when they scroll in, so "last composed" would depend on the viewport (#59).
                    // The audio selector claims; these stay put.
                    claimInitialFocus = false,
                )
                MidnightHelper(
                    "Hard sub is burned into the picture. Soft sub is a track you can restyle or turn " +
                        "off, and it is exported beside the MP4.",
                )
            }
        }

        if (episodes.isEmpty()) {
            item(key = "notice-empty-library") {
                MidnightInlineNotice(
                    "Every episode on this list is already in your offline library.",
                    tone = MidnightNoticeTone.SUCCESS,
                )
            }
            return@MidnightDialogHost
        }

        item(key = "section-quality") {
            MidnightSectionLabel("Quality")
            MidnightUnderlineSelector(
                options = DownloadQuality.entries,
                selected = quality,
                onSelect = onQualityChange,
                tabLabel = { if (it == DownloadQuality.BEST) "Best" else it.label },
                // Not a claimant; see the subtitles selector (#59).
                claimInitialFocus = false,
            )
            MidnightHelper("Best available rendition at or below the selected resolution.")
            // Most servers publish one rendition, and a ceiling can do nothing with one: asking such a
            // server for 480p downloads its 1080p. Said here, before the download, because afterwards
            // it reads as the setting being ignored.
            when (qualityCheck) {
                null, BatchQualityCheck.Unknown -> Unit
                BatchQualityCheck.Checking -> MidnightSettingRow(
                    title = "Checking what this server offers…",
                    busy = true,
                )
                is BatchQualityCheck.Fits -> MidnightHelper(
                    "${serverLabel(qualityCheck.provider)} will download at ${qualityCheck.height}p " +
                        "(checked on one episode).",
                )
                is BatchQualityCheck.Misses -> {
                    val ladder = qualityCheck.offered.joinToString(" / ") { "${it}p" }
                    MidnightInlineNotice(
                        if (qualityCheck.offered.size == 1) {
                            "${serverLabel(qualityCheck.provider)} only has $ladder for episode " +
                                "${qualityCheck.episodeLabel}, so it would download at $ladder."
                        } else {
                            "${serverLabel(qualityCheck.provider)} has $ladder for episode " +
                                "${qualityCheck.episodeLabel} — no ${quality.label}, so it would " +
                                "download at ${qualityCheck.delivered}p."
                        },
                        tone = MidnightNoticeTone.WARNING,
                    )
                    val alternative = qualityCheck.alternative
                    when {
                        alternative != null -> MidnightSettingRow(
                            title = "Use ${serverLabel(alternative.provider)} instead",
                            description = "Has ${alternative.height}p for episode ${qualityCheck.episodeLabel}",
                            value = "Switch",
                            onClick = { onUseProvider(alternative.provider) },
                        )
                        qualityCheck.searching -> MidnightSettingRow(
                            title = "Looking for a server with ${quality.label}…",
                            busy = true,
                        )
                        else -> MidnightHelper(
                            "No other server checked has ${quality.label} for this episode.",
                        )
                    }
                }
            }
            if (quality != DownloadQuality.BEST) {
                MidnightSettingSwitchRow(
                    title = "Switch servers per episode",
                    description = "When an episode's server has no ${quality.label}, try others " +
                        "for that episode. Slower, and the sub style may differ.",
                    checked = matchQuality,
                    onCheckedChange = onMatchQualityChange,
                )
            }
        }

        item(key = "section-episodes") {
            MidnightSectionLabel("Choose episodes")
            MidnightSettingSwitchRow(
                title = "Skip filler episodes",
                description = if (fillerCount == 0) {
                    "No episodes in this list are marked as filler"
                } else {
                    "$fillerCount marked as filler"
                },
                checked = skipFillers,
                enabled = fillerCount > 0,
                onCheckedChange = onSkipFillersChange,
            )
            MidnightSelectionRow(
                title = if (selectableCount == 1) "Select the episode" else "Select all $selectableCount available",
                subtitle = if (allSelected) "Clear selection" else "Include every episode allowed below",
                selected = allSelected,
                onSelectedChange = onSelectAll,
                claimInitialFocus = false,
            )
        }
        items(
            episodes,
            key = { it.pipeId },
            contentType = { SEASON_EPISODE_ROW_CONTENT_TYPE },
        ) { episode ->
            val row = seasonEpisodeRow(episode, skipFillers, selectedEpisodeIds)
            MidnightSelectionRow(
                title = row.title,
                subtitle = row.subtitle,
                selected = row.selected,
                enabled = row.enabled,
                onSelectedChange = { checked -> onEpisodeSelected(episode, checked) },
                trailing = Formatter.formatShortFileSize(context, perEpisodeBytes),
                claimInitialFocus = false,
            )
        }
        if (count == 0) {
            item(key = "notice-empty-selection") {
                MidnightInlineNotice(
                    "Choose at least one episode to start a download.",
                    tone = MidnightNoticeTone.WARNING,
                )
            }
        }
        item(key = "note-fetch-pace") {
            // Otherwise a season half in the library reads as a wrong count rather than as the skip
            // it is, and the viewer goes looking for the missing episodes.
            if (alreadyHeldCount > 0) {
                MidnightHelper(
                    "$alreadyHeldCount already saved and skipped. Fetched one at a time — you can " +
                        "keep using the app while it runs.",
                )
            } else {
                MidnightHelper("Fetched one at a time — you can keep using the app while it runs.")
            }
        }

        item(key = "section-storage") {
            MidnightSectionLabel("Storage")
            MidnightStorageStrip(
                sizeLabel = "≈ ${Formatter.formatShortFileSize(context, estimatedBytes)}",
                noteLabel = if (count == 1) "1 episode" else "$count episodes",
                freeLabel = when {
                    space.freeBytes <= 0L -> "Free space unreadable"
                    // Named whenever a second volume is in play, so a figure that does not match the
                    // one the viewer has in mind is at least attributable to a disk.
                    gaugeVolume != null ->
                        "${Formatter.formatShortFileSize(context, space.freeBytes)} free on $gaugeVolume"
                    else -> "${Formatter.formatShortFileSize(context, space.freeBytes)} free"
                },
                afterLabel = when {
                    wontFit -> "${Formatter.formatShortFileSize(
                        context,
                        space.neededBytes - space.freeBytes,
                    )} short"
                    space.freeBytes > 0L -> "${Formatter.formatShortFileSize(
                        context,
                        (space.freeBytes - space.neededBytes).coerceAtLeast(0L),
                    )} after"
                    else -> ""
                },
                fillFraction = if (space.freeBytes > 0L) {
                    space.neededBytes.toFloat() / space.freeBytes
                } else {
                    0f
                },
                blocked = wontFit,
            )
            // A stick has a few GB total, so the count is a genuinely useful number there; on a
            // phone with 200 GB free it would just be noise.
            if (device.isTv && space.freeBytes > 0L && !wontFit) {
                MidnightHelper(
                    "Room for about ${space.freeBytes / perEpisodeBytes.coerceAtLeast(1L)} episodes in total",
                )
            }
            when {
                wontFit -> MidnightInlineNotice(
                    when (space.volume) {
                        DownloadSpaceVolume.ONLY ->
                            "Not enough room — choose fewer episodes, a lower resolution, or free " +
                                "some storage."
                        DownloadSpaceVolume.SAVE_LOCATION ->
                            "Not enough room on $volumeLabel — choose fewer episodes, a lower " +
                                "resolution, or free some storage."
                        // Otherwise a download bound for a card is refused over the phone's storage
                        // with nothing on screen to say why the phone has anything to do with it.
                        DownloadSpaceVolume.LIBRARY_CACHE ->
                            "Not enough room on $LIBRARY_CACHE_VOLUME_LABEL — episodes are fetched " +
                                "there before being saved to $locationLabel, so a little room is " +
                                "needed on both. Free some storage or choose a lower resolution."
                    },
                    tone = MidnightNoticeTone.ERROR,
                )
                tight -> MidnightInlineNotice(
                    if (gaugeVolume != null) {
                        "${gaugeVolume.replaceFirstChar(Char::uppercase)} is nearly full — the " +
                            "download may fail part-way or crowd out other apps."
                    } else {
                        "Storage is nearly full — the download may fail part-way or crowd out other apps."
                    },
                    tone = MidnightNoticeTone.WARNING,
                )
            }
            // A batch follows the destination preference like any other download — the export runs per
            // episode once its download finishes, which needs no resolved stream up front. Still worth
            // stating: this dialog has no destination control of its own, so the setting decides, and
            // the difference is a viewer finding Downloads/Anilili full or empty an hour later.
            MidnightInlineNotice(
                when (downloadDestination) {
                    DownloadDestination.APP_ONLY -> "Kept in the Anilili offline library."
                    DownloadDestination.DEVICE_ONLY -> "Saved as MP4 files in $locationLabel."
                    DownloadDestination.BOTH ->
                        "Kept in the Anilili library and saved as MP4 files in $locationLabel."
                },
            )
        }
    }
}

/**
 * What the dialog says "all" covers.
 *
 * Kept out of the composable so the wording is checkable without a UI test, and because getting it
 * wrong is the one way this feature can mislead: "all" over a filtered list and "all" over a
 * hundred-episode block are very different downloads.
 */
internal fun seasonDownloadScopeLabel(
    seasonLabel: String?,
    blockLabel: String?,
    filterQuery: String,
    shownCount: Int,
): String {
    val trimmed = filterQuery.trim()
    if (trimmed.isNotEmpty()) return "Matching “$trimmed” · $shownCount episodes"
    val extent = blockLabel?.let { "episodes $it" } ?: "$shownCount episodes"
    return listOfNotNull(seasonLabel, extent).joinToString(" · ")
}

/** Applies explicit picks and the filler rule without changing the list's episode order. */
internal fun selectedDownloadEpisodes(
    episodes: List<EpisodeItem>,
    selectedEpisodeIds: Set<String>,
    skipFillers: Boolean,
): List<EpisodeItem> = episodes.filter { episode ->
    episode.pipeId in selectedEpisodeIds && (!skipFillers || !episode.filler)
}
