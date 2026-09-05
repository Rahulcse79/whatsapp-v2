package com.whatsappv2.feature.accounts.detail

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.feature.accounts.list.AccountStatus

/**
 * One account's registration, in as much detail as the user can act on (Task 31).
 *
 * The list answers "is this working". This answers "why not, and what happens next" —
 * which are the two questions a row cannot fit and the two that decide whether someone
 * waits or goes and fixes their password.
 */
sealed interface AccountDetailUiState {

    data object Loading : AccountDetailUiState

    /**
     * The account was deleted while this screen was open.
     *
     * A state rather than a blank screen: the row is gone and saying so is the only
     * honest thing left to show.
     */
    data object Gone : AccountDetailUiState

    data class Content(
        val id: AccountId,
        val label: String,
        /** `user@domain`, the identity that actually registers. */
        val identity: String,
        val transport: String,
        val isDefault: Boolean,
        val status: AccountStatus,
        /** Why it failed, when it failed. Null whenever [status] is not a failure. */
        val failure: RegistrationFailure?,
        /**
         * Epoch millis of the next scheduled attempt, or null when none is pending.
         *
         * Published by the recovery coordinator rather than computed here — see
         * `RegistrationRetrySchedule`. A screen that recomputed the backoff would draw a
         * countdown to a moment nothing is going to happen, because the delay is sampled
         * from a random window.
         */
        val nextRetryAtEpochMillis: Long?,
        /** The expiry the registrar granted, when registered. */
        val grantedExpirySeconds: Int?,
    ) : AccountDetailUiState {

        /**
         * True when pressing "register now" can plausibly help.
         *
         * Not offered while a REGISTER is already in flight: a second one does not arrive
         * sooner, and a button that appears to do nothing reads as a broken app.
         */
        val canRegisterNow: Boolean get() = status != AccountStatus.REGISTERING
    }
}

/**
 * What went wrong, in words the user can act on (§5.1, Task 31 done-when 2).
 *
 * Lives beside the status wording in the list package for the reason stated there: the
 * two screens must not describe one failure differently. "Authentication failed" is named
 * explicitly by the task — a generic "error" tells someone nothing about whether the
 * problem is their password or their Wi-Fi.
 */
fun RegistrationFailure.detailLabel(): String = when (this) {
    RegistrationFailure.AUTHENTICATION_FAILED -> "Authentication failed"
    RegistrationFailure.ACCOUNT_REJECTED -> "The server rejected this account"
    RegistrationFailure.NETWORK_UNAVAILABLE -> "No network"
    RegistrationFailure.TIMEOUT -> "The server did not respond"
    RegistrationFailure.SERVER_UNAVAILABLE -> "The server is unavailable"
    RegistrationFailure.TRANSPORT_FAILURE -> "The connection could not be established"
    RegistrationFailure.INVALID_CONFIGURATION -> "This account's settings are incomplete"
}

/**
 * The sentence under the heading: what the app is doing about it.
 *
 * Separated from [detailLabel] because the cause and the consequence are different
 * things, and only one of them changes when a retry is scheduled.
 */
fun RegistrationFailure.remedy(): String = when (this) {
    RegistrationFailure.AUTHENTICATION_FAILED ->
        "Check the username and password, then register again."
    RegistrationFailure.ACCOUNT_REJECTED ->
        "Check the identity and domain with whoever runs the server."
    RegistrationFailure.INVALID_CONFIGURATION ->
        "Open the account and fill in what is missing."
    // The three the app recovers from on its own. Saying so is the point: a user who is
    // told to act on a transient network fault will change settings that were correct.
    RegistrationFailure.NETWORK_UNAVAILABLE ->
        "Waiting for a network. This will recover on its own."
    RegistrationFailure.TIMEOUT, RegistrationFailure.SERVER_UNAVAILABLE ->
        "Retrying automatically."
    RegistrationFailure.TRANSPORT_FAILURE ->
        "Retrying automatically. If it persists, check the transport and port."
}
