package com.whatsappv2.feature.accounts.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.feature.accounts.list.AccountStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The registration indicator in the Chats top bar (item 5.5).
 *
 * The same two sources the account list combines — the stored accounts and the engine's
 * live registration map — reduced to what a top bar has room for. Status comes from the
 * engine and never from storage, for the reason `AccountsViewModel` gives: the database
 * cannot know whether the transport is up, and a bar that said "Registered" over a dead
 * socket would be the §6 lie in the most visible place in the app.
 *
 * It is a second ViewModel over the same data rather than a reuse of `AccountsViewModel`
 * because that one also owns delete, log-in and log-out with their confirmations and
 * events; the bar needs none of that, and a bar that could delete an account is a bar
 * one mis-tap away from a very bad afternoon.
 */
@HiltViewModel
class RegistrationStatusViewModel @Inject constructor(
    private val repository: SipAccountRepository,
    registrar: SipRegistrar,
) : ViewModel() {

    val uiState: StateFlow<RegistrationStatusUiState> =
        combine(repository.observeAccounts(), registrar.registrationState) { accounts, registrations ->
            val rows = accounts.map { account ->
                AccountIndicatorRow(
                    id = account.id,
                    label = account.label,
                    extension = account.shownExtension,
                    status = AccountStatus.from(registrations[account.id]),
                    isDefault = account.isDefault,
                )
            }
            // The repository keeps exactly one default while there are accounts. Falling
            // back to the first row is for the instant between a delete and its promotion,
            // so the bar never has to render "no default" over a list that has accounts.
            val default = rows.firstOrNull { it.isDefault } ?: rows.firstOrNull()
            if (default == null) {
                RegistrationStatusUiState.NoAccounts
            } else {
                RegistrationStatusUiState.Content(rows, default)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = RegistrationStatusUiState.Loading,
        )

    /**
     * Makes [id] the account outgoing calls use.
     *
     * The repository clears the previous default in the same transaction, which is why
     * this is one call and not two — see `SipAccountRepository.setDefault`.
     */
    fun setDefault(id: AccountId) {
        viewModelScope.launch { repository.setDefault(id) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}

/** The dialable extension when there is one, else the username — never blank. */
internal val SipAccount.shownExtension: String
    get() = extension?.takeIf { it.isNotBlank() } ?: username
