package com.whatsappv2.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.paging.compose.LazyPagingItems
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.ConfirmDialog
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.repository.CallDirectionFilter
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.repository.CallLogQuery
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Call history (Task 48, §5.2) — and, since Task 70, the app's home screen.
 *
 * Paged rather than a list: ten thousand entries is an ordinary year of calls for a
 * business handset, and loading them to draw twenty would make opening the screen a
 * visible pause and scrolling it a stutter.
 *
 * ## It owns the shell now
 *
 * The bottom bar is gone (Tasks 69, 70), so this screen carries what used to be in it: a
 * settings action in the top bar, and floating buttons for the dialler and the group-call
 * page. All three are callbacks on [HistoryActions] — this module may not navigate to
 * another feature, so it reports the press and `:app` decides where it goes.
 *
 * The list is stateless in the Compose sense — every action goes up through [HistoryActions]
 * — so it can be rendered from literal rows in a test and in a preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    rows: LazyPagingItems<HistoryRow>,
    actions: HistoryActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { HistoryTopBar(state = state, actions = actions) },
        floatingActionButton = { HistoryFabs(actions = actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            FilterTabs(
                selected = state.query.tabFilter,
                onFilterChanged = actions.onFilterChanged,
            )

            AdvancedFilters(query = state.query, actions = actions)

            if (rows.itemCount == 0) {
                EmptyState(
                    title = state.query.emptyTitle,
                    description = state.query.emptyDescription,
                    icon = Icons.Filled.History,
                    modifier = Modifier.testTag(TAG_EMPTY),
                )
                return@Column
            }

            CallList(rows = rows, actions = actions, zone = zone)
        }
    }

    state.openEntry?.let { row ->
        CallDetail(row, actions, zone)
    }

    if (state.confirmingClearAll) {
        ConfirmDialog(
            title = "Clear call history?",
            message = "Every entry is removed. This cannot be undone.",
            confirmLabel = "Clear all",
            onConfirm = actions.onClearAllConfirmed,
            onDismiss = actions.onClearAllDismissed,
            destructive = true,
            modifier = Modifier.testTag(TAG_CONFIRM_CLEAR),
        )
    }
}

/**
 * The title and the one destructive action.
 *
 * The settings gear that used to live here is gone: Settings is a tab again, and two
 * doors into one screen is one more than a top bar should spend. `onOpenSettings` stays
 * on [HistoryActions] because the account-status banner still uses it — that is a jump to
 * a *particular* account, not a trip to the settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTopBar(state: HistoryUiState, actions: HistoryActions) {
    TopAppBar(
        title = {
            if (state.searching) {
                SearchField(text = state.query.text, onTextChanged = actions.onSearchTextChanged)
            } else {
                Text("Calls")
            }
        },
        navigationIcon = {
            // Only while searching. A back arrow on the app's home screen invites a press
            // that has nowhere to go.
            if (state.searching) {
                IconButton(onClick = { actions.onSearchToggled(false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                }
            }
        },
        actions = {
            if (!state.searching) {
                IconButton(
                    onClick = { actions.onSearchToggled(true) },
                    modifier = Modifier.testTag(TAG_SEARCH),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Search calls")
                }
                IconButton(
                    onClick = actions.onClearAllRequested,
                    modifier = Modifier.testTag(TAG_CLEAR_ALL),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear call history")
                }
            }
        },
    )
}

/** The search box, focused the moment it appears — opening it is the request to type. */
@Composable
private fun SearchField(text: String, onTextChanged: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    TextField(
        value = text,
        onValueChange = onTextChanged,
        singleLine = true,
        placeholder = { Text("Name, number or address") },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .testTag(TAG_SEARCH_FIELD),
    )
}

/**
 * Direction and date, as chips under the tabs.
 *
 * Shown only while searching or while something is narrowed — on a resting call log they
 * would be three controls for a list nobody is looking through yet.
 */
@Composable
private fun AdvancedFilters(query: CallLogQuery, actions: HistoryActions) {
    if (!query.text.isNotEmpty() && query.activeFilterCount == 0) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.small),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallDirectionFilter.entries.forEach { direction ->
            FilterChip(
                selected = query.direction == direction,
                onClick = { actions.onDirectionChanged(direction) },
                label = { Text(direction.label) },
                modifier = Modifier.testTag(directionChipTag(direction)),
            )
        }
        if (!query.isMatchAll) {
            TextButton(onClick = actions.onFiltersCleared) { Text("Clear") }
        }
    }
}

