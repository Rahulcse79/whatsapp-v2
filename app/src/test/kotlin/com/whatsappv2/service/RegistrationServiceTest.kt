package com.whatsappv2.service

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The rules §6 states about the foreground service, asserted directly.
 *
 * The done-when for Task 28 suggests verifying with `dumpsys`, which can only be done on
 * a device and only tells you what happened, not why. Extracting the decision makes the
 * rule itself testable - including the one that matters most, that nothing keeps the
 * service (and its wake lock) alive once there is no reason for it.
 */
class ServiceRunPolicyTest {

    private fun registrations(vararg states: RegistrationState): Map<AccountId, RegistrationState> =
        states.mapIndexed { index, state -> AccountId("acct-$index") to state }.toMap()

    private val registered = RegistrationState.Registered(3_600)
    private val registering = RegistrationState.Registering
    private val failedRetrying = RegistrationState.Failed(RegistrationFailure.TIMEOUT, retryScheduled = true)
    private val failedFinal = RegistrationState.Failed(
        RegistrationFailure.AUTHENTICATION_FAILED,
        retryScheduled = false,
    )

    @Test
    fun `nothing registered and no call means stop`() {
        // The hard stop rule. A foreground service that outlives its purpose is a battery
        // bug, and a notification with nothing behind it teaches people to dismiss this
        // app's notifications.
        assertEquals(ServiceDecision.Stop, ServiceRunPolicy.decide(emptyMap<AccountId, RegistrationState>(), 0))
        assertEquals(ServiceDecision.Stop, ServiceRunPolicy.decide(registrations(RegistrationState.Unregistered), 0))
    }

    @Test
    fun `even a Stop decision yields a foreground reason`() {
        // The regression test for the crash a handset found on 2026-09-07:
        // ForegroundServiceDidNotStartInTimeException, with startForegroundCount:0.
        //
        // startForegroundService promises that startForeground will be called. The
        // service used to make that call only from its flow collector, and the
        // collector's first answer while somebody adds their FIRST account is Stop -
        // nothing is registered yet. So startForeground was never called at all and the
        // platform killed the process five seconds later.
        //
        // Every decision must therefore name a type to go foreground with, Stop included.
        val nothing = emptyMap<AccountId, RegistrationState>()
        assertEquals(ServiceDecision.Stop, ServiceRunPolicy.decide(nothing, activeCalls = 0))
        assertEquals(ServiceReason.REGISTRATION, ServiceDecision.Stop.foregroundReason())
    }

    @Test
    fun `a running decision keeps its own reason`() {
        assertEquals(
            ServiceReason.ACTIVE_CALL,
            ServiceDecision.Run(ServiceReason.ACTIVE_CALL).foregroundReason(),
        )
    }

    @Test
    fun `a failure only the user can fix does not keep the service alive on its own`() {
        // A wrong password is not coming back by itself; holding a foreground service
        // open for it is the battery drain §6 forbids.
        assertEquals(ServiceDecision.Stop, ServiceRunPolicy.decide(registrations(failedFinal), 0))
    }

    @Test
    fun `a failure that clears on its own keeps the service alive, without a wake lock`() {
        // The hole the old rule had (2026-09-18): Wi-Fi drops, every Registered account
        // becomes Failed(NETWORK_UNAVAILABLE), the rule said Stop, the service exited and
        // the platform killed the now-ordinary process within minutes - so when Wi-Fi
        // returned nothing was alive to re-register. The registration is *recovering*, and
        // the process must be there when the network is.
        val offline = registrations(
            RegistrationState.Failed(RegistrationFailure.NETWORK_UNAVAILABLE, retryScheduled = false),
        )
        val decision = assertIs<ServiceDecision.Run>(ServiceRunPolicy.decide(offline, 0))
        assertEquals(ServiceReason.REGISTRATION, decision.reason)
        assertIs<ServiceDecision.Run>(ServiceRunPolicy.decide(registrations(failedRetrying), 0))

        // Waiting costs no CPU - the recovery waits on the platform's connectivity callback -
        // so §6's wake-lock rule stays as narrow as it was.
        assertFalse(ServiceRunPolicy.justifiesWakeLock(offline, 0))
        assertFalse(ServiceRunPolicy.justifiesWakeLock(registrations(failedRetrying), 0))
    }

