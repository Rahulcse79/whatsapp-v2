package com.whatsappv2.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.ErrorState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatMessageType
import com.whatsappv2.domain.chat.ConversationId

/**
 * The Chats tab: the conversation list, or a reason there isn't one.
 *
 * ## Signing in gates this tab, not the application
 *
 * This is a SIP client first; chat is one of its two tabs. A signed-out user gets a prompt
 * *here* and a fully working Calls tab beside it — never a full-screen login in front of
 * the app, which would make placing a call depend on a chat account (decision D4).
 *
 * It is also a separate gate from `FirstRunGate`. That one runs terms, tour and
 * permissions once and is done; this one recurs, because signing out returns to it.
 *
 * ## The floating button is bottom-right, and that is the whole argument
 *
 * "New conversation" lives at the bottom right of the first tab in every messaging app
 * this one sits beside. A thumb is already there. It is shown only when signed in,
 * because there is nobody to start a conversation with otherwise.
 *
 * ## The top bar is the shell's, inherited from the placeholder this replaces
 *
 * The **gear** — Settings is reached from here and the Calls tab and nowhere else — and
 * the **registration indicator**, because "am I reachable" is what a phone app's home
 * screen should answer unasked. Same signatures the placeholder had.
 */
@Composable
fun ChatsScreen(
    state: ChatsUiState,
    onSignIn: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (ConversationId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    registrationIndicator: (@Composable () -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                title = "Chats",
                actions = {
                    registrationIndicator?.invoke()
                    onOpenSettings?.let { open ->
                        IconButton(onClick = open, modifier = Modifier.testTag(TAG_CHATS_SETTINGS)) {
                            Icon(Icons.Filled.Settings, contentDescription = "Open settings")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.isSignedIn) {
                FloatingActionButton(
                    onClick = onNewConversation,
                    modifier = Modifier.testTag(TAG_NEW_CONVERSATION),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "New conversation")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Only while signed in: a signed-out user is told so by the body below, and
            // saying it twice in two different shapes is worse than saying it once.
            if (state.isSignedIn) {
                ChatConnectionBanner(state = state.connection, onSignIn = onSignIn)
            }

            when {
                !state.isSignedIn -> SignedOut(onSignIn = onSignIn)

                state.conversations.isEmpty() && state.error != null ->
                    ErrorState(
                        title = "Could not load conversations",
                        description = state.error.describe(),
                        onRetry = onRetry.takeIf { state.error.isRetryable },
                        modifier = Modifier.testTag(TAG_CHATS_ERROR),
                    )

                state.isEmpty -> NoConversations()

                else -> LazyColumn(modifier = Modifier.fillMaxSize().testTag(TAG_CONVERSATIONS)) {
                    items(state.conversations, key = { it.id.value }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            onClick = { onOpenConversation(conversation.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ChatConversation, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.medium),
    ) {
        Avatar(displayName = conversation.title)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppTheme.spacing.large),
        ) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = conversation.preview(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (conversation.unreadCount > 0) {
            Badge { Text(conversation.unreadCount.toString()) }
        }
    }
}

/**
 * The row's second line.
 *
 * A type the renderer cannot draw is named rather than shown blank: the server can send
 * IMAGE, VIDEO, AUDIO and DOCUMENT even though only TEXT can be sent (finding 1.3-9), and
 * an empty second line would read as a bug rather than as an unsupported attachment.
 */
private fun ChatConversation.preview(): String = when (lastMessageType) {
    null -> "No messages yet"
    ChatMessageType.TEXT -> lastMessageBody.orEmpty().ifBlank { "No messages yet" }
    ChatMessageType.IMAGE -> "Photo"
    ChatMessageType.VIDEO -> "Video"
    ChatMessageType.AUDIO -> "Audio message"
    ChatMessageType.DOCUMENT -> "Document"
    ChatMessageType.SYSTEM -> lastMessageBody.orEmpty().ifBlank { "System message" }
    ChatMessageType.UNKNOWN -> "Unsupported message"
}

@Composable
private fun SignedOut(onSignIn: () -> Unit) {
    Centred(
        title = "Sign in to chat",
        body = "Chat needs its own account. Calls work as they always have, signed in or not.",
    ) {
        Button(
            onClick = onSignIn,
            modifier = Modifier
                .padding(top = AppTheme.spacing.large)
                .testTag(TAG_SIGN_IN),
        ) {
            Text("Sign in")
        }
    }
}

@Composable
private fun NoConversations() = Centred(
    title = "No conversations yet",
    body = "Start one with the button below.",
)

@Composable
private fun Centred(title: String, body: String, action: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppTheme.spacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(AppTheme.spacing.large)
                    .size(AppTheme.spacing.extraLarge),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = AppTheme.spacing.large),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppTheme.spacing.small),
        )
        action()
    }
}

/**
 * Identifies the gear, so a test presses the control it means.
 *
 * The same string the placeholder used. `AppRootNavigationTest` reaches for it by that
 * value, and the tab changing hands is not a reason for a navigation test to change.
 */
internal const val TAG_CHATS_SETTINGS = "chats-settings"
internal const val TAG_SIGN_IN = "chats-sign-in"
internal const val TAG_NEW_CONVERSATION = "chats-new-conversation"
internal const val TAG_CONVERSATIONS = "chats-conversations"
internal const val TAG_CHATS_ERROR = "chats-error"

private val SAMPLE = listOf(
    ChatConversation(
        id = ConversationId("c1"),
        type = "DIRECT",
        createdAtMs = 0,
        muted = false,
        archived = false,
        pinned = false,
        otherUserId = "8102",
        otherUserContactIdentifier = "8102",
        lastMessageBody = "See you at four",
        lastMessageType = ChatMessageType.TEXT,
        lastMessageSenderId = "8102",
        lastMessageAtMs = 0,
        unreadCount = 2,
    ),
)

@ThemePreviews
@Composable
private fun ChatsListPreview() = PreviewSurface {
    ChatsScreen(
        state = ChatsUiState(
            isSignedIn = true,
            connection = ChatConnectionState.Connected,
            conversations = SAMPLE,
        ),
        onSignIn = {},
        onNewConversation = {},
        onOpenConversation = {},
        onRetry = {},
        onOpenSettings = {},
    )
}

@ThemePreviews
@Composable
private fun ChatsSignedOutPreview() = PreviewSurface {
    ChatsScreen(
        state = ChatsUiState(),
        onSignIn = {},
        onNewConversation = {},
        onOpenConversation = {},
        onRetry = {},
        onOpenSettings = {},
    )
}
