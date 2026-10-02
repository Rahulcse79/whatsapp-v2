package com.whatsappv2.feature.chat

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.feature.chat.contacts.ChatContactsScreen
import com.whatsappv2.feature.chat.contacts.ChatContactsViewModel
import com.whatsappv2.feature.chat.signin.ChatSignInEvent
import com.whatsappv2.feature.chat.signin.ChatSignInScreen
import com.whatsappv2.feature.chat.signin.ChatSignInViewModel
import com.whatsappv2.feature.chat.thread.ChatThreadEvent
import com.whatsappv2.feature.chat.thread.ChatThreadScreen
import com.whatsappv2.feature.chat.thread.ChatThreadViewModel

/**
 * The Chats tab.
 *
 * The feature exposes routes rather than screens, so `:app` wires navigation without
 * needing to know which composable, ViewModel or state type sits behind each one — the
 * same shape `AccountsRoute` uses, and the reason `AppNavHost` changes by one line when a
 * screen is replaced.
 *
 * [onOpenSettings] and [registrationIndicator] keep the signatures `ChatsPlaceholderScreen`
 * had. The gear and the registration indicator travel with the **route**, not with whatever
 * is drawn behind it, which is what its KDoc promised whoever replaced it.
 */
@Composable
fun ChatsRoute(
    onSignIn: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    registrationIndicator: (@Composable () -> Unit)? = null,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                // Said here rather than prevented in the UI: a sixth pin is a reasonable
                // thing to try, and the limit is only interesting at the moment you hit it.
                is ChatsEvent.PinLimitReached ->
                    snackbars.showSnackbar("You can pin up to ${event.limit} chats")
            }
        }
    }

    ChatsScreen(
        state = state,
        onSignIn = onSignIn,
        onNewConversation = onNewConversation,
        // The id is handed up as a String rather than a ConversationId: `:app` puts it in
        // a navigation argument, and a value class would only be unwrapped there anyway.
        onOpenConversation = { onOpenConversation(it.value) },
        onRetry = viewModel::refresh,
        onQueryChange = viewModel::setQuery,
        onTogglePin = viewModel::togglePin,
        onMarkRead = viewModel::markRead,
        onMarkUnread = viewModel::markUnread,
        snackbarHostState = snackbars,
        onOpenSettings = onOpenSettings,
        registrationIndicator = registrationIndicator,
        modifier = modifier,
    )
}

/**
 * One conversation.
 *
 * The conversation id reaches the ViewModel through `SavedStateHandle`, so this route
 * takes no id parameter — `:app` puts it in the navigation argument named
 * [ChatThreadViewModel.CONVERSATION_ID] and Hilt does the rest.
 */
@Composable
fun ChatThreadRoute(
    onCallPlaced: (CallId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                // The thread stays where it is. A call is an activity of its own, and
                // coming off it should return to the conversation, not to the list.
                is ChatThreadEvent.CallPlaced -> onCallPlaced(event.callId)
                // Shown here rather than pushed anywhere: the user is in a conversation,
                // and the useful thing is that the call failed and the chat still works.
                is ChatThreadEvent.CallFailed -> snackbars.showSnackbar(event.reason)
            }
        }
    }

    // The host is made here and shown by the screen's own Scaffold. Wrapping the screen
    // in a second Scaffold to hold it is what every other route avoids: the inner one
    // then pads against system bars the outer one already padded against, which lifts the
    // header off the status bar and the composer off the navigation bar.
    ChatThreadScreen(
        state = state,
        onDraftChange = viewModel::setDraft,
        onSend = viewModel::send,
        onRetry = viewModel::retry,
        onAudioCall = { viewModel.call(MediaProfile.AUDIO) },
        onVideoCall = { viewModel.call(MediaProfile.AUDIO_VIDEO) },
        onBack = onBack,
        modifier = modifier,
        snackbarHostState = snackbars,
    )
}

/**
 * The sign-in form.
 *
 * [onSignedIn] fires once, from the ViewModel's event channel rather than from its state.
 * A "signed in" flag in state would re-fire on every recomposition after a rotation and
 * navigate twice.
 *
 * Both callbacks default to doing nothing, because the app's gate needs neither: it draws
 * this instead of the app while there is no session, so the session appearing is what
 * dismisses it, and there is nothing behind it to go back to. A caller that navigates to
 * this as a screen passes both.
 */
@Composable
fun ChatSignInRoute(
    modifier: Modifier = Modifier,
    onSignedIn: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: ChatSignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ChatSignInEvent.SignedIn -> onSignedIn()
            }
        }
    }

    ChatSignInScreen(
        state = state,
        onServerUrlChange = viewModel::setServerUrl,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onTogglePasswordVisible = viewModel::togglePasswordVisible,
        onSubmit = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The company directory.
 *
 * Choosing somebody **opens the conversation and then navigates** — it does not navigate
 * to a screen that opens it. The thread's id is the chat server's, not the directory's, so
 * it has to be obtained before there is anywhere to go; a route that pushed first would
 * land on a screen with nothing to load.
 *
 * Which directory field becomes the other party's id is decided in one place
 * ([ChatContactsViewModel.conversationIdOf]). `userId`, `contactIdentifier`, `deviceKey`
 * and an extension are four different things here, and the wrong one fails at
 * conversation-creation time — a screen away from its cause.
 */
@Composable
fun ChatContactsRoute(
    onConversationOpened: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.opened.collect { conversationId -> onConversationOpened(conversationId.value) }
    }

    ChatContactsScreen(
        state = state,
        onQueryChange = viewModel::setQuery,
        onContactSelected = viewModel::openConversationWith,
        onRetry = viewModel::refresh,
        onBack = onBack,
        modifier = modifier,
    )
}
