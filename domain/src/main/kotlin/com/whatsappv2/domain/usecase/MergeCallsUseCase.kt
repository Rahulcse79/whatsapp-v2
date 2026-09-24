package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.domain.engine.MergeTopology
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.CallId
import javax.inject.Inject

/**
 * One button, one conference (ADR-009).
 *
 * Merge means "put these calls together", and it is always done here on this device:
 * `pjmedia_conf` mixes the audio and `pjmedia`'s video bridge composes a canvas for each
 * peer. [MergeTopology] decides whether the merge is possible at all, and this runs the
 * decision — so the ViewModel asks for a merge rather than reasoning about ceilings, and
 * the rule itself stays a pure function with a JVM test.
 *
 * ## The conference bridge is gone from this path
 *
 * Under ADR-003 a merge that carried video sent every leg into a FreeSWITCH room by blind
 * REFER. Client-side composition replaced that, and the last case still going to the room
 * — a merge with both video and audio-only legs — went with it: the mixer composes among
 * the legs that carry video and leaves the rest in the audio mix, which is strictly better
 * than a dependency on a reachable server for a room that has to exist in a dialplan.
 *
 * Nothing here dials a conference room any more, so nothing here needs to know one.
 */
class MergeCallsUseCase @Inject constructor(
    private val calls: SipCallController,
    private val conferences: SipConferenceController,
) {

    /**
     * Merges every established call on this device.
     *
     * Everything, not a chosen pair: the phone has one microphone and one camera, so
     * "merge" can only ever mean all of them. Ringing calls are left out and join when
     * they are answered.
     *
     * @return the calls actually mixed, which excludes any the stack could not take.
     */
    suspend operator fun invoke(): Outcome<Set<CallId>, SipError> =
        when (val topology = MergeTopology.of(calls.activeCalls.value)) {
            is MergeTopology.Unavailable -> failure(topology.reason.toSipError())
            is MergeTopology.LocalMix -> conferences.mixCalls(topology.callIds)
        }

    private fun MergeTopology.Unavailable.Reason.toSipError(): SipError = when (this) {
        MergeTopology.Unavailable.Reason.NOT_ENOUGH_CALLS ->
            SipError.InvalidState("a conference needs at least two established calls")

        MergeTopology.Unavailable.Reason.TOO_MANY_CALLS ->
            SipError.InvalidState(
                "this device conferences at most ${SipConferenceController.MAX_LOCAL_CONFERENCE} calls, " +
                    "and shows at most ${SipConferenceController.MAX_VIDEO_CONFERENCE} of them on video",
            )
    }
}
