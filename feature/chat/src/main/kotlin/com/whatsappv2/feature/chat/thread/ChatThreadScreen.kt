package com.whatsappv2.feature.chat.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.Avatar
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
 * One conversation: its messages, a composer, and the two ways to call the other party.
 *
 * ## Why this screen has a palette of its own
 *
 * A thread is the one screen in this app that is mostly somebody else's words, read like
 * a page rather than scanned like a list. `AppTheme.chatColors` gives it the tinted page
 * and the two bubble tones every messaging app uses, because "mine" and "theirs" decided
 * by brightness alone stops working the moment the phone is outdoors. The colours live in
 * `:core:designsystem` — architecture rule 8 — and are the reference client's own, so the
 * two products look like one.
 *
 * ## The call buttons are SIP, and the chat server knows nothing about them
 *
 * The SDK has no call API. What joins the two halves is that on this deployment a
 * person's chat handle and their PBX extension are the same string, which
 * `ChatConversation.callableExtension` unwraps from chat-node's `guest-8102@guest.local`.
 * They appear only when there is an extension behind them.
 *
 * ## The composer is disabled until we know who we are
 *
 * Not a nicety. `me()` resolves lazily on socket connect, and a message sent before it
 * lands carries a **null sender** — so the user's own message draws on the left, as
 * though somebody else had said it (finding 1.3-5). It corrects itself on the ack, which
 * is worse than waiting: the thread visibly rearranges.
 */
