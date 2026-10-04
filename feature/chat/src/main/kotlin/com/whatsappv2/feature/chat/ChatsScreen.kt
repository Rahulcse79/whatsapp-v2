package com.whatsappv2.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.ConfirmDialog
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
 * ## The signed-out branch IS the gate, and this screen is the only one that has one
 *
 * Decision D4: sign-in gates this **tab**, not the application. It was briefly the whole
 * app's gate — mounted above `AppRoot` in `:app` — and that made a signed-out device an app
 * with nothing in it: no dialler, no call log, no Settings, no account list. This is a SIP
 * client that also chats, a SIP account is configured in Accounts and registers with no chat
 * session involved, so placing a call must not require a chat login. The gate came back here.
 *
 * What that means for everything below: this screen is composed **with or without** a
 * session, [ChatsUiState.isSignedIn] is load-bearing rather than decorative, and every
 * control that needs an identity — the search field, the two floating buttons, the overflow
 * menu — is absent while signed out.
 *
 * ## Sign out is in the overflow menu, and it is the only sign-out in the app
 *
 * It used to be a Settings row. Settings is reached from the Calls tab as well, which put
 * the chat login's one exit outside the section it belongs to — on a screen a user with no
 * chat account visits to change their audio route. The three dots here are in the section
 * whose session they end, next to the gear and the registration indicator.
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
    /** Confirmed first by the bar's menu — see [ChatsOverflow]. Defaulted for previews. */
    onSignOut: () -> Unit = {},
    onNewConversation: () -> Unit,
    /** Defaulted so a preview renders without wiring a route that does not exist there. */
    onNewGroup: () -> Unit = {},
    onOpenConversation: (ConversationId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit = {},
    onTogglePin: (ConversationId) -> Unit = {},
    onMarkRead: (ConversationId) -> Unit = {},
    onMarkUnread: (ConversationId) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenSettings: (() -> Unit)? = null,
    registrationIndicator: (@Composable () -> Unit)? = null,
) {
    // Which row the sheet is about, or null. Screen-local because it is pure presentation:
    // nothing outside this composable needs to know a menu is open.
    var selected by remember { mutableStateOf<ConversationId?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ChatsBar(
                state = state,
                onQueryChange = onQueryChange,
                onSignOut = onSignOut,
                onOpenSettings = onOpenSettings,
                registrationIndicator = registrationIndicator,
            )
        },
        floatingActionButton = {
            if (state.isSignedIn) {
                ChatsActionButtons(onNewConversation = onNewConversation, onNewGroup = onNewGroup)
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

                state.hasNoMatches -> NoMatches(query = state.query)

                else -> ConversationList(
                    state = state,
                    onOpenConversation = onOpenConversation,
                    // Long press opens the menu rather than pinning outright: an invisible
                    // gesture doing a silent thing is how somebody pins a chat by accident
                    // and cannot work out how to undo it.
                    onLongPress = { selected = it },
                )
            }
        }
    }

    ConversationSheetHost(
        state = state,
        selected = selected,
        onTogglePin = onTogglePin,
        onMarkRead = onMarkRead,
        onMarkUnread = onMarkUnread,
        onClose = { selected = null },
    )
}

/**
 * Draws the long-press menu for [selected], when there is one.
 *
 * The conversation is resolved from [state] on every composition rather than captured when
 * the press happened, so a row that changes underneath an open sheet — a message lands and
 * the badge appears — is described by what it is now. A row that disappears closes the sheet
 * rather than leaving a menu attached to nothing.
 */
/**
 * The two things you can start from here, stacked.
 *
 * A group is the rarer of the two, so it sits **above** the everyday button and smaller — the
 * ordinary shape of a secondary action, and the reason it does not compete with the one people
 * press every day. Shown only when signed in: there is nobody to start either with otherwise.
 */
