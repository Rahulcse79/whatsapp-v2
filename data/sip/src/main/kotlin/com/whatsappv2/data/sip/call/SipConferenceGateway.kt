package com.whatsappv2.data.sip.call

import com.whatsappv2.core.common.result.Outcome

/**
 * The conference half of the SDK seam (ADR-009).
 *
 * One function, and its own interface for the same reason [SipRecordingGateway] has one:
 * mixing calls together is a job that should not come with the ability to place or end
 * them. A narrow interface is also a narrow fake.
 *
 * ## Membership, not operations
 *
 * There is no `add` and no `remove`. The caller states which calls should be mixed and the
 * implementation works out the difference — see `ConferenceMix`. That is deliberate: an
 * `add`/`remove` pair has to be called exactly once per participant in exactly the right
 * order, and the bridge's state then depends on a history nobody can inspect. A membership
 * set can be re-stated on every call-state change, cheaply, and converges.
 *
 * ## Audio only
 *
 * ADR-009's gate measured one video stream at ~135 % of a core and ~107 MB; seven of them
 * does not fit on this hardware. A video conference is still a call to the FreeSWITCH
 * bridge (ADR-003). This interface deals in audio ports and nothing else.
 */
internal interface SipConferenceGateway {

    /**
     * Makes the bridge mix exactly [callKeys], and nothing else.
     *
     * Idempotent: calling it twice with the same membership is a no-op the second time, so
     * it is safe on every media-state change. Members whose audio is not up yet are
     * skipped and picked up on the next call — a participant still ringing simply has no
     * port to connect.
     *
     * Fewer than two members tears the bridge down, which is how a conference ends: there
     * is no separate teardown to forget to call.
     *
     * @param relay whether this device carries one member's audio to another. True for
     *   ADR-009's star. **False for a mesh**, where every pair holds a dialog of its own
     *   and a cross-link here would be that pair heard twice — see `ConferenceMesh`. The
     *   membership is still stated either way; only the links between members go.
     * @return the members actually mixed, which is [callKeys] minus any whose media was
     *   not available. A caller that needs to know the conference is whole compares them.
     */
    suspend fun setConferenceMembers(
        callKeys: Set<String>,
        relay: Boolean = true,
    ): Outcome<Set<String>, String>

    /**
     * Tells [callKey]'s far end who is in the conference, as RFC 4575 XML.
     *
     * ## Why the host has to say it
     *
     * On a device-mixed conference the host is the only thing that knows the membership:
     * a member holds one leg, receives one composed picture, and cannot tell a conference
     * from an ordinary call. Until this existed a member showed no badge, no participant
     * list, and cropped the host's canvas as though it were one person's face.
     *
     * Fire-and-forget, and deliberately not an `Outcome`: a member that will not take the
     * MESSAGE is a member without a participant list, which is what every member had
     * before, and failing a conference over it would be absurd. The gateway logs it.
     *
     * @param document a full roster from [ConferenceInfoWriter]. Full every time, never a
     *   delta — see [StackConferenceEvent].
     */
    fun announceRoster(callKey: String, document: String)

    /**
     * Composes the conference **picture** from [callKeys], on this device.
     *
     * The video counterpart of [setConferenceMembers], and deliberately a second call
     * rather than a flag on the first: the two memberships differ in practice — a member
     * who has not turned their camera on belongs in the audio mix and not in the picture —
     * and the audio conference works, so nothing here may change how it behaves.
     *
     * No server mixes anything. `pjmedia`'s video bridge composes a canvas for each peer
     * out of this device's camera and every *other* peer's stream, and one for this
     * device's own screen; video RTP is phone-to-phone.
     *
     * Idempotent, like its audio counterpart, and safe on every media-state change:
     * members whose video is not up yet are skipped and join by themselves when it is.
     * Fewer than two tears the mix down.
     *
     * **Refuses** a membership larger than the mixer can draw rather than truncating it.
     * `vid_conf` composes at most four sources onto one sink and does not draw a fifth —
     * no error, no log — so the ceiling is enforced where it can be reported.
     *
     * @param compose whether a canvas is built for each peer. False for a mesh, where
     *   each peer receives every other's camera on a dialog of its own; composing as well
     *   would draw every participant twice.
     * @return the members actually in the picture, which is [callKeys] minus any whose
     *   video was not up yet.
     */
    suspend fun setVideoConferenceMembers(
        callKeys: Set<String>,
        compose: Boolean = true,
    ): Outcome<Set<String>, String>
}
