package com.whatsappv2.domain.call

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 56, and the ordering is the whole of it.
 *
 * "Never two active" is a statement about the sequence of steps, not about the state that
 * comes out the other end — which is why the policy returns the steps and why these tests
 * read them in order rather than inspecting a result.
 */
class CallWaitingPolicyTest {

    private val remote = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    private fun call(id: String, state: CallState) = CallSnapshot(
        callId = CallId(id),
        accountId = AccountId("acct-1"),
        remote = remote,
        remoteDisplayName = null,
        direction = CallDirection.INCOMING,
        state = state,
        media = MediaProfile.AUDIO,
        startedAtEpochMillis = 0,
        connectedAtEpochMillis = 0,
    )

    private val first = call("first", CallState.Connected())
    private val second = CallId("second")

    @Test
    fun `rejecting a second call sends 486, because the user is on another call`() {
        val steps = CallWaitingPolicy.respondTo(listOf(first), second, SecondCallResponse.REJECT)

        assertEquals(listOf(CallStep.Reject(second, HangupReason.BUSY)), steps)
    }

    @Test
    fun `accept-and-hold holds the first call before answering the second`() {
        val steps = CallWaitingPolicy.respondTo(listOf(first), second, SecondCallResponse.ACCEPT_AND_HOLD)

        // The order is the assertion. Answering first would be two live microphones.
        assertEquals(listOf(CallStep.Hold(first.callId), CallStep.Answer(second)), steps)
    }

    @Test
    fun `accept-and-end hangs the first call up before answering the second`() {
        val steps = CallWaitingPolicy.respondTo(listOf(first), second, SecondCallResponse.ACCEPT_AND_END)

        assertEquals(
            listOf(CallStep.Hangup(first.callId, HangupReason.LOCAL_HANGUP), CallStep.Answer(second)),
            steps,
        )
    }

    @Test
    fun `a call already held is not held again`() {
        val held = call("first", CallState.Held(HoldParty.LOCAL))

        val steps = CallWaitingPolicy.respondTo(listOf(held), second, SecondCallResponse.ACCEPT_AND_HOLD)

        assertEquals(listOf(CallStep.Answer(second)), steps)
    }

    @Test
    fun `a call the far end is holding is still held by us before we answer`() {
        // Their hold is not ours; ours is what stops our microphone reaching them.
        val heldByThem = call("first", CallState.Held(HoldParty.REMOTE))

        val steps = CallWaitingPolicy.respondTo(listOf(heldByThem), second, SecondCallResponse.ACCEPT_AND_HOLD)

        assertEquals(listOf(CallStep.Hold(heldByThem.callId), CallStep.Answer(second)), steps)
    }

    @Test
    fun `with no other call, accepting is simply answering`() {
        val steps = CallWaitingPolicy.respondTo(emptyList(), second, SecondCallResponse.ACCEPT_AND_HOLD)

        assertEquals(listOf(CallStep.Answer(second)), steps)
    }

    @Test
    fun `a call that has ended is not held or hung up again`() {
        val ended = call("first", CallState.Terminated(HangupReason.REMOTE_HANGUP))

        val steps = CallWaitingPolicy.respondTo(listOf(ended), second, SecondCallResponse.ACCEPT_AND_END)

        assertEquals(listOf(CallStep.Answer(second)), steps)
    }

    // ================================================================ swapping

    @Test
    fun `swapping holds the live call before resuming the held one`() {
        val held = call("held", CallState.Held(HoldParty.LOCAL))

        val steps = CallWaitingPolicy.swapTo(listOf(first, held), held.callId)

        assertEquals(listOf(CallStep.Hold(first.callId), CallStep.Resume(held.callId)), steps)
    }

    @Test
    fun `swapping to the call that is already live does nothing`() {
        val held = call("held", CallState.Held(HoldParty.LOCAL))

        assertTrue(CallWaitingPolicy.swapTo(listOf(first, held), first.callId).isEmpty())
    }

    @Test
    fun `swapping to a call that is not there does nothing`() {
        assertTrue(CallWaitingPolicy.swapTo(listOf(first), CallId("gone")).isEmpty())
    }

    @Test
    fun `every other call is held, so a third can never be left active`() {
        val second = call("second", CallState.Connected())
        val third = call("third", CallState.Held(HoldParty.LOCAL))

        val steps = CallWaitingPolicy.swapTo(listOf(first, second, third), third.callId)

        assertEquals(
            listOf(CallStep.Hold(first.callId), CallStep.Hold(second.callId), CallStep.Resume(third.callId)),
            steps,
        )
    }

    @Test
    fun `swapping to an ended call does nothing`() {
        val ended = call("ended", CallState.Terminated(HangupReason.REMOTE_HANGUP))

        assertTrue(CallWaitingPolicy.swapTo(listOf(first, ended), ended.callId).isEmpty())
    }
}