@Composable
private fun ChatsActionButtons(onNewConversation: () -> Unit, onNewGroup: () -> Unit) {
    Column(horizontalAlignment = Alignment.End) {
        SmallFloatingActionButton(
            onClick = onNewGroup,
            modifier = Modifier
                .padding(bottom = AppTheme.spacing.medium)
                .testTag(TAG_NEW_GROUP),
        ) {
            Icon(Icons.Filled.Group, contentDescription = "New group")
        }
        FloatingActionButton(
            onClick = onNewConversation,
            modifier = Modifier.testTag(TAG_NEW_CONVERSATION),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "New conversation")
        }
    }
}

@Composable
private fun ConversationSheetHost(
    state: ChatsUiState,
    selected: ConversationId?,
    onTogglePin: (ConversationId) -> Unit,
    onMarkRead: (ConversationId) -> Unit,
    onMarkUnread: (ConversationId) -> Unit,
    onClose: () -> Unit,
) {
    val id = selected ?: return
    val conversation = state.conversations.firstOrNull { it.id == id }
    if (conversation == null) {
        onClose()
        return
    }

    val readAction = state.readActionFor(conversation)
    ChatConversationSheet(
        title = state.titleOf(conversation),
        pinned = state.isPinned(id),
        readAction = readAction,
        onTogglePin = {
            onClose()
            onTogglePin(id)
        },
        onReadAction = {
            onClose()
            when (readAction) {
                ConversationReadAction.MarkRead -> onMarkRead(id)
                ConversationReadAction.MarkUnread -> onMarkUnread(id)
                null -> Unit
            }
        },
        onDismiss = onClose,
    )
}

/**
 * The header: the title, which extension you are, the gear, and the search field under all three.
 *
 * The field is in the header's own slot rather than behind a magnifier that expands —
 * the directory screen already searches this way, and a field that is always there is one
 * tap closer than one that has to be revealed. It is absent while signed out, where there
 * is nothing to search.
 *
 * The extension is under the title because on a deployment where one person has a platform
 * username and a different PBX number, "which extension am I" is the question asked before
 * anything else — and signing in now provisions that extension, so the header is also the
 * receipt for what signing in did.
 */
@Composable
private fun ChatsBar(
    state: ChatsUiState,
    onQueryChange: (String) -> Unit,
    onSignOut: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    registrationIndicator: (@Composable () -> Unit)?,
) {
    val extensionLabel = state.myExtensionLabel
    AppTopBar(
        title = "Chats",
        // The same shape the search slot below uses: a composable or nothing, never a
        // composable that draws nothing.
        titleContent = if (extensionLabel != null) {
            { ChatsTitle(extensionLabel = extensionLabel) }
        } else {
            null
        },
        actions = {
            registrationIndicator?.invoke()
            onOpenSettings?.let { open ->
                IconButton(onClick = open, modifier = Modifier.testTag(TAG_CHATS_SETTINGS)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Open settings")
                }
            }
            // Only while there is a session to end. Three dots over a menu holding one
            // disabled item is a control that invites a tap and then explains nothing.
            if (state.isSignedIn) {
                ChatsOverflow(extensionLabel = extensionLabel, onSignOut = onSignOut)
            }
        },
        below = if (state.isSignedIn) {
            {
                SearchField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = AppTheme.spacing.large,
                            vertical = AppTheme.spacing.small,
                        ),
                )
            }
        } else {
            null
        },
    )
}

/**
 * "Chats", with the signed-in extension under it.
 *
 * A second line rather than a longer title: `Chats · 8101 (mcx8101)` ellipsises on a narrow
 * handset and loses exactly the half that carries the information. The label is quieter than
 * the title because it is context, not the name of the screen.
 */
@Composable
private fun ChatsTitle(extensionLabel: String) {
    Column {
        Text(
            text = "Chats",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = extensionLabel,
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.barColors.onTopVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(TAG_MY_EXTENSION),
        )
    }
}

