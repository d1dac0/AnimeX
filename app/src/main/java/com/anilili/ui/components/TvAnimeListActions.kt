@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.anilili.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.anilili.data.auth.AccountService
import com.anilili.data.library.formatListScore
import com.anilili.ui.adaptive.FocusRingGap
import com.anilili.ui.adaptive.focusHalo
import com.anilili.ui.adaptive.TvCardCornerShape
import com.anilili.ui.theme.Midnight

/**
 * The television face of [AnimeListActionsDialog]: one centred card in the Home rail's language —
 * the title's cover dimmed behind it, the poster and name up top, and every choice a large glass
 * button that lights the way the top menu's tabs do.
 *
 * The shared Midnight card this replaces was built for dense pickers: a narrow sheet pinned to the
 * left edge, a list of rows each with a caption, and a solid accent button. Here there are at most
 * six destinations and two extra actions, which fit on screen at once as a grid, so the remote
 * reaches any of them in two presses instead of walking a list.
 *
 * All state and every network call stay in [AnimeListActionsDialog]; this only draws them.
 */
@Composable
internal fun TvAnimeListActionsCard(
    title: String,
    cover: String?,
    metadata: String?,
    service: AccountService?,
    currentStatus: String?,
    busyStatus: String?,
    deleting: Boolean,
    busy: Boolean,
    saved: Boolean,
    canSave: Boolean,
    /** Marked watched on this device, which is the signed-out stand-in for Completed. */
    watched: Boolean,
    onToggleWatched: () -> Unit,
    errorMessage: String?,
    /** The viewer's own lists, each with whether this title is in it. */
    customLists: List<Pair<String, Boolean>>,
    onToggleList: (String) -> Unit,
    onNewList: () -> Unit,
    onMoveTo: (String) -> Unit,
    onToggleSaved: () -> Unit,
    onDelete: () -> Unit,
    /** The viewer's own score out of ten on the account, null when unscored. */
    score: Double?,
    onRate: () -> Unit,
    onEditProgress: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val initialFocus = remember { FocusRequester() }
    Dialog(
        // Back closes the card even mid-request: the write runs in a scope of its own (see
        // AnimeListActionsDialog), so leaving does not cancel it, and a slow account call no longer
        // holds the remote hostage for up to the client's 45 s timeout.
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = .55f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .width(600.dp)
                    .clip(TvCardCornerShape)
                    .background(Midnight.TvSurface),
            ) {
                // The cover, cropped wide and sunk under a fade, so the card carries the title's
                // colours without competing with the controls on top of it.
                AsyncImage(
                    model = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().alpha(.45f),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(.45f),
                                .45f to Color.Black.copy(.82f),
                                1f to Color.Black.copy(.94f),
                            ),
                        ),
                )
                Column(Modifier.padding(24.dp)) {
                    Header(title, cover, metadata, service, currentStatus, score, saved, watched, accent, reserveEnd = if (service != null) 252.dp else 0.dp)
                    Spacer(Modifier.height(22.dp))
                    if (service == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // First and under the cursor: with no account, this is the one list
                            // status there is.
                            TvListButton(
                                icon = if (watched) Icons.Filled.CheckCircle else Icons.Filled.CheckCircleOutline,
                                label = "Watched",
                                onClick = onToggleWatched,
                                selected = watched,
                                enabled = !busy,
                                modifier = Modifier.weight(1f).focusRequester(initialFocus),
                            )
                            if (canSave || saved) {
                                TvListButton(
                                    icon = if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    label = if (saved) "Remove from watchlist" else "Save to watchlist",
                                    onClick = onToggleSaved,
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            TvListButton(
                                icon = Icons.Filled.Close,
                                label = "Close",
                                onClick = onDismiss,
                                modifier = Modifier.width(150.dp),
                            )
                        }
                        Row(
                            Modifier.padding(top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = Color.White.copy(.55f),
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "Watched and your own lists stay on this device. Sign in to AniList " +
                                    "or MyAnimeList in Settings for Watching, Paused and more — " +
                                    "anything marked here goes with you.",
                                color = Color.White.copy(.62f),
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    } else {
                        Text(
                            "MOVE ON ${service.label.uppercase()} TO",
                            color = Color.White.copy(.55f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.6.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        // The cursor opens on the status the title already holds, so the first OK
                        // confirms what is on screen; on no list at all, on the first destination.
                        val cursorStatus = currentStatus ?: listDestinations.first().status
                        listDestinations.chunked(3).forEach { row ->
                            Row(
                                Modifier.padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                row.forEachIndexed { column, destination ->
                                    TvListButton(
                                        icon = destination.icon,
                                        label = destination.label,
                                        onClick = { onMoveTo(destination.status) },
                                        selected = destination.status == currentStatus,
                                        busy = destination.status == busyStatus,
                                        enabled = !busy,
                                        modifier = Modifier
                                            .weight(1f)
                                            // Left and Right stop at the row's ends. Unfenced, Right
                                            // off the last column went looking for the nearest
                                            // thing further right and found the corner's close.
                                            .focusProperties {
                                                if (column == 0) left = FocusRequester.Cancel
                                                if (column == row.lastIndex) right = FocusRequester.Cancel
                                            }
                                            .then(
                                                if (destination.status == cursorStatus) {
                                                    Modifier.focusRequester(initialFocus)
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                    TvCustomListRow(customLists, busy, onToggleList, onNewList)
                    errorMessage?.let { message ->
                        Text(
                            message,
                            color = Midnight.Red,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
                if (service != null) {
                    // The card's own actions, as icons in its corner: they act on the title rather
                    // than choosing a list, so they sit apart from the grid. A bare glyph is guesswork
                    // from across a room, so the one under the cursor names itself underneath.
                    var hint by remember { mutableStateOf<String?>(null) }
                    Column(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 20.dp, end = 20.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (canSave || saved) {
                                val label = if (saved) "Remove from watchlist" else "Save to watchlist"
                                TvListIconButton(
                                    icon = if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    label = label,
                                    onClick = onToggleSaved,
                                    enabled = !busy,
                                    onFocused = { hint = if (it) label else hint.takeIf { h -> h != label } },
                                    modifier = Modifier.focusProperties { left = FocusRequester.Cancel },
                                )
                            }
                            // Only for a title the account holds: a score anywhere else would make
                            // an entry on a list the viewer never chose.
                            if (currentStatus != null) {
                                val label = if (score != null) "Change your score" else "Rate this season"
                                TvListIconButton(
                                    icon = if (score != null) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                    label = label,
                                    onClick = onRate,
                                    enabled = !busy,
                                    onFocused = { hint = if (it) label else hint.takeIf { h -> h != label } },
                                    modifier = if (canSave || saved) {
                                        Modifier
                                    } else {
                                        Modifier.focusProperties { left = FocusRequester.Cancel }
                                    },
                                )
                                val progressLabel = "Episodes watched"
                                TvListIconButton(
                                    icon = Icons.Outlined.Visibility,
                                    label = progressLabel,
                                    onClick = onEditProgress,
                                    enabled = !busy,
                                    onFocused = {
                                        hint = if (it) progressLabel else hint.takeIf { h -> h != progressLabel }
                                    },
                                )
                            }
                            // Only for a title the account holds; on anything else it could not
                            // do what it says.
                            if (currentStatus != null) {
                                val label = "Delete from ${service.label}"
                                TvListIconButton(
                                    icon = Icons.Filled.DeleteOutline,
                                    label = label,
                                    onClick = onDelete,
                                    busy = deleting,
                                    enabled = !busy,
                                    danger = true,
                                    onFocused = { hint = if (it) label else hint.takeIf { h -> h != label } },
                                )
                            }
                            TvListIconButton(
                                icon = Icons.Filled.Close,
                                label = "Close",
                                onClick = onDismiss,
                                onFocused = { hint = if (it) "Close" else hint.takeIf { h -> h != "Close" } },
                                modifier = Modifier.focusProperties { right = FocusRequester.Cancel },
                            )
                        }
                        Text(
                            hint.orEmpty(),
                            color = Color.White.copy(.78f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .border(1.dp, Color.White.copy(.10f), TvCardCornerShape),
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }
    }
}

@Composable
private fun Header(
    title: String,
    cover: String?,
    metadata: String?,
    service: AccountService?,
    currentStatus: String?,
    score: Double?,
    saved: Boolean,
    watched: Boolean,
    accent: Color,
    reserveEnd: Dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 84.dp, height = 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(.08f)),
        ) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.padding(start = 18.dp, end = reserveEnd)) {
            Text(
                title,
                color = Color.White,
                fontSize = 24.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!metadata.isNullOrBlank()) {
                Text(
                    metadata,
                    color = Color.White.copy(.62f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val destination = listDestinations.firstOrNull { it.status == currentStatus }
            val (icon, label) = when {
                destination != null && service != null ->
                    destination.icon to listOfNotNull(
                        "${destination.label} on ${service.label}",
                        score?.let { "${formatListScore(it)} / 10" },
                    ).joinToString(" · ")
                watched -> Icons.Filled.CheckCircle to "Watched"
                saved -> Icons.Filled.Bookmark to "On your watchlist"
                else -> Icons.Filled.BookmarkBorder to "Not on your list yet"
            }
            val onList = destination != null || saved || watched
            val pillShape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .clip(pillShape)
                    .background(if (onList) accent.copy(.24f) else Color.White.copy(.10f))
                    .border(1.dp, if (onList) accent.copy(.5f) else Color.White.copy(.22f), pillShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                Text(
                    label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * The viewer's own lists, one toggle each, in a row the remote scrolls sideways: a title may be in
 * any number of them, so each is its own switch rather than one choice among several, and however
 * many lists there are the card stays one screen tall.
 */
@Composable
private fun TvCustomListRow(
    lists: List<Pair<String, Boolean>>,
    busy: Boolean,
    onToggle: (String) -> Unit,
    onNew: () -> Unit,
) {
    Text(
        "MY LISTS",
        color = Color.White.copy(.55f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(10.dp))
    val first = remember { FocusRequester() }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            // Down from the buttons above lands on the row's first button. Left to the search it
            // took whichever button sat nearest the one above — measured on the emulator, "New
            // list" rather than the list beside it.
            .focusProperties { enter = { first } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(lists, key = { _, list -> list.first }) { index, (name, member) ->
            TvListButton(
                icon = if (member) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                label = name,
                onClick = { onToggle(name) },
                selected = member,
                enabled = !busy,
                modifier = Modifier
                    .width(200.dp)
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
            )
        }
        item(key = "new-list") {
            TvListButton(
                icon = Icons.Filled.Add,
                label = "New list",
                onClick = onNew,
                enabled = !busy,
                modifier = Modifier
                    .width(150.dp)
                    .then(if (lists.isEmpty()) Modifier.focusRequester(first) else Modifier)
                    // The row's far end: Right past it has nothing of the card's own to reach.
                    .focusProperties { right = FocusRequester.Cancel },
            )
        }
    }
}

/**
 * A glass button: faint white at rest, the top menu's accent tint when it is the current choice,
 * and the shared white focus fill under the cursor. A pointer clicks it directly.
 */
@Composable
private fun TvListButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    // Focus is the television's ring (see focusHighlight): the button keeps its own colours, and a
    // plain one only brightens a little.
    val container = when {
        selected -> accent.copy(.24f)
        danger -> Midnight.Red.copy(.10f)
        focused -> Color.White.copy(.16f)
        else -> Color.White.copy(.08f)
    }
    val outline = when {
        selected -> accent.copy(.5f)
        danger -> Midnight.Red.copy(.35f)
        else -> Color.White.copy(.16f)
    }
    val content = if (danger) Midnight.Red else Color.White
    Row(
        modifier
            .height(52.dp)
            .alpha(if (enabled || busy) 1f else .5f)
            .onFocusChanged { focused = it.isFocused }
            // Ahead of the clip, which would shave off a ring drawn outside the button.
            .focusHalo(focused, shape, FocusRingGap)
            .clip(shape)
            .background(container)
            .border(1.dp, outline, shape)
            .then(clickableWhileFocusable(enabled, label, onClick))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Text(
            label,
            color = content,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weightIfRow(this),
        )
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Current", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** A round glass icon button for the card's corner; same states as [TvListButton]. */
@Composable
private fun TvListIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val container = when {
        danger -> Midnight.Red.copy(.10f)
        focused -> Color.White.copy(.16f)
        else -> Color.White.copy(.08f)
    }
    val outline = if (danger) Midnight.Red.copy(.35f) else Color.White.copy(.16f)
    val content = if (danger) Midnight.Red else Color.White
    Box(
        modifier
            .size(44.dp)
            .alpha(if (enabled || busy) 1f else .5f)
            .onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            }
            .focusHalo(focused, CircleShape, FocusRingGap)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, outline, CircleShape)
            .then(clickableWhileFocusable(enabled, label, onClick)),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(21.dp))
        }
    }
}

/**
 * A click target that stays focusable when [enabled] is false and only ignores the press.
 *
 * `clickable(enabled = false)` also drops the node out of focus, so pressing a status — which marks
 * every button busy — took the focus off the very button under the cursor and left the remote with
 * no ring to move from, during the request and after a failure (#57). The dimming the callers apply
 * already says the press would do nothing; the semantics say it too.
 */
private fun clickableWhileFocusable(enabled: Boolean, label: String, onClick: () -> Unit): Modifier =
    Modifier
        .semantics { if (!enabled) disabled() }
        .clickable(role = Role.Button, onClickLabel = label) { if (enabled) onClick() }

/** Lets the label give way to the check mark instead of pushing it out of a narrow button. */
private fun Modifier.weightIfRow(scope: RowScope): Modifier = with(scope) { this@weightIfRow.weight(1f, fill = false) }