@Composable
private fun CallList(
    rows: LazyPagingItems<HistoryRow>,
    actions: HistoryActions,
    zone: ZoneId,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TAG_LIST),
        // Room for the floating buttons, so the newest call is not the one row the user
        // can never fully read (Task 70).
        contentPadding = PaddingValues(
            bottom = AppTheme.spacing.huge + AppTheme.sizing.callActionButton,
        ),
    ) {
        items(
            count = rows.itemCount,
            // Keyed by row identity so deleting one animates that row out rather than
            // re-drawing everything below it.
            key = { index -> rows.peek(index)?.key() ?: index },
        ) { index ->
            when (val row = rows[index]) {
                is HistoryRow.DayHeader -> DayHeading(row.epochDay)
                is HistoryRow.Call -> CallRow(row, actions, zone)
                null -> Unit
            }
        }
    }
}

/**
 * The dialler (Task 70).
 *
 * A second, smaller FAB above it opened the group-call page. That page is gone: it built a
 * group this app had no way to act on, because ADR-003's dial-in MCU gives a client no way
 * to create a room or invite anyone into one. Dialling a bridge is an ordinary call and
 * the dialler already places it.
 */
@Composable
private fun HistoryFabs(actions: HistoryActions) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        FloatingActionButton(
            onClick = actions.onOpenDialer,
            elevation = FloatingActionButtonDefaults.elevation(),
            modifier = Modifier.testTag(TAG_DIALER),
        ) {
            Icon(Icons.Filled.Dialpad, contentDescription = "Open the dialler")
        }
    }
}

@Composable
private fun FilterTabs(
    selected: CallLogFilter,
    onFilterChanged: (CallLogFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    // PrimaryTabRow, not TabRow: the plain one is deprecated in Material 3 and CI
    // builds warnings as errors.
    PrimaryTabRow(
        selectedTabIndex = CallLogFilter.entries.indexOf(selected),
        modifier = modifier,
    ) {
        CallLogFilter.entries.forEach { filter ->
            Tab(
                selected = filter == selected,
                onClick = { onFilterChanged(filter) },
                text = { Text(if (filter == CallLogFilter.ALL) "All" else "Missed") },
                modifier = Modifier.testTag(filterTag(filter)),
            )
        }
    }
}

@Composable
private fun DayHeading(epochDay: Long) {
    Text(
        text = LocalDate.ofEpochDay(epochDay).format(DAY_FORMAT),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.small)
            .testTag(dayHeadingTag(epochDay)),
    )
}

@Composable
private fun CallRow(row: HistoryRow.Call, actions: HistoryActions, zone: ZoneId) {
    val entry = row.entry

    // Swipe to call back, rather than two buttons on every row (item 3). The log is a
    // list people scan, and a pair of icons per line competes with the thing they are
    // scanning for. Left is video, right is audio.
    //
    // This is a swipe *action*, not a dismissal: the row performs the call and springs
    // back, so the entry stays in the log — which is the whole point of a log. The box
    // reports the swipe once it settles, and `reset` animates the row home. (It used to
    // veto the settle in `confirmValueChange`; that callback is deprecated without a
    // replacement, and letting the row settle then return is the documented shape.)
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()

    SwipeToDismissBox(
        state = swipe,
        backgroundContent = { SwipeAffordance(swipe.dismissDirection) },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> actions.onVideoCallBack(entry)
                SwipeToDismissBoxValue.StartToEnd -> actions.onCallBack(entry)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { swipe.reset() }
        },
        modifier = Modifier.testTag(entryTag(entry)),
    ) {
        CallRowContent(row, actions, zone)
    }
}

