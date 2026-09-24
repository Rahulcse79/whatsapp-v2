package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When a call that was asked to join is mixed, and when it has to wait (ADR-009). */
class ConferenceJoinPolicyTest {

    private val a = CallId("a")
    private val b = CallId("b")
    private val c = CallId("c")
    private val d = CallId("d")

    @Test
    fun `a connected call joins the running conference with every current member`() {
        val calls = listOf(call(a), call(b), call(c))

        assertEquals(setOf(a, b, c), ConferenceJoinPolicy.plan(calls, wanted = setOf(c), mixed = setOf(a, b)))
    }

    @Test
    fun `a call nobody asked to have joined is left alone`() {
        // A consultation call for a transfer, a second call taken with "hold and answer":
        // neither is a request, and timing must not be read as one.
        val calls = listOf(call(a), call(b), call(c))

        assertNull(ConferenceJoinPolicy.plan(calls, wanted = emptySet(), mixed = setOf(a, b)))
    }

    @Test
    fun `a call still ringing waits`() {
        val calls = listOf(call(a), call(b), call(c, CallState.Outgoing.Ringing))

        assertNull(ConferenceJoinPolicy.plan(calls, wanted = setOf(c), mixed = setOf(a, b)))
    }

    @Test
    fun `a member the platform parked while the next one rang still joins`() {
        // Telecom holds every other call the instant one goes active. Two legs of a
        // group being called back: the first is held by the time the second connects,
        // and both are the conference. mixCalls resumes the held one.
        val calls = listOf(call(a, CallState.Held(HoldParty.LOCAL)), call(b))

        assertEquals(setOf(a, b), ConferenceJoinPolicy.plan(calls, wanted = setOf(a, b), mixed = emptySet()))
    }

    @Test
    fun `a call the far end holds cannot join yet`() {
        val calls = listOf(call(a), call(b), call(c, CallState.Held(HoldParty.REMOTE)))

        assertNull(ConferenceJoinPolicy.plan(calls, wanted = setOf(c), mixed = setOf(a, b)))
    }

    @Test
    fun `one call and no conference is not a conference yet`() {
        // The first leg of a group called back from history: nobody to join until the
        // second answers.
        assertNull(ConferenceJoinPolicy.plan(listOf(call(a)), wanted = setOf(a, b), mixed = emptySet()))
    }

    @Test
    fun `the call somebody is added to is in the conference they are added to`() {
        // "Add participant" is reached from a call, and that call is what becomes the
        // conference. Counting only the person named left the original party outside a
        // conference built out of their own call (1000/1001/1003/1005, 2026-09-24).
        val calls = listOf(call(a), call(b))

        assertEquals(setOf(a, b), ConferenceJoinPolicy.plan(calls, wanted = setOf(b), mixed = emptySet()))
    }

    @Test
    fun `a fourth participant joins the three already in the conference`() {
        // The step that was impossible: adding one at a time never reached four, because
        // the first Add mixed nobody and the second mixed only the two it had named.
        val calls = listOf(call(a), call(b), call(c), call(d))

        assertEquals(
            setOf(a, b, c, d),
            ConferenceJoinPolicy.plan(calls, wanted = setOf(d), mixed = setOf(a, b, c)),
        )
    }

    @Test
    fun `a call the far end holds is not dragged in as the conference being formed`() {
        // The anchor is what could be mixed now, not every call on the device: a call the
        // far end has held has no media to contribute and `mixCalls` would drop it.
        val calls = listOf(call(a, CallState.Held(HoldParty.REMOTE)), call(b), call(c))

        assertEquals(setOf(b, c), ConferenceJoinPolicy.plan(calls, wanted = setOf(c), mixed = emptySet()))
    }

    @Test
    fun `a ringing call is not counted as the conference being formed`() {
        // Adding a second participant while the first is still ringing: the ringing leg
        // joins when it answers, and until then there is no conference to form.
        val calls = listOf(call(a, CallState.Outgoing.Ringing), call(b))

        assertNull(ConferenceJoinPolicy.plan(calls, wanted = setOf(b), mixed = emptySet()))
    }

    @Test
    fun `a held conference is not resumed by somebody arriving`() {
        // The user stepped out of the room. A member arriving then must not drag them
        // back in; the join happens when they resume.
        val calls = listOf(call(a, CallState.Held(HoldParty.LOCAL)), call(b, CallState.Held(HoldParty.LOCAL)), call(c))

        assertNull(ConferenceJoinPolicy.plan(calls, wanted = setOf(c), mixed = setOf(a, b)))
    }

    @Test
    fun `nothing is asked of the engine when the membership would not change`() {
        val calls = listOf(call(a), call(b))

        assertNull(ConferenceJoinPolicy.plan(calls, wanted = setOf(a), mixed = setOf(a, b)))
    }

    @Test
    fun `the room holds eight, and a ninth is left as a call`() {
        val mixed = (1..8).map { CallId("m$it") }.toSet()
        val calls = mixed.map { call(it) } + call(d)

        assertNull(ConferenceJoinPolicy.plan(calls, wanted = setOf(d), mixed = mixed))
    }

    private fun call(id: CallId, state: CallState = CallState.Connected()) = CallSnapshot(
        callId = id,
        accountId = AccountId("acct"),
        remote = SipUri.parse("sip:${id.value}@example.com").getOrNull()!!,
        remoteDisplayName = null,
        direction = CallDirection.OUTGOING,
        state = state,
        media = MediaProfile.AUDIO,
        startedAtEpochMillis = 0L,
        connectedAtEpochMillis = 1L,
    )
}
