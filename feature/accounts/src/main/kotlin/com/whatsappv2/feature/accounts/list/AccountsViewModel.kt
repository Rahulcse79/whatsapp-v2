package com.whatsappv2.feature.accounts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.usecase.DeleteAccountError
import com.whatsappv2.domain.usecase.DeleteAccountUseCase
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
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: SipAccountRepository,
    private val deleteAccount: DeleteAccountUseCase,
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

    private fun DeleteAccountError.toEvent(): AccountsEvent = when (this) {
        is DeleteAccountError.CallInProgress -> AccountsEvent.DeleteRefusedCallInProgress(activeCalls)
        is DeleteAccountError.Failed -> AccountsEvent.DeleteFailed(detail)
        is DeleteAccountError.NotFound -> AccountsEvent.DeleteFailed("account no longer exists")
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}