/**
 * The three dots: who you are, and the one thing that can be done about it.
 *
 * ## It confirms, and the confirmation is here rather than in the ViewModel
 *
 * Signing out clears the session and the token, and the only way back is to type a password
 * again — so it is guarded. Whether to confirm is a UI decision, which is why `ConfirmDialog`
 * is raised here and [ChatsViewModel.signOut] is called only on the way out of it.
 *
 * ## The identity is repeated at the top of the menu on purpose
 *
 * The header already carries it under the title, but a sign-out confirmation that does not
 * say whose session it is ending is the one every multi-account user has pressed by mistake.
 * Not a menu item — it does nothing, so it is text with a divider under it rather than
 * something that looks tappable.
 *
 * The item is in the error colour, which is where a destructive action reads as destructive —
 * the same treatment "Clear call history" gets in the Calls overflow.
 */
@Composable
private fun ChatsOverflow(extensionLabel: String?, onSignOut: () -> Unit) {
    // rememberSaveable, so a rotation with the menu open does not drop it. The dialog is
    // saved for the same reason: it outlives the menu that raised it.
    var open by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(TAG_CHATS_OVERFLOW)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.testTag(TAG_CHATS_OVERFLOW_MENU),
        ) {
            extensionLabel?.let { label ->
                Text(
                    text = "Signed in as $label",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        horizontal = AppTheme.spacing.large,
                        vertical = AppTheme.spacing.small,
                    ),
                )
                HorizontalDivider()
            }

            DropdownMenuItem(
                text = { Text("Sign out of chat") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
                onClick = {
                    open = false
                    confirming = true
                },
                modifier = Modifier.testTag(TAG_CHATS_SIGN_OUT),
            )
        }
    }

    if (confirming) {
        ConfirmDialog(
            title = "Sign out of chat?",
            message = "You will need your password to sign in again. Calls are not affected — " +
                "your SIP accounts stay registered.",
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                confirming = false
                onSignOut()
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * The list, in two sections.
 *
 * Both sections draw the same row and differ only in what they are given, which is what
 * keeps "pinned" a property of the row rather than a second kind of row.
 */
@Composable
private fun ConversationList(
    state: ChatsUiState,
    onOpenConversation: (ConversationId) -> Unit,
    onLongPress: (ConversationId) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(TAG_CONVERSATIONS)) {
        // A heading only when there is something under it — a lone "Pinned" label over an
        // empty section is a label about nothing.
        if (state.pinnedConversations.isNotEmpty()) {
            item(key = PINNED_HEADER_KEY) { SectionHeader("Pinned") }
        }
        items(state.pinnedConversations, key = { "pinned-" + it.id.value }) { conversation ->
            ConversationRow(
                conversation = conversation,
                title = state.titleOf(conversation),
                avatarName = state.avatarNameOf(conversation),
                unread = state.unreadOf(conversation),
                pinned = true,
                onClick = { onOpenConversation(conversation.id) },
                onLongClick = { onLongPress(conversation.id) },
            )
            HorizontalDivider()
        }

        if (state.pinnedConversations.isNotEmpty() && state.otherConversations.isNotEmpty()) {
            item(key = OTHERS_HEADER_KEY) { SectionHeader("All chats") }
        }
        items(state.otherConversations, key = { it.id.value }) { conversation ->
            ConversationRow(
                conversation = conversation,
                title = state.titleOf(conversation),
                avatarName = state.avatarNameOf(conversation),
                unread = state.unreadOf(conversation),
                pinned = false,
                onClick = { onOpenConversation(conversation.id) },
                onLongClick = { onLongPress(conversation.id) },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ChatConversation,
    title: String,
    avatarName: String,
    unread: Int,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Long press pins, the way it does in the app this screen is modelled on.
            // `combinedClickable` rather than a trailing button: a control on every row
            // for something at most five rows can have is five rows of clutter.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = AppTheme.spacing.large, vertical = AppTheme.spacing.medium),
    ) {
        // The name, not the label: initials of `8102 (mcx8102)` are "8(".
        Avatar(displayName = avatarName, size = AppTheme.sizing.avatarLarge / 2)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppTheme.spacing.large),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
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

        RowStatus(conversation = conversation, unread = unread, pinned = pinned)
    }
}

/**
 * The search field, and the X that empties it.
 *
 * The X appears only once there is something to clear: a permanent one is a control that
 * does nothing most of the time, and it is also how you tell at a glance that a list is
 * filtered rather than simply short.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Search chats") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag(TAG_SEARCH_CLEAR)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear the search")
                }
            }
        },
        shape = RoundedCornerShape(AppTheme.radius.full),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AppTheme.chatColors.composer,
            unfocusedContainerColor = AppTheme.chatColors.composer,
        ),
        modifier = modifier.testTag(TAG_SEARCH),
    )
}

/** A section label. Quiet, because it is a divider with a word on it, not a heading. */
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = AppTheme.spacing.large,
            end = AppTheme.spacing.large,
            top = AppTheme.spacing.medium,
            bottom = AppTheme.spacing.small,
        ),
    )
}

