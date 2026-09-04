package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * Shown while content is loading.
 *
 * Carries a content description so a screen reader announces "loading" rather than
 * silence, which is otherwise indistinguishable from a broken screen.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String = "Loading",
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Shown when there is genuinely nothing to display.
 *
 * Distinct from [ErrorState] on purpose: "you have no call history yet" and "we could
 * not load your call history" look similar and mean opposite things, and conflating
 * them teaches people to ignore both.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector = Icons.Filled.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    MessageState(
        icon = icon,
        title = title,
        description = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
    )
}

/**
 * Shown when something failed.
 *
 * [onRetry] is optional because not everything is retryable — offering "Try again" for
 * a wrong password is worse than offering nothing, since it invites the user to repeat
 * an action that cannot succeed.
 */
@Composable
fun ErrorState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector = Icons.Filled.ErrorOutline,
    retryLabel: String = "Try again",
    onRetry: (() -> Unit)? = null,
) {
    MessageState(
        icon = icon,
        title = title,
        description = description,
        tint = MaterialTheme.colorScheme.error,
        actionLabel = retryLabel.takeIf { onRetry != null },
        onAction = onRetry,
        modifier = modifier,
    )
}

/** Shown when the device has no usable network. Honest state, never a silent blank (§6). */
@Composable
fun OfflineState(
    modifier: Modifier = Modifier,
    title: String = "No network",
    description: String? = "Calls and registration will resume when the connection returns.",
    onRetry: (() -> Unit)? = null,
) {
    MessageState(
        icon = Icons.Filled.CloudOff,
        title = title,
        description = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        actionLabel = "Retry".takeIf { onRetry != null },
        onAction = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun MessageState(
    icon: ImageVector,
    title: String,
    description: String?,
    tint: androidx.compose.ui.graphics.Color,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(AppTheme.spacing.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            // Decorative: the title below already says what this is, and announcing the
            // icon as well would read the same thing twice.
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(AppTheme.sizing.avatarSmall),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppTheme.spacing.large),
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppTheme.spacing.small),
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.padding(top = AppTheme.spacing.extraLarge),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@ThemePreviews
@Composable
private fun LoadingStatePreview() = PreviewSurface { LoadingState() }

@ThemePreviews
@Composable
private fun EmptyStatePreview() = PreviewSurface {
    EmptyState(
        title = "No calls yet",
        description = "Calls you make and receive will appear here.",
    )
}

@ThemePreviews
@Composable
private fun ErrorStatePreview() = PreviewSurface {
    ErrorState(
        title = "Could not load call history",
        description = "Something went wrong reading your calls.",
        onRetry = {},
    )
}

@ThemePreviews
@Composable
private fun OfflineStatePreview() = PreviewSurface { OfflineState(onRetry = {}) }
