package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.data.sip.call.StackCallState

/**
 * One call's outstanding hold, and the answer PJSIP never gives to it.
 *
 * ## Why this exists beside [PendingResume]
 *
 * For the same reason and with the opposite symptom. PJSIP reports a re-INVITE's success
 * through the media state — a hold lands as `PJSUA_CALL_MEDIA_LOCAL_HOLD` — and reports
 * its **failure** to nobody: `pjsua_call_on_media_update` reverts the provisional media
 * and returns before `on_call_media_state`, and the invite session stays `CONFIRMED`, so
 * `on_call_state` is silent too. The only trace is the INVITE transaction ending with a
 * failure code.
 *
 * A refused *resume* was visible: the call sat on "Resuming" for the rest of its life. A
 * refused *hold* is invisible, which is worse. The call stays connected, which is exactly
 * right and exactly what a user sees when a hold does nothing — but
 * `PjsipSipEngine.pendingHolds` still holds the call, because it is cleared by an answer
 * that never came. From then on every press of Hold is answered "that is already
 * happening", no re-INVITE is sent, and the button is dead for the rest of the call with
 * nothing anywhere saying why.
 *
 * So the refusal is detected here and published as [StackCallState.HOLD_FAILED], which
 * carries no FSM transition — the call really did stay connected — and exists so the
 * engine can settle the hold it was waiting on.
 *
 * Written on the gateway's PJSIP thread and read on the library's worker threads, hence
 * the volatile; one flag, no compound update, nothing stronger needed.
 */
internal class PendingHold {

    @Volatile
    private var outstanding = false

    /** True between [begin] and whatever settles it. */
    val isOutstanding: Boolean
        get() = outstanding

    /** Our hold re-INVITE is going out. */
    fun begin() {
        outstanding = true
    }

    /** The request never left, or the call is gone. */
    fun cancel() {
        outstanding = false
    }

    /**
     * A media state the stack reported. Media paused **by us** is the hold landing.
     *
     * [StackCallState.PAUSED_BY_REMOTE] deliberately does not settle it: the far end
     * holding us while our own hold is in flight answers a different question, and the
     * hold we asked for is still unanswered.
     */
    fun onMediaState(state: StackCallState) {
        if (state == StackCallState.PAUSED) outstanding = false
    }

    /**
     * Whether a transaction that just changed state is our hold being refused.
     *
     * The same narrow rule [PendingResume.refusedBy] applies, for the same reasons: a
     * client transaction, for INVITE, with a final failure code, while a hold is actually
     * outstanding. A re-INVITE *we* answer is a server transaction and does not match; a
     * BYE or INFO that fails is not a hold; `1xx` and `2xx` are not failures; and a
     * transaction answered once must not be reported twice as it winds down from
     * `COMPLETED` to `TERMINATED`.
     */
    fun refusedBy(isClient: Boolean, method: String, statusCode: Int): Boolean {
        if (!outstanding || !isClient) return false
        if (method != INVITE_METHOD || statusCode < SIP_ERROR_FLOOR) return false
        outstanding = false
        return true
    }

    private companion object {
        /** `SipTransaction.getMethod` returns the name, not an enum. */
        const val INVITE_METHOD = "INVITE"

        /** The first final response class that is a failure. */
        const val SIP_ERROR_FLOOR = 300
    }
}
