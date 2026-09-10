package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.data.sip.call.StackCallState

/**
 * One call's outstanding resume, and the answer PJSIP never gives to it.
 *
 * A resume is a re-INVITE with `PJSUA_CALL_UNHOLD`. The stack reports its success — media
 * comes back as `PJSUA_CALL_MEDIA_ACTIVE` and the gateway publishes
 * [StackCallState.STREAMS_RUNNING] — but it reports its failure to nobody:
 * `pjsua_call_on_media_update` reverts the provisional media and returns before
 * `on_call_media_state` (`third_party/pjproject/pjsip/src/pjsua-lib/pjsua_call.c:5470-5500`),
 * and the invite session stays `CONFIRMED`, so `on_call_state` is silent too. The only
 * trace of a refused resume is the INVITE transaction ending with a failure code, in a
 * callback that fires for every transaction on the call in both directions.
 *
 * This is the rule for reading that callback, kept out of the gateway so it can be proved
 * on the JVM — the same arrangement as `NameAddr`, and for the same reason: PJSIP does not
 * run here, and a rule that lives only inside a SWIG director can only be tested by
 * placing a call and refusing it.
 *
 * Cleared in exactly three places — the resume landing, the resume failing, and the call
 * going away — rather than on any published state, because PJSIP restates
 * `PJSUA_CALL_MEDIA_LOCAL_HOLD` *while the re-INVITE is in flight*, and clearing on that
 * would disarm [refusedBy] before the answer arrived.
 *
 * Written on the gateway's PJSIP thread and read on the library's worker threads, hence
 * the volatile; there is one flag and no compound update, so nothing stronger is needed.
 */
internal class PendingResume {

    @Volatile
    private var outstanding = false

    /** True between [begin] and whatever settles it. */
    val isOutstanding: Boolean
        get() = outstanding

    /** Our resume re-INVITE is going out. */
    fun begin() {
        outstanding = true
    }

    /**
     * The request never left, or the call is gone: nothing is outstanding any more.
     *
     * The caller decides what that means — a send that threw is a failed resume to
     * report, a disconnect is not — which is why this does not answer with a state.
     */
    fun cancel() {
        outstanding = false
    }

    /** A media state the stack reported. Running media is the resume landing. */
    fun onMediaState(state: StackCallState) {
        if (state == StackCallState.STREAMS_RUNNING) outstanding = false
    }

    /**
     * Whether a transaction that just changed state is our resume being refused.
     *
     * Narrow on purpose: a client transaction, for INVITE, with a final failure code,
     * while a resume is actually outstanding. A re-INVITE *we* reject is a server
     * transaction and does not match — that is what [isClient] is for. A BYE or INFO
     * that fails is not a resume. A `1xx` or `2xx` is not a failure. And once answered,
     * a resume is answered: the `COMPLETED → TERMINATED` transition that follows the
     * response carries the same code and must not be reported twice.
     *
     * A `401`/`407` that PJSIP retries with credentials never reaches this — pjsua skips
     * the application callback for a challenge it is about to answer itself
     * (`pjsua_call.c`, `pjsua_call_on_tsx_state_changed`, issue #1452) — so a challenge
     * that does arrive here is one the stack has given up on, and is a failure.
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
