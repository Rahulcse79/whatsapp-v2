package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.CallId

/**
 * Whether a merge can be performed, and with which legs.
 *
 * A type rather than a boolean because there are merges that cannot be performed at all,
 * and reporting one as "false" would put a silent failure on screen.
 *
 * ## There is one conference now, and it is on this device
 *
 * This used to choose between two mechanisms: audio was mixed here (ADR-009) and anything
 * carrying video was REFERred into a FreeSWITCH room (ADR-003). `pjmedia`'s video bridge
 * removed the reason for the second — it gives every peer's encoder a canvas composed of
 * this camera and every other peer's decoder, so each participant receives a picture of
 * everyone else and no server is in the media path at all.
 *
 * The bridge survived here for one residual case: a merge with both video and audio-only
 * legs, on the grounds that a canvas with a hole in it was worse than the room. It is not.
 * The mixer composes whichever subset it is handed ([SipConferenceController.mixCalls]
 * composes among the legs that carry video and leaves the rest in the audio mix), while
 * the room is a dependency on a reachable server, a dialplan entry that has to exist, and
 * a blind REFER per leg. So every merge is local now, and this type no longer chooses a
 * mechanism — it decides whether the merge is possible.
 */
sealed interface MergeTopology {

    /**
     * Mix on the device with `pjmedia_conf`, and compose the picture with `pjmedia`'s
     * video bridge (ADR-009).
     *
     * The only way a conference is built. Audio costs ~22 % of a core per participant and
     * needs no server, so it survives anything being unreachable.
     */
    data class LocalMix(val callIds: Set<CallId>) : MergeTopology

    /**
     * Nothing can be merged, and [reason] says what the user should be told.
     *
     * Its own case rather than an empty [LocalMix]: "there is nothing to merge" and "merge
     * these two" are different instructions, and an engine handed the first would publish
     * an empty conference rather than decline.
     */
    data class Unavailable(val reason: Reason) : MergeTopology {

        /** Why a merge cannot happen, as a value the UI maps to a sentence. */
        enum class Reason {
            /** Fewer than two established calls. */
            NOT_ENOUGH_CALLS,

            /** More participants than this device will mix, or than fit the picture. */
            TOO_MANY_CALLS,
        }
    }

    companion object {

        /**
         * Whether [calls] can be merged, and which of them.
         *
         * ## Two ceilings, counted over different sets
         *
         * **The conference** is capped at [SipConferenceController.MAX_LOCAL_CONFERENCE] —
         * ADR-009's measured audio ceiling, and `PJSUA_MAX_CALLS`.
         *
         * **The picture** is capped at [SipConferenceController.MAX_VIDEO_CONFERENCE]
         * counting this handset, and that ceiling is counted over **the legs carrying
         * video**, not over the whole conference. This is the change that makes a mixed
         * merge work: two people on video and three on audio is a five-way conference with
         * a three-way picture, which is within both ceilings and was previously refused
         * for exceeding a video ceiling that had been counted over the audio members too.
         *
         * A merge whose *video* legs overflow the picture is still refused outright rather
         * than composed for some of them: a participant who is in the call, in the roster
         * and in nobody's picture is the one outcome `pjmedia`'s silent four-source limit
         * produces by itself, and the whole reason the ceiling is stated here.
         *
         * A pure function, and separate from both the engine and the ViewModel, because it
         * is the whole of the policy behind one button — a rule worth enumerating in a JVM
         * test rather than inferring from a device.
         *
         * @param calls every call on this device. Only established ones can be merged —
         *   a ringing call has no media to contribute and joins when it is answered.
         * @param maxParticipants the ceiling, from ADR-009's measurement.
         */
        fun of(
            calls: List<CallSnapshot>,
            maxParticipants: Int = SipConferenceController.MAX_LOCAL_CONFERENCE,
        ): MergeTopology {
            val established = calls.filter { it.state.isEstablished }
            val ids = established.map { it.callId }.toSet()

            if (ids.size < SipConferenceController.MINIMUM_MIXED) {
                return Unavailable(Unavailable.Reason.NOT_ENOUGH_CALLS)
            }
            if (ids.size > maxParticipants) {
                return Unavailable(Unavailable.Reason.TOO_MANY_CALLS)
            }

            // The video legs plus this handset: three peers on video is a picture of four,
            // which is the ceiling. Audio-only members are not counted — they are not in
            // the picture and cost it nothing.
            val videoLegs = established.count { it.media.hasVideo }
            if (videoLegs > 0 && videoLegs + 1 > SipConferenceController.MAX_VIDEO_CONFERENCE) {
                return Unavailable(Unavailable.Reason.TOO_MANY_CALLS)
            }

            return LocalMix(ids)
        }
    }
}
