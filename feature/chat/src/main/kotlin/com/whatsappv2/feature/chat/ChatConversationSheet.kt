package com.whatsappv2.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * What a long press on a chat offers.
 *
 * ## Why a sheet replaced the gesture that pinned
 *
 * Long press used to pin, immediately and silently. That is an invisible control doing an
 * irreversible-looking thing: nothing on the row says the gesture exists, and somebody who
 * discovers it by accident has no idea what just happened or how to undo it. A sheet names
 * the actions, says which way each one goes for *this* row, and costs one tap.
 *
 * ## Why there are only these actions
 *
 * Because they are the only ones this app can perform. `ChatSdk`'s entire surface is
 * `connect`, `me`, `getConversations`, `openDirectConversation`, `getMessages` and
 * `sendText` — there is no delete, no mute, no archive and no mark-read, and the
 * conversation summary's `muted`/`archived` flags are read-only for exactly that reason
 * (finding 1.3-10). Offering a Delete that could not delete would be worse than not
 * offering one.
 *
 * Both actions here are this device's own: pins because chat-node has no frame for them,
 * read state because it refuses `message.read`. See `ChatPinRepository` and
 * `ChatReadRepository` for what that costs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatConversationSheet(
    title: String,
    pinned: Boolean,
    readAction: ConversationReadAction?,
    onTogglePin: () -> Unit,
    onReadAction: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        modifier = Modifier.testTag(TAG_CONVERSATION_SHEET),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Which row this is about. A menu of verbs with no subject is how somebody
            // pins the wrong chat and never finds out which.
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = AppTheme.spacing.large,
                    end = AppTheme.spacing.large,
                    bottom = AppTheme.spacing.small,
                ),
            )

            SheetAction(
                icon = Icons.Filled.PushPin,
                label = if (pinned) "Unpin chat" else "Pin chat",
                tag = TAG_SHEET_PIN,
                onClick = onTogglePin,
            )

            // At most one, and only when it would do something — see
            // `ChatsUiState.readActionFor`, which is where that is decided.
            when (readAction) {
                ConversationReadAction.MarkRead -> SheetAction(
                    icon = Icons.Filled.MarkChatRead,
                    label = "Mark as read",
                    tag = TAG_SHEET_READ,
                    onClick = onReadAction,
                )

                ConversationReadAction.MarkUnread -> SheetAction(
                    icon = Icons.Filled.MarkChatUnread,
                    label = "Mark as unread",
                    tag = TAG_SHEET_UNREAD,
                    onClick = onReadAction,
                )

                null -> Unit
            }
        }
    }
}

/** One row of the sheet. The whole row is the target, not just the label. */
@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.medium),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

internal const val TAG_CONVERSATION_SHEET = "chats-conversation-sheet"
internal const val TAG_SHEET_PIN = "chats-sheet-pin"
internal const val TAG_SHEET_READ = "chats-sheet-read"
internal const val TAG_SHEET_UNREAD = "chats-sheet-unread"
