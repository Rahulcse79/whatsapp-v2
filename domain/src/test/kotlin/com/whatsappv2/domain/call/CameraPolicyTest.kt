package com.whatsappv2.domain.call

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 51's first done-when, asserted where it can actually be asserted.
 *
 * "The camera is released on hangup, on error, and on process death" is a claim about
 * every terminal path, and no instrumented test can check it — an emulator will not tell
 * you a capture device was left open. Because the policy is a pure function of the call
 * list, the paths can be enumerated here instead.
 */
class CameraPolicyTest {

    private val remote = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    private fun call(
        id: String = "call-1",
        state: CallState,
        media: MediaProfile = MediaProfile.AUDIO_VIDEO,
    ) = CallSnapshot(
        callId = CallId(id),
        accountId = AccountId("acct-1"),
        remote = remote,
        remoteDisplayName = null,
        direction = CallDirection.OUTGOING,
        state = state,
        media = media,
        startedAtEpochMillis = 0,
        connectedAtEpochMillis = 0,
    )

    private val sending = CallControls(isVideoEnabled = true)

    @Test
    fun `a connected video call with video on holds the camera`() {
        val calls = listOf(call(state = CallState.Connected(sending)))

        assertEquals(CallId("call-1"), CameraPolicy.ownerOf(calls))
        assertTrue(CameraPolicy.shouldCapture(calls))
    }

    @Test
    fun `no calls means no camera, which is every terminal path at once`() {
        assertNull(CameraPolicy.ownerOf(emptyList()))
        assertFalse(CameraPolicy.shouldCapture(emptyList()))
    }

    @Test
    fun `a call that has ended does not hold the camera`() {
        // The engine drops terminated calls from the list, but assert the state too: a
        // snapshot that lingered for a frame must not keep the device.
        val calls = listOf(call(state = CallState.Terminated(HangupReason.REMOTE_HANGUP)))

        assertNull(CameraPolicy.ownerOf(calls))
    }

    @Test
    fun `an outgoing video call holds the camera before it is answered`() {
        // This assertion used to be the opposite, and the opposite was the bug behind
        // "video calling does not work". liblinphone builds the SDP offer at INVITE time
        // and can only offer to *send* video if the capture device is already running;
        // with the camera released until the call was established, every outgoing video
        // call went out `recvonly` and no later change re-negotiated it.
        val calls = listOf(call(state = CallState.Outgoing.Ringing))

        assertEquals(CallId("call-1"), CameraPolicy.ownerOf(calls))
    }

    @Test
    fun `an outgoing audio call never opens the camera, ringing or not`() {
        // The profile still decides. Placing an ordinary call must not turn a camera on.
        val calls = listOf(call(state = CallState.Outgoing.Ringing, media = MediaProfile.AUDIO))

        assertNull(CameraPolicy.ownerOf(calls))
    }

    @Test
    fun `an incoming video call does not open the camera while it rings`() {
        // The asymmetry with an outgoing call is deliberate. Somebody who pressed "video
        // call" expects their camera on; somebody whose phone is ringing has agreed to
        // nothing, and §5.2 puts the preview on the call rather than on the alert.
        val calls = listOf(call(state = CallState.Incoming(remote)))

        assertNull(CameraPolicy.ownerOf(calls))
    }

    @Test
    fun `video mute releases the camera while the call carries on`() {
        // Task 53: video mute stops the outbound stream and audio continues. The call is
        // still a video call - the stream is negotiated - so only the control says stop.
        val calls = listOf(call(state = CallState.Connected(CallControls(isVideoEnabled = false))))

        assertNull(CameraPolicy.ownerOf(calls))
    }

    @Test
    fun `an audio call never holds the camera, whatever its controls say`() {
        val calls = listOf(call(state = CallState.Connected(sending), media = MediaProfile.AUDIO))

        assertNull(CameraPolicy.ownerOf(calls))
    }

    @Test
    fun `a held video call keeps the camera, because it is still ours to resume`() {
        // Held on its own: the call is paused, not gone, and taking the camera away would
        // mean re-acquiring it on resume - a visible stall at the worst moment.
        val calls = listOf(call(state = CallState.Held(HoldParty.LOCAL, sending)))

        assertEquals(CallId("call-1"), CameraPolicy.ownerOf(calls))
    }

    @Test
    fun `with two video calls the connected one wins and the held one lets go`() {
        val calls = listOf(
            call(id = "held", state = CallState.Held(HoldParty.LOCAL, sending)),
            call(id = "live", state = CallState.Connected(sending)),
        )

        // One camera, one call. Never both.
        assertEquals(CallId("live"), CameraPolicy.ownerOf(calls))
    }

    @Test
    fun `a transferring call still holds the camera`() {
        val calls = listOf(
            call(
                state = CallState.Transferring(type = TransferType.BLIND, controls = sending),
            ),
        )

        assertEquals(CallId("call-1"), CameraPolicy.ownerOf(calls))
    }
}
