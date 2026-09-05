package com.whatsappv2.feature.dialer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipRegistrar
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.usecase.PlaceCallError
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The dialer (Task 36).
 *
 * ## What it does not decide
 *
 * How `1001` becomes an address. That is [PlaceCallUseCase]'s job, and deliberately: the
 * rule — complete a bare extension against the chosen account's domain, leave a full URI
 * alone — has to be identical wherever a call starts, and a copy of it in a ViewModel is a
 * copy that will drift the first time another screen places a call.
 *
 * What it does decide is which account a call goes out on, because that is a choice the
 * user makes on this screen: the per-call override if they set one, and the default
 * account otherwise.
 */
@HiltViewModel
class DialerViewModel @Inject constructor(
    private val placeCall: PlaceCallUseCase,
    private val recentDials: RecentDials,
    repository: SipAccountRepository,
    registrar: SipRegistrar,
) : ViewModel() {

    /**
     * Everything the user has entered, in one holder.
     *
     * One flow rather than three, because they are read together on every emission and
     * `combine` runs out of arities quickly — and because "what is being dialled, from
     * which account, is it going out yet" is genuinely one piece of state.
     */
    private data class Entry(
        val input: String = "",
        val override: AccountId? = null,
        val placing: Boolean = false,
    )

    private val entry = MutableStateFlow(Entry())

    private val eventChannel = Channel<DialerEvent>(Channel.BUFFERED)
    val events: Flow<DialerEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<DialerUiState> = combine(
        repository.observeAccounts(),
        registrar.registrationState,
        entry,
        recentDials.recent,
    ) { accounts, registrations, current, recent ->
        val rows = accounts.map { account ->
            DialerAccount(
                id = account.id,
                label = account.label,
                identity = "${account.username}@${account.domain}",
                isRegistered = registrations[account.id]?.isUsable == true,
            )
        }
        // The override if there is one, the default account otherwise, and the first
        // account if nothing is marked default - which is what the use case would pick.
        val selected = rows.firstOrNull { it.id == current.override }
            ?: accounts.firstOrNull { it.isDefault }?.let { default -> rows.first { it.id == default.id } }
            ?: rows.firstOrNull()

        DialerUiState(
            input = current.input,
            accounts = rows,
            selectedAccount = selected,
            isOverridden = current.override != null,
            selectionIsDefault = accounts.firstOrNull { it.id == selected?.id }?.isDefault == true,
            recent = recent,
            isPlacing = current.placing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = DialerUiState(),
    )

    /** Replaces the input, for the text field. */
    fun onInputChanged(value: String) {
        entry.update { it.copy(input = value) }
    }

    /** Appends one keypad character. */
    fun onDigitPressed(digit: Char) {
        entry.update { it.copy(input = it.input + digit) }
    }

    /** Removes the last character. */
    fun onBackspace() {
        entry.update { it.copy(input = it.input.dropLast(1)) }
    }

    /** Clears the input. Bound to a long press on backspace, as every dialler does. */
    fun onClear() {
        entry.update { it.copy(input = "") }
    }

    /**
     * Chooses the account for the next call.
     *
     * Per call, not a setting: it is cleared once the call is placed, so a one-off call
     * from the work account does not silently become every later call's account too.
     */
    fun onAccountSelected(id: AccountId) {
        entry.update { it.copy(override = id) }
    }

    /** Fills the input from a recent target. Tapping does not dial: a misplaced tap should
     * not place a call. */
    fun onRecentSelected(target: String) {
        entry.update { it.copy(input = target) }
    }

    /**
     * Places the call.
     *
     * The input is recorded as dialled **before** the outcome is known, on purpose: a call
     * that failed is exactly the one someone wants to redial, and a recents list that only
     * remembers successes is missing the entries that matter.
     */
    fun onCall() {
        val state = uiState.value
        if (!state.canPlaceCall) return
        val target = state.input

        entry.update { it.copy(placing = true) }
        viewModelScope.launch {
            try {
                recentDials.record(target)
                val result = placeCall(
                    // Named explicitly unless it is the default account: an override is
                    // one reason for that, and "nothing is marked default" is the other -
                    // the screen shows an account, so the call must go out on it rather
                    // than fail for want of a default the user never set.
                    accountOverride = state.selectedAccount?.id.takeIf { !state.selectionIsDefault },
                    input = target,
                )
                eventChannel.send(result.toEvent(target))
                // Cleared only on success: a call that was refused leaves what was typed
                // on screen, because the user is about to correct it or try again.
                if (result is Outcome.Success) entry.value = Entry()
            } finally {
                entry.update { it.copy(placing = false) }
            }
        }
    }

    private fun Outcome<CallId, PlaceCallError>.toEvent(target: String): DialerEvent = when (this) {
        is Outcome.Success -> DialerEvent.CallPlaced(value)
        is Outcome.Failure -> when (val reason = error) {
            is PlaceCallError.NoAccountAvailable -> DialerEvent.NoAccount
            is PlaceCallError.UnknownAccount -> DialerEvent.Refused("That account is no longer configured")
            is PlaceCallError.InvalidTarget -> DialerEvent.InvalidTarget(target)
            is PlaceCallError.Rejected -> reason.cause.toEvent()
        }
    }

    /**
     * The engine's refusal, in a sentence.
     *
     * The sentences are the domain's, not this screen's (Task 44). They used to be a
     * `when` here with a general branch under it, which meant an error this dialler had
     * not thought of read "The call could not be placed" while the in-call screen called
     * the same error something else again.
     */
    private fun SipError.toEvent(): DialerEvent = DialerEvent.Refused(userMessage())

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}
