package com.whatsappv2.domain.call

import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.CallId

/**
 * Which call, if any, should be holding the camera right now (Task 51, §5.2).
 *
 * ## Why this is a function of the whole call list
 *
 * The camera is a single, exclusive device. Releasing it on hangup is easy to write and
 * easy to get wrong, because "hangup" is not the only way a call ends: an error, a
 * transfer completing, the stack going down with the process, and a second call taking
 * over all end a call's claim on the camera, and a `release()` written beside the hangup
 * button covers exactly one of them. The bug that follows is the one Task 51 names —
 * "camera in use" on the *next* call, long after the call that leaked it is forgotten.
 *
 * So the question is never "should this call release the camera". It is "given every call
 * that exists, who should have it", asked again on every change to that list. A call that
 * has ended is not in the list, so it cannot hold anything; there is no path that ends a
 * call without also answering this. That is what makes the release total rather than
 * enumerated.
 *
 * ## Pure, so the leak is testable without a camera
 *
 * No Android, no clock, no device. A test drives call lists through [ownerOf] and asserts
 * that every terminal path arrives at `null`, which is a thing no instrumented test can
 * check as thoroughly — an emulator has no way to tell you the camera was left open.
 */
object CameraPolicy {

    /**
     * The call that should be capturing, or `null` when the camera must be released.
     *
     * The rules, in the order they matter:
     *
     * 1. **A call must want video.** [CallControls.isVideoEnabled] is the user's answer,
     *    and a video-muted call keeps its negotiated video stream while sending nothing —
     *    so it releases the camera too (Task 53). That is the whole of "video mute stops
     *    the outbound stream while audio continues".
     * 2. **The call must have media.** A ringing call has negotiated nothing; turning the
     *    camera on to light up a preview for a call that may never connect is a privacy
     *    surprise, and §5.2 puts the preview on the call, not on the dialler.
     * 3. **Exactly one call may hold it.** With a second call in progress (Task 56) the
     *    active one wins and the held one lets go — two calls cannot share one camera, and
     *    a held call is by definition not sending.
     */
    fun ownerOf(calls: List<CallSnapshot>): CallId? {
        val candidates = calls.filter { it.wantsCamera }
        // The unheld one first: with a second call answered, the held call is not sending
        // and must not keep the device from the call the user is actually on.
        return (candidates.firstOrNull { it.state is CallState.Connected } ?: candidates.firstOrNull())
            ?.callId
    }

    /** True when [calls] means the camera should be capturing at all. */
    fun shouldCapture(calls: List<CallSnapshot>): Boolean = ownerOf(calls) != null

    /**
     * True when this call is asking for the camera.
     *
     * `isEstablished` rather than `isActive`: video needs a negotiated stream, and the
     * states that have one are exactly the states that carry [CallControls].
     */
    private val CallSnapshot.wantsCamera: Boolean
        get() = state.isEstablished &&
            media.hasVideo &&
            state.controlsOrNull?.isVideoEnabled == true
}
