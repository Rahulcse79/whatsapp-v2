@file:OptIn(ExperimentalCoroutinesApi::class)

package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Video, the camera lifecycle, and escalation, in the real engine (Tasks 51, 53, 54).
 *
 * The camera assertions read [FakeSipCoreGateway.cameraCaptureChanges] as an ordered
 * log rather than a flag, because the property being asserted is a sequence: acquired when
 * a call needs it, released when it does not — and "released" is only distinguishable from
 * "never acquired" if the order is visible.
 */
class PjsipSipEngineVideoTest : PjsipSipEngineFixture() {

    // ================================================================ Task 51

    @Test
    fun `the camera is not claimed for an audio call`() = runTest {
        connectedCall()

        assertTrue(gateway.cameraCaptureChanges.none { it })
    }

    @Test
    fun `the camera is claimed when a video call starts sending, and released when it ends`() = runTest {
        val engine = registeredEngine()
        val callId = requireNotNull(
            engine.placeCall(account.id, TARGET, MediaProfile.AUDIO_VIDEO).getOrNull(),
        )
        runCurrent()
        gateway.emitCall(callId.value, StackCallState.CONNECTED, videoActive = true)
        runCurrent()
        engine.setVideoEnabled(callId, enabled = true)

        assertTrue(gateway.cameraCaptureChanges.contains(true))

        engine.hangup(callId, HangupReason.LOCAL_HANGUP)

        // Released on the hangup, and the log proves it was held first.
        assertEquals(false, gateway.cameraCaptureChanges.last())
    }

    @Test
    fun `a video call placed as one sends video without anybody toggling it`() = runTest {
        // The regression test for "video calling is not working", and it fails on the
        // parent commit. `CallControls.isVideoEnabled` defaults to false and, until
        // `adoptNegotiatedVideo` existed, the only thing that ever set it was the in-call
        // video button. So a call placed *as* a video call connected with video negotiated
        // and its own controls saying video was off — and CameraPolicy, which reads those
        // controls, never claimed the camera. Note that nothing below calls
        // setVideoEnabled: that call is what used to paper over this.
        val engine = registeredEngine()
        val callId = requireNotNull(
            engine.placeCall(account.id, TARGET, MediaProfile.AUDIO_VIDEO).getOrNull(),
        )
        runCurrent()
        gateway.emitCall(callId.value, StackCallState.CONNECTED, videoActive = true)
        runCurrent()

        val call = engine.activeCalls.value.single()
        assertEquals(true, call.state.controlsOrNull?.isVideoEnabled)
        assertTrue(gateway.cameraCaptureChanges.contains(true))
    }

    @Test
    fun `the camera is capturing before the INVITE, so the offer can send`() = runTest {
        // The stack writes the SDP offer when the INVITE goes out. With capture off it
        // can only offer recvonly, and no later change re-negotiates it — which is why
        // waiting for the call to be established was too late.
        val engine = registeredEngine()
        gateway.cameraCaptureChanges.clear()

        engine.placeCall(account.id, TARGET, MediaProfile.AUDIO_VIDEO)
        runCurrent()

        assertEquals(listOf(true), gateway.cameraCaptureChanges)
    }

    @Test
    fun `an audio call still claims no camera when it is placed`() = runTest {
        val engine = registeredEngine()
        gateway.cameraCaptureChanges.clear()

        engine.placeCall(account.id, TARGET, MediaProfile.AUDIO)
        runCurrent()

        assertTrue(gateway.cameraCaptureChanges.none { it })
    }

    @Test
    fun `the camera is released when the stack tears the call down`() = runTest {
        val engine = videoCall()
        gateway.cameraCaptureChanges.clear()

        gateway.emitCall(activeCallId(engine).value, StackCallState.ERROR, statusCode = 503)
        runCurrent()

        // An error is not a hangup, and a release written beside the hangup button would
        // miss it. Deriving the owner from the call list does not (Task 51).
        assertEquals(listOf(false), gateway.cameraCaptureChanges)
    }

    @Test
    fun `stopping the stack releases the camera, which is the process-death path`() = runTest {
        val engine = videoCall()
        gateway.cameraCaptureChanges.clear()

        engine.stop()

        assertEquals(listOf(false), gateway.cameraCaptureChanges)
    }

    @Test
    fun `video mute releases the camera while the call carries on`() = runTest {
        val engine = videoCall()
        val callId = activeCallId(engine)
        gateway.cameraCaptureChanges.clear()

        engine.setVideoEnabled(callId, enabled = false)

        assertEquals(listOf(false), gateway.cameraCaptureChanges)
        // Task 53: audio continues. The call is still there and still connected.
        assertIs<CallState.Connected>(engine.activeCalls.value.single().state)
    }

    @Test
    fun `turning video on with no camera is refused rather than silently doing nothing`() = runTest {
        val engine = connectedCall()
        val callId = activeCallId(engine)
        camera.usable = false

        val result = engine.setVideoEnabled(callId, enabled = true)

        assertIs<SipError.InvalidState>(assertIs<Outcome.Failure<SipError>>(result).error)
    }

