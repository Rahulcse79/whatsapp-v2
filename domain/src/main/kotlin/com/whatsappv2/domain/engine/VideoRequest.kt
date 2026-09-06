package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri

/**
 * The far end has asked to add video to a call that is already running (Task 54, §5.2).
 *
 * A re-INVITE offering a video stream, held here rather than applied. **The offer is not
 * accepted by arriving**: §5.2 requires an accept/decline prompt, because a call that
 * silently turns the camera on is a call that showed someone's room to a person they only
 * agreed to speak to. Nothing is negotiated, and no camera is opened, until
 * [SipMediaController.respondToVideoRequest] says so.
 *
 * The engine defers the stack's answer while this is outstanding, so the peer sees a
 * pending re-INVITE rather than a rejection followed by a change of mind.
 */
data class VideoRequest(
    val callId: CallId,

    /** Who asked. Redacted in [toString] because it is a phone number (§7). */
    val from: SipUri,

    /** The name the peer asserted, when it asserted one. */
    val fromDisplayName: String?,

    val receivedAtEpochMillis: Long,
) {
    /** Redacted: the remote URI is a phone number or extension (§7, DoD 12). */
    override fun toString(): String = "VideoRequest(call=$callId, from=$from)"
}
