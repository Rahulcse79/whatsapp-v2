package com.whatsappv2.feature.accounts.list

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState

/**
 * One account as the list needs it.
 *
 * Presentation-ready: the ViewModel resolves the identity string and the status once, so
 * two rows cannot disagree about how to render the same thing, and a Compose preview can
 * be built without a repository.
 */
data class AccountRow(
    val id: AccountId,
    val label: String,
    /** `user@domain`, the identity that actually registers. */
    val identity: String,
    val isDefault: Boolean,
    val status: AccountStatus,
) {
    /**
     * True when the engine is holding this account, whether or not it is working.
     *
     * Derived rather than stored: it is a reading of [status], and a second field could
     * disagree with it. A failing registration counts as logged in — the account is being
     * attempted, and "log out" is exactly the way to stop that.
     */
    val isLoggedIn: Boolean get() = status != AccountStatus.OFFLINE
}

/**
 * Registration status, reduced to what the list shows.
 *
 * Deliberately not [RegistrationState] itself: the UI does not need the granted expiry,
 * and mapping once here means the "never show Registered while the transport is down"
 * rule (§6) is enforced in one place rather than per composable.
 */
enum class AccountStatus {
    /** Registered and able to place calls. */
    REGISTERED,

    /** A REGISTER is in flight. */
    REGISTERING,

    /** Failed for a reason the user must fix - a wrong password, say. */
    FAILED_NEEDS_ATTENTION,

    /** Failed transiently; the app will retry on its own. */
    FAILED_RETRYING,

    /** Deliberately not registered, or never attempted. */
    OFFLINE,
    ;

    /** True when the user has to do something; the row highlights these. */
    val needsAttention: Boolean get() = this == FAILED_NEEDS_ATTENTION

    companion object {
        fun from(state: RegistrationState?): AccountStatus = when (state) {
            is RegistrationState.Registered -> REGISTERED
            is RegistrationState.Registering -> REGISTERING
            is RegistrationState.Failed -> if (state.reason.requiresUserAction) {
                FAILED_NEEDS_ATTENTION
            } else {
                FAILED_RETRYING
            }
            // Absent means the engine has never seen this account - which is offline, not
            // an error. Reporting it as failed would make a fresh install look broken.
            RegistrationState.Unregistered, null -> OFFLINE
        }

        /** The reason shown beneath a failed row, when there is one worth showing. */
        fun reasonOf(state: RegistrationState?): RegistrationFailure? =
            (state as? RegistrationState.Failed)?.reason
    }
}

/** What the account list is showing. */
sealed interface AccountsUiState {

    /** Reading from storage. Distinct from [Empty] so a blank list is not shown first. */
    data object Loading : AccountsUiState

    /** No accounts configured. The app cannot register or call until one is added. */
    data object Empty : AccountsUiState

    data class Content(val accounts: List<AccountRow>) : AccountsUiState
}

/** A one-shot thing the screen must react to, as opposed to state it renders. */
sealed interface AccountsEvent {

    /** The account could not be deleted because a call is using it. */
    data class DeleteRefusedCallInProgress(val activeCalls: Int) : AccountsEvent

    /** Deleting failed for a reason the user cannot act on. */
    data class DeleteFailed(val detail: String) : AccountsEvent

    data class Deleted(val label: String) : AccountsEvent

    /** REGISTER was accepted; the account can now take calls. */
    data class LoggedIn(val label: String) : AccountsEvent

    /**
     * Logging in failed.
     *
     * Carries a [RegistrationFailure] rather than a message: it is the same vocabulary
     * the row's status uses, so a snackbar and the row beneath it cannot describe one
     * failure two different ways.
     */
    data class LoginFailed(val label: String, val reason: RegistrationFailure) : AccountsEvent

    /** The account was unregistered and is still configured. */
    data class LoggedOut(val label: String) : AccountsEvent

    /** Logging out was refused because a call is using the registration. */
    data class LogoutRefusedCallInProgress(val activeCalls: Int) : AccountsEvent

    /** The account is no longer there — a stale row after a delete elsewhere. */
    data class AccountGone(val label: String) : AccountsEvent
}
