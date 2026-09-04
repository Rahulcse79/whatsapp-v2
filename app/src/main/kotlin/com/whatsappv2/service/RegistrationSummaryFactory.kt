package com.whatsappv2.service

import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState

/** What the persistent notification says, derived once so nothing can disagree. */
data class RegistrationSummary(
    val title: String,
    val text: String,
    /** True when the user has to act - a wrong password, not a passing network blip. */
    val needsAttention: Boolean,
)

/**
 * Turns registration state into notification text.
 *
 * Pure, because the wording is worth asserting: §6 forbids ever claiming "Registered"
 * while the transport is down, and a persistent notification is the most visible place
 * that lie would appear.
 */
object RegistrationSummaryFactory {

    fun summarise(
        registrations: Map<*, RegistrationState>,
        activeCalls: Int,
    ): RegistrationSummary {
        if (activeCalls > 0) {
            return RegistrationSummary(
                title = "Call in progress",
                text = if (activeCalls == 1) "1 active call" else "$activeCalls active calls",
                needsAttention = false,
            )
        }

        val registered = registrations.values.count { it.isUsable }
        val registering = registrations.values.count { it is RegistrationState.Registering }
        val failures = registrations.values.filterIsInstance<RegistrationState.Failed>()
        val needsUser = failures.filter { it.reason.requiresUserAction }

        return when {
            // Reported first even when other accounts are fine: an account that needs a
            // password is the only thing here the user can actually do something about.
            needsUser.isNotEmpty() -> RegistrationSummary(
                title = "Account needs attention",
                text = describe(needsUser.first().reason),
                needsAttention = true,
            )

            registered > 0 && failures.isEmpty() -> RegistrationSummary(
                title = "Ready for calls",
                text = if (registered == 1) "1 account registered" else "$registered accounts registered",
                needsAttention = false,
            )

            registered > 0 -> RegistrationSummary(
                title = "Ready for calls",
                text = "$registered registered, ${failures.size} reconnecting",
                needsAttention = false,
            )

            registering > 0 -> RegistrationSummary(
                title = "Connecting",
                text = "Registering...",
                needsAttention = false,
            )

            failures.isNotEmpty() -> RegistrationSummary(
                title = "Reconnecting",
                text = describe(failures.first().reason),
                needsAttention = false,
            )

            else -> RegistrationSummary(
                title = "Not registered",
                text = "No account is registered",
                needsAttention = false,
            )
        }
    }

    /** Wording per failure, so the notification says what to do rather than "error". */
    private fun describe(reason: RegistrationFailure): String = when (reason) {
        RegistrationFailure.AUTHENTICATION_FAILED -> "Check your username and password"
        RegistrationFailure.ACCOUNT_REJECTED -> "The server rejected this account"
        RegistrationFailure.INVALID_CONFIGURATION -> "Check the account settings"
        RegistrationFailure.NETWORK_UNAVAILABLE -> "Waiting for a network"
        RegistrationFailure.TIMEOUT -> "The server did not respond"
        RegistrationFailure.SERVER_UNAVAILABLE -> "The server is unavailable"
        RegistrationFailure.TRANSPORT_FAILURE -> "Could not reach the server"
    }
}
