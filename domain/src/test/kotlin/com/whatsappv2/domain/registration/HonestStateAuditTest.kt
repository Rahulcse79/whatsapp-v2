package com.whatsappv2.domain.registration

import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 66's third done-when, as an audit rather than a spot check: **nothing is queued for
 * retry that cannot meaningfully be retried.**
 *
 * ## Why exhaustive
 *
 * The individual cases are already covered by `RegistrationRecoveryPolicy`'s own tests.
 * What those cannot catch is the case added *later*: a new [RegistrationFailure] whose
 * author did not think about whether retrying it means anything. This walks every value
 * of the enum, so a new one arrives with this test failing until somebody has decided.
 *
 * The rule it enforces is DoD 6's other half. A retry loop against a rejected password is
 * not resilience — it is an account locked out by its own client, and the user is never
 * told why.
 */
class HonestStateAuditTest {

    private val policy = RegistrationRecoveryPolicy()
    private val online = NetworkStatus.Available(networkId = 1, transport = NetworkTransport.WIFI)
    private val deterministic = Random(seed = 0)

    private fun decideFor(failure: RegistrationFailure, boundNetworkId: Long? = 1) = policy.decide(
        network = online,
        state = RegistrationState.Failed(failure, retryScheduled = false),
        boundNetworkId = boundNetworkId,
        attempt = 1,
        random = deterministic,
    )

    @Test
    fun `no failure the user must fix is ever queued for retry`() {
        RegistrationFailure.entries
            .filter { it.requiresUserAction }
            .forEach { failure ->
                assertEquals(
                    RecoveryAction.Idle,
                    decideFor(failure),
                    "$failure needs the user to act, so retrying it is a loop that cannot end",
                )
            }
    }

    @Test
    fun `a failure the user must fix is not retried even when the network changed`() {
        // A new network is the strongest trigger this policy has, and it still must not
        // make a rejected password correct.
        RegistrationFailure.entries
            .filter { it.requiresUserAction }
            .forEach { failure ->
                assertEquals(
                    RecoveryAction.Idle,
                    decideFor(failure, boundNetworkId = 99),
                    "$failure was retried because the network moved",
                )
            }
    }

    @Test
    fun `every transient failure is retried, so nothing recoverable is abandoned`() {
        // The mirror of the rule above, and the reason it cannot be satisfied by simply
        // never retrying: a dropped transport must come back on its own (DoD 6).
        RegistrationFailure.entries
            .filterNot { it.requiresUserAction }
            .forEach { failure ->
                val action = decideFor(failure)
                assertTrue(
                    action is RecoveryAction.RetryAfter || action is RecoveryAction.RegisterNow,
                    "$failure is recoverable but produced $action",
                )
            }
    }

    @Test
    fun `every failure is classified, so a new one cannot be silently neither`() {
        // Both lists non-empty is the assertion: a change that made every failure require
        // user action would pass the first test and break the app, and vice versa.
        val (needsUser, transient) = RegistrationFailure.entries.partition { it.requiresUserAction }

        assertTrue(needsUser.isNotEmpty(), "no failure requires user action; the first test is vacuous")
        assertTrue(transient.isNotEmpty(), "no failure is transient; the third test is vacuous")
        assertEquals(RegistrationFailure.entries.size, needsUser.size + transient.size)
    }

    @Test
    fun `an account the user logged out of is left alone`() {
        // §6 and Task 29: logging out is a decision, and a network change is not
        // permission to undo it.
        val action = policy.decide(
            network = online,
            state = RegistrationState.Unregistered,
            boundNetworkId = 99,
            attempt = 0,
            random = deterministic,
        )

        assertEquals(RecoveryAction.Idle, action)
    }

    @Test
    fun `with no network nothing is scheduled, because there is nothing to schedule it against`() {
        // Queuing a retry for an unreachable network is the "queued for something that
        // cannot be retried" case at its most literal: the timer fires, the attempt fails,
        // the counter escalates, and the radio was never on.
        RegistrationFailure.entries.forEach { failure ->
            val action = policy.decide(
                network = NetworkStatus.Unavailable,
                state = RegistrationState.Failed(failure, retryScheduled = false),
                boundNetworkId = 1,
                attempt = 3,
                random = deterministic,
            )

            assertIs<RecoveryAction.AwaitNetwork>(action, "$failure scheduled work with no network")
        }
    }
}
