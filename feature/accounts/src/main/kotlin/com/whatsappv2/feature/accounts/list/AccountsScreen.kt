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
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
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
        onLogIn = viewModel::logIn,
        onLogOut = viewModel::logOut,
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
    onLogIn: (AccountId, String) -> Unit,
    onLogOut: (AccountId, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The two destructive actions are confirmed, so the row that is waiting on an answer
    // has to outlive the click. Held here, above the list, so scrolling the pending row
    // off screen cannot dismiss its dialog.
    var pendingDeletion by remember { mutableStateOf<AccountRow?>(null) }
    var pendingLogout by remember { mutableStateOf<AccountRow?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("SIP accounts") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAccount) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { innerPadding ->
        AccountsBody(
            state = state,
            onAddAccount = onAddAccount,
            onEditAccount = onEditAccount,
            onSetDefault = onSetDefault,
            onDeleteRequest = { pendingDeletion = it },
            onLogIn = onLogIn,
            onLogOutRequest = { pendingLogout = it },
            modifier = Modifier.padding(innerPadding),
        )
    }

    pendingLogout?.let { account ->
        LogOutConfirmation(
            account = account,
            onConfirm = {
                onLogOut(account.id, account.label)
                pendingLogout = null
            },
            onDismiss = { pendingLogout = null },
        )
    }

    pendingDeletion?.let { account ->
        DeleteConfirmation(
            account = account,
            onConfirm = {
                onDelete(account.id, account.label)
                pendingDeletion = null
            },
            onDismiss = { pendingDeletion = null },
        )
    }
}

/** The part of the screen the state actually selects: loading, empty, or the list. */
@Composable
private fun AccountsBody(
    state: AccountsUiState,
    onAddAccount: () -> Unit,
    onEditAccount: (AccountId) -> Unit,
    onSetDefault: (AccountId) -> Unit,
    onDeleteRequest: (AccountRow) -> Unit,
    onLogIn: (AccountId, String) -> Unit,
    onLogOutRequest: (AccountRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AccountsUiState.Loading -> LoadingState(modifier = modifier, label = "Loading accounts")

        AccountsUiState.Empty -> EmptyState(
            title = "No SIP accounts",
            description = "Add an account to register and start making calls.",
            actionLabel = "Add account",
            onAction = onAddAccount,
            modifier = modifier,
        )

        is AccountsUiState.Content -> LazyColumn(modifier = modifier.fillMaxSize()) {
            items(state.accounts, key = { it.id.value }) { account ->
                AccountListItem(
                    account = account,
                    onClick = { onEditAccount(account.id) },
                    onSetDefault = { onSetDefault(account.id) },
                    onDelete = { onDeleteRequest(account) },
                    onToggleSession = {
                        if (account.isLoggedIn) {
                            onLogOutRequest(account)
                        } else {
                            // Logging in needs no confirmation: it takes nothing away
                            // and the button already says what it does.
                            onLogIn(account.id, account.label)
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LogOutConfirmation(
    account: AccountRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = "Log out of ${account.label}?",
        // Says what survives as well as what stops, because this is the action people
        // confuse with delete: the account stays, so logging back in needs no password.
        message = "Calls to ${account.identity} will stop arriving. " +
            "The account stays on this device and you can log back in without " +
            "entering your password again.",
        confirmLabel = "Log out",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun DeleteConfirmation(
    account: AccountRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = "Delete ${account.label}?",
        // Names the consequence rather than asking a vague "are you sure": the SIP
        // password is removed and cannot be recovered from the device.
        message = "The password for ${account.identity} will be removed from this device.",
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun AccountListItem(
    account: AccountRow,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onToggleSession: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(AppTheme.spacing.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(account.status)

        AccountSummary(
            account = account,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppTheme.spacing.large),
        )

        DefaultAccountButton(account = account, onSetDefault = onSetDefault)
        SessionButton(account = account, onToggleSession = onToggleSession)

        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete ${account.label}")
        }
    }
}

@Composable
private fun AccountSummary(account: AccountRow, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
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
}

@Composable
private fun DefaultAccountButton(account: AccountRow, onSetDefault: () -> Unit) {
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
}

@Composable
private fun SessionButton(account: AccountRow, onToggleSession: () -> Unit) {
    IconButton(onClick = onToggleSession) {
        Icon(
            imageVector = if (account.isLoggedIn) {
                Icons.AutoMirrored.Filled.Logout
            } else {
                Icons.AutoMirrored.Filled.Login
            },
            // Names the account, not just the action: a screen reader moving down a
            // list of identical "Log out" buttons cannot say which one it is on.
            contentDescription = if (account.isLoggedIn) {
                "Log out of ${account.label}"
            } else {
                "Log in to ${account.label}"
            },
        )
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
        onLogIn = { _, _ -> },
        onLogOut = { _, _ -> },
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
        onLogIn = { _, _ -> },
        onLogOut = { _, _ -> },
    )
}
