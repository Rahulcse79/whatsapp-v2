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
     * @return the members actually mixed, which is [callKeys] minus any whose media was
     *   not available. A caller that needs to know the conference is whole compares them.
     */
    suspend fun setConferenceMembers(callKeys: Set<String>): Outcome<Set<String>, String>
}
