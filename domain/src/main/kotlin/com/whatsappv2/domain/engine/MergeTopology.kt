package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.CallId

/**
 * How a merge should be satisfied: on this device, or in the bridge.
 *
 * A type rather than a boolean because the two carry different things — the local mix
 * needs the set of legs, the bridge needs them *and* somewhere to send them — and because
 * a third answer already exists and has to be expressible: there are merges that cannot
 * be performed at all, and reporting one as "false" would put a silent failure on screen.
 */
sealed interface MergeTopology {

    /**
     * Mix on the device with `pjmedia_conf` (ADR-009).
     *
     * The answer for an audio conference, and the better one: it needs no server, so it
     * survives a bridge being unreachable, and it costs ~22 % of a core per participant.
     */
    data class LocalMix(val callIds: Set<CallId>) : MergeTopology

    /**
     * Move every leg into the conference bridge (ADR-003).
     *
     * No longer the answer whenever video is involved. A device-hosted star could not show
     * peers to each other while this handset only ever sent its own camera — which is what
     * sent every video merge here. `pjmedia`'s video bridge removes that limit by giving
     * each peer's encoder a canvas composed of this camera and every other peer's decoder
     * (ADR-009's video half), so an all-video merge within the ceiling is now mixed on the
     * device and this case is what is left: a merge with both video and audio-only legs,
     * where a canvas cannot be composed for a member who sends no picture.
     */
    data class Bridge(val callIds: Set<CallId>) : MergeTopology

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

            /** More participants than this device or the bridge will take. */
            TOO_MANY_CALLS,

            /** Video legs to merge, and no bridge configured to merge them into. */
            NO_BRIDGE_CONFIGURED,
        }
    }

    companion object {

        /**
         * Which topology [calls] need, given whether a bridge is configured.
         *
         * ## The rule, and why it is this way round
         *
         * **Any** video leg sends the whole conference to the bridge, rather than only a
         * conference that is video throughout. A mixed merge — two video calls and one
         * audio — has the same defect as an all-video one for every video participant, and
         * splitting the difference (mix the audio legs here, bridge the video ones) would
         * be two conferences that cannot hear each other. The bridge takes audio-only
         * members perfectly well and leaves them off the canvas, which is the behaviour the
         * `video-required-for-canvas` flag on the `whatsapp-video` profile exists for.
         *
         * **Video conferences are capped at [SipConferenceController.MAX_VIDEO_CONFERENCE]**,
         * counting this handset — a tighter ceiling than audio's, and for an unrelated
         * reason: audio's is CPU, video's is how small a face can be on a phone and still
         * be a face.
         *
         * **No video means no bridge**, even when one is configured. An audio conference
         * that works with no server is strictly better than one that depends on a
         * reachable FreeSWITCH, and ADR-009 reversed ADR-003 for audio precisely to remove
         * that dependency. Routing audio through the bridge anyway would hand it back.
         *
         * A pure function, and separate from both the engine and the ViewModel, because it
         * is the whole of the policy: which of two very different pieces of machinery runs
         * when the user presses one button. That is a rule worth enumerating in a JVM test
         * rather than inferring from a device.
         *
         * @param calls every call on this device. Only established ones can be merged —
         *   a ringing call has no media to contribute and joins when it is answered.
         * @param bridgeConfigured whether a conference room address is known.
         * @param maxParticipants the ceiling, from ADR-009's measurement.
         */
        fun of(
            calls: List<CallSnapshot>,
            bridgeConfigured: Boolean,
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

            val anyVideo = established.any { it.media.hasVideo }
            val allVideo = anyVideo && established.all { it.media.hasVideo }
            // The legs plus this handset: a merge of three others is a conference of
            // four, which is the ceiling. Video's limit is about how small a face can be
            // and still be a face; audio's is CPU, and they are different numbers for
            // different reasons.
            val withinVideoCeiling = ids.size + 1 <= SipConferenceController.MAX_VIDEO_CONFERENCE

            return when {
                !anyVideo -> LocalMix(ids)

                // Composed here, by `pjmedia`'s video bridge: every peer's encoder is
                // given this camera and every *other* peer's decoder, so each participant
                // receives a canvas of everyone else and the room carries no picture at
                // all. That is what makes a device-hosted video conference possible, and
                // it is why this case now precedes the bridge rather than being folded
                // into it. Only when the mix is composable: every leg has to be carrying
                // video, because a canvas with a hole in it is worse than the bridge, and
                // it has to fit the mixer's four-source ceiling.
                allVideo && withinVideoCeiling -> LocalMix(ids)

                // The ceiling first: "too many people for a video conference" is true
                // whether or not a bridge exists, and is the more useful thing to be told.
                !withinVideoCeiling -> Unavailable(Unavailable.Reason.TOO_MANY_CALLS)
                !bridgeConfigured -> Unavailable(Unavailable.Reason.NO_BRIDGE_CONFIGURED)

                // A mixed merge — some legs with video, some without. The canvas cannot
                // be composed for a member that sends nothing, so the bridge takes it,
                // which leaves audio-only members off the canvas and everyone still in
                // one conference.
                else -> Bridge(ids)
            }
        }
    }
}