    @Test
    fun `a logged-in account keeps the service alive before the registrar has said anything`() {
        // The first seconds of a process the platform started - after a reboot, a sticky
        // restart or a task swipe: the account store says somebody is logged in, the stack
        // has not been told yet. Stopping here is a notification flashed on and off and a
        // restart that achieved nothing.
        val nothingYet = emptyMap<AccountId, RegistrationState>()
        val decision = assertIs<ServiceDecision.Run>(
            ServiceRunPolicy.decide(nothingYet, activeCalls = 0, wantsRegistration = true),
        )
        assertEquals(ServiceReason.REGISTRATION, decision.reason)
        assertIs<ServiceDecision.Run>(
            ServiceRunPolicy.decide(registrations(RegistrationState.Unregistered), 0, wantsRegistration = true),
        )

        // And the user's intent is the only thing that is not a wake-lock reason.
        assertFalse(ServiceRunPolicy.justifiesWakeLock(nothingYet, 0))
    }

    @Test
    fun `logged out everywhere is the one state that stops it`() {
        // wantsRegistration false: every account logged out, or none ever added.
        assertEquals(
            ServiceDecision.Stop,
            ServiceRunPolicy.decide(registrations(RegistrationState.Unregistered), 0, wantsRegistration = false),
        )
        assertEquals(ServiceDecision.Stop, ServiceRunPolicy.decide(emptyMap<AccountId, RegistrationState>(), 0, false))
    }

    @Test
    fun `a registered account runs the service for registration`() {
        val decision = assertIs<ServiceDecision.Run>(ServiceRunPolicy.decide(registrations(registered), 0))
        assertEquals(ServiceReason.REGISTRATION, decision.reason)
    }

    @Test
    fun `an in-flight registration keeps the service alive`() {
        // Stopping between the request and the response would kill the very attempt the
        // service exists to make.
        val decision = assertIs<ServiceDecision.Run>(ServiceRunPolicy.decide(registrations(registering), 0))
        assertEquals(ServiceReason.REGISTRATION, decision.reason)
    }

    @Test
    fun `an active call outranks everything`() {
        // Including a registration that has since failed - otherwise the call is killed
        // mid-sentence because the binding behind it lapsed.
        val decision = assertIs<ServiceDecision.Run>(
            ServiceRunPolicy.decide(registrations(failedFinal), activeCalls = 1),
        )
        assertEquals(ServiceReason.ACTIVE_CALL, decision.reason)

        val noAccounts = assertIs<ServiceDecision.Run>(
            ServiceRunPolicy.decide(emptyMap<AccountId, RegistrationState>(), activeCalls = 1),
        )
        assertEquals(ServiceReason.ACTIVE_CALL, noAccounts.reason)
    }

    @Test
    fun `logging out of the last account stops the service`() {
        // Task 29 asks logout to stop the service, and this is where that happens: the
        // use case does not reach for Android, it unregisters, and the policy the service
        // already watches sees nothing left to hold open. Doing it the other way round -
        // a logout that stopped the service directly - would kill it while a second
        // account was still registered, which the third case here rules out.
        val two = registrations(registered, registered)
        assertIs<ServiceDecision.Run>(ServiceRunPolicy.decide(two, 0))

        val afterFirstLogout = two + (AccountId("acct-0") to RegistrationState.Unregistered)
        assertIs<ServiceDecision.Run>(
            ServiceRunPolicy.decide(afterFirstLogout, 0),
            "one account logging out must not stop the service for the other",
        )

        val afterBoth = afterFirstLogout + (AccountId("acct-1") to RegistrationState.Unregistered)
        assertEquals(ServiceDecision.Stop, ServiceRunPolicy.decide(afterBoth, 0))
        assertFalse(ServiceRunPolicy.justifiesWakeLock(afterBoth, 0))
    }

