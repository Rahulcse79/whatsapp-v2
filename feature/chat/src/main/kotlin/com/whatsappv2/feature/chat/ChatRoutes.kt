package com.whatsappv2.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.feature.chat.contacts.ChatContactsScreen
import com.whatsappv2.feature.chat.contacts.ChatContactsViewModel
import com.whatsappv2.feature.chat.signin.ChatSignInEvent
import com.whatsappv2.feature.chat.signin.ChatSignInScreen
import com.whatsappv2.feature.chat.signin.ChatSignInViewModel

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
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    registrationIndicator: (@Composable () -> Unit)? = null,
    viewModel: ChatsViewModel = hiltViewModel(),
) {
    val isSignedIn by viewModel.isSignedIn.collectAsStateWithLifecycle()

    ChatsScreen(
        isSignedIn = isSignedIn,
        onSignIn = onSignIn,
        onNewConversation = onNewConversation,
        onOpenSettings = onOpenSettings,
        registrationIndicator = registrationIndicator,
        modifier = modifier,
    )
}

/**
 * The sign-in form.
 *
 * [onSignedIn] fires once, from the ViewModel's event channel rather than from its state.
 * A "signed in" flag in state would re-fire on every recomposition after a rotation and
 * navigate twice.
 */
@Composable
fun ChatSignInRoute(
    onSignedIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
 * [onConversationOpened] takes the id the conversation API expects, decided in one place
 * ([ChatContactsViewModel.conversationIdOf]) rather than by each caller picking a field off
 * a [ChatContact]. In this deployment's vocabulary `userId`, `contactIdentifier`,
 * `deviceKey` and an extension are four different things, and choosing wrong fails when a
 * conversation is opened rather than when contacts are listed — a screen away from its cause.
 */
@Composable
fun ChatContactsRoute(
    onConversationOpened: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatContactsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ChatContactsScreen(
        state = state,
        onQueryChange = viewModel::setQuery,
        onContactSelected = { onConversationOpened(viewModel.conversationIdOf(it)) },
        onRetry = viewModel::refresh,
        onBack = onBack,
        modifier = modifier,
    )
}
