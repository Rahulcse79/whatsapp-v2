package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.model.CallId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Local mixing at the engine, where a resume actually takes time (ADR-009).
 *
 * `FakeSipEngine` resumes synchronously, so the ViewModel's merge tests cannot see the
 * defect these cover: a resume is a **re-INVITE**, and a call stays Held until the far end
 * answers, then passes through Resuming before media flows. The fake gateway here models
 * that faithfully — `resumeCall` emits RESUMING and leaves the rest to the test — which is
 * why this is the level that guards it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PjsipSipEngineConferenceTest {

    private val fixture = PjsipSipEngineTest()

    @Test
    fun `merging waits for a held call to finish resuming before it mixes`() = runTest {
        // The first run on hardware reported "Mixing 1 call(s)" and wired only
        // microphone-to-call links, never call-to-call, because the call list was read in
        // the same breath as the resume was issued.
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()

        val merging = async { engine.mixCalls(ids) }
        runCurrent()

        assertTrue(
            fixture.gateway.conferenceMemberships.isEmpty(),
            "mixed before the resume landed: ${fixture.gateway.conferenceMemberships}",
        )

        // The far end answers the re-INVITE and media runs again.
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()

        val mixed = assertIs<Outcome.Success<Set<CallId>>>(merging.await())
        assertEquals(ids, mixed.value, "both calls should be mixed once media is running")
        assertEquals(ids.mapTo(mutableSetOf()) { it.value }, fixture.gateway.conferenceMemberships.last())
        engine.stop()
    }

    @Test
    fun `the mixed set is published while the conference lasts, and empties when it is a call again`() = runTest {
        // What keeps Telecom from breaking the mix: the platform holds every other active
        // call the moment one goes active, and the bridge that carries its holds reads
        // this set to decline them for a member (ADR-009's "RX 0pkt on two legs").
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()
        assertEquals(emptySet(), engine.mixedCalls.value)

        val merging = async { engine.mixCalls(ids) }
        runCurrent()
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()
        merging.await()

        assertEquals(ids, engine.mixedCalls.value)

        // One member hangs up. One left is a call, not a conference, so the set empties
        // rather than naming a lone member the bridge would then refuse to hold.
        fixture.gateway.emitCall(ids.first().value, StackCallState.ENDED)
        advanceUntilIdle()

        assertEquals(emptySet(), engine.mixedCalls.value)
        engine.stop()
    }

    @Test
    fun `mixing more than eight is refused here, not by a native error later`() = runTest {
        // PJSUA_MAX_CALLS refusing the ninth call is a native failure at the wrong moment;
        // the ceiling is a number the user can be told about (ADR-009).
        val engine = with(fixture) { twoCallsOneHeld() }
        val tooMany = (1..9).mapTo(mutableSetOf()) { CallId("call-$it") }

        val result = engine.mixCalls(tooMany)

        assertIs<Outcome.Failure<*>>(result)
        assertTrue(fixture.gateway.conferenceMemberships.isEmpty(), "the stack was asked anyway")
        engine.stop()
    }
}
