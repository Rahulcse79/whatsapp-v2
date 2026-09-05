package com.whatsappv2.feature.dialer

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId

/** One account, as the dialer's account picker needs it. */
data class DialerAccount(
    val id: AccountId,
    val label: String,
    /** `user@domain` — what a bare extension is completed against. */
    val identity: String,
    /** True when the account can actually place a call right now. */
    val isRegistered: Boolean,
)

/**
 * What the dialer is showing (Task 36).
 *
 * One immutable value rather than a handful of flows, so the screen cannot render an
 * account picker and a call button that disagree about which account is selected.
 */
data class DialerUiState(
    /** What has been typed or tapped, exactly as entered. */
    val input: String = "",

    /** Every configured account, for the per-call override. */
    val accounts: List<DialerAccount> = emptyList(),

    /**
     * The account this call would use.
     *
     * Null before accounts have loaded. Otherwise the user's override if they made one,
     * and the default account if they did not — resolved here so the screen shows the
     * account the call will actually go out on rather than a guess.
     */
    val selectedAccount: DialerAccount? = null,

    /** True when the user chose an account rather than inheriting the default. */
    val isOverridden: Boolean = false,

    /**
     * True when [selectedAccount] is the account marked default.
     *
     * The dialler passes an explicit account to the use case whenever this is false — for
     * a deliberate override, and also for the case where nothing is marked default at all,
     * where the screen shows an account and the use case would otherwise find none.
     */
    val selectionIsDefault: Boolean = false,

    /** Recently dialled targets, most recent first. */
    val recent: List<String> = emptyList(),

    /** True while a call is being placed, so the button cannot be pressed twice. */
    val isPlacing: Boolean = false,
) {
    /**
     * Whether the call button does anything.
     *
     * Empty input cannot be dialled, and neither can a call with no account behind it. An
     * unregistered account is deliberately **not** blocked here: the engine refuses it with
     * `NotRegistered`, which is a message that names the problem, and a dead button names
     * nothing.
     */
    val canPlaceCall: Boolean get() = input.isNotBlank() && selectedAccount != null && !isPlacing

    /** True when the picker is worth showing at all. */
    val hasChoiceOfAccounts: Boolean get() = accounts.size > 1
}

/** A one-shot thing the screen must react to, as opposed to state it renders. */
sealed interface DialerEvent {

    /** The call is on its way; the caller opens the call screen for it. */
    data class CallPlaced(val callId: CallId) : DialerEvent

    /** Nothing to call from — no accounts are configured yet. */
    data object NoAccount : DialerEvent

    /** What was typed is not an address, however it is read. */
    data class InvalidTarget(val input: String) : DialerEvent

    /**
     * The engine refused, with a sentence that says what to do about it.
     *
     * A message rather than an error type: every case the dialer can distinguish is
     * distinguished in the ViewModel, where the mapping is asserted, and what reaches the
     * screen is the one line it will show.
     */
    data class Refused(val message: String) : DialerEvent
}
