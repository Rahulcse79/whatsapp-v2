package com.whatsappv2.feature.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatFailure

/**
 * Says whether messages can actually move.
 *
 * ## It is absent when connected, and that is the point
 *
 * A permanent "connected" strip is a row of chrome telling the user what they can already
 * see. This appears only when something is wrong, so its presence carries the information
 * rather than its content.
 *
 * ## Only one of its states is the user's to fix
 *
 * [ChatConnectionState.NotConfigured] means nobody is signed in — one tap fixes it, so it
 * offers the tap. [ChatConnectionState.Disconnected] is the SDK already reconnecting with
 * its own backoff, so it reports and offers nothing: a "retry" that raced an automatic
 * retry would be a button that appears to do nothing.
 */
@Composable
fun ChatConnectionBanner(
    state: ChatConnectionState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isUsable) return

    val (text, action) = when (state) {
        ChatConnectionState.NotConfigured -> "You are not signed in to chat." to "Sign in"
        ChatConnectionState.Connecting -> "Connecting…" to null
        is ChatConnectionState.Disconnected -> "Reconnecting…" to null
        ChatConnectionState.Connected -> return
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_CONNECTION_BANNER),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = AppTheme.spacing.large,
                vertical = AppTheme.spacing.small,
            ),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            action?.let {
                TextButton(onClick = onSignIn, modifier = Modifier.testTag(TAG_BANNER_SIGN_IN)) {
                    Text(it)
                }
            }
        }
    }
}

internal const val TAG_CONNECTION_BANNER = "chat-connection-banner"
internal const val TAG_BANNER_SIGN_IN = "chat-connection-sign-in"

@ThemePreviews
@Composable
private fun ConnectionBannerPreview() = PreviewSurface {
    ChatConnectionBanner(state = ChatConnectionState.NotConfigured, onSignIn = {})
}

/**
 * The wording for a chat-server failure.
 *
 * Deliberately separate from `ChatAuthError.describe`: that one is the Coral UC platform's
 * vocabulary and this one is chat-node's. Two services, two failure models — and a shared
 * mapper would have to pretend they were one.
 */
internal fun ChatFailure.describe(): String = when (this) {
    ChatFailure.Network -> "Could not reach the chat server. Check your connection."
    ChatFailure.Unauthorized -> "The chat server refused this account. Sign in again."
    is ChatFailure.Server -> message ?: "The chat server returned an error ($httpStatus)."
    ChatFailure.NotConfigured -> "You are not signed in to chat."
    is ChatFailure.Unknown -> message ?: "Something went wrong with chat."
}
