package com.anilili.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.library.CustomList
import com.anilili.data.library.CustomListStore
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.LocalWatchedStore
import com.anilili.data.library.WatchedEntry
import com.anilili.data.library.WatchlistEntry
import com.anilili.data.library.formatListScore
import com.anilili.data.library.listScoreLabel
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.toFailure
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightSectionLabel
import com.anilili.ui.dialogs.MidnightSelectionRow
import com.anilili.ui.dialogs.MidnightStatusRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether the device's saved row should survive a move to [status].
 *
 * The local watchlist is the app's own Planning list — it is what "My watchlist" is built from and
 * what syncs to the account as PLANNING. So a title moved anywhere else stops being a save; leaving
 * the row behind would keep it in My watchlist wearing a "Completed" caption.
 *
 * Dropping it is safe for the account: both services only delete a remote entry that is still
 * PLANNING, so the unsave that follows the removal is a no-op against the status just written.
 */
internal fun savedRowSurvivesMoveTo(status: String): Boolean = status == "PLANNING"

/**
 * Where the menu's account writes run: outside the dialog's composition, so closing the menu while
 * a status change or a removal is in flight does not cancel it. The television card lets Back close
 * it mid-request (#57); with the dialog's own scope that would abandon the call after the account
 * may already have applied it, and skip the local bookkeeping that follows a success. Main, like the
 * composition scope it replaces, because the follow-up touches the dialog's state and the stores.
 */
private val listWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Take one title's entry off the account, then make this device agree with it.
 *
 * Shared by the long-press menu and the poster's own X so the two can never clean up differently.
 * The local rows go for the same reason a move to a non-watching status drops them: a title the
 * account has never heard of must not be left on the watchlist or in Continue Watching, where the
 * next sync reads it as a save made on another device and pushes it straight back.
 *
 * A successful `false` means the account had no entry to remove. That is not a failure to report —
 * the viewer asked for the title to be off their list and it is — so callers close on it either way,
 * and the repository's diagnostics event is what records which of the two happened.
 */
internal suspend fun deleteAnimeListEntry(anilistId: Int, from: String): Result<Boolean> {
    DiagnosticsLog.event("List entry removal requested from=$from id=$anilistId")
    return runCatching { AppGraph.repository.deleteAnimeListEntry(anilistId) }
        .onSuccess {
            LibraryStore.updateRemoteStatus(anilistId, null)
            LibraryStore.removeHistory(anilistId, dismissRemoteSeed = true)
            LibraryStore.watchlist.value
                .firstOrNull { it.anilistId == anilistId }
                ?.let(LibraryStore::toggleWatchlist)
            LibraryStore.refreshRemoteLibrary(force = true)
        }
}

/**
 * Long-press menu for any poster: My List's saved cards, and the catalogue rows and grids.
 *
 * My List's cards had no long press at all, so the only way to mark a finished title Completed
 * was to open it, start an episode and use the player's list picker — the report that prompted
 * this said exactly that: the poster could be saved for later but never finished. Home, Browse and
 * Search posters then had the same hole for a viewer whose watch history lives on another service
 * and who is filling their list in from what they can find, not from what they play here.
 *
 * The destinations are the same status list Continue Watching offers (§44), so no two long-press
 * menus in the app can answer the same question differently. [kicker] only names the surface the
 * press came from.
 *
 * Deleting the entry is offered here and nowhere else, because it is not a destination: every other
 * menu in the app only ever relabels a row, and this is the one place that can take one away.
 *
 * [saveEntry] is what the device should file under "save for later" if the viewer asks for it here,
 * and it is what the detail page passes now that its own save button opens this menu instead of
 * toggling. Without it the footer can only offer the removal, which would leave a signed-out viewer
 * — or one looking at a title they have never saved — with a menu that cannot do the one thing the
 * button used to. Callers whose surface keeps its own save control pass nothing and see no change.
 *
 * [completedMarksWatched] makes Completed finish the title rather than only relabel it: the episode
 * count is filled to [totalEpisodes] as well, the way the watched eye does. The television's title
 * page passes it, because there this menu *is* the eye — without it, marking a show Completed from
 * its own page would leave the account saying episode 3 of 12.
 */
