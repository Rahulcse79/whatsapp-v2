package com.whatsappv2.data.sip.call

/**
 * The video half of the SDK seam (Tasks 51 to 54).
 *
 * Its own interface rather than more of [LinphoneCallGateway], because its callers are
 * their own: `StackVideoSurfaceController` needs the windows and the camera and nothing
 * else, and handing it the ability to place and terminate calls to get them is exactly
 * the coupling the seam exists to prevent.
 *
 * One class implements this and [LinphoneCallGateway] both, because one `Core` owns both.
 */
internal interface LinphoneVideoGateway {

    /**
     * Adds or drops the video stream by re-INVITE (Tasks 53, 54).
     *
     * `update()` with new params, not a fresh INVITE: the dialog is already established
     * and re-offering it would tear down audio that is working perfectly well in order to
     * change something audio does not care about.
     */
    fun setVideoEnabled(callKey: String, enabled: Boolean)

    /**
     * Answers a re-INVITE the far end sent offering video (Task 54).
     *
     * @param accept true accepts with a video stream; false accepts the re-INVITE while
     *   leaving video off — which keeps the audio call rather than refusing it outright,
     *   Task 54's second done-when. A 488 here would be within the letter of SIP and would
     *   end some peers' calls entirely.
     */
    fun respondToVideoUpdate(callKey: String, accept: Boolean)

    /**
     * Points the encoder at the other camera (Task 53).
     *
     * No SDP: the stream keeps running and only its source moves, so the far end sees the
     * picture change rather than a gap.
     */
    fun switchCamera(callKey: String)

    /**
     * Starts or stops the camera capturing at all (Task 51).
     *
     * Core-wide and separate from [setVideoEnabled], because the two answer different
     * questions: whether a *call* has negotiated video, and whether this process is
     * holding the *device*. Only the second decides whether the next call finds the camera
     * free, which is the failure Task 51 names — so `CameraPolicy` drives this one from
     * the whole call list rather than from any single call's teardown.
     */
    fun setCameraCapturing(capturing: Boolean)

    /**
     * Attaches the views video is drawn into, or clears them with nulls (Task 52).
     *
     * Both at once, because they are released together: a surface that outlives its call
     * is a texture the stack keeps writing into after the screen has gone.
     */
    fun setVideoWindows(remoteView: Any?, localPreview: Any?)
}
