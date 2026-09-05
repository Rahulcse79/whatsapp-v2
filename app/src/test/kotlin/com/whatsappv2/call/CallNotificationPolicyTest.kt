package com.whatsappv2.call

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Which notification the foreground service shows (Task 37). */
class CallNotificationPolicyTest {

    @Test
    fun `no calls means no call notification`() {
        assertEquals(CallNotification.None, CallNotificationPolicy.decide(emptyList()))
    }

    @Test
    fun `a ringing call is the one to show`() {
        val decision = CallNotificationPolicy.decide(listOf(ringing()))

        assertIs<CallNotification.Incoming>(decision)
    }

    @Test
    fun `a ringing call outranks one already in progress`() {
        // The established call is not going anywhere; the ringing one needs a decision
        // now, and burying it under the other is how calls get missed (Task 56 builds
        // call waiting on this).
        val decision = CallNotificationPolicy.decide(listOf(connected(), ringing()))

        assertIs<CallNotification.Incoming>(decision)
    }

    @Test
    fun `an established call shows as ongoing`() {
        val decision = CallNotificationPolicy.decide(listOf(connected()))

        assertIs<CallNotification.Ongoing>(decision)
    }

    @Test
    fun `the oldest call wins among equals, so the card does not shuffle`() {
        val first = connected(id = "first", startedAt = 1_000)
        val second = connected(id = "second", startedAt = 2_000)

        val decision = CallNotificationPolicy.decide(listOf(second, first))

        assertEquals(CallId("first"), (decision as CallNotification.Ongoing).call.callId)
    }

    @Test
    fun `a terminated call is not shown at all`() {
        val ended = connected().copy(state = CallState.Terminated(HangupReason.REMOTE_HANGUP))

        assertEquals(CallNotification.None, CallNotificationPolicy.decide(listOf(ended)))
    }

    private fun ringing(id: String = "in-1") = snapshot(id, CallState.Incoming(REMOTE), CallDirection.INCOMING)

    private fun connected(id: String = "out-1", startedAt: Long = 1_000) =
        snapshot(id, CallState.Connected(), CallDirection.OUTGOING, startedAt)

    private fun snapshot(
        id: String,
        state: CallState,
        direction: CallDirection,
        startedAt: Long = 1_000,
    ) = CallSnapshot(
        callId = CallId(id),
        accountId = AccountId("acct-1"),
        remote = REMOTE,
        remoteDisplayName = null,
        direction = direction,
        state = state,
        media = MediaProfile.AUDIO,
        startedAtEpochMillis = startedAt,
        connectedAtEpochMillis = null,
    )

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
    }
}
