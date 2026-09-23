package com.whatsappv2.data.sip.network

import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.core.common.time.MutableClock
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.registration.RegistrationBackoff
import com.whatsappv2.domain.registration.RegistrationRecoveryPolicy
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Network-change recovery, exercised on the JVM (Task 30, DoD 6).
 *
 * Every scenario in the task's done-when list is here as a sequence of network statuses
 * and registration states. That is only possible because `ConnectivityManager` sits behind
 * [NetworkMonitor] and the rules sit in a pure policy — otherwise "airplane mode on, then
 * off" means a person, a handset, and a result nobody else can reproduce.
 *
 * ## Why the delays are exact numbers
 *
 * The backoff is deliberately random, which would leave every timing assertion a range.
 * The coordinator is given a [RegistrationBackoff] with a 60-second base and a [Random]
 * that always samples the top of the window, so the sequence is 60s, 120s, 240s, … — and a
 * coordinator that never incremented the attempt count would fail here while passing any
 * assertion written as "at least a second".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistrationRecoveryCoordinatorTest {

    private val monitor = FakeNetworkMonitor()
    private val device = FakeDeviceWakeMonitor()
    private val engine = FakeSipEngine()
    private val logger = RecordingLogger()
    private val rebinds = mutableListOf<Boolean>()

    private val account = SipAccount(
        id = AccountId("acct-1"),
        label = "Work",
        username = "alice",
        extension = null,
        authUsername = null,
        password = Secret("hunter22"),
        displayName = null,
        domain = "sip.example.com",
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = Transport.UDP,
        registrationExpirySeconds = 3_600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    /** Accounts the coordinator reported as having an unanswered REGISTER, in order. */
    private val stalled = mutableListOf<AccountId>()

    private fun coordinator(
        scope: CoroutineScope,
        clock: Clock = MutableClock(),
    ) = RegistrationRecoveryCoordinator(
        networkMonitor = monitor,
        registrar = engine,
        rebinder = { reachable -> rebinds += reachable },
        scope = scope,
        logger = logger,
        policy = RegistrationRecoveryPolicy(RegistrationBackoff(baseDelay = BASE_DELAY)),
        random = WidestSample,
        clock = clock,
        wakeMonitor = device,
        onAttemptStalled = { id ->
            stalled += id
            // What the engine does with it, so the fake's state moves the way the real
            // one's does and the chain that follows is the real chain.
            engine.givenRegistrationFailed(account, RegistrationFailure.TIMEOUT)
        },
    )

    /** Registered on Wi-Fi, watched, and past the first debounce. */
    private fun TestScope.arrangeRegisteredOnWifi(): RegistrationRecoveryCoordinator {
        engine.givenRegistered(account)
        monitor.onWifi()
        val recovery = coordinator(backgroundScope)
        recovery.start()
        settle()
        return recovery
    }

    /** Makes the registrar stop answering, leaving the account transiently failed. */
    private suspend fun givenRegistrarUnreachable() {
        engine.alwaysFail(FakeSipEngine.Operation.REGISTER, SipError.Timeout)
        engine.register(account)
        engine.alwaysFail(FakeSipEngine.Operation.REFRESH_REGISTRATION, SipError.Timeout)
        engine.clearInvocations()
    }

    /** Past the debounce window, then let everything that released actually run. */
    private fun TestScope.settle(after: Duration = SETTLE) {
        advanceTimeBy(after)
        runCurrent()
    }

    private val refreshes: List<String>
        get() = engine.invocations
            .filter { it.operation == FakeSipEngine.Operation.REFRESH_REGISTRATION }
            .map { it.detail }

    private val scheduledRetries: List<String>
        get() = logger.matching("Registrar unreachable")

    // ---------------------------------------------------------------- handover

    @Test
    fun `a Wi-Fi to cellular handover re-registers with no user action`() = runTest {
        arrangeRegisteredOnWifi()
        engine.clearInvocations()

        monitor.onCellular()
        settle()

        // The transports are rebuilt before the REGISTER, not after: the stack's sockets
        // are bound to an address the device no longer holds, so a REGISTER sent first
        // never leaves the handset.
        assertEquals(listOf(false, true), rebinds)
        assertEquals(listOf(account.id.value), refreshes)
    }

    @Test
    fun `a network that has not changed re-registers nothing`() = runTest {
        arrangeRegisteredOnWifi()
        engine.clearInvocations()

        // The platform repeats itself constantly - a capability flip, a validation change.
        // Acting on a repeat would mean a REGISTER every few seconds.
        monitor.onWifi()
        settle()

        assertTrue(refreshes.isEmpty())
        assertTrue(rebinds.isEmpty())
    }

    // ---------------------------------------------------------------- airplane mode

    @Test
    fun `airplane mode on then off recovers the registration by itself`() = runTest {
        arrangeRegisteredOnWifi()

        // On. The radio goes first; the stack then reports its bindings gone.
        monitor.lost()
        settle()
        engine.simulateNetworkLoss()
        settle(10.minutes)

        assertTrue(refreshes.isEmpty(), "nothing may be attempted while the radio is off")
        assertEquals(listOf(false), rebinds, "the transports are torn down, not left dangling")

        // Off. A different network id, because the platform issues a new one every time a
        // network is established - even back onto the same access point.
        rebinds.clear()
        monitor.onWifi(networkId = 77L)
        settle()

        assertEquals(listOf(false, true), rebinds)
        assertEquals(listOf(account.id.value), refreshes)
    }

    @Test
    fun `losing the network cancels a retry that was already scheduled`() = runTest {
        // DoD 6, and the case that actually burns a battery: an account is mid-backoff
        // when the network goes. Without the cancellation the timer fires into a void and
        // wakes the radio to do it.
        arrangeRegisteredOnWifi()
        givenRegistrarUnreachable()

        // Schedules retry 1 for 60s away, without letting any time pass.
        runCurrent()
        assertEquals(1, scheduledRetries.size, "arrange: a retry is pending")

        monitor.lost()
        settle()
        settle(10.minutes)

        assertTrue(refreshes.isEmpty(), "the pending retry must not fire with no network")
        assertTrue(
            logger.matching("No network").isNotEmpty(),
            "stopping is a rule, and must be stated in the log rather than inferred",
        )
    }

    // ---------------------------------------------------------------- registrar down

    @Test
    fun `a registrar that is down is retried behind a growing backoff`() = runTest {
        // The other half of the distinction the task is built around: the network is fine,
        // the server is not. This one keeps trying - but never in a tight loop, because
        // 5,000 clients returning together is what knocks a recovering registrar over
        // again (§2.1).
        arrangeRegisteredOnWifi()
        givenRegistrarUnreachable()

        settle()
        settle(30.minutes)

        // The evidence Task 30 asks to capture: each attempt waits longer than the last,
        // and the attempt number climbs rather than resetting to one - up to the ceiling,
        // and then no longer. The ceiling is five minutes because the alternative was
        // measured: a handset that had been asleep sat on "Reconnecting…" with its next
        // attempt up to half an hour away.
        assertEquals(
            listOf(
                "Registrar unreachable for acct-1: retry 1 in 60s",
                "Registrar unreachable for acct-1: retry 2 in 120s",
                "Registrar unreachable for acct-1: retry 3 in 240s",
                "Registrar unreachable for acct-1: retry 4 in 300s",
                "Registrar unreachable for acct-1: retry 5 in 300s",
                "Registrar unreachable for acct-1: retry 6 in 300s",
                "Registrar unreachable for acct-1: retry 7 in 300s",
                "Registrar unreachable for acct-1: retry 8 in 300s",
            ),
            scheduledRetries,
        )
    }

    @Test
    fun `a retry that fails schedules the next one itself`() = runTest {
        // The subtle one. Reacting only to registration state stalls: a retry that fails
        // the same way produces an EQUAL state, a StateFlow does not re-emit an equal
        // value, and the chain would stop silently after one attempt.
        arrangeRegisteredOnWifi()
        givenRegistrarUnreachable()

        settle()
        settle(30.minutes)

        assertTrue(refreshes.size >= 4, "the chain stopped early: ${refreshes.size} attempts")
    }

    @Test
    fun `a wrong password is never retried`() = runTest {
        // Nothing about waiting makes a rejected credential correct, and retrying it is
        // the loop DoD 6 forbids. The account waits for the edit that fixes it, which
        // re-registers on its own (Task 29).
        arrangeRegisteredOnWifi()
        engine.alwaysFail(FakeSipEngine.Operation.REGISTER, SipError.AuthenticationFailed(401))
        engine.register(account)
        engine.clearInvocations()

        settle()
        settle(30.minutes)

        assertTrue(refreshes.isEmpty(), "a rejected password must not be retried")
        assertTrue(scheduledRetries.isEmpty(), "and no backoff should even be scheduled")
    }

    // ---------------------------------------------------------------- flapping

    @Test
    fun `a network that never settles produces no traffic at all`() = runTest {
        // The normal case at the edge of Wi-Fi coverage. Reacting to each callback would
        // mean a transport rebind and a REGISTER per flap; the debounce means a link that
        // will not hold still is simply not believed.
        arrangeRegisteredOnWifi()
        engine.clearInvocations()
        rebinds.clear()

        repeat(FLAPS) { flap ->
            monitor.lost()
            advanceTimeBy(FLAP_INTERVAL)
            monitor.onCellular(networkId = 100L + flap)
            advanceTimeBy(FLAP_INTERVAL)
        }
        runCurrent()

        assertTrue(rebinds.isEmpty(), "a flapping link must not rebind the transports")
        assertTrue(refreshes.isEmpty(), "a flapping link must not re-register")
    }

    @Test
    fun `a flap that lands back where it started is not a change`() = runTest {
        arrangeRegisteredOnWifi()
        rebinds.clear()

        // Off and straight back onto the same connection, inside the window. Debouncing
        // before distinctUntilChanged is what makes this a no-op rather than a round trip.
        monitor.lost()
        advanceTimeBy(FLAP_INTERVAL)
        monitor.onWifi()
        settle()

        assertTrue(rebinds.isEmpty(), "the link ended where it began; nothing changed")
        assertTrue(refreshes.isEmpty())
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `stopping cancels everything it had scheduled`() = runTest {
        val recovery = arrangeRegisteredOnWifi()
        givenRegistrarUnreachable()
        runCurrent()

        recovery.stop()
        settle(30.minutes)

        assertTrue(refreshes.isEmpty(), "a stopped coordinator must leave no timer running")
    }

    @Test
    fun `starting twice does not double every decision`() = runTest {
        val recovery = arrangeRegisteredOnWifi()
        recovery.start()
        settle()
        engine.clearInvocations()

        monitor.onCellular()
        settle()

        assertEquals(listOf(false, true), rebinds, "two collectors would rebind twice")
        assertEquals(listOf(account.id.value), refreshes)
    }

    // ---------------------------------------------------------------- retry schedule

    @Test
    fun `a scheduled retry is published as the moment it is due`() = runTest {
        // Task 31 shows this as a countdown. It has to come from here rather than be
        // recomputed by the screen: the delay is sampled from a random window, so a UI
        // that worked it out again would draw a countdown to a moment nothing happens.
        val clock = MutableClock().set(NOW)
        engine.givenRegistered(account)
        monitor.onWifi()
        val recovery = coordinator(backgroundScope, clock)
        recovery.start()
        settle()
        givenRegistrarUnreachable()

        runCurrent()

        assertEquals(
            mapOf(account.id to NOW + BASE_DELAY.inWholeMilliseconds),
            recovery.nextRetryAt.value,
            "retry 1 is 60s away, so the due time is 60s after now",
        )
    }

    @Test
    fun `losing the network clears the published retry as well as cancelling it`() = runTest {
        // The cancellation was already asserted above. This is the half that reaches the
        // user: a countdown still ticking towards an attempt the app has called off is
        // worse than showing nothing, because it states an intention that no longer
        // exists.
        val recovery = arrangeRegisteredOnWifi()
        givenRegistrarUnreachable()
        runCurrent()
        assertTrue(recovery.nextRetryAt.value.isNotEmpty(), "arrange: a retry is published")

        monitor.lost()
        settle()

        assertEquals(emptyMap(), recovery.nextRetryAt.value)
    }

    @Test
    fun `a fired retry is replaced by the next one, not left on display`() = runTest {
        // The stale-countdown bug: a screen that keeps showing a due time the app has
        // already acted on counts down into the negative and then sits there.
        //
        // "Cleared" is the wrong assertion, and asserting it is how this test was wrong
        // the first time. The attempt fires, fails the same way, and schedules the next
        // one in the same turn - so what is observable, and what actually matters, is that
        // the published time MOVED rather than lingering at the value that has passed.
        val clock = MutableClock().set(NOW)
        engine.givenRegistered(account)
        monitor.onWifi()
        val recovery = coordinator(backgroundScope, clock)
        recovery.start()
        settle()
        givenRegistrarUnreachable()
        runCurrent()

        val firstDue = recovery.nextRetryAt.value.getValue(account.id)
        assertEquals(NOW + BASE_DELAY.inWholeMilliseconds, firstDue, "arrange: retry 1 is 60s out")

        settle(BASE_DELAY + SETTLE)

        assertTrue(refreshes.isNotEmpty(), "arrange: the retry actually fired")
        assertEquals(
            NOW + BASE_DELAY.inWholeMilliseconds * 2,
            recovery.nextRetryAt.value.getValue(account.id),
            "retry 2 waits twice as long, and its time replaces retry 1's",
        )
    }

    // ---------------------------------------------------------------- keepalive

    @Test
    fun `a registered account is refreshed at half its lease, and keeps being`() = runTest {
        // The lease is 3600s. PJSIP's own refresh at expiry - 5s runs on a clock that
        // stops in deep sleep; this one runs on the WakeTimer, which does not, and it is
        // early enough that an inexact alarm cannot land past the lease.
        arrangeRegisteredOnWifi()
        engine.clearInvocations()

        settle(KEEPALIVE - SETTLE)
        assertTrue(refreshes.isEmpty(), "nothing before the half-lease")

        settle(SETTLE)
        assertEquals(listOf(account.id.value), refreshes, "one refresh at the half-lease")

        // A refresh that succeeds produces an EQUAL Registered state, which a StateFlow
        // does not re-emit - so if the keepalive relied on the state to re-arm itself it
        // would fire exactly once. It re-arms from its own firing instead.
        settle(KEEPALIVE)
        assertEquals(2, refreshes.size, "and again a half-lease later")
    }

    @Test
    fun `losing the network disarms the keepalive`() = runTest {
        // DoD 6 applies to this timer as much as to the retry: with no network a REGISTER
        // wakes the radio for nothing.
        arrangeRegisteredOnWifi()
        engine.clearInvocations()

        monitor.lost()
        settle()
        engine.simulateNetworkLoss()
        settle(KEEPALIVE * 3)

        assertTrue(refreshes.isEmpty(), "no keepalive may fire with no network")
    }

    @Test
    fun `a failed account has no keepalive, only its retry`() = runTest {
        // The retry chain owns a failed account. A keepalive firing beside it would be a
        // second REGISTER cadence on top of the backoff.
        arrangeRegisteredOnWifi()
        givenRegistrarUnreachable()
        runCurrent()
        engine.clearFailures()
        engine.clearInvocations()
        logger.lines.clear()

        // Retry 1 is 60s out and succeeds; from then on only the keepalive should run.
        settle(BASE_DELAY + SETTLE)
        assertEquals(listOf(account.id.value), refreshes, "retry 1 fired and succeeded")

        settle(KEEPALIVE)
        assertEquals(2, refreshes.size, "then the keepalive, once, at the half-lease")
        assertTrue(scheduledRetries.isEmpty(), "and no retry was scheduled against a healthy account")
    }

    // ---------------------------------------------------------------- waking up

    @Test
    fun `waking the device runs a pending retry immediately`() = runTest {
        // The backoff was earned against a registrar that may have been back for an
        // hour. A person holding the phone should not wait it out.
        arrangeRegisteredOnWifi()
        givenRegistrarUnreachable()
        engine.clearFailures()
        runCurrent()
        assertTrue(refreshes.isEmpty(), "arrange: retry 1 is 60s out and has not fired")

        device.wake(WakeReason.SCREEN_ON)
        runCurrent()

        assertEquals(listOf(account.id.value), refreshes, "the retry ran on the wake, not on its timer")
        assertTrue(logger.matching("device woke (SCREEN_ON)").isNotEmpty())

        // And the timer it pre-empted does not fire a second REGISTER when its time comes.
        settle(BASE_DELAY + SETTLE)
        assertEquals(1, refreshes.size, "the pre-empted retry was cancelled, not left to fire")
    }

    @Test
    fun `waking the device refreshes a registration that looks healthy`() = runTest {
        // "Healthy" is what the lease looked like when the device went to sleep. Nothing
        // on the wire says it lapsed, so the only way to know is to REGISTER.
        arrangeRegisteredOnWifi()
        engine.clearInvocations()

        device.wake(WakeReason.DOZE_EXIT)
        runCurrent()

        assertEquals(listOf(account.id.value), refreshes)
    }

    @Test
    fun `wakes inside the throttle window are one REGISTER, not three`() = runTest {
        // Screen on, unlock, app to the front: three events in a second, one reason.
        val clock = MutableClock().set(NOW)
        engine.givenRegistered(account)
        monitor.onWifi()
        coordinator(backgroundScope, clock).start()
        settle()
        engine.clearInvocations()

        device.wake(WakeReason.SCREEN_ON)
        device.wake(WakeReason.USER_PRESENT)
        device.wake(WakeReason.APP_FOREGROUND)
        runCurrent()

        assertEquals(1, refreshes.size, "three wakes inside the gap are one refresh")

        // Past the gap, a wake is a wake again. The gap is measured on the wall clock -
        // the one that keeps going through sleep - so it is moved by hand here; virtual
        // time does not advance a MutableClock.
        clock.advanceBy(31.seconds.inWholeMilliseconds)
        device.wake(WakeReason.SCREEN_ON)
        runCurrent()

        assertEquals(2, refreshes.size, "a wake after the gap refreshes again")
    }

    @Test
    fun `waking with no network re-registers nothing`() = runTest {
        // Screen on in airplane mode. DoD 6 still holds.
        arrangeRegisteredOnWifi()
        monitor.lost()
        settle()
        engine.simulateNetworkLoss()
        engine.clearInvocations()

        device.wake(WakeReason.SCREEN_ON)
        runCurrent()

        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun `waking does not retry a wrong password`() = runTest {
        // Waking is not permission to retry a failure the user has to fix (Task 29).
        arrangeRegisteredOnWifi()
        engine.alwaysFail(FakeSipEngine.Operation.REGISTER, SipError.AuthenticationFailed(401))
        engine.register(account)
        engine.clearInvocations()
        settle()

        device.wake(WakeReason.USER_PRESENT)
        runCurrent()

        assertTrue(refreshes.isEmpty())
    }

    // ---------------------------------------------------------------- unanswered REGISTERs

    @Test
    fun `a REGISTER nothing ever answers is retried instead of stranding the account`() = runTest {
        // The defect this class was missing. `Registering` was an absorbing state: the
        // policy answers Idle for it, a device wake skipped it, and `withoutNetwork` leaves
        // it alone - so a request that was lost left the account on that spinner for the
        // life of the process, and only "Register now" got it out, because that path
        // rebuilds the PJSIP account rather than asking the existing one to try again.
        monitor.onWifi()
        val recovery = coordinator(backgroundScope)
        recovery.start()
        settle()

        engine.givenRegistering(account)
        settle()
        engine.clearInvocations()

        // Nothing yet: the stack is allowed the time SIP's own transaction timeout takes.
        settle(RegistrationRecoveryCoordinator.ATTEMPT_TIMEOUT - SETTLE - 1.seconds)
        assertTrue(refreshes.isEmpty(), "a REGISTER in flight must be left alone while it is young")

        settle(2.seconds)

        assertEquals(listOf(account.id), stalled, "the engine is told, so the screen stops saying Registering")
        assertEquals(listOf(account.id.value), refreshes, "and the account is registered again")
    }

    @Test
    fun `an answer that arrives in time leaves the watchdog with nothing to do`() = runTest {
        // The watchdog must not become a second REGISTER generator on a healthy account.
        monitor.onWifi()
        val recovery = coordinator(backgroundScope)
        recovery.start()
        settle()

        engine.givenRegistering(account)
        settle()
        engine.givenRegistered(account)
        settle()
        engine.clearInvocations()

        settle(RegistrationRecoveryCoordinator.ATTEMPT_TIMEOUT * 2)

        assertTrue(stalled.isEmpty())
        assertTrue(refreshes.isEmpty(), "the answer arrived; nothing is owed")
    }

    @Test
    fun `a refresh the stack accepted and never sent does not end the retry chain`() = runTest {
        // The second half of the same defect, and the one that made an account go quiet
        // after a single retry. `reRegister` clears the pending retry before it issues, and
        // only reschedules when the refresh *returns* a failure - so a refresh the stack
        // quietly dropped reported success, produced no state change, and the chain ended
        // there with nothing armed and nothing on the wire.
        arrangeRegisteredOnWifi()
        givenRegistrarUnreachable()
        engine.swallowRefreshes()
        runCurrent()

        // Retry 1 fires, is accepted, and goes nowhere.
        settle(BASE_DELAY)
        assertEquals(1, refreshes.size, "arrange: one attempt was made and answered by nothing")

        settle(RegistrationRecoveryCoordinator.ATTEMPT_TIMEOUT)
        // The watchdog turns the silence into a timeout, which the backoff escalates.
        settle(BASE_DELAY * 2)

        assertTrue(refreshes.size >= 2, "a dropped refresh must not be the end of it, was ${refreshes.size}")
    }

    @Test
    fun `a logged-out account is not dragged back by the watchdog`() = runTest {
        // Recovery is for connections that broke. A logout while a REGISTER was in flight
        // is a decision, and the watchdog firing afterwards must not undo it.
        monitor.onWifi()
        val recovery = coordinator(backgroundScope)
        recovery.start()
        settle()

        engine.givenRegistering(account)
        settle()
        engine.unregister(account.id)
        settle()
        engine.clearInvocations()

        settle(RegistrationRecoveryCoordinator.ATTEMPT_TIMEOUT * 2)

        assertTrue(stalled.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun `picking the phone up does not mean waiting out the watchdog`() = runTest {
        // A person holding a handset that says "Registering" should not have to wait for a
        // timer sized for SIP's transaction timeout. An attempt older than the wake gap is
        // treated as the stalled one it is, immediately.
        val clock = MutableClock()
        monitor.onWifi()
        val recovery = coordinator(backgroundScope, clock)
        recovery.start()
        settle()

        engine.givenRegistering(account)
        settle()
        engine.clearInvocations()
        // Wall-clock, which is what the gap is measured in: the device was asleep, so
        // virtual time standing still is exactly the situation.
        clock.advanceBy(WAKE_GAP.inWholeMilliseconds)

        device.wake(WakeReason.SCREEN_ON)
        runCurrent()

        assertEquals(listOf(account.id), stalled)
        assertEquals(listOf(account.id.value), refreshes)
    }

    @Test
    fun `a REGISTER that has only just gone out survives a device wake`() = runTest {
        // The other side of the same rule. Waking must not fire a second REGISTER on top
        // of one that is a second old, or a screen turned on during a normal registration
        // would double every attempt.
        monitor.onWifi()
        val recovery = coordinator(backgroundScope, MutableClock())
        recovery.start()
        settle()

        engine.givenRegistering(account)
        settle()
        engine.clearInvocations()

        device.wake(WakeReason.SCREEN_ON)
        runCurrent()

        assertTrue(stalled.isEmpty())
        assertTrue(refreshes.isEmpty())
    }

    private companion object {
        /** Comfortably past the coordinator's one-second debounce window. */
        val SETTLE: Duration = 3.seconds

        /** Half the test account's 3600s lease: when its keepalive is due. */
        val KEEPALIVE: Duration = 1_800.seconds

        /**
         * A base delay large enough that a retry cannot fire during a [SETTLE].
         *
         * Keeps "the retry was cancelled" and "the retry had not run yet" from being the
         * same observation.
         */
        val BASE_DELAY: Duration = 60.seconds

        /** Past the coordinator's WAKE_REFRESH_GAP, so a wake treats an attempt as stale. */
        val WAKE_GAP: Duration = 31.seconds

        /** Shorter than the debounce window, so the status never settles. */
        val FLAP_INTERVAL: Duration = 200.milliseconds

        /** An arbitrary fixed instant, so a due time is an equality rather than a range. */
        const val NOW = 1_700_000_000_000L

        const val FLAPS = 8
    }
}

/**
 * A [Random] that always samples the top of the window it is given.
 *
 * Turns a deliberately random delay into an exact number for the length of a test. The
 * randomness itself is [RegistrationBackoff]'s to prove, and it does, in its own test.
 */
private object WidestSample : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextLong(until: Long): Long = until - 1
}
