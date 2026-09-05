package com.whatsappv2.data.sip.call

import kotlinx.coroutines.flow.Flow

/**
 * The call half of the SDK seam (Task 35).
 *
 * Separate from `LinphoneCoreGateway` for the same reason `SipEngine` splits into role
 * interfaces: the thing that places calls and the thing that registers accounts are used
 * by different code, and a narrow interface is a narrow fake. One class implements both,
 * because one `Core` owns both — but nothing above here has to know that.
 *
 * As with registration, everything decidable without the stack lives above this line.
 * The gateway reports what happened; [CallStateMapper] decides what it means.
 */
internal interface LinphoneCallGateway {

    /** Call progress, as the stack reports it. */
    val callEvents: Flow<StackCallEvent>

    /**
     * Sends an INVITE.
     *
     * @param callKey the app's id for this call, echoed back on every event so the engine
     *   never has to reconcile two identifier spaces.
     * @param accountKey which configured account places it — the `From` identity.
     * @param destination a full SIP URI. Resolving a bare extension against the account's
     *   domain happens above, in the use case, where it can be tested.
     * @param videoEnabled Task 51 turns this on; audio calls pass false.
     *
     * Returns immediately. The call's fate arrives on [callEvents]: this returning does
     * not mean the far end is ringing, and waiting on it would block the caller for the
     * length of a network round trip.
     */
    fun placeCall(
        callKey: String,
        accountKey: String,
        destination: String,
        videoEnabled: Boolean,
    )

    /**
     * Answers a ringing inbound call (Task 37).
     *
     * @param videoEnabled whether to answer with video. Answering an audio-only offer
     *   with video would be an escalation the peer never asked for, so the caller decides
     *   from what was offered rather than this deciding for it.
     */
    fun answerCall(callKey: String, videoEnabled: Boolean)

    /**
     * Rejects a ringing inbound call.
     *
     * @param busy true sends **486 Busy Here**, false sends **603 Decline**. The
     *   difference reaches the caller — "the line is engaged" versus "they refused" — so
     *   it has to reflect what actually happened rather than one convenient default.
     */
    fun rejectCall(callKey: String, busy: Boolean)

    /**
     * Mutes or unmutes the microphone for one call.
     *
     * Per call rather than on the core: with a second call arriving (Task 56) a core-wide
     * mute would silence a call the user never muted.
     */
    fun setMicrophoneMuted(callKey: String, muted: Boolean)

    /**
     * Ends a call, whatever stage it is at.
     *
     * One method rather than cancel/reject/hangup, because the stack already knows which
     * request is correct — CANCEL before a final response, BYE after one, and a status
     * code when rejecting an inbound INVITE. Splitting it here would mean this module
     * tracking call state a second time purely to choose a verb, and getting it wrong
     * sends a BYE for a call that was never answered.
     */
    fun terminateCall(callKey: String)
}
