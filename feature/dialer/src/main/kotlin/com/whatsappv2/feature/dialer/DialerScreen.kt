package com.whatsappv2.feature.dialer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.component.CallActionButton
import com.whatsappv2.core.designsystem.component.CallActionStyle
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId

/**
 * What the dialler can do, gathered into one value.
 *
 * Seven callbacks is a long parameter list by any measure, and every one of them is the
 * same kind of thing: something the user did. Grouping them keeps the screen's signature
 * readable and means a new key or shortcut adds a field here rather than another argument
 * to thread through three call sites.
 */
@Stable
data class DialerActions(
    val onInputChanged: (String) -> Unit = {},
    val onDigit: (Char) -> Unit = {},
    val onBackspace: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onAccountSelected: (AccountId) -> Unit = {},
    val onRecentSelected: (String) -> Unit = {},
    val onCall: () -> Unit = {},
)

/**
 * The dialer, wired to its ViewModel (Task 36).
 *
 * [onCallPlaced] is how the call screen is opened. The dialer does not know what that
 * screen is — it lives in another module and is hosted by an activity `:app` owns — which
 * is exactly the layering that keeps a feature independently testable.
 */
@Composable
fun DialerScreen(
    onCallPlaced: (CallId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DialerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DialerEvent.CallPlaced -> onCallPlaced(event.callId)
                is DialerEvent.NoAccount ->
                    snackbarHostState.showSnackbar("Add an account before calling")
                is DialerEvent.InvalidTarget ->
                    snackbarHostState.showSnackbar("\"${event.input}\" is not a number or address")
                is DialerEvent.Refused -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    DialerScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = DialerActions(
            onInputChanged = viewModel::onInputChanged,
            onDigit = viewModel::onDigitPressed,
            onBackspace = viewModel::onBackspace,
            onClear = viewModel::onClear,
            onAccountSelected = viewModel::onAccountSelected,
            onRecentSelected = viewModel::onRecentSelected,
            onCall = viewModel::onCall,
        ),
        modifier = modifier,
    )
}

/**
 * The stateless dialler.
 *
 * Separated from the ViewModel-bound version so it can be previewed and driven by a UI
 * test with nothing behind it — which is what Task 36's third done-when asks for.
 */
@Composable
internal fun DialerScreen(
    state: DialerUiState,
    snackbarHostState: SnackbarHostState,
    actions: DialerActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppTheme.spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.hasChoiceOfAccounts) {
                AccountPicker(state = state, onAccountSelected = actions.onAccountSelected)
            }

            OutlinedTextField(
                value = state.input,
                onValueChange = actions.onInputChanged,
                singleLine = true,
                label = { Text("Number or SIP address") },
                placeholder = { Text("1001 or sip:1001@example.com") },
                textStyle = MaterialTheme.typography.headlineSmall,
                trailingIcon = {
                    if (state.input.isNotEmpty()) {
                        IconButton(
                            onClick = actions.onBackspace,
                            modifier = Modifier.testTag(TAG_BACKSPACE),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Delete last character",
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppTheme.spacing.medium)
                    .testTag(TAG_INPUT),
            )

            if (state.recent.isNotEmpty()) {
                Recents(recent = state.recent, onRecentSelected = actions.onRecentSelected)
            }

            Spacer(Modifier.weight(1f))

            Keypad(onDigit = actions.onDigit, onClear = actions.onClear)

            CallActionButton(
                icon = Icons.Filled.Call,
                contentDescription = "Place call",
                onClick = actions.onCall,
                style = CallActionStyle.ANSWER,
                enabled = state.canPlaceCall,
                modifier = Modifier
                    .padding(top = AppTheme.spacing.large, bottom = AppTheme.spacing.large)
                    .testTag(TAG_CALL),
            )
        }
    }
}

/**
 * The per-call account override.
 *
 * Shown only with more than one account, because a picker with one entry is a control that
 * cannot be used. The selected account's identity is shown beneath it: a bare extension is
 * completed against that domain, so which account is chosen decides where `1001` goes.
 */
@Composable
private fun AccountPicker(
    state: DialerUiState,
    onAccountSelected: (AccountId) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.selectedAccount

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(TAG_ACCOUNT),
        ) {
            Text(selected?.label ?: "Choose an account")
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = "Choose the account to call from",
            )
        }
        selected?.let {
            Text(
                text = it.identity,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.accounts.forEach { account ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(account.label)
                            Text(
                                // The status is here because it changes what will happen:
                                // an unregistered account cannot place a call, and the
                                // refusal is easier to understand before it arrives.
                                text = if (account.isRegistered) account.identity else "${account.identity} · offline",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onAccountSelected(account.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag(accountTag(account.id)),
                )
            }
        }
    }
}

@Composable
private fun Recents(recent: List<String>, onRecentSelected: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppTheme.spacing.small)
            .testTag(TAG_RECENTS),
    ) {
        items(recent) { target ->
            AssistChip(
                onClick = { onRecentSelected(target) },
                label = { Text(target) },
                modifier = Modifier.testTag(recentTag(target)),
            )
        }
    }
}

/**
 * The keypad.
 *
 * A grid of characters rather than twelve composables: the layout is the same for every
 * key, and writing it twelve times is twelve chances for one of them to drift.
 */
@Composable
private fun Keypad(onDigit: (Char) -> Unit, onClear: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KEYPAD_ROWS.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.extraLarge),
            ) {
                row.forEach { key ->
                    TextButton(
                        onClick = { onDigit(key) },
                        modifier = Modifier
                            .size(AppTheme.sizing.callActionButton)
                            .testTag(keyTag(key)),
                    ) {
                        Text(
                            text = key.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        TextButton(onClick = onClear, modifier = Modifier.testTag(TAG_CLEAR)) {
            Text("Clear")
        }
    }
}

private val KEYPAD_ROWS = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9'),
    listOf('*', '0', '#'),
)

internal const val TAG_INPUT = "dialer-input"
internal const val TAG_CALL = "dialer-call"
internal const val TAG_BACKSPACE = "dialer-backspace"
internal const val TAG_CLEAR = "dialer-clear"
internal const val TAG_ACCOUNT = "dialer-account"
internal const val TAG_RECENTS = "dialer-recents"

internal fun keyTag(key: Char): String = "dialer-key-$key"
internal fun recentTag(target: String): String = "dialer-recent-$target"
internal fun accountTag(id: AccountId): String = "dialer-account-${id.value}"

@ThemePreviews
@Composable
private fun DialerPreview() = PreviewSurface {
    DialerScreen(
        state = DialerUiState(
            input = "1001",
            accounts = listOf(PREVIEW_WORK, PREVIEW_HOME),
            selectedAccount = PREVIEW_WORK,
            recent = listOf("1002", "sip:carol@example.com"),
        ),
        snackbarHostState = remember { SnackbarHostState() },
        actions = DialerActions(),
    )
}

private val PREVIEW_WORK = DialerAccount(
    id = AccountId("work"),
    label = "Work",
    identity = "alice@sip.example.com",
    isRegistered = true,
)

private val PREVIEW_HOME = DialerAccount(
    id = AccountId("home"),
    label = "Home",
    identity = "alice@home.example.com",
    isRegistered = false,
)