@Composable
fun AnimeListActionsDialog(
    anilistId: Int,
    title: String,
    cover: String?,
    metadata: String?,
    onDismiss: () -> Unit,
    kicker: String = "My list",
    onMoved: () -> Unit = {},
    saveEntry: WatchlistEntry? = null,
    completedMarksWatched: Boolean = false,
    totalEpisodes: Int? = null,
) {
    val service = AccountService.active
    val remoteStatuses by LibraryStore.remoteStatuses.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val savedEntry = watchlist.firstOrNull { it.anilistId == anilistId }
    val currentStatus = remoteStatuses[anilistId]
    val remoteScores by LibraryStore.remoteScores.collectAsState()
    val currentScore = remoteScores[anilistId]
    var scoring by remember(anilistId) { mutableStateOf(false) }
    var editingProgress by remember(anilistId) { mutableStateOf(false) }
    // Whether this menu is still on screen. A write that finishes after the viewer has closed it
    // must not close the caller's next dialog — a menu opened since for another title, say.
    val showing = remember(anilistId) { AtomicBoolean(true) }
    DisposableEffect(showing) { onDispose { showing.set(false) } }
    val dismissIfShowing: () -> Unit = { if (showing.get()) onDismiss() }
    var busyStatus by remember(anilistId) { mutableStateOf<String?>(null) }
    var deleting by remember(anilistId) { mutableStateOf(false) }
    var confirmingDelete by remember(anilistId) { mutableStateOf(false) }
    var errorMessage by remember(anilistId) { mutableStateOf<String?>(null) }
    val busy = busyStatus != null || deleting
    val customLists by CustomListStore.lists.collectAsState()
    var namingList by remember(anilistId) { mutableStateOf(false) }
    // What a list holds for this title. The detail page's save entry is the richest; a card that
    // passes none still knows the title and the cover, which is all a list needs to draw it.
    val listEntry = saveEntry ?: savedEntry ?: WatchlistEntry(anilistId = anilistId, title = title, cover = cover)
    val toggleList: (CustomList) -> Unit = { list ->
        CustomListStore.setMember(list.name, listEntry, !list.holds(anilistId))
    }
    // Signed out, Completed has nowhere to live but this device. The mark stands in for it until
    // an account takes it over, so it is offered only when there is no account to move the title on.
    val localWatched by LocalWatchedStore.entries.collectAsState()
    val markedWatched = localWatched.any { it.anilistId == anilistId }
    val toggleWatched: () -> Unit = {
        LocalWatchedStore.setWatched(
            WatchedEntry(
                anilistId = anilistId,
                title = listEntry.title,
                cover = listEntry.cover,
                format = listEntry.format,
                averageScore = listEntry.averageScore,
                episodes = totalEpisodes,
            ),
            watched = !markedWatched,
        )
        onMoved()
        onDismiss()
    }

    fun moveTo(status: String) {
        if (service == null || busy || status == currentStatus) return
        busyStatus = status
        errorMessage = null
        DiagnosticsLog.event(
            "List status requested from=$kicker service=${service.label} id=$anilistId status=$status",
        )
        listWriteScope.launch {
            runCatching {
                if (status == "COMPLETED" && completedMarksWatched) {
                    AppGraph.repository.setAnimeWatched(anilistId, true, totalEpisodes)
                } else {
                    AppGraph.repository.updateAnimeListStatus(anilistId, status)
                }
            }
                .onSuccess {
                    LibraryStore.updateRemoteStatus(anilistId, status)
                    if (status != "CURRENT" && status != "REPEATING") {
                        LibraryStore.removeHistory(anilistId, dismissRemoteSeed = true)
                    }
                    if (!savedRowSurvivesMoveTo(status)) {
                        savedEntry?.let(LibraryStore::toggleWatchlist)
                    }
                    LibraryStore.refreshRemoteLibrary(force = true)
                    DiagnosticsLog.event(
                        "List status confirmed from=$kicker service=${service.label} id=$anilistId status=$status",
                    )
                    onMoved()
                    dismissIfShowing()
                }
                .onFailure { error ->
                    errorMessage = error.toFailure(
                        AnililiError.ACCOUNT_LIST_UPDATE_FAILED,
                        service.label,
                        attributes = mapOf(
                            "from" to kicker,
                            "service" to service.name,
                            "anilistId" to anilistId,
                            "status" to status,
                        ),
                    ).withCode()
                    busyStatus = null
                }
        }
    }

    fun deleteEntry() {
        if (service == null || busy) return
        deleting = true
        errorMessage = null
        listWriteScope.launch {
            deleteAnimeListEntry(anilistId, from = kicker)
                .onSuccess {
                    onMoved()
                    dismissIfShowing()
                }
                .onFailure { error ->
                    errorMessage = error.toFailure(
                        AnililiError.ACCOUNT_LIST_ENTRY_DELETE_FAILED,
                        service.label,
                        attributes = mapOf(
                            "from" to kicker,
                            "service" to service.name,
                            "anilistId" to anilistId,
                        ),
                    ).withCode()
                    deleting = false
                }
        }
    }

    // Shown instead of the sheet rather than on top of it: the sheet is itself a dialog window on a
    // television, and two of those stacked leaves the remote's Back key arguing over which one it
    // belongs to. Cancelling here simply brings the sheet back with its state intact.
    // In place of the menu, for the same reason as the delete confirmation below: two dialog
    // windows stacked on a television leave Back arguing over which one it belongs to.
    if (namingList) {
        CustomListNameDialog(
            title = "New list",
            confirmText = "Create",
            onSave = { name -> createListWith(name, listEntry)?.also { namingList = false } },
            onDismiss = { namingList = false },
        )
        return
    }

    // In place of the menu, like the two above. Closing the score sheet returns to the menu, which
    // then shows the score the account just confirmed.
    if (scoring && service != null) {
        AnimeScoreDialog(
            anilistId = anilistId,
            title = title,
            cover = cover,
            service = service,
            onDismiss = { scoring = false },
        )
        return
    }

    // In place of the menu, like the score sheet above it.
    if (editingProgress && service != null) {
        AnimeProgressDialog(
            anilistId = anilistId,
            title = title,
            cover = cover,
            service = service,
            onDismiss = { editingProgress = false },
        )
        return
    }

    if (confirmingDelete && service != null) {
        MidnightConfirmDialog(
            kicker = "Remove from ${service.label}",
            title = "Delete this from your list?",
            body = "$title comes off your ${service.label} list completely — its status, episode " +
                "count and score with it, and there is no undo on either service. It leaves this " +
                "device's watchlist and Continue Watching too. Downloaded episodes stay.",
            confirmText = "Delete",
            onConfirm = {
                confirmingDelete = false
                deleteEntry()
            },
            onDismiss = { confirmingDelete = false },
        )
        return
    }

    if (LocalAppDeviceProfile.current.isTv) {
        TvAnimeListActionsCard(
            title = title,
            cover = cover,
            metadata = metadata,
            service = service,
            currentStatus = currentStatus,
            busyStatus = busyStatus,
            deleting = deleting,
            busy = busy,
            saved = savedEntry != null,
            canSave = saveEntry != null,
            watched = markedWatched,
            onToggleWatched = toggleWatched,
            errorMessage = errorMessage,
            customLists = customLists.map { it.name to it.holds(anilistId) },
            onToggleList = { name -> customLists.firstOrNull { it.name == name }?.let(toggleList) },
            onNewList = { namingList = true },
            onMoveTo = ::moveTo,
            onToggleSaved = {
                (savedEntry ?: saveEntry)?.let(LibraryStore::toggleWatchlist)
                onDismiss()
            },
            onDelete = { confirmingDelete = true },
            score = currentScore,
            onRate = { scoring = true },
            onEditProgress = { editingProgress = true },
            onDismiss = onDismiss,
        )
        return
    }

    MidnightDialogHost(
        onDismiss = onDismiss,
        // A status change is a network call; dismissing mid-flight would leave the viewer unsure
        // whether it landed.
        canDismiss = !busy,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = kicker,
                title = title,
                metadata = metadata,
                artworkUrl = cover,
                onDismiss = onDismiss.takeIf { showClose && !busy },
            )
        },
        footer = {
            MidnightDialogFooter(
                dangerText = "Remove from watchlist".takeIf { savedEntry != null },
                onDanger = savedEntry?.let {
                    {
                        LibraryStore.toggleWatchlist(it)
                        onDismiss()
                    }
                },
                quietText = "Close",
                onQuiet = onDismiss,
                enabled = !busy,
                extraQuietText = "Save to watchlist"
                    .takeIf { savedEntry == null && saveEntry != null },
                onExtraQuiet = saveEntry?.takeIf { savedEntry == null }?.let {
                    {
                        LibraryStore.toggleWatchlist(it)
                        onDismiss()
                    }
                },
            )
        },
    ) {
        if (service == null) {
            // This used to open on "Sign in … to move this title between lists", which read as if
            // the lists underneath needed an account too. They never did.
            MidnightInlineNotice(
                "Watched and your own lists are kept on this device — no account needed. Sign in " +
                    "to AniList or MyAnimeList for Watching, Paused and the rest; anything marked " +
                    "here goes with you.",
            )
            // A tick, like the lists below it: a mark that is on or off, not one status among several.
            MidnightSelectionRow(
                title = "Watched",
                subtitle = "Finished it — Browse can hide it from now on",
                selected = markedWatched,
                onSelectedChange = { toggleWatched() },
                enabled = !busy,
                claimInitialFocus = true,
            )
            CustomListRows(customLists, anilistId, toggleList, onNew = { namingList = true })
            return@MidnightDialogHost
        }
        MidnightHelper("Move on ${service.label} to")
        // On a television the cursor is placed on the status the title already holds, so the first
        // OK press confirms what is on screen (§31). A title that is on no list has no such row —
        // and with nothing claiming the cursor the remote opens onto a menu it cannot move in — so
        // the first destination takes it instead.
        val cursorStatus = currentStatus ?: listDestinations.first().status
        listDestinations.forEach { destination ->
            MidnightStatusRow(
                label = destination.label,
                subtitle = destination.hint,
                selected = destination.status == currentStatus,
                busy = destination.status == busyStatus,
                enabled = !busy,
                destructive = destination.status == "DROPPED",
                claimInitialFocus = destination.status == cursorStatus,
                icon = destination.icon,
                onSelect = { moveTo(destination.status) },
            )
        }
        // Only offered for a title the account actually holds: on anything else it would be a
        // control that looks like it does something and cannot, and the status snapshot this reads
        // is the same one the checkmark above comes from.
        // Scoring is offered for a title on the account only: both services would otherwise make an
        // entry to hold the score, on a list the viewer never picked.
        if (currentStatus != null) {
            MidnightSectionLabel("Your score")
            MidnightStatusRow(
                label = currentScore?.let { "${formatListScore(it)} / 10" } ?: "Rate this season",
                subtitle = if (currentScore == null) {
                    "Sent to your ${service.label} list"
                } else {
                    listScoreLabel(currentScore.toInt()).takeIf { currentScore % 1.0 == 0.0 }
                },
                selected = false,
                enabled = !busy,
                claimInitialFocus = false,
                icon = if (currentScore != null) Icons.Filled.Star else Icons.Outlined.StarOutline,
                onSelect = { scoring = true },
            )
            MidnightStatusRow(
                label = "Episodes watched",
                subtitle = "Change the count on ${service.label}, up or down",
                selected = false,
                enabled = !busy,
                claimInitialFocus = false,
                icon = Icons.Outlined.Visibility,
                onSelect = { editingProgress = true },
            )
        }
        if (currentStatus != null) {
            MidnightSectionLabel("Remove entirely")
            MidnightStatusRow(
                label = "Delete from ${service.label}",
                selected = false,
                busy = deleting,
                enabled = !busy,
                destructive = true,
                onSelect = { confirmingDelete = true },
            )
        }
        CustomListRows(customLists, anilistId, toggleList, onNew = { namingList = true })
        errorMessage?.let { message ->
            MidnightInlineNotice(message, tone = MidnightNoticeTone.ERROR)
        }
    }
}

/**
 * The viewer's own lists as checkboxes — a title can be in any number of them, unlike the statuses
 * above, which is why these are ticks and not a radio — and a row to start a new one.
 */
@Composable
private fun CustomListRows(
    lists: List<CustomList>,
    anilistId: Int,
    onToggle: (CustomList) -> Unit,
    onNew: () -> Unit,
) {
    MidnightSectionLabel("My lists")
    lists.forEach { list ->
        MidnightSelectionRow(
            title = list.name,
            selected = list.holds(anilistId),
            onSelectedChange = { onToggle(list) },
            trailing = list.entries.size.toString(),
        )
    }
    MidnightStatusRow(
        label = "New list…",
        subtitle = if (lists.isEmpty()) "Group titles your own way — favourites, a marathon, anything" else null,
        selected = false,
        claimInitialFocus = false,
        icon = Icons.Default.Add,
        onSelect = onNew,
    )
}
