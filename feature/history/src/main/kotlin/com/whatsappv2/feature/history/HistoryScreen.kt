package com.whatsappv2.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Call history (Task 48, §5.2).
 *
 * Paged rather than a list: ten thousand entries is an ordinary year of calls for a
 * business handset, and loading them to draw twenty would make opening the screen a
 * visible pause and scrolling it a stutter.
 *
 * The list is stateless in the Compose sense — every action goes up through [HistoryActions]
 * — so it can be rendered from literal rows in a test and in a preview.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    rows: LazyPagingItems<HistoryRow>,
    actions: HistoryActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Column(modifier = modifier.fillMaxSize()) {
        FilterTabs(state.filter, actions.onFilterChanged)

        if (rows.itemCount == 0) {
            EmptyState(
                title = if (state.filter == CallLogFilter.MISSED) "No missed calls" else "No calls yet",
                description = "Calls you make and receive appear here.",
                icon = Icons.Filled.History,
                modifier = Modifier.testTag(TAG_EMPTY),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize().testTag(TAG_LIST)) {
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

@Composable
private fun FilterTabs(selected: CallLogFilter, onFilterChanged: (CallLogFilter) -> Unit) {
    // PrimaryTabRow, not TabRow: the plain one is deprecated in Material 3 and CI
    // builds warnings as errors.
    PrimaryTabRow(selectedTabIndex = CallLogFilter.entries.indexOf(selected)) {
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
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.medium)
            .testTag(entryTag(entry)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        Icon(
            imageVector = entry.directionIcon(),
            contentDescription = entry.directionDescription(),
            // A missed call is the one being looked for, so it is the one that is coloured.
            tint = if (entry.wasMissed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.subtitle(zone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = { actions.onCallBack(entry) },
            modifier = Modifier.testTag(callBackTag(entry)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.CallMade,
                contentDescription = "Call ${entry.title()} back",
            )
        }
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

private fun CallLogEntry.directionIcon() = when {
    wasMissed -> Icons.AutoMirrored.Filled.CallMissed
    direction == CallDirection.INCOMING -> Icons.AutoMirrored.Filled.CallReceived
    else -> Icons.AutoMirrored.Filled.CallMade
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

internal fun filterTag(filter: CallLogFilter) = "history-filter-${filter.name.lowercase()}"
internal fun entryTag(entry: CallLogEntry) = "history-entry-${entry.id.value}"
internal fun callBackTag(entry: CallLogEntry) = "history-callback-${entry.id.value}"
internal fun dayHeadingTag(epochDay: Long) = "history-day-$epochDay"
