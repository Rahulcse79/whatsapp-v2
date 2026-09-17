package com.whatsappv2.feature.calls

import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.CallId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which pictures are on screen, and whose (Task 52, Task 61).
 *
 * Three booleans with one rule between them, and the rule was wrong in a way that only
 * showed up on a conference: the self-view used to require the *far end's* picture, so
 * between joining a room and the bridge's first composed frame a user with a working camera
 * saw nothing of themselves and had every reason to think it had failed.
 */
class CallDisplayVideoTest {

    private fun display(
        phase: CallPhase = CallPhase.CONNECTED,
        videoActive: Boolean = false,
        cameraOn: Boolean = false,
    ) = CallDisplay(
        callId = CallId("call-1"),
        title = "Carol",
        subtitle = "sip:1002@sip.example.com",
        direction = CallDirection.OUTGOING,
        phase = phase,
        controls = CallControls(isVideoEnabled = cameraOn),
        durationSeconds = null,
        videoOffered = false,
        videoActive = videoActive,
    )

    @Test
    fun `the self-view follows this device's camera, not the far end's picture`() {
        val joining = display(videoActive = false, cameraOn = true)

        assertTrue(joining.showsLocalPreview, "our camera is running, so we must see ourselves")
        assertFalse(joining.showsRemoteVideo, "nothing has been decoded from the far end yet")
        assertTrue(joining.showsAnyVideo, "a video surface has to exist to hold the preview")
    }

    @Test
    fun `no camera means no self-view, however much the far end sends`() {
        val watching = display(videoActive = true, cameraOn = false)

        assertFalse(watching.showsLocalPreview)
        assertTrue(watching.showsRemoteVideo)
        assertTrue(watching.showsAnyVideo)
    }

    @Test
    fun `an audio call draws no video surface at all`() {
        val audio = display(videoActive = false, cameraOn = false)

        assertFalse(audio.showsAnyVideo)
        assertFalse(audio.showsLocalPreview)
        assertFalse(audio.showsRemoteVideo)
    }

    @Test
    fun `a call without media shows neither picture`() {
        // Ringing with the camera already chosen: there is no media path yet, so there is
        // nothing to draw and no surface to hand the stack.
        val ringing = display(phase = CallPhase.RINGING, videoActive = true, cameraOn = true)

        assertFalse(ringing.showsLocalPreview)
        assertFalse(ringing.showsRemoteVideo)
        assertFalse(ringing.showsAnyVideo)
    }
}