@Composable
fun ChatThreadScreen(
    state: ChatThreadUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val listState = rememberLazyListState()
    val rows = state.messages.withDayBreaks()

    // Follow the bottom as messages arrive, keyed on the count so it does not fight a
    // user who has scrolled up to read history.
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    // The keyboard is an arrival too: it takes a third of the screen, and a thread that
    // stays where it was answers "what did they say?" with the composer.
    //
    // Keyed on the inset's HEIGHT rather than on "is it showing", because the keyboard
    // animates in over a few hundred milliseconds: a single scroll fired when it starts
    // opening aims at a list that is still full height, and the message it was aiming at
    // ends up behind the composer anyway. Following the inset tracks it the whole way.
    // `snapshotFlow` keeps that per-frame reading inside the effect, so the screen does
    // not recompose on every frame of the animation.
    //
    // Only for a reader who is at the bottom: pulling somebody out of history to show
    // them the newest message is the same mistake in the other direction.
    //
    // Its keys are all stable, so a new message does not restart it — the index is read
    // through `rememberUpdatedState` instead. Keying it on the row count made it fire
    // alongside the effect above, and an instant scroll cancels an animated one already
    // on its way to the same place: a sent message landed half behind the composer.
    val lastRowIndex by rememberUpdatedState(rows.lastIndex)
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(listState, ime, density) {
        snapshotFlow { ime.getBottom(density) }.collect { inset ->
            if (inset > 0 && lastRowIndex >= 0 && listState.isAtBottom) {
                listState.scrollToItem(lastRowIndex)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AppTheme.chatColors.background,
        topBar = {
            ThreadBar(
                state = state,
                onAudioCall = onAudioCall,
                onVideoCall = onVideoCall,
                onBack = onBack,
            )
        },
        // The route owns the state, this screen owns the only Scaffold. A second one
        // wrapping this screen hands it the system-bar insets that this one then adds
        // again — the header stops short of the status bar, leaving the system's own
        // white icons on a white strip, and the composer floats a navigation bar too
        // high. It is the trap `AppRoot` documents, and every other screen avoids it the
        // same way: one Scaffold, and the host passed in.
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Consumed, not just padded: without this `imePadding` cannot know the
                // navigation bar is already accounted for above, and raises the composer
                // by the keyboard PLUS a navigation bar when the keyboard opens.
                .consumeWindowInsets(padding)
                .background(AppTheme.chatColors.background)
                .imePadding(),
        ) {
            ChatConnectionBanner(state = state.connection, onSignIn = {})

            Box(modifier = Modifier.weight(1f)) {
                if (rows.isEmpty()) {
                    EmptyThread()
                } else {
                    Messages(rows = rows, state = state, listState = listState, onRetry = onRetry)
                }
            }

            Composer(state = state, onDraftChange = onDraftChange, onSend = onSend)
        }
    }
}

/**
 * The thread's own bar: who you are talking to, and the two ways to call them.
 *
 * Lifted out of the screen so the screen reads as a layout. The call actions are here
 * rather than in the body because that is where every messaging app puts them, and
 * because a control that leaves the conversation should not sit inside it.
 */
@Composable
private fun ThreadBar(
    state: ChatThreadUiState,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit,
    onBack: () -> Unit,
) {
    AppTopBar(
        title = state.title,
        // The avatar belongs beside the name, not in the body: it is the one thing on
        // this screen that says WHO at a glance, and a thread opened from a notification
        // is often the only context the reader has.
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(displayName = state.title, size = AppTheme.sizing.avatarLarge / 3)
                Column(modifier = Modifier.padding(start = AppTheme.spacing.medium)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.presenceLabel?.let { presence ->
                        Text(
                            text = presence,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.barColors.onTopVariant,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            // Only when there is a real extension behind them. A call button that cannot
            // work is worse than no call button, and a group has nobody to ring.
            if (state.callableExtension == null) return@AppTopBar

            IconButton(
                onClick = onVideoCall,
                enabled = state.canCall,
                modifier = Modifier.testTag(TAG_VIDEO_CALL),
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = "Video call")
            }
            IconButton(
                onClick = onAudioCall,
                enabled = state.canCall,
                modifier = Modifier.testTag(TAG_AUDIO_CALL),
            ) {
                Icon(Icons.Filled.Call, contentDescription = "Audio call")
            }
        },
    )
}

@Composable
private fun Messages(
    rows: List<ThreadRow>,
    state: ChatThreadUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetry: (String) -> Unit,
) {
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
        contentPadding = PaddingValues(
            horizontal = AppTheme.spacing.small,
            vertical = AppTheme.spacing.medium,
        ),
        modifier = Modifier.fillMaxSize().testTag(TAG_THREAD_LIST),
    ) {
        items(rows) { row ->
            when (row) {
                is ThreadRow.Day -> DaySeparator(row.label)
                is ThreadRow.Said -> MessageBubble(
                    message = row.message,
                    identity = state.identity,
                    // Never in a one-to-one thread: the bar already names the one person
                    // who can be speaking, and the label the SDK can produce is a
                    // shortened user id rather than a name.
                    showSender = row.showSender && !state.isDirect,
                    onRetry = onRetry,
                )
            }
        }
    }
}

/** Typed so `items` can key a day capsule and a message apart without a sentinel. */
private fun androidx.compose.foundation.lazy.LazyListScope.items(
    rows: List<ThreadRow>,
    content: @Composable (ThreadRow) -> Unit,
) = items(count = rows.size, key = { rows[it].key }) { content(rows[it]) }

@Composable
private fun DaySeparator(label: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.small)) {
        Surface(
            color = AppTheme.chatColors.dayDivider,
            contentColor = AppTheme.chatColors.onDayDivider,
            shape = RoundedCornerShape(AppTheme.radius.large),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(
                    horizontal = AppTheme.spacing.medium,
                    vertical = AppTheme.spacing.extraSmall,
                ),
            )
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    identity: ChatIdentity?,
    showSender: Boolean,
    onRetry: (String) -> Unit,
) {
    val mine = message.isMine(identity)
    val colors = AppTheme.chatColors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            // The squared-off corner marks the speaker, which is the cheapest way to tell
            // two bubbles apart without colour — and the one that survives a colour-blind
            // reader and a sunlit screen.
            shape = RoundedCornerShape(
                topStart = AppTheme.radius.medium,
                topEnd = AppTheme.radius.medium,
                bottomStart = if (mine) AppTheme.radius.medium else AppTheme.radius.none,
                bottomEnd = if (mine) AppTheme.radius.none else AppTheme.radius.medium,
            ),
            color = if (mine) colors.outgoingBubble else colors.incomingBubble,
            contentColor = if (mine) colors.onOutgoingBubble else colors.onIncomingBubble,
            modifier = Modifier.widthIn(max = AppTheme.sizing.chatBubbleMaxWidth),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = AppTheme.spacing.medium,
                    vertical = AppTheme.spacing.small,
                ),
            ) {
                if (showSender && !mine) {
                    Text(
                        text = message.senderLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.senderName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(text = message.displayBody(), style = MaterialTheme.typography.bodyLarge)

                MessageMeta(
                    message = message,
                    mine = mine,
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/**
 * The time and, for a message of ours, how far it got.
 *
 * Inside the bubble and right-aligned, which is what makes a thread scannable: the eye
 * follows one column of timestamps rather than hunting for them between bubbles.
 */
@Composable
private fun MessageMeta(
    message: ChatMessage,
    mine: Boolean,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
        modifier = modifier.padding(top = AppTheme.spacing.extraSmall),
    ) {
        Text(
            text = message.timeLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = AppTheme.chatColors.bubbleMeta,
        )

        if (!mine) return@Row

        when (message.delivery) {
            // A clock, not a spinner: a spinner says "working" and invites waiting, while
            // most of these resolve in well under a second.
            ChatMessage.Delivery.Pending -> Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = "Sending",
                tint = AppTheme.chatColors.bubbleMeta,
                modifier = Modifier.size(AppTheme.sizing.chatTick).testTag(TAG_PENDING),
            )

            ChatMessage.Delivery.Sent -> Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Sent",
                tint = AppTheme.chatColors.deliveredTick,
                modifier = Modifier.size(AppTheme.sizing.chatTick),
            )

            ChatMessage.Delivery.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag(TAG_FAILED),
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "Not sent",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(AppTheme.sizing.chatTick),
                )
                message.clientId?.let { id ->
                    TextButton(onClick = { onRetry(id) }, modifier = Modifier.testTag(TAG_RETRY)) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyThread() {
    Column(
        modifier = Modifier.fillMaxSize().padding(AppTheme.spacing.extraLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(AppTheme.radius.large),
            color = AppTheme.chatColors.dayDivider,
            contentColor = AppTheme.chatColors.onDayDivider,
            modifier = Modifier.testTag(TAG_THREAD_EMPTY),
        ) {
            Text(
                text = "No messages yet. Say something to start this conversation.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(AppTheme.spacing.large),
            )
        }
    }
}

@Composable
private fun Composer(
    state: ChatThreadUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        // No navigation-bar padding here: the Scaffold above already reserves it, and
        // adding it a second time parks the composer a bar's height up the page.
        modifier = Modifier
            .fillMaxWidth()
            .padding(AppTheme.spacing.small),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            enabled = state.identity != null,
            placeholder = { Text(state.composerHint ?: "Message") },
            maxLines = COMPOSER_MAX_LINES,
            shape = RoundedCornerShape(AppTheme.radius.full),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AppTheme.chatColors.composer,
                unfocusedContainerColor = AppTheme.chatColors.composer,
                disabledContainerColor = AppTheme.chatColors.composer,
            ),
            modifier = Modifier
                .weight(1f)
                .testTag(TAG_COMPOSER),
        )

        // A filled circle rather than a bare icon: it is the screen's primary action and
        // the only control on a tinted page that should look pressable.
        Surface(
            shape = CircleShape,
            color = if (state.canSend) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.size(AppTheme.sizing.minimumTouchTarget),
        ) {
            IconButton(
                onClick = onSend,
                enabled = state.canSend,
                modifier = Modifier.testTag(TAG_SEND),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (state.canSend) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private const val COMPOSER_MAX_LINES = 5

internal const val TAG_THREAD_LIST = "chat-thread-list"
internal const val TAG_THREAD_EMPTY = "chat-thread-empty"
internal const val TAG_COMPOSER = "chat-thread-composer"
internal const val TAG_SEND = "chat-thread-send"
internal const val TAG_PENDING = "chat-thread-pending"
internal const val TAG_FAILED = "chat-thread-failed"
internal const val TAG_RETRY = "chat-thread-retry"
internal const val TAG_AUDIO_CALL = "chat-thread-audio-call"
internal const val TAG_VIDEO_CALL = "chat-thread-video-call"

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
            callableExtension = "8102",
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
        onAudioCall = {},
        onVideoCall = {},
        onBack = {},
    )
}

/**
 * Whether the newest row is on screen — the test for "is this reader following along".
 *
 * An empty list counts as at the bottom: there is nothing to have scrolled away from.
 */
private val LazyListState.isAtBottom: Boolean
    get() = layoutInfo.visibleItemsInfo.lastOrNull()
        ?.let { it.index >= layoutInfo.totalItemsCount - 1 }
        ?: true
