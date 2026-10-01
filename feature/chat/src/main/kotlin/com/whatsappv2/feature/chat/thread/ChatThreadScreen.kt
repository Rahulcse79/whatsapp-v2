package com.whatsappv2.feature.chat.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageType
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.feature.chat.ChatConnectionBanner

/**
 * One conversation: its messages and a composer.
 *
 * ## The composer is disabled until we know who we are
 *
 * Not a nicety. `me()` resolves lazily on socket connect, and a message sent before it
 * lands carries a **null sender** — `isMine` is then false, and the user's own message is
 * drawn on the left, as though somebody else had said it (finding 1.3-5). It corrects
 * itself when the ack arrives, which is worse than waiting: the thread visibly rearranges.
 *
 * ## A failed bubble offers a retry, and that state is ours
 *
 * The SDK has no failure state for a send at all — an offer with no socket is dropped and
 * left "sending" for ever. `Delivery.Failed` is the app's own, produced by the outbox, and
 * this is what it is for.
 */
@Composable
fun ChatThreadScreen(
    state: ChatThreadUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Follow the bottom as messages arrive. Keyed on the count so it does not fight a
    // user who has scrolled up to read history.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                title = state.title,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            ChatConnectionBanner(state = state.connection, onSignIn = {})

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isEmpty -> EmptyState(
                        title = "No messages yet",
                        description = "Say something to start this conversation.",
                        modifier = Modifier.testTag(TAG_THREAD_EMPTY),
                    )

                    else -> LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            AppTheme.spacing.large,
                        ),
                        modifier = Modifier.fillMaxSize().testTag(TAG_THREAD_LIST),
                    ) {
                        items(state.messages, key = { it.id?.value ?: it.clientId.orEmpty() }) { message ->
                            MessageBubble(
                                message = message,
                                identity = state.identity,
                                onRetry = onRetry,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            Composer(state = state, onDraftChange = onDraftChange, onSend = onSend)
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    identity: ChatIdentity?,
    onRetry: (String) -> Unit,
) {
    val mine = message.isMine(identity)

    Column(
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (mine) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (mine) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.widthIn(max = AppTheme.sizing.avatarLarge * BUBBLE_WIDTH_IN_AVATARS),
        ) {
            Text(
                text = message.displayBody(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(AppTheme.spacing.medium),
            )
        }

        when (message.delivery) {
            ChatMessage.Delivery.Pending -> CircularProgressIndicator(
                strokeWidth = AppTheme.sizing.videoTileBorder,
                modifier = Modifier
                    .padding(top = AppTheme.spacing.extraSmall)
                    .size(AppTheme.sizing.chipIcon)
                    .testTag(TAG_PENDING),
            )

            ChatMessage.Delivery.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag(TAG_FAILED),
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(AppTheme.sizing.chipIcon),
                )
                message.clientId?.let { id ->
                    TextButton(onClick = { onRetry(id) }, modifier = Modifier.testTag(TAG_RETRY)) {
                        Text("Retry")
                    }
                }
            }

            ChatMessage.Delivery.Sent -> Unit
        }
    }
}

/**
 * What to draw for a message.
 *
 * Only TEXT can be sent, but IMAGE, VIDEO, AUDIO, DOCUMENT and types this build has never
 * heard of can all **arrive** (findings 1.3-9 and the UNKNOWN case). Naming them is how a
 * thread stays readable instead of showing a run of empty bubbles.
 */
private fun ChatMessage.displayBody(): String = when (type) {
    ChatMessageType.TEXT, ChatMessageType.SYSTEM -> body.orEmpty()
    ChatMessageType.IMAGE -> "📷 Photo"
    ChatMessageType.VIDEO -> "🎬 Video"
    ChatMessageType.AUDIO -> "🎤 Audio message"
    ChatMessageType.DOCUMENT -> "📄 Document"
    ChatMessageType.UNKNOWN -> "Unsupported message"
}

@Composable
private fun Composer(
    state: ChatThreadUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(AppTheme.spacing.small),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            enabled = state.identity != null,
            placeholder = { Text(state.composerHint ?: "Message") },
            maxLines = COMPOSER_MAX_LINES,
            modifier = Modifier
                .weight(1f)
                .testTag(TAG_COMPOSER),
        )
        IconButton(
            onClick = onSend,
            enabled = state.canSend,
            modifier = Modifier.testTag(TAG_SEND),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

private const val COMPOSER_MAX_LINES = 5

/** About three-quarters of a phone's width, expressed in a design-system unit. */
private const val BUBBLE_WIDTH_IN_AVATARS = 3

internal const val TAG_THREAD_LIST = "chat-thread-list"
internal const val TAG_THREAD_EMPTY = "chat-thread-empty"
internal const val TAG_COMPOSER = "chat-thread-composer"
internal const val TAG_SEND = "chat-thread-send"
internal const val TAG_PENDING = "chat-thread-pending"
internal const val TAG_FAILED = "chat-thread-failed"
internal const val TAG_RETRY = "chat-thread-retry"

private fun sample(body: String, mine: Boolean, delivery: ChatMessage.Delivery) = ChatMessage(
    id = null,
    clientId = body,
    conversationId = ConversationId("c1"),
    senderId = if (mine) "me" else "8102",
    type = ChatMessageType.TEXT,
    body = body,
    sequenceNumber = 0,
    createdAtMs = 0,
    delivery = delivery,
)

@ThemePreviews
@Composable
private fun ChatThreadPreview() = PreviewSurface {
    ChatThreadScreen(
        state = ChatThreadUiState(
            title = "8102",
            identity = ChatIdentity("me", "d1"),
            connection = ChatConnectionState.Connected,
            messages = listOf(
                sample("Are you there?", mine = false, delivery = ChatMessage.Delivery.Sent),
                sample("On my way", mine = true, delivery = ChatMessage.Delivery.Sent),
                sample("Two minutes", mine = true, delivery = ChatMessage.Delivery.Failed),
            ),
            draft = "Almost there",
        ),
        onDraftChange = {},
        onSend = {},
        onRetry = {},
        onBack = {},
    )
}
