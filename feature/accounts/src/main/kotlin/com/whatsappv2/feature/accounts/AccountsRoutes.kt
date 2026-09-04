package com.whatsappv2.feature.accounts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.feature.accounts.editor.AccountEditorScreen
import com.whatsappv2.feature.accounts.editor.AccountEditorViewModel
import com.whatsappv2.feature.accounts.list.AccountsScreen

/**
 * The account list route.
 *
 * The feature exposes routes rather than screens so `:app` wires navigation without
 * needing to know which composable, ViewModel or state type sits behind each one.
 */
@Composable
fun AccountsRoute(
    onAddAccount: () -> Unit,
    onEditAccount: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
) {
    AccountsScreen(
        onAddAccount = onAddAccount,
        onEditAccount = onEditAccount,
        modifier = modifier,
    )
}

/**
 * The editor route.
 *
 * [accountId] is null when adding. `LaunchedEffect` keys on it so returning to the editor
 * for a different account reloads rather than showing the previous one's fields.
 */
@Composable
fun AccountEditorRoute(
    accountId: AccountId?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(accountId) { viewModel.load(accountId) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is com.whatsappv2.feature.accounts.editor.AccountEditorEvent.Saved) onSaved()
        }
    }

    AccountEditorScreen(
        state = state,
        onDraftChange = viewModel::update,
        onSave = viewModel::save,
        onBack = onBack,
        modifier = modifier,
    )
}
