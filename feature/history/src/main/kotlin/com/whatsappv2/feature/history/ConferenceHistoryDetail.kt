package com.whatsappv2.feature.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.CallLogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A past conference, member by member (ADR-009).
 *
 * The log writes one row per leg, and until now the detail of any of them named one
 * person: "1005 · Voice call · Call ended · Conference". This is the conference itself —
 * when it started and how long it ran, and then every member: who they are, their
 * extension, and whether they were reached at all, because a member who never answered
 * was in the plan and not in the room, and the difference is the thing worth recording.
 *
 * It can be called back as one — every member dialled and mixed as they answer, the way
 * a group call is called back anywhere else — and deleted as one, because deleting the
 * leg the entry was built on and leaving the others would put the conference straight
 * back in the list, one member shorter.
 */
@Composable
internal fun ConferenceHistoryDetail(
    row: HistoryRow.Call,
    actions: HistoryActions,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = actions.onDetailDismissed,
        modifier = modifier.testTag(TAG_DETAIL),
        icon = { Icon(imageVector = Icons.Filled.Groups, contentDescription = null) },
        // Video or voice, in the title, because the two "Call again" buttons below give
        // the conference back in either form and the user should know which it was.
        title = { Text(if (row.hasVideo) "Video conference" else "Voice conference") },
        text = {
            // The people, not the legs: a conference held in the bridge has one leg more
            // than it has members — this device's own call to the room — and the room is
            // not somebody who attended.
            val members = row.members
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = row.summary(zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (members.size == 1) "1 member" else "${members.size} members",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = AppTheme.spacing.medium, bottom = AppTheme.spacing.extraSmall),
                )
                // Bounded and scrollable: eight members and a dialog's chrome do not
                // fit a handset otherwise, and the buttons must stay reachable.
                Column(
                    modifier = Modifier
                        .heightIn(max = AppTheme.sizing.dialogListMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .testTag(TAG_DETAIL_MEMBERS),
                ) {
                    members.forEachIndexed { index, member ->
                        MemberRow(member)
                        if (index < members.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Both forms, as the row's two swipes offer them: voice is mixed here, video
            // is built in the bridge (ADR-003, ADR-009).
            Row {
                TextButton(onClick = { actions.onCallBack(row) }, modifier = Modifier.testTag(TAG_DETAIL_CALL_AGAIN)) {
                    Text("Voice call")
                }
                TextButton(
                    onClick = { actions.onVideoCallBack(row) },
                    modifier = Modifier.testTag(TAG_DETAIL_VIDEO_CALL_AGAIN),
                ) {
                    Text("Video call")
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = actions.onDetailDismissed) { Text("Close") }
                TextButton(onClick = { actions.onDelete(row) }, modifier = Modifier.testTag(TAG_DETAIL_DELETE)) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/** One member: their face or initials, name over extension, and how their leg went. */
@Composable
private fun MemberRow(member: HistoryRow.Member) {
    val entry = member.entry
    val address = entry.remote.user ?: entry.remote.render()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppTheme.spacing.small)
            .testTag(memberTag(entry)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
    ) {
        Avatar(displayName = member.title)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (address != member.title) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            // Reached, and for how long — or not, which is the thing a host wants to know
            // about a meeting somebody missed.
            text = if (entry.wasAnswered) formatDuration(entry.durationSeconds) else "Not answered",
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.wasAnswered) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/** "Started 16:34 · lasted 2:12", or just when it started if nobody was ever reached. */
private fun HistoryRow.Call.summary(zone: ZoneId): String {
    val at = Instant.ofEpochMilli(startedAtEpochMillis).atZone(zone).format(TIME_FORMAT)
    return if (wasAnswered) {
        "Started $at · lasted ${formatDuration(durationSeconds)}"
    } else {
        "Started $at · nobody answered"
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal const val TAG_DETAIL_MEMBERS = "history-detail-members"
internal const val TAG_DETAIL_CALL_AGAIN = "history-detail-call-again"
internal const val TAG_DETAIL_VIDEO_CALL_AGAIN = "history-detail-video-call-again"
internal const val TAG_DETAIL_DELETE = "history-detail-delete"

/** The test tag of one member's row in the conference detail. */
internal fun memberTag(entry: CallLogEntry) = "history-member-${entry.id.value}"