@Composable
private fun NoMatches(query: String) = Centred(
    title = "No chats match \u201C$query\u201D",
    body = "Search looks at who you are talking to and the last message in each chat.",
)

/**
 * Time above, unread count below.
 *
 * The arrangement every messaging app uses, and it works because the eye reads one
 * column for "when" and one for "how many" rather than hunting along each row.
 *
 * [unread] is the state's answer rather than `conversation.unreadCount`, and the difference
 * matters: the server's count only ever goes up, because chat-node refuses `message.read`.
 * What clears a badge is this device's own read mark — see `ChatsUiState.unreadOf`.
 */
@Composable
private fun RowStatus(conversation: ChatConversation, unread: Int, pinned: Boolean) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
    ) {
        conversation.lastMessageAtMs.takeIf { it > 0 }?.let { at ->
            Text(
                text = rowTime(at),
                style = MaterialTheme.typography.labelSmall,
                color = if (unread > 0) {
                    AppTheme.chatColors.unreadBadge
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (unread > 0) {
            Surface(
                shape = CircleShape,
                color = AppTheme.chatColors.unreadBadge,
                contentColor = AppTheme.chatColors.onUnreadBadge,
                modifier = Modifier.testTag(TAG_UNREAD_BADGE),
            ) {
                Text(
                    text = unread.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(
                        horizontal = AppTheme.spacing.small,
                        vertical = AppTheme.spacing.extraSmall / 2,
                    ),
                )
            }
        }

        // Under the unread count rather than over it: the count is the thing that changed,
        // the pin is a standing fact about the row and can afford to be the quieter mark.
        if (pinned) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppTheme.sizing.chatTick).testTag(TAG_PINNED_MARK),
            )
        }
    }
}

/** `14:05` today, the date before that — the same rule the thread's day capsules use. */
private fun rowTime(atMs: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
    val now = java.util.Calendar.getInstance()
    val sameDay = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        then.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)

    val pattern = if (sameDay) "HH:mm" else "dd/MM/yy"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(atMs))
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
internal const val TAG_CHATS_OVERFLOW = "chats-overflow"
internal const val TAG_CHATS_OVERFLOW_MENU = "chats-overflow-menu"
internal const val TAG_CHATS_SIGN_OUT = "chats-sign-out"
internal const val TAG_NEW_CONVERSATION = "chats-new-conversation"
internal const val TAG_NEW_GROUP = "chats-new-group"
internal const val TAG_CONVERSATIONS = "chats-conversations"
internal const val TAG_CHATS_ERROR = "chats-error"
internal const val TAG_SEARCH = "chats-search"
internal const val TAG_SEARCH_CLEAR = "chats-search-clear"
internal const val TAG_PINNED_MARK = "chats-pinned-mark"
internal const val TAG_MY_EXTENSION = "chats-my-extension"
internal const val TAG_UNREAD_BADGE = "chats-unread-badge"

/** Stable keys, so a section heading is never confused with a conversation id. */
private const val PINNED_HEADER_KEY = "chats-header-pinned"
private const val OTHERS_HEADER_KEY = "chats-header-others"

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
