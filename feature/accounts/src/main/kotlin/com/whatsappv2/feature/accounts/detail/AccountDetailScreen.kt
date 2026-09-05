package com.whatsappv2.feature.accounts.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.component.LoadingState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.feature.accounts.list.AccountStatus
import com.whatsappv2.feature.accounts.list.label

/**
 * One account's registration, in detail (Task 31).
 *
 * Stateless, like the other screens here: it takes a state and emits events, so a test can
 * render any registration outcome without a repository, an engine or a network.
 *
 * @param nowEpochMillis the current time, passed in rather than read. The countdown is the
 *   only thing on this screen that depends on *when* it is drawn, and a screen that calls
 *   `System.currentTimeMillis()` cannot be asserted — the test would have to sleep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    state: AccountDetailUiState,
    nowEpochMillis: Long,
    onRegisterNow: () -> Unit,
    onEdit: (AccountId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Account status") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        when (state) {
            is AccountDetailUiState.Loading ->
                LoadingState(
                    modifier = Modifier.padding(innerPadding),
                    label = "Loading account",
                )

            is AccountDetailUiState.Gone ->
                EmptyState(
                    title = "This account is gone",
                    modifier = Modifier.padding(innerPadding),
                    description = "It was deleted from another screen.",
                )

            is AccountDetailUiState.Content ->
                AccountDetailBody(
                    state = state,
                    nowEpochMillis = nowEpochMillis,
                    onRegisterNow = onRegisterNow,
                    onEdit = onEdit,
                    modifier = Modifier.padding(innerPadding),
                )
        }
    }
}

@Composable
private fun AccountDetailBody(
    state: AccountDetailUiState.Content,
    nowEpochMillis: Long,
    onRegisterNow: () -> Unit,
    onEdit: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppTheme.spacing.large),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
    ) {
        Text(text = state.label, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = state.identity,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        RegistrationSection(state = state, nowEpochMillis = nowEpochMillis)

        HorizontalDivider()

        Field(name = "Transport", value = state.transport)
        Field(name = "Default account", value = if (state.isDefault) "Yes" else "No")

        Button(
            onClick = onRegisterNow,
            enabled = state.canRegisterNow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Register now")
        }

        Button(
            onClick = { onEdit(state.id) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Edit account")
        }
    }
}

/**
 * The part the screen exists for.
 *
 * The status line carries a content description naming the state, so a test — and a
 * screen reader — can tell "Registered" from "Offline" without matching on the visible
 * wording, which is free to change.
 */
@Composable
private fun RegistrationSection(
    state: AccountDetailUiState.Content,
    nowEpochMillis: Long,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small)) {
        Text(
            text = state.status.label(),
            style = MaterialTheme.typography.titleMedium,
            color = if (state.status.needsAttention) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.semantics { contentDescription = "Status: ${state.status.name}" },
        )

        state.failure?.let { failure ->
            Text(
                text = failure.detailLabel(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (failure.requiresUserAction) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = failure.remedy(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.nextRetryAtEpochMillis?.let { due ->
            Text(
                text = retryCountdown(due, nowEpochMillis),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.grantedExpirySeconds?.let { expiry ->
            Text(
                text = "Registration granted for ${expiry}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Next attempt in 42s", or that it is due now.
 *
 * Seconds rather than a clock time: the delays are tens of seconds to minutes, and a
 * wall-clock time would make the reader do the subtraction. A due time already in the past
 * reads as "any moment" rather than a negative number — the attempt is running, and the
 * schedule clears when it does.
 */
internal fun retryCountdown(dueEpochMillis: Long, nowEpochMillis: Long): String {
    val remainingSeconds = (dueEpochMillis - nowEpochMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND
    return if (remainingSeconds <= 0) {
        "Next attempt: any moment now"
    } else {
        "Next attempt in ${remainingSeconds}s"
    }
}

private const val MILLIS_PER_SECOND = 1_000L

@Composable
private fun Field(name: String, value: String) {
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@ThemePreviews
@Composable
private fun AccountDetailRegisteredPreview() = PreviewSurface {
    AccountDetailScreen(
        state = AccountDetailUiState.Content(
            id = AccountId("1"),
            label = "Work",
            identity = "alice@sip.example.com",
            transport = "TLS",
            isDefault = true,
            status = AccountStatus.REGISTERED,
            failure = null,
            nextRetryAtEpochMillis = null,
            grantedExpirySeconds = 3_600,
        ),
        nowEpochMillis = 0,
        onRegisterNow = {},
        onEdit = {},
        onBack = {},
    )
}

@ThemePreviews
@Composable
private fun AccountDetailFailedPreview() = PreviewSurface {
    AccountDetailScreen(
        state = AccountDetailUiState.Content(
            id = AccountId("1"),
            label = "Work",
            identity = "alice@sip.example.com",
            transport = "TCP",
            isDefault = false,
            status = AccountStatus.FAILED_NEEDS_ATTENTION,
            failure = RegistrationFailure.AUTHENTICATION_FAILED,
            nextRetryAtEpochMillis = null,
            grantedExpirySeconds = null,
        ),
        nowEpochMillis = 0,
        onRegisterNow = {},
        onEdit = {},
        onBack = {},
    )
}