    // ================================================================ Task 53

    @Test
    fun `switching the camera reaches the stack while video is running`() = runTest {
        val engine = videoCall()

        assertIs<Outcome.Success<Unit>>(engine.switchCamera(activeCallId(engine)))
        assertEquals(1, gateway.cameraSwitches.size)
    }

    @Test
    fun `switching the camera on an audio call is refused and never reaches the stack`() = runTest {
        val engine = connectedCall()

        val result = engine.switchCamera(activeCallId(engine))

        assertIs<Outcome.Failure<SipError>>(result)
        assertTrue(gateway.cameraSwitches.isEmpty())
    }

    // ================================================================ Task 54

    @Test
    fun `an escalation is held for an answer rather than accepted on arrival`() = runTest {
        val engine = connectedCall()
        val callId = activeCallId(engine)

        gateway.emitCall(callId.value, StackCallState.UPDATED_BY_REMOTE, videoOffered = true)
        runCurrent()

        // §5.2: nothing negotiated and no camera until somebody is asked.
        assertTrue(gateway.videoUpdateAnswers.isEmpty())
        assertTrue(gateway.cameraCaptureChanges.none { it })
        assertEquals(MediaProfile.AUDIO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `a re-INVITE that does not offer video is accepted without asking anybody`() = runTest {
        val engine = connectedCall()
        val callId = activeCallId(engine)

        gateway.emitCall(callId.value, StackCallState.UPDATED_BY_REMOTE, videoOffered = false)
        runCurrent()

        // A codec change is not a question for the user.
        assertEquals(listOf(callId.value to false), gateway.videoUpdateAnswers)
    }

    @Test
    fun `accepting an escalation answers the re-INVITE with video`() = runTest {
        val engine = connectedCall()
        val callId = activeCallId(engine)
        gateway.emitCall(callId.value, StackCallState.UPDATED_BY_REMOTE, videoOffered = true)
        runCurrent()

        assertIs<Outcome.Success<Unit>>(engine.respondToVideoRequest(callId, accept = true))

        assertEquals(listOf(callId.value to true), gateway.videoUpdateAnswers)
    }

    @Test
    fun `declining an escalation answers without video and keeps the call`() = runTest {
        val engine = connectedCall()
        val callId = activeCallId(engine)
        gateway.emitCall(callId.value, StackCallState.UPDATED_BY_REMOTE, videoOffered = true)
        runCurrent()

        assertIs<Outcome.Success<Unit>>(engine.respondToVideoRequest(callId, accept = false))

        assertEquals(listOf(callId.value to false), gateway.videoUpdateAnswers)
        // Task 54's second done-when.
        assertIs<CallState.Connected>(engine.activeCalls.value.single().state)
    }

    @Test
    fun `accepting with no camera answers without video rather than promising one`() = runTest {
        val engine = connectedCall()
        val callId = activeCallId(engine)
        camera.usable = false
        gateway.emitCall(callId.value, StackCallState.UPDATED_BY_REMOTE, videoOffered = true)
        runCurrent()

        engine.respondToVideoRequest(callId, accept = true)

        assertEquals(listOf(callId.value to false), gateway.videoUpdateAnswers)
    }

    @Test
    fun `answering an escalation nobody offered is refused`() = runTest {
        val engine = connectedCall()

        val result = engine.respondToVideoRequest(activeCallId(engine), accept = true)

        assertIs<SipError.InvalidState>(assertIs<Outcome.Failure<SipError>>(result).error)
    }

    @Test
    fun `the negotiated media follows the wire once video is running`() = runTest {
        val engine = connectedCall()
        val callId = activeCallId(engine)

        gateway.emitCall(callId.value, StackCallState.STREAMS_RUNNING, videoActive = true)
        runCurrent()

        assertEquals(MediaProfile.AUDIO_VIDEO, engine.activeCalls.value.single().media)
    }

    @Test
    fun `a ringing call keeps the profile it was placed with`() = runTest {
        val engine = registeredEngine()
        val callId = requireNotNull(
            engine.placeCall(account.id, TARGET, MediaProfile.AUDIO_VIDEO).getOrNull(),
        )
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.OUTGOING_RINGING, videoActive = false)
        runCurrent()

        // Before an answer the stack's params describe an offer, not a negotiation.
        assertEquals(MediaProfile.AUDIO_VIDEO, engine.activeCalls.value.single().media)
    }

    // ================================================================ helpers

    /** A connected call with video negotiated and sending. */
    private suspend fun TestScope.videoCall(): PjsipSipEngine {
        val engine = registeredEngine()
        val callId = requireNotNull(
            engine.placeCall(account.id, TARGET, MediaProfile.AUDIO_VIDEO).getOrNull(),
        )
        runCurrent()
        gateway.emitCall(callId.value, StackCallState.CONNECTED, videoActive = true)
        runCurrent()
        engine.setVideoEnabled(callId, enabled = true)
        return engine
    }

    private fun activeCallId(engine: PjsipSipEngine) = engine.activeCalls.value.single().callId
}