    @Test
    fun `one registered account among failures still runs`() {
        val decision = assertIs<ServiceDecision.Run>(
            ServiceRunPolicy.decide(registrations(failedFinal, registered, failedRetrying), 0),
        )
        assertEquals(ServiceReason.REGISTRATION, decision.reason)
    }

    @Test
    fun `no wake lock is justified while unregistered`() {
        // §6 names this specifically, so it gets its own assertion rather than being
        // inferred from the decision above.
        assertFalse(ServiceRunPolicy.justifiesWakeLock(emptyMap<AccountId, RegistrationState>(), 0))
        assertFalse(ServiceRunPolicy.justifiesWakeLock(registrations(RegistrationState.Unregistered), 0))
        assertFalse(ServiceRunPolicy.justifiesWakeLock(registrations(failedFinal), 0))

        assertTrue(ServiceRunPolicy.justifiesWakeLock(registrations(registered), 0))
        assertTrue(ServiceRunPolicy.justifiesWakeLock(emptyMap<AccountId, RegistrationState>(), 1))
    }
}

class RegistrationSummaryFactoryTest {

    private fun registrations(vararg states: RegistrationState): Map<AccountId, RegistrationState> =
        states.mapIndexed { index, state -> AccountId("acct-$index") to state }.toMap()

    private val registered = RegistrationState.Registered(3_600)

    @Test
    fun `it never claims Registered while nothing is`() {
        // §6: the persistent notification is the most visible place that lie could appear.
        val summary = RegistrationSummaryFactory.summarise(
            registrations(RegistrationState.Unregistered),
            activeCalls = 0,
        )
        assertFalse("Ready" in summary.title, "claimed readiness with no registration: $summary")
    }

    @Test
    fun `a registered account reads as ready`() {
        val summary = RegistrationSummaryFactory.summarise(registrations(registered), 0)
        assertEquals("Ready for calls", summary.title)
        assertEquals("1 account registered", summary.text)
        assertFalse(summary.needsAttention)
    }

    @Test
    fun `a wrong password is surfaced above healthy accounts`() {
        // It is the only thing here the user can actually act on.
        val summary = RegistrationSummaryFactory.summarise(
            registrations(
                registered,
                RegistrationState.Failed(RegistrationFailure.AUTHENTICATION_FAILED, retryScheduled = false),
            ),
            activeCalls = 0,
        )
        assertEquals("Account needs attention", summary.title)
        assertEquals("Check your username and password", summary.text)
        assertTrue(summary.needsAttention)
    }

    @Test
    fun `a transient failure does not demand attention`() {
        val summary = RegistrationSummaryFactory.summarise(
            registrations(RegistrationState.Failed(RegistrationFailure.NETWORK_UNAVAILABLE, retryScheduled = true)),
            activeCalls = 0,
        )
        assertFalse(summary.needsAttention, "a network blip must not nag")
        assertEquals("Waiting for a network", summary.text)
    }

    @Test
    fun `a mixed state reports both halves rather than only the good news`() {
        val summary = RegistrationSummaryFactory.summarise(
            registrations(registered, RegistrationState.Failed(RegistrationFailure.TIMEOUT, retryScheduled = true)),
            activeCalls = 0,
        )
        assertEquals("1 registered, 1 reconnecting", summary.text)
    }

    @Test
    fun `an active call takes over the notification`() {
        val summary = RegistrationSummaryFactory.summarise(registrations(registered), activeCalls = 2)
        assertEquals("Call in progress", summary.title)
        assertEquals("2 active calls", summary.text)
    }

    @Test
    fun `every failure reason has its own wording`() {
        // "Error" tells the user nothing about what to do.
        val texts = RegistrationFailure.entries.map { reason ->
            RegistrationSummaryFactory.summarise(
                registrations(RegistrationState.Failed(reason, retryScheduled = true)),
                activeCalls = 0,
            ).text
        }
        assertEquals(RegistrationFailure.entries.size, texts.toSet().size, "two reasons share wording")
        assertTrue(texts.none { it.equals("error", ignoreCase = true) })
    }
}
