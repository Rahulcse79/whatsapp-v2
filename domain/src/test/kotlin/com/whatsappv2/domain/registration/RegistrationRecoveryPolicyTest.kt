package com.whatsappv2.domain.registration

import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The network-recovery rules, decided without a device (Task 30, DoD 6).
 *
 * Airplane mode, a Wi-Fi to cellular handover and a registrar outage are each just a
 * different set of arguments here — which is the entire reason the policy is pure. The
 * alternative is a person with a handset and a stopwatch, once.
 */
class RegistrationRecoveryPolicyTest {

    private val policy = RegistrationRecoveryPolicy()

    private val wifi = NetworkStatus.Available(networkId = 1L, transport = NetworkTransport.WIFI)
    private val cellular = NetworkStatus.Available(networkId = 2L, transport = NetworkTransport.CELLULAR)

    private val registered = RegistrationState.Registered(3_600)
    private val transientFailure = RegistrationState.Failed(
        RegistrationFailure.TIMEOUT,
        retryScheduled = true,
    )
    private val wrongPassword = RegistrationState.Failed(
        RegistrationFailure.AUTHENTICATION_FAILED,
        retryScheduled = false,
    )

    // ---------------------------------------------------------------- no network

    @Test
    fun `with no network nothing is attempted, whatever the account is doing`() {
        // DoD 6, stated as an exhaustive sweep rather than one example: a REGISTER with no
        // network cannot succeed, and every attempt wakes the radio. The only correct
        // answer is to stop and let the platform's callback restart things.
        val states = listOf(
            registered,
            transientFailure,
            wrongPassword,
            RegistrationState.Registering,
            RegistrationState.Unregistered,
        )

        for (state in states) {
            assertEquals(
                RecoveryAction.AwaitNetwork,
                policy.decide(NetworkStatus.Unavailable, state, boundNetworkId = 1L, attempt = 5),
                "for $state",
            )
        }
    }

    // ---------------------------------------------------------------- handover

    @Test
    fun `a handover re-registers a healthy account immediately`() {
        // Wi-Fi to cellular changes the source address, so the binding on the old link is
        // already dead even though the account still reads as Registered. Waiting for it
        // to expire means inbound calls ring a device that cannot answer.
        val action = policy.decide(cellular, registered, boundNetworkId = wifi.networkId, attempt = 0)

        assertEquals(RecoveryAction.RegisterNow, action)
    }

    @Test
    fun `a registered account on the network it registered over is left alone`() {
        val action = policy.decide(wifi, registered, boundNetworkId = wifi.networkId, attempt = 0)

        assertEquals(RecoveryAction.Idle, action)
    }

    @Test
    fun `coming back from airplane mode retries at once, not after the backoff`() {
        // The failures were caused by a link that no longer exists. Making the user wait
        // out a delay earned by a dead network is punishing them for the platform.
        val action = policy.decide(
            network = wifi,
            state = transientFailure,
            // Whatever it was bound to before airplane mode, it is not this connection:
            // the platform issues a new id every time a network is established.
            boundNetworkId = 99L,
            attempt = 6,
        )

        assertEquals(RecoveryAction.RegisterNow, action)
    }

    // ---------------------------------------------------------------- registrar down

    @Test
    fun `a registrar that is down is retried behind the backoff`() {
        // Same network, so the network is not the problem - the server is. This is the
        // case that must NOT retry immediately: 5,000 clients returning together is what
        // knocks a recovering registrar over again (§2.1).
        val action = policy.decide(wifi, transientFailure, boundNetworkId = wifi.networkId, attempt = 0)

        val retry = assertIs<RecoveryAction.RetryAfter>(action)
        assertTrue(retry.delay >= 1.seconds, "never faster than the backoff floor")
    }

    @Test
    fun `the attempt count really is fed through to the backoff`() {
        // Pinned with a Random that always takes the top of the window, so these are
        // values rather than ranges: 2s for the first failure, 512s by the ninth. A policy
        // that quietly always asked for attempt zero would pass a range-based assertion
        // and fail this one.
        val early = policy.decide(wifi, transientFailure, wifi.networkId, attempt = 0, random = WidestSample)
        val late = policy.decide(wifi, transientFailure, wifi.networkId, attempt = 8, random = WidestSample)

        assertEquals(2.seconds, assertIs<RecoveryAction.RetryAfter>(early).delay)
        assertEquals(512.seconds, assertIs<RecoveryAction.RetryAfter>(late).delay)
    }

    // ---------------------------------------------------------------- never retried

    @Test
    fun `a wrong password is never retried, on any network`() {
        // Nothing about cellular makes a rejected password correct. Retrying it is exactly
        // the battery-burning loop DoD 6 forbids; the account waits for the edit that
        // fixes it, which re-registers on its own (Task 29).
        assertEquals(
            RecoveryAction.Idle,
            policy.decide(wifi, wrongPassword, boundNetworkId = wifi.networkId, attempt = 0),
        )
        assertEquals(
            RecoveryAction.Idle,
            policy.decide(cellular, wrongPassword, boundNetworkId = wifi.networkId, attempt = 0),
            "not even a handover justifies retrying a rejected credential",
        )
    }

    @Test
    fun `a logged-out account is not logged back in by a network change`() {
        // Recovery is for connections that broke, not for decisions the user made.
        assertEquals(
            RecoveryAction.Idle,
            policy.decide(cellular, RegistrationState.Unregistered, boundNetworkId = wifi.networkId, attempt = 0),
        )
    }

    @Test
    fun `an in-flight REGISTER is not duplicated`() {
        // Two transactions competing over one binding is worse than waiting: the loser's
        // response can arrive last and leave the engine believing the wrong thing.
        assertEquals(
            RecoveryAction.Idle,
            policy.decide(cellular, RegistrationState.Registering, boundNetworkId = wifi.networkId, attempt = 0),
        )
    }

    // ---------------------------------------------------------------- first sight

    @Test
    fun `an account that has never registered is treated as having moved`() {
        // boundNetworkId is null before the first REGISTER, which reads as "not this
        // network" - so a failed account seen for the first time is tried at once rather
        // than serving a backoff it never earned.
        assertEquals(
            RecoveryAction.RegisterNow,
            policy.decide(wifi, transientFailure, boundNetworkId = null, attempt = 0),
        )
    }
}

/**
 * A [Random] that always samples the top of the window it is given.
 *
 * Turns a deliberately random delay into an exact number for the length of one assertion.
 * The randomness itself is [RegistrationBackoff]'s to prove, and it does, in its own test.
 */
private object WidestSample : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextLong(until: Long): Long = until - 1
}
