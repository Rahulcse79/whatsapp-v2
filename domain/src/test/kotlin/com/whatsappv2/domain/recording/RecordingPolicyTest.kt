package com.whatsappv2.domain.recording

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 58's first done-when: off by default, and no way to start without consent.
 *
 * The "by default" half is a property of the type rather than of a value — there is no
 * [RecordingConsent] a *previous* call could leave behind that makes this one recordable,
 * and the third test is what proves it.
 */
class RecordingPolicyTest {

    private val callId = CallId("call-1")
    private val other = CallId("call-2")
    private val connected = CallState.Connected()
    private val remote = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    private fun consent(forCall: CallId = callId) =
        RecordingConsent.GrantedByLocalUser(forCall, grantedAtEpochMillis = 1_000)

    @Test
    fun `with consent on an established call, recording is allowed`() {
        assertNull(
            RecordingPolicy.mayRecord(callId, connected, consent(), platformSupported = true),
        )
    }

    @Test
    fun `without consent it is refused, and that is the default state`() {
        val refusal = RecordingPolicy.mayRecord(
            callId,
            connected,
            RecordingConsent.None,
            platformSupported = true,
        )

        assertIs<RecordingRefusal.NoConsent>(refusal)
    }

    @Test
    fun `consent given on another call does not carry to this one`() {
        // The whole reason consent is per call: a user who agreed to record one
        // conversation has not agreed to record the next person who rings.
        val refusal = RecordingPolicy.mayRecord(
            callId,
            connected,
            consent(forCall = other),
            platformSupported = true,
        )

        assertEquals(RecordingRefusal.ConsentForAnotherCall(other), refusal)
    }

    @Test
    fun `a ringing call has nothing to record`() {
        val refusal = RecordingPolicy.mayRecord(
            callId,
            CallState.Incoming(remote),
            consent(),
            platformSupported = true,
        )

        assertIs<RecordingRefusal.CallNotEstablished>(refusal)
    }

    @Test
    fun `an ended call has nothing to record`() {
        val refusal = RecordingPolicy.mayRecord(
            callId,
            CallState.Terminated(HangupReason.LOCAL_HANGUP),
            consent(),
            platformSupported = true,
        )

        assertIs<RecordingRefusal.CallNotEstablished>(refusal)
    }

    @Test
    fun `a platform that cannot capture says so rather than pretending`() {
        val refusal = RecordingPolicy.mayRecord(callId, connected, consent(), platformSupported = false)

        assertIs<RecordingRefusal.NotSupportedOnThisPlatform>(refusal)
    }

    @Test
    fun `consent is checked before the platform, because it is the answer about the user`() {
        val refusal = RecordingPolicy.mayRecord(
            callId,
            connected,
            RecordingConsent.None,
            platformSupported = false,
        )

        assertIs<RecordingRefusal.NoConsent>(refusal)
    }

    @Test
    fun `a held call keeps recording, because it still has a dialog and a file`() {
        assertNull(
            RecordingPolicy.mayRecord(
                callId,
                CallState.Held(HoldParty.LOCAL, CallControls.DEFAULT),
                consent(),
                platformSupported = true,
            ),
        )
        assertFalse(RecordingPolicy.mustStop(CallState.Held(HoldParty.LOCAL)))
    }

    @Test
    fun `a recording id must not be blank, and renders as itself`() {
        assertEquals("rec-1", RecordingId("rec-1").toString())
        assertFailsWith<IllegalArgumentException> { RecordingId("") }
        assertFailsWith<IllegalArgumentException> { RecordingId("  ") }
    }

    @Test
    fun `a recording's duration is measured from its own timestamps, never negative`() {
        val recording = Recording(
            id = RecordingId("rec-1"),
            callId = callId,
            startedAtEpochMillis = 1_000,
            endedAtEpochMillis = 4_000,
            sizeBytes = 2_048,
        )

        assertEquals(3_000L, recording.durationMillis)
        // A clock that went backwards is a clock, not a negative recording.
        assertEquals(0L, recording.copy(endedAtEpochMillis = 0).durationMillis)
    }

    @Test
    fun `a recording must stop when its call leaves the established states`() {
        assertTrue(RecordingPolicy.mustStop(CallState.Terminated(HangupReason.REMOTE_HANGUP)))
        assertTrue(RecordingPolicy.mustStop(CallState.Incoming(remote)))
        assertTrue(RecordingPolicy.mustStop(CallState.Idle))
        assertFalse(RecordingPolicy.mustStop(connected))
    }
}
