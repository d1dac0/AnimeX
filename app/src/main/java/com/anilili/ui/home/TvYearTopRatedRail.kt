@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.anilili.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.anilili.data.model.Media
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.dialogs.MidnightChoiceRow
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightMediaHeader
import java.time.LocalDate

@Composable
internal fun TvYearTopRatedRail(
    state: YearTopRatedState,
    onSelectYear: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onAnimeClick: (Int) -> Unit,
    onPreview: (Media?) -> Unit,
    blockDown: Boolean,
    onFocusUp: (() -> Boolean)?,
) {
    var choosingYear by remember { mutableStateOf(false) }
    val yearFocus = remember { FocusRequester() }
    val currentYear = LocalDate.now().year
    val dismiss = {
        choosingYear = false
        runCatching { yearFocus.requestFocus() }
        Unit
    }
    if (choosingYear) {
        // The picker used to compose all 126 year rows the moment it opened (issue #49). The lazy
        // host composes the rows on screen, and the list opens on the row holding the current
        // value so that row is composed and can take the cursor (§31).
        val years = remember(currentYear) { yearPickerYears(currentYear) }
        val yearListState = rememberLazyListState(
            initialFirstVisibleItemIndex = yearPickerInitialIndex(years, state.year),
        )
        var openingFocusPlaced by remember { mutableStateOf(false) }
        MidnightDialogHost(
            onDismiss = dismiss,
            bodyListState = yearListState,
            onBodyFocusPlaced = { openingFocusPlaced = true },
            header = { showClose ->
                MidnightMediaHeader(
                    kicker = "Top scored by year",
                    title = "Choose a year",
                    metadata = "All formats · At least 5,000 ratings",
                    onDismiss = dismiss.takeIf { showClose },
                )
            },
            footer = { MidnightDialogFooter(quietText = "Cancel", onQuiet = dismiss) },
        ) {
            items(
                years,
                key = { it },
                contentType = { YEAR_ROW_CONTENT_TYPE },
            ) { year ->
                MidnightChoiceRow(
                    title = year.toString(),
                    selected = year == state.year,
                    // One claim, at the opening: after the cursor has landed, a year row composed
                    // again on the way back must not yank it off the row the viewer is on (#49).
                    claimInitialFocus = !openingFocusPlaced,
                    onSelect = {
                        onPreview(null)
                        onSelectYear(year)
                        dismiss()
                    },
                )
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Top scored by year", style = MaterialTheme.typography.titleLarge)
            YearRailButton(
                text = "${state.year} ▾",
                onClick = { choosingYear = true },
                modifier = Modifier.focusRequester(yearFocus).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && onFocusUp != null) {
                        onFocusUp()
                    } else false
                },
            )
            Text("5,000+ ratings · All formats", style = MaterialTheme.typography.bodyMedium)
        }
        if (state.items.isNotEmpty()) {
            // Reuse Home's card focus memory, details navigation and poster preferences.
            androidx.compose.runtime.key(state.year) {
                TvMediaRail(
                    title = "Top scored in ${state.year}",
                    media = state.items,
                    onAnimeClick = onAnimeClick,
                    onPreview = onPreview,
                    blockDown = false,
                    onFocusUp = { yearFocus.requestFocus(); true },
                    showTitle = false,
                )
            }
        }
        Row(
            Modifier.padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Loading ${state.year}…")
                }
                state.failed -> Text("Couldn't load anime for ${state.year}.")
                state.items.isEmpty() && !state.hasMore -> Text("No anime with 5,000 ratings in ${state.year} yet.")
                state.items.isEmpty() -> Text("No qualifying anime in these results. Check the next page.")
            }
            // This action stays attached through loading and exhaustion so remote focus survives.
            YearRailButton(
                text = when {
                    state.loading -> "Loading…"
                    state.failed -> "Retry"
                    state.hasMore -> "Load more"
                    else -> "Change year"
                },
                onClick = { if (state.hasMore || state.failed) onLoadMore() else choosingYear = true },
                modifier = Modifier.focusProperties {
                    if (blockDown) down = FocusRequester.Cancel
                    if (state.items.isEmpty()) up = yearFocus
                },
            )
        }
    }
}

@Composable
private fun YearRailButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val focus = rememberFocusFill()
    TextButton(
        onClick = onClick,
        colors = focus.buttonColors(ButtonDefaults.textButtonColors()),
        modifier = modifier.focusFill(focus, RoundedCornerShape(50)),
    ) { Text(text) }
}
