package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.IncomingCall
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The platform fake, which is the only way Telecom's rules are testable at all.
 *
 * Every engine test that asserts "the connection exists before the INVITE", "a refusal is
 * honoured", or "Telecom was told the call is held" is really asserting against this
 * recording. A fake that quietly lost a call would turn those assertions into ones that
 * cannot fail, so what it records is worth pinning here.
 */
class FakePlatformCallRegistryTest {

    private val registry = FakePlatformCallRegistry()
    private val callId = CallId("call-1")

    @Test
    fun `a refusal is what the test asked for, not what the fake prefers`() = runTest {
        registry.permitOutgoing = false
        registry.permitIncoming = false

        assertFalse(registry.registerOutgoing(snapshot()))
        assertFalse(registry.registerIncoming(incoming()))
        // Recorded even when refused: "the platform was asked and said no" and "the
        // platform was never asked" are different bugs.
        assertEquals(1, registry.registeredOutgoing.size)
        assertEquals(1, registry.registeredIncoming.size)
    }

    @Test
    fun `the ordering hook runs before the answer is given`() = runTest {
        // This is what makes Task 35's "Telecom first, INVITE second" observable: once
        // both calls have returned, nothing outside says which happened first.
        var asked = false
        registry.onRegisterOutgoing = { asked = true }

        registry.registerOutgoing(snapshot())

        assertTrue(asked)
    }

    @Test
    fun `hold is a log and mute is a state, because the questions are different`() {
        // "Was it held and then resumed" needs order; "is the microphone muted right now"
        // needs the latest value, and a log could not answer the second without a scan.
        registry.onHoldChanged(callId, held = true)
        registry.onHoldChanged(callId, held = false)
        registry.setMuted(callId, muted = true)
        registry.setMuted(callId, muted = false)

        assertEquals(listOf(callId to true, callId to false), registry.holdChanges)
        assertEquals(false, registry.muted[callId])
    }

    @Test
    fun `connections and endings are recorded with their reasons`() {
        registry.onConnected(callId)
        registry.onEnded(callId, HangupReason.REMOTE_HANGUP)

        assertEquals(listOf(callId), registry.connected)
        assertEquals(listOf(callId to HangupReason.REMOTE_HANGUP), registry.ended)
    }

    @Test
    fun `a route outside the available set is refused, and still recorded as asked`() = runTest {
        registry.availableRoutes = setOf(AudioRoute.EARPIECE)

        assertTrue(registry.requestAudioRoute(callId, AudioRoute.EARPIECE))
        assertFalse(registry.requestAudioRoute(callId, AudioRoute.BLUETOOTH))
        assertEquals(2, registry.requestedRoutes.size)
    }

    private fun snapshot() = CallSnapshot(
        callId = callId,
        accountId = ACCOUNT,
        remote = REMOTE,
        remoteDisplayName = null,
        direction = CallDirection.OUTGOING,
        state = CallState.Outgoing.Calling,
        media = MediaProfile.AUDIO,
        startedAtEpochMillis = 0L,
        connectedAtEpochMillis = null,
    )

    private fun incoming() = IncomingCall(
        callId = callId,
        accountId = ACCOUNT,
        from = REMOTE,
        fromDisplayName = null,
        offeredMedia = MediaProfile.AUDIO,
        receivedAtEpochMillis = 0L,
    )

    private companion object {
        val ACCOUNT = AccountId("acct-1")
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
    }
}
