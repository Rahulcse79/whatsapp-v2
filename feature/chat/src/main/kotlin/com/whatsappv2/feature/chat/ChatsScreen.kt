package com.whatsappv2.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
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
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The Chats tab.
 *
 * ## Signing in gates this tab, not the application
 *
 * This is a SIP client first; chat is one of its two tabs. A signed-out user gets a prompt
 * *here* and a fully working Calls tab beside it — never a full-screen login in front of
 * the app, which would make placing a call depend on a chat account (decision D4).
 *
 * It is also a separate gate from `FirstRunGate`. That one runs terms, tour and permissions
 * once and is done; this one recurs, because signing out puts the user back in front of it.
 *
 * ## The floating button is bottom-right, and that is the whole argument
 *
 * "New conversation" lives at the bottom right of the first tab in every messaging app this
 * one sits beside. A user's thumb is already there. It is shown only when signed in,
 * because there is nobody to start a conversation with otherwise.
 *
 * ## The top bar is the shell's, inherited from the placeholder this replaces
 *
 * The **gear** — Settings is reached from here and from the Calls tab and nowhere else —
 * and the **registration indicator**, because "am I reachable" is the question a phone
 * app's home screen should answer without being asked. Same signatures the placeholder
 * had, so `AppNavHost` changed by one import and one call.
 */
@Composable
fun ChatsScreen(
    isSignedIn: Boolean,
    onSignIn: () -> Unit,
    onNewConversation: () -> Unit,
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
            if (isSignedIn) {
                FloatingActionButton(
                    onClick = onNewConversation,
                    modifier = Modifier.testTag(TAG_NEW_CONVERSATION),
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "New conversation")
                }
            }
        },
    ) { padding ->
        ChatsBody(
            isSignedIn = isSignedIn,
            onSignIn = onSignIn,
            modifier = Modifier.padding(padding),
        )
    }
}

/** The tab's body, split out so the screen above it stays a layout. */
@Composable
private fun ChatsBody(isSignedIn: Boolean, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
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
            text = if (isSignedIn) "No conversations yet" else "Sign in to chat",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = AppTheme.spacing.large),
        )

        Text(
            text = if (isSignedIn) {
                "Start one with the button below. Messages themselves are still being built."
            } else {
                "Chat needs its own account. Calls work as they always have, signed in or not."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppTheme.spacing.small),
        )

        if (!isSignedIn) {
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
}

/**
 * Identifies the gear, so a test presses the control it means rather than an icon.
 *
 * The same string the placeholder used. `AppRootNavigationTest` reaches for it by that
 * value, and the tab changing hands is not a reason for a navigation test to change.
 */
internal const val TAG_CHATS_SETTINGS = "chats-settings"
internal const val TAG_SIGN_IN = "chats-sign-in"
internal const val TAG_NEW_CONVERSATION = "chats-new-conversation"

@ThemePreviews
@Composable
private fun ChatsSignedOutPreview() = PreviewSurface {
    ChatsScreen(isSignedIn = false, onSignIn = {}, onNewConversation = {}, onOpenSettings = {})
}

@ThemePreviews
@Composable
private fun ChatsSignedInPreview() = PreviewSurface {
    ChatsScreen(isSignedIn = true, onSignIn = {}, onNewConversation = {}, onOpenSettings = {})
}
