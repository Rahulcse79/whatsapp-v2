package com.whatsappv2.feature.accounts.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.engine.toRegistrationFailure
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.registration.RegistrationRetrySchedule
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.usecase.LoginError
import com.whatsappv2.domain.usecase.LoginUseCase
import com.whatsappv2.feature.accounts.list.AccountStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One account's registration, in detail (Task 31).
 *
 * ## Why it is a combine and not a refresh
 *
 * Everything on this screen is derived from three streams — the stored account, the
 * engine's registration state, and the retry schedule — so it updates because the
 * underlying thing changed, never because a timer went off. That is the task's first
 * done-when, and it is a property of the shape rather than a thing to remember: there is
 * nowhere in this class to put a poll.
 *
 * ## Honest offline (§6)
 *
 * A failure the user must fix and a failure the network caused look identical in a
 * boolean. They are kept apart all the way to the screen: [AccountStatus] separates
 * "needs attention" from "retrying", and `NETWORK_UNAVAILABLE` carries its own wording and
 * its own remedy, so airplane mode reads as offline rather than as a broken account.
 */
@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    private val repository: SipAccountRepository,
    private val registrar: SipRegistrar,
    private val login: LoginUseCase,
    retrySchedule: RegistrationRetrySchedule,
) : ViewModel() {

    private val accountId = MutableStateFlow<AccountId?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<AccountDetailUiState> =
        accountId
            .flatMapLatest { id ->
                if (id == null) {
                    // Nothing selected yet. Loading, not Gone: the screen has not been
                    // told which account it is showing, which is not the same as being
                    // told about one that no longer exists.
                    flowOf(AccountDetailUiState.Loading)
                } else {
                    combine(
                        repository.observeAccount(id),
                        registrar.registrationState,
                        retrySchedule.nextRetryAt,
                    ) { account, registrations, retries ->
                        if (account == null) {
                            AccountDetailUiState.Gone
                        } else {
                            val state = registrations[id]
                            AccountDetailUiState.Content(
                                id = id,
                                label = account.label,
                                identity = "${account.username}@${account.domain}",
                                transport = account.transport.name,
                                isDefault = account.isDefault,
                                status = AccountStatus.from(state),
                                failure = AccountStatus.reasonOf(state),
                                nextRetryAtEpochMillis = retries[id],
                                grantedExpirySeconds =
                                (state as? RegistrationState.Registered)?.grantedExpirySeconds,
                            )
                        }
                    }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
                initialValue = AccountDetailUiState.Loading,
            )

    private val eventChannel = Channel<AccountDetailEvent>(Channel.BUFFERED)
    val events: Flow<AccountDetailEvent> = eventChannel.receiveAsFlow()

    /** Tells the screen which account it is showing. Idempotent. */
    fun load(id: AccountId) {
        accountId.value = id
    }

    /**
     * "Register now".
     *
     * Goes through [LoginUseCase] rather than `refreshRegistration` on purpose. Refresh
     * only works for an account the stack already holds, and the state this button exists
     * for — a wrong password that was corrected, an account that was logged out — is
     * exactly the state where the stack holds nothing. Login covers both: it registers an
     * account the engine has never seen, and re-registers one it has.
     */
    fun registerNow() {
        val id = accountId.value ?: return
        viewModelScope.launch {
            val event = when (val result = login(id)) {
                is Outcome.Success -> AccountDetailEvent.RegisterRequested
                is Outcome.Failure -> when (val error = result.error) {
                    is LoginError.NotFound -> AccountDetailEvent.Gone
                    is LoginError.Rejected ->
                        AccountDetailEvent.RegisterFailed(error.cause.toRegistrationFailure())
                }
            }
            eventChannel.send(event)
        }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}

/** A one-shot outcome of pressing something, as opposed to state the screen renders. */
sealed interface AccountDetailEvent {

    /**
     * The REGISTER was accepted for sending.
     *
     * Deliberately not "registered": [LoginUseCase] returning success means the request
     * went out, and whether the registrar accepts it arrives later on
     * `registrationState`. Claiming success here is exactly the §6 lie this screen exists
     * to avoid.
     */
    data object RegisterRequested : AccountDetailEvent

    data class RegisterFailed(val reason: RegistrationFailure) : AccountDetailEvent

    /** The account was deleted elsewhere. */
    data object Gone : AccountDetailEvent
}
