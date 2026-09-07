package com.whatsappv2.feature.accounts

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.usecase.RegistrationAttempt
import com.whatsappv2.feature.accounts.detail.AccountDetailScreen
import com.whatsappv2.feature.accounts.detail.AccountDetailUiState
import com.whatsappv2.feature.accounts.detail.AccountDetailViewModel
import com.whatsappv2.feature.accounts.detail.detailLabel
import com.whatsappv2.feature.accounts.editor.AccountEditorEvent
import com.whatsappv2.feature.accounts.editor.AccountEditorScreen
import com.whatsappv2.feature.accounts.editor.AccountEditorViewModel
import com.whatsappv2.feature.accounts.list.AccountsEvent
import com.whatsappv2.feature.accounts.list.AccountsScreen
import com.whatsappv2.feature.accounts.list.AccountsViewModel
import kotlinx.coroutines.delay

/**
 * The account list route.
 *
 * The feature exposes routes rather than screens so `:app` wires navigation without
 * needing to know which composable, ViewModel or state type sits behind each one.
 *
 * ## Where a save is confirmed (Task 73)
 *
 * Here, not on the editor. The editor pops the moment its save succeeds, so a message
 * shown there would be drawn onto a screen that is already leaving — which is exactly why
 * saving an account used to look like it did nothing at all. [savedMessage] is handed in
 * by `:app` from the editor's result, and [onSavedMessageShown] clears it so a rotation
 * does not announce the same save twice.
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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    savedMessage: AccountSavedMessage? = null,
    onSavedMessageShown: () -> Unit = {},
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(savedMessage) {
        val message = savedMessage ?: return@LaunchedEffect
        onSavedMessageShown()
        snackbarHostState.showSnackbar(
            message = message.text,
            // A failed re-registration has to be read, not glimpsed: the account saved
            // fine and the user is now not receiving calls, which they can only act on if
            // the sentence is still there when they look up.
            withDismissAction = message.isWarning,
            duration = if (message.isWarning) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        )
    }

    // Deleting, logging in and logging out all had outcomes nothing collected, so every
    // one of them was silent (Task 73's sibling, and Task 77's sweep found no other).
    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it.describe()) }
    }

    AccountsScreen(
        onAddAccount = onAddAccount,
        onEditAccount = onOpenAccount,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        viewModel = viewModel,
    )
}

/** One sentence per outcome, in the list's own vocabulary rather than the engine's. */
private fun AccountsEvent.describe(): String = when (this) {
    is AccountsEvent.Deleted -> "$label deleted"
    is AccountsEvent.LoggedIn -> "$label registered"
    is AccountsEvent.LoggedOut -> "$label logged out"
    is AccountsEvent.LoginFailed -> "$label could not register: ${reason.detailLabel()}"
    is AccountsEvent.AccountGone -> "$label is no longer on this device"
    is AccountsEvent.DeleteFailed -> "Could not delete the account: $detail"
    is AccountsEvent.DeleteRefusedCallInProgress ->
        "Finish the call first — $activeCalls in progress on this account"
    is AccountsEvent.LogoutRefusedCallInProgress ->
        "Finish the call first — $activeCalls in progress on this account"
}

/**
 * The editor route.
 *
 * [accountId] is null when adding. `LaunchedEffect` keys on it so returning to the editor
 * for a different account reloads rather than showing the previous one's fields.
 *
 * [onSaved] carries the sentence rather than a bare signal (Task 73): the wording belongs
 * to this feature, which owns the vocabulary, and `:app` only has to carry it to the
 * screen that outlives the editor.
 */
@Composable
fun AccountEditorRoute(
    accountId: AccountId?,
    onSaved: (AccountSavedMessage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(accountId) { viewModel.load(accountId) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AccountEditorEvent.Saved -> onSaved(event.toMessage())
                // These two keep the user on the form with their input intact: there is
                // something to correct, and leaving would lose it.
                is AccountEditorEvent.SaveFailed ->
                    snackbarHostState.showSnackbar("Could not save the account: ${event.detail}")
                AccountEditorEvent.CredentialsMustBeReEntered ->
                    snackbarHostState.showSnackbar("Enter the password again to save this account")
            }
        }
    }

    AccountEditorScreen(
        state = state,
        onDraftChange = viewModel::update,
        onSave = viewModel::save,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

/**
 * What the save actually did, in a sentence (Task 73).
 *
 * "Saved" alone is not the whole story. An edit to a registered account releases the old
 * binding and takes out a new one, and if that second half failed the account is stored
 * but the user is unreachable — §5.1 is explicit that hiding this is a bug. The three
 * cases are therefore three different sentences, and only one of them is a warning.
 */
internal fun AccountEditorEvent.Saved.toMessage(): AccountSavedMessage =
    when (val attempt = registration) {
        RegistrationAttempt.Succeeded -> AccountSavedMessage(
            text = if (unregisteredFirst) "$label saved and re-registered" else "$label saved and registered",
            isWarning = false,
        )
        // An edit to an account that was already logged out. Saving must not log someone
        // back in behind their back, so "saved" is the whole truth here.
        RegistrationAttempt.NotAttempted -> AccountSavedMessage("$label saved", isWarning = false)
        is RegistrationAttempt.Rejected -> AccountSavedMessage(
            text = "$label saved, but registration failed: ${attempt.cause.userMessage()}",
            isWarning = true,
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
