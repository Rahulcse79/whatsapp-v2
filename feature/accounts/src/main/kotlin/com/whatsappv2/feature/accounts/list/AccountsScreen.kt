package com.whatsappv2.feature.accounts.list

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.component.ConfirmDialog
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.component.LoadingState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.AccountId

/** The account list, wired to its ViewModel. */
@Composable
fun AccountsScreen(
    onAddAccount: () -> Unit,
    onEditAccount: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AccountsScreen(
        state = state,
        onAddAccount = onAddAccount,
        onEditAccount = onEditAccount,
        onSetDefault = viewModel::setDefault,
        onDelete = viewModel::deleteAccount,
        modifier = modifier,
    )
}

/**
 * The stateless list.
 *
 * Separated from the ViewModel-bound version so it can be previewed and tested with a
 * literal state, with no DI and no repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onAddAccount: () -> Unit,
    onEditAccount: (AccountId) -> Unit,
    onSetDefault: (AccountId) -> Unit,
    onDelete: (AccountId, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDeletion by remember { mutableStateOf<AccountRow?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("SIP accounts") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAccount) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { innerPadding ->
        when (state) {
            AccountsUiState.Loading -> LoadingState(
                modifier = Modifier.padding(innerPadding),
                label = "Loading accounts",
            )

            AccountsUiState.Empty -> EmptyState(
                title = "No SIP accounts",
                description = "Add an account to register and start making calls.",
                actionLabel = "Add account",
                onAction = onAddAccount,
                modifier = Modifier.padding(innerPadding),
            )

            is AccountsUiState.Content -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(state.accounts, key = { it.id.value }) { account ->
                    AccountListItem(
                        account = account,
                        onClick = { onEditAccount(account.id) },
                        onSetDefault = { onSetDefault(account.id) },
                        onDelete = { pendingDeletion = account },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDeletion?.let { account ->
        ConfirmDialog(
            title = "Delete ${account.label}?",
            // Names the consequence rather than asking a vague "are you sure": the SIP
            // password is removed and cannot be recovered from the device.
            message = "The password for ${account.identity} will be removed from this device.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                onDelete(account.id, account.label)
                pendingDeletion = null
            },
            onDismiss = { pendingDeletion = null },
        )
    }
}

@Composable
private fun AccountListItem(
    account: AccountRow,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(AppTheme.spacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(account.status)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraSmall),
        ) {
            Text(text = account.label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = account.identity,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = account.status.label(),
                style = MaterialTheme.typography.labelMedium,
                color = if (account.status.needsAttention) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        IconButton(onClick = onSetDefault, enabled = !account.isDefault) {
            Icon(
                imageVector = if (account.isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (account.isDefault) {
                    "Default account"
                } else {
                    "Make ${account.label} the default account"
                },
                tint = if (account.isDefault) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${account.label}")
        }
    }
}

@Composable
private fun StatusIcon(status: AccountStatus) {
    // The status text sits beside this, so the icon is decorative - announcing it too
    // would read the same thing twice.
    Icon(
        imageVector = when (status) {
            AccountStatus.REGISTERED -> Icons.Filled.CheckCircle
            AccountStatus.FAILED_NEEDS_ATTENTION -> Icons.Filled.ErrorOutline
            else -> Icons.Filled.ErrorOutline
        },
        contentDescription = null,
        tint = when (status) {
            AccountStatus.REGISTERED -> MaterialTheme.colorScheme.primary
            AccountStatus.FAILED_NEEDS_ATTENTION -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .size(AppTheme.sizing.avatarSmall)
            .semantics { contentDescription = status.label() },
    )
}

/** Wording lives here so the list and the detail view cannot describe a state differently. */
internal fun AccountStatus.label(): String = when (this) {
    AccountStatus.REGISTERED -> "Registered"
    AccountStatus.REGISTERING -> "Registering..."
    AccountStatus.FAILED_NEEDS_ATTENTION -> "Check your details"
    AccountStatus.FAILED_RETRYING -> "Reconnecting..."
    AccountStatus.OFFLINE -> "Offline"
}

@ThemePreviews
@Composable
private fun AccountsScreenContentPreview() = PreviewSurface {
    AccountsScreen(
        state = AccountsUiState.Content(
            listOf(
                AccountRow(AccountId("1"), "Work", "alice@sip.example.com", true, AccountStatus.REGISTERED),
                AccountRow(AccountId("2"), "Home", "bob@home.example.com", false, AccountStatus.REGISTERING),
                AccountRow(
                    AccountId("3"),
                    "Old",
                    "carol@old.example.com",
                    false,
                    AccountStatus.FAILED_NEEDS_ATTENTION,
                ),
            ),
        ),
        onAddAccount = {},
        onEditAccount = {},
        onSetDefault = {},
        onDelete = { _, _ -> },
    )
}

@ThemePreviews
@Composable
private fun AccountsScreenEmptyPreview() = PreviewSurface {
    AccountsScreen(
        state = AccountsUiState.Empty,
        onAddAccount = {},
        onEditAccount = {},
        onSetDefault = {},
        onDelete = { _, _ -> },
    )
}
