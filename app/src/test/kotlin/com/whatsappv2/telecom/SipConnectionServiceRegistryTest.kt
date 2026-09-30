package com.whatsappv2.telecom

import com.whatsappv2.domain.model.CallId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registry Telecom's callbacks and the engine share, across an unbinding.
 *
 * Only the companion object is exercised, which is the whole of the state that matters: the
 * service instance is constructed and destroyed by Telecom on its own schedule, so anything
 * that has to survive that lives in static maps — and all of it is reachable without an
 * Android runtime.
 *
 * Those maps outlive a test for the same reason they outlive the service, so each test names
 * its own [CallId], counts placements against [baseline] rather than against zero, and gives
 * back what it registered in [drainWhatThisTestRegistered].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SipConnectionServiceRegistryTest {

    private val baseline = SipConnectionService.unanswered()
    private val registered = mutableListOf<CallId>()

    private fun expect(name: String) = CallId(name)
        .also { registered += it }
        .let { it to SipConnectionService.expect(it) }

    @AfterTest
    fun drainWhatThisTestRegistered() {
        registered.forEach { SipConnectionService.forget(it) }
        // `forget` leaves an abandon mark, which is what cancels a late connection in
        // production; consumed here so it cannot reach another test.
        registered.forEach { SipConnectionService.wasAbandoned(it) }
    }

    @Test
    fun `an unbinding does not answer a placement Telecom has already accepted`() = runTest {
        // Phase 4, measured 2026-09-27: the mesh collapsing from three to two promotes its
        // surviving leg to Telecom, and Telecom unbinds in the same instant, because the leg
        // that *ended* had been holding the only connection. Settling the waiter here reported
        // a refusal Telecom never made; the placement then arrived on the rebind as a
        // connection nobody was waiting for, sat DIALING for the life of the process, and every
        // later call was told the phone was on another call.
        val (_, waiter) = expect("unbind-does-not-answer")

        SipConnectionService.releaseOnUnbind()

        assertFalse(waiter.isCompleted, "an unbind settled a placement that was still in flight")
        assertEquals(
            baseline + 1, SipConnectionService.unanswered(),
            "the placement stopped being tracked",
        )
    }

    @Test
    fun `a connection arriving after the unbind still answers the caller that asked`() = runTest {
        // The other half: having kept the waiter, the rebind must be able to complete it. Without
        // this the fix would only trade a wrong answer for a slow one -- the same unmanaged call,
        // reached three seconds later through the timeout.
        val (promoted, waiter) = expect("rebind-answers")
        SipConnectionService.releaseOnUnbind()

        SipConnectionService.settle(promoted, created = true)

        assertTrue(waiter.isCompleted, "the rebind could not answer the waiter it kept")
        assertTrue(waiter.getCompleted(), "a placement Telecom accepted was reported as refused")
        assertEquals(baseline, SipConnectionService.unanswered(), "a settled placement stayed pending")
    }

    @Test
    fun `a placement given up on cancels the connection that arrives for it`() = runTest {
        // Unchanged behaviour, asserted because the fix above deliberately leans on it: when the
        // rebind never comes, `TelecomCallRegistry.awaitDecision`'s own timeout is what gives up,
        // and it is this mark that stops the late connection becoming a phantom.
        val (promoted, _) = expect("given-up-on")

        SipConnectionService.forget(promoted)

        assertEquals(baseline, SipConnectionService.unanswered(), "a forgotten placement stayed pending")
        assertTrue(
            SipConnectionService.wasAbandoned(promoted),
            "a placement given up on was not marked, so a late connection would be kept",
        )
    }
}
