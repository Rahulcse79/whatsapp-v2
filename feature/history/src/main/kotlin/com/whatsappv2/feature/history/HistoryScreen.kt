package com.whatsappv2.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.paging.compose.LazyPagingItems
import com.whatsappv2.core.designsystem.component.ConfirmDialog
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.repository.CallLogFilter
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
        topBar = { HistoryTopBar(actions = actions) },
        floatingActionButton = { HistoryFabs(actions = actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            FilterTabs(
                selected = state.filter,
                onFilterChanged = actions.onFilterChanged,
            )

            if (rows.itemCount == 0) {
                EmptyState(
                    title = if (state.filter == CallLogFilter.MISSED) "No missed calls" else "No calls yet",
                    description = "Calls you make and receive appear here.",
                    icon = Icons.Filled.History,
                    modifier = Modifier.testTag(TAG_EMPTY),
                )
                return@Column
            }

            CallList(rows = rows, actions = actions, zone = zone)
        }
    }

    state.openEntry?.let { entry ->
        CallDetail(entry, actions, zone)
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

/** Clearing the log, and the one way into settings now that it is not a tab (Task 69). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTopBar(actions: HistoryActions) {
    TopAppBar(
        title = { Text("Calls") },
        actions = {
            IconButton(
                onClick = actions.onClearAllRequested,
                modifier = Modifier.testTag(TAG_CLEAR_ALL),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Clear call history")
            }
            IconButton(
                onClick = actions.onOpenSettings,
                modifier = Modifier.testTag(TAG_SETTINGS),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings and accounts")
            }
        },
    )
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
                is HistoryRow.Call -> CallRow(row.entry, actions, zone)
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
private fun CallRow(entry: CallLogEntry, actions: HistoryActions, zone: ZoneId) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { actions.onEntryOpened(entry) }
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.small)
            .testTag(entryTag(entry)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        DirectionBadge(entry)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title(),
                style = MaterialTheme.typography.bodyLarge,
                // A missed call is what someone opens this screen looking for, so it is
                // the one the eye lands on. Weight as well as colour: colour alone is not
                // a channel everybody has.
                color = if (entry.wasMissed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle(zone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Two ways to call back, because the log records which kind the call was and the
        // one people want again is usually the same kind (Tasks 74, 75).
        IconButton(
            onClick = { actions.onVideoCallBack(entry) },
            modifier = Modifier.testTag(videoCallBackTag(entry)),
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = "Video call ${entry.title()} back",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(
            onClick = { actions.onCallBack(entry) },
            modifier = Modifier.testTag(callBackTag(entry)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.CallMade,
                contentDescription = "Call ${entry.title()} back",
                tint = MaterialTheme.colorScheme.primary,
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
private fun DirectionBadge(entry: CallLogEntry) {
    Box(
        modifier = Modifier
            .size(AppTheme.sizing.avatarSmall)
            .background(entry.directionContainer(), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = entry.directionIcon(),
            contentDescription = entry.directionDescription(),
            tint = entry.directionTint(),
            modifier = Modifier.size(AppTheme.spacing.extraLarge),
        )
    }
}

@Composable
private fun CallDetail(entry: CallLogEntry, actions: HistoryActions, zone: ZoneId) {
    ConfirmDialog(
        title = entry.title(),
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

/** The contact's name if we know it, then what the peer called itself, then the address. */
private fun CallLogEntry.title(): String =
    contactName ?: remoteDisplayName ?: remote.render()

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
internal const val TAG_SETTINGS = "history-settings"
internal const val TAG_DIALER = "history-dialer"

internal fun filterTag(filter: CallLogFilter) = "history-filter-${filter.name.lowercase()}"
internal fun entryTag(entry: CallLogEntry) = "history-entry-${entry.id.value}"
internal fun callBackTag(entry: CallLogEntry) = "history-callback-${entry.id.value}"
internal fun videoCallBackTag(entry: CallLogEntry) = "history-video-callback-${entry.id.value}"
internal fun dayHeadingTag(epochDay: Long) = "history-day-$epochDay"