/** The row itself, so [CallRow] stays the gesture and this stays the layout. */
@Composable
private fun CallRowContent(row: HistoryRow.Call, actions: HistoryActions, zone: ZoneId) {
    val entry = row.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { actions.onEntryOpened(row) }
            // A swipe is not an affordance everyone has. TalkBack reads these two as
            // actions on the row, so removing the buttons did not remove the ability
            // to call back — it removed two taps from everybody who can swipe.
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Call ${row.title} back") {
                        actions.onCallBack(entry)
                        true
                    },
                    CustomAccessibilityAction("Video call ${row.title} back") {
                        actions.onVideoCallBack(entry)
                        true
                    },
                )
            }
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        // The face first, the way every list of people is arranged. The direction moved
        // down beside the time it belongs to: it describes what happened, not who it was
        // with, and it had been sitting where the person should be.
        Avatar(displayName = row.title.takeIf { title -> title.any(Char::isLetter) })

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                // A missed call is what someone opens this screen looking for, so it
                // is the one the eye lands on. Weight as well as colour: colour alone
                // is not a channel everybody has.
                color = if (entry.wasMissed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
            ) {
                Icon(
                    imageVector = entry.directionIcon(),
                    contentDescription = entry.directionDescription(),
                    tint = entry.directionTint(),
                    modifier = Modifier.size(AppTheme.spacing.medium),
                )
                Text(
                    text = entry.subtitle(zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * What appears behind a row being swiped, so the gesture says what it will do.
 *
 * A swipe with no feedback is a guess. The icon and the side it sits on are the whole
 * instruction: drag right for a voice call, left for video.
 */
@Composable
private fun SwipeAffordance(direction: SwipeToDismissBoxValue) {
    val audio = direction == SwipeToDismissBoxValue.StartToEnd
    val colour = if (audio) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (direction == SwipeToDismissBoxValue.Settled) Color.Transparent else colour)
            .padding(horizontal = AppTheme.spacing.large),
        contentAlignment = if (audio) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Icon(
                imageVector = if (audio) Icons.Filled.Call else Icons.Filled.Videocam,
                contentDescription = null,
                tint = if (audio) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
        }
    }
}

/**
 * The direction, as a filled circle rather than a bare glyph (Task 75).
 *
 * Three things carry the meaning, not one: the glyph differs per direction, the colour
 * differs per direction, and a missed call gets the error role so it stands out of a
 * scrolling list. Colour on its own would be invisible to a lot of people, and a glyph on
 * its own is what this row had — three small monochrome arrows that all read the same at
 * a glance.
 */

@Composable
private fun CallDetail(row: HistoryRow.Call, actions: HistoryActions, zone: ZoneId) {
    val entry = row.entry
    ConfirmDialog(
        title = row.title,
        // The reason comes from Task 44's table, so the sentence here and the one the
        // dialler showed when the call failed are the same sentence.
        message = buildString {
            appendLine(entry.remote.render())
            appendLine(entry.reason.userMessage())
            append(entry.subtitle(zone))
        },
        confirmLabel = "Delete",
        onConfirm = { actions.onDelete(entry) },
        onDismiss = actions.onDetailDismissed,
        destructive = true,
        dismissLabel = "Close",
        modifier = Modifier.testTag(TAG_DETAIL),
    )
}

// ---------------------------------------------------------------- presentation

private fun HistoryRow.key(): Any = when (this) {
    is HistoryRow.DayHeader -> "day-$epochDay"
    is HistoryRow.Call -> "call-${entry.id.value}"
}

private fun CallLogEntry.directionIcon(): ImageVector = when {
    wasMissed -> Icons.AutoMirrored.Filled.CallMissed
    direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
    else -> Icons.AutoMirrored.Filled.CallMade
}

/** The badge's fill. Roles from the scheme, so dark mode and dynamic colour both hold. */
@Composable
private fun CallLogEntry.directionContainer(): Color = when {
    wasMissed -> MaterialTheme.colorScheme.errorContainer
    direction == CallDirection.INCOMING -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.primaryContainer
}

/** The glyph on top of [directionContainer], paired so contrast holds in both themes. */
@Composable
private fun CallLogEntry.directionTint(): Color = when {
    wasMissed -> MaterialTheme.colorScheme.onErrorContainer
    direction == CallDirection.INCOMING -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.onPrimaryContainer
}

private fun CallLogEntry.directionDescription() = when {
    wasMissed -> "Missed call"
    direction == CallDirection.INCOMING -> "Incoming call"
    else -> "Outgoing call"
}

private fun CallLogEntry.subtitle(zone: ZoneId): String {
    val at = Instant.ofEpochMilli(startedAtEpochMillis).atZone(zone).format(TIME_FORMAT)
    return if (wasAnswered) "$at · ${formatDuration(durationSeconds)}" else at
}

/** `m:ss`, or `h:mm:ss` past the hour. A 75-minute call is not 75:00. */
internal fun formatDuration(seconds: Long): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val remainder = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "$hours:${minutes.padded()}:${remainder.padded()}"
    } else {
        "$minutes:${remainder.padded()}"
    }
}

private fun Long.padded(): String = toString().padStart(2, '0')

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

internal const val TAG_LIST = "history-list"
internal const val TAG_EMPTY = "history-empty"
internal const val TAG_DETAIL = "history-detail"
internal const val TAG_CONFIRM_CLEAR = "history-confirm-clear"
internal const val TAG_CLEAR_ALL = "history-clear-all"
internal const val TAG_SEARCH = "history-search"
internal const val TAG_SEARCH_FIELD = "history-search-field"

/** Identifies a direction chip, so a test presses the one it means. */
internal fun directionChipTag(direction: CallDirectionFilter) = "history-direction-${direction.name.lowercase()}"
internal const val TAG_SETTINGS = "history-settings"
internal const val TAG_DIALER = "history-dialer"

internal fun filterTag(filter: CallLogFilter) = "history-filter-${filter.name.lowercase()}"
internal fun dayHeadingTag(epochDay: Long) = "history-day-$epochDay"

/** Identifies one row, so a test can find the entry it means rather than a position. */
internal fun entryTag(entry: CallLogEntry) = "history-entry-${entry.id.value}"
