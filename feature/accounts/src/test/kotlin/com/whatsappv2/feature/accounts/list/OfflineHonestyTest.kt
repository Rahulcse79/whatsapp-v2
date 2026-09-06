package com.whatsappv2.feature.accounts.list

import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Task 66's second done-when, audited exhaustively: **no screen ever claims "Registered"
 * while the transport is down.**
 *
 * ## Why this is worth a test of its own
 *
 * §6 makes it a rule rather than a nicety, and the reason is what an optimistic status
 * costs: a user looking at "Registered" does not check their phone again, and the call
 * they were waiting for never rings. "Offline" is a worse-looking screen and a better one.
 *
 * The mapping already lives in exactly one place, which is what makes an exhaustive audit
 * possible — every screen renders [AccountStatus], so proving the rule here proves it for
 * all of them. This walks every [RegistrationState] and every [RegistrationFailure], so a
 * case added later cannot default into `REGISTERED` unnoticed.
 */
class OfflineHonestyTest {

    /** Every state the engine can report, including one Failed per reason. */
    private fun everyState(): List<RegistrationState> = buildList {
        add(RegistrationState.Registering)
        add(RegistrationState.Unregistered)
        add(RegistrationState.Registered(grantedExpirySeconds = 3_600))
        RegistrationFailure.entries.forEach { reason ->
            add(RegistrationState.Failed(reason, retryScheduled = true))
            add(RegistrationState.Failed(reason, retryScheduled = false))
        }
    }

    @Test
    fun `only an actual registration reads as registered`() {
        everyState()
            .filterNot { it is RegistrationState.Registered }
            .forEach { state ->
                assertNotEquals(
                    AccountStatus.REGISTERED,
                    AccountStatus.from(state),
                    "$state was shown as Registered",
                )
            }
    }

    @Test
    fun `an account the engine has never seen reads as offline, not as broken`() {
        // A fresh install has no state for its accounts. Showing that as a failure makes
        // a working app look broken before it has been given a chance to register.
        assertEquals(AccountStatus.OFFLINE, AccountStatus.from(null))
        assertEquals(AccountStatus.OFFLINE, AccountStatus.from(RegistrationState.Unregistered))
    }

    @Test
    fun `losing the network reads as retrying, not as something the user must fix`() {
        val status = AccountStatus.from(
            RegistrationState.Failed(RegistrationFailure.NETWORK_UNAVAILABLE, retryScheduled = true),
        )

        assertEquals(AccountStatus.FAILED_RETRYING, status)
        // Nothing for the user to do about a train tunnel; highlighting it as their
        // problem trains them to ignore the highlight when it is.
        assertTrue(!status.needsAttention)
    }

    @Test
    fun `a rejected password is shown as needing attention, because it does`() {
        val status = AccountStatus.from(
            RegistrationState.Failed(RegistrationFailure.AUTHENTICATION_FAILED, retryScheduled = false),
        )

        assertEquals(AccountStatus.FAILED_NEEDS_ATTENTION, status)
        assertTrue(status.needsAttention)
    }

    @Test
    fun `every failure is shown as one of the two failure states, never as offline`() {
        // "Offline" for a rejected password would be the same lie in the other direction:
        // it hides a problem the user could fix in ten seconds.
        RegistrationFailure.entries.forEach { reason ->
            val status = AccountStatus.from(RegistrationState.Failed(reason, retryScheduled = false))

            assertTrue(
                status == AccountStatus.FAILED_NEEDS_ATTENTION || status == AccountStatus.FAILED_RETRYING,
                "$reason was shown as $status rather than as a failure",
            )
        }
    }

    @Test
    fun `needing attention is exactly the set of failures the user can act on`() {
        RegistrationFailure.entries.forEach { reason ->
            val status = AccountStatus.from(RegistrationState.Failed(reason, retryScheduled = false))

            assertEquals(
                reason.requiresUserAction,
                status.needsAttention,
                "$reason disagrees with its own requiresUserAction",
            )
        }
    }
}
