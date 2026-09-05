package com.whatsappv2.feature.accounts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.feature.accounts.detail.AccountDetailScreen
import com.whatsappv2.feature.accounts.detail.AccountDetailUiState
import com.whatsappv2.feature.accounts.detail.AccountDetailViewModel
import com.whatsappv2.feature.accounts.editor.AccountEditorScreen
import com.whatsappv2.feature.accounts.editor.AccountEditorViewModel
import com.whatsappv2.feature.accounts.list.AccountsScreen
import kotlinx.coroutines.delay

/**
 * The account list route.
 *
 * The feature exposes routes rather than screens so `:app` wires navigation without
 * needing to know which composable, ViewModel or state type sits behind each one.
 */
@Composable
fun AccountsRoute(
    onAddAccount: () -> Unit,
    /**
     * Tapping a row opens that account's detail, not its editor (Task 31).
     *
     * The status is what someone came to the list to check, so it is one tap away and the
     * form is two. `AccountsScreen` still calls its own callback `onEditAccount` because
     * that is what the row press meant before the detail screen existed; what it opens is
     * this route's decision, not the screen's.
     */
    onOpenAccount: (AccountId) -> Unit,
    modifier: Modifier = Modifier,
) {
    AccountsScreen(
        onAddAccount = onAddAccount,
        onEditAccount = onOpenAccount,
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

/**
 * The registration-detail route (Task 31).
 *
 * ## The one thing here that ticks
 *
 * Status is pushed: it comes from `registrationState` and the retry schedule, and nothing
 * polls for it. The countdown is different — "in 42s" has to become "in 41s" while nothing
 * at all has changed — so a one-second ticker drives *only* the clock reading, and only
 * while an attempt is actually pending. With nothing scheduled the ticker does not run,
 * which is the difference between a countdown and a wakeup every second for ever.
 */
@Composable
fun AccountDetailRoute(
    accountId: AccountId,
    onEditAccount: (AccountId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(accountId) { viewModel.load(accountId) }

    val retryPending = (state as? AccountDetailUiState.Content)?.nextRetryAtEpochMillis != null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryPending) {
        while (retryPending) {
            now = System.currentTimeMillis()
            delay(COUNTDOWN_TICK_MILLIS)
        }
    }

    AccountDetailScreen(
        state = state,
        nowEpochMillis = now,
        onRegisterNow = viewModel::registerNow,
        onEdit = onEditAccount,
        onBack = onBack,
        modifier = modifier,
    )
}

/** One second: the resolution the countdown is displayed at, so a finer tick shows nothing. */
private const val COUNTDOWN_TICK_MILLIS = 1_000L
