package com.whatsappv2.feature.accounts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.engine.toRegistrationFailure
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.usecase.DeleteAccountError
import com.whatsappv2.domain.usecase.DeleteAccountUseCase
import com.whatsappv2.domain.usecase.LoginError
import com.whatsappv2.domain.usecase.LoginUseCase
import com.whatsappv2.domain.usecase.LogoutError
import com.whatsappv2.domain.usecase.LogoutUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The account list.
 *
 * State is a single immutable [AccountsUiState] on a [StateFlow]; one-shot outcomes go to
 * a [Channel]. The split matters: a delete refusal must be shown once, and a refusal held
 * in state would be re-shown on every recomposition and after every rotation.
 *
 * Registration status is combined in from the engine rather than read from storage, which
 * is what keeps §6 honest — the database has no idea whether the transport is up, so a
 * status column would go stale the moment the network changed.
 *
 * ## Log out is not delete
 *
 * Both actions live on the same row and they are deliberately different: logging out
 * releases the registration and the decrypted credentials while leaving the account
 * configured, and deleting removes the account and its stored password for good. Keeping
 * them separate is what lets someone stop taking calls on a work account over the weekend
 * without retyping a password on Monday.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: SipAccountRepository,
    private val deleteAccount: DeleteAccountUseCase,
    private val login: LoginUseCase,
    private val logout: LogoutUseCase,
    registrar: SipRegistrar,
) : ViewModel() {

    val uiState: StateFlow<AccountsUiState> =
        combine(repository.observeAccounts(), registrar.registrationState) { accounts, registrations ->
            if (accounts.isEmpty()) {
                AccountsUiState.Empty
            } else {
                AccountsUiState.Content(
                    accounts.map { account ->
                        AccountRow(
                            id = account.id,
                            label = account.label,
                            identity = "${account.username}@${account.domain}",
                            isDefault = account.isDefault,
                            status = AccountStatus.from(registrations[account.id]),
                        )
                    },
                )
            }
        }.stateIn(
            scope = viewModelScope,
            // Keeps the flow alive briefly across a rotation, so the list does not
            // re-query and flash Loading when the screen is recreated.
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = AccountsUiState.Loading,
        )

    private val eventChannel = Channel<AccountsEvent>(Channel.BUFFERED)
    val events: Flow<AccountsEvent> = eventChannel.receiveAsFlow()

    /**
     * Deletes an account.
     *
     * The refusal path is the interesting one: a call in progress produces an event
     * naming how many calls, not a silent no-op, because the user pressed a button and is
     * owed an explanation.
     */
    fun deleteAccount(id: AccountId, label: String) {
        viewModelScope.launch {
            when (val result = deleteAccount.invoke(id)) {
                is Outcome.Success -> eventChannel.send(AccountsEvent.Deleted(label))
                is Outcome.Failure -> eventChannel.send(result.error.toEvent())
            }
        }
    }

    fun setDefault(id: AccountId) {
        viewModelScope.launch { repository.setDefault(id) }
    }

    /**
     * Registers an account that is configured but logged out (Task 29).
     *
     * No password prompt: the credentials are already stored, encrypted, and the engine
     * decrypts them for the length of the REGISTER. Asking again would be theatre.
     */
    fun logIn(id: AccountId, label: String) {
        viewModelScope.launch {
            val event = when (val result = login(id)) {
                is Outcome.Success -> AccountsEvent.LoggedIn(label)
                is Outcome.Failure -> when (val error = result.error) {
                    is LoginError.NotFound -> AccountsEvent.AccountGone(label)
                    is LoginError.Rejected ->
                        AccountsEvent.LoginFailed(label, error.cause.toRegistrationFailure())
                }
            }
            eventChannel.send(event)
        }
    }

    /**
     * Unregisters an account, keeping it configured.
     *
     * The refusal path matters here as much as it does for delete: logging out drops the
     * registration a call is running over, so a call in progress produces an event naming
     * how many, not a silent no-op.
     */
    fun logOut(id: AccountId, label: String) {
        viewModelScope.launch {
            val event = when (val result = logout(id)) {
                is Outcome.Success -> AccountsEvent.LoggedOut(label)
                is Outcome.Failure -> when (val error = result.error) {
                    is LogoutError.NotFound -> AccountsEvent.AccountGone(label)
                    is LogoutError.CallInProgress ->
                        AccountsEvent.LogoutRefusedCallInProgress(error.activeCalls)
                }
            }
            eventChannel.send(event)
        }
    }

    private fun DeleteAccountError.toEvent(): AccountsEvent = when (this) {
        is DeleteAccountError.CallInProgress -> AccountsEvent.DeleteRefusedCallInProgress(activeCalls)
        is DeleteAccountError.Failed -> AccountsEvent.DeleteFailed(detail)
        is DeleteAccountError.NotFound -> AccountsEvent.DeleteFailed("account no longer exists")
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}
