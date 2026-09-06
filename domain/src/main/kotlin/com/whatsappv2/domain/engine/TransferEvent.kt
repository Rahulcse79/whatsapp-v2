package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.TransferType

/**
 * How a transfer is getting on (Task 55, §5.2, DoD 10).
 *
 * ## Why a transfer needs its own stream
 *
 * REFER is answered twice. The `202 Accepted` says the transferee agreed to *try*; the
 * NOTIFY sipfrags that follow say what actually happened to the call it then placed. The
 * gap between the two can be the length of a ring, and during it the only honest thing to
 * show is "transferring" — which is why `CallState.Transferring` exists.
 *
 * The outcome cannot be read off [SipCallController.activeCalls] alone. A blind transfer
 * that **succeeds** takes the call with it, and a call that has vanished looks exactly
 * like one the far end hung up: same absence, very different sentence to show the user.
 * A transfer that **fails** returns the call to `Connected`, which looks exactly like a
 * call that was never transferred. So the events are published here, where a failure can
 * carry the reason it failed rather than leaving the screen to guess from a state that
 * has already moved on.
 */
sealed interface TransferEvent {

    /** Which call is being transferred. Every event names one. */
    val callId: CallId

    /**
     * The REFER was accepted (202) and the transferee is now trying.
     *
     * Not success. The transferor learns nothing more than "it is being attempted", and a
     * UI that says "transferred" here is claiming something nobody has confirmed.
     */
    data class Accepted(override val callId: CallId, val type: TransferType) : TransferEvent

    /**
     * A NOTIFY sipfrag reported progress — typically `180 Ringing` at the transferee.
     *
     * [responseCode] is the code inside the sipfrag body, which is the only place this
     * information exists. Null when the server sent a NOTIFY this app could not read a
     * code from, which is a progress report with nothing to report.
     */
    data class Progressing(override val callId: CallId, val responseCode: Int?) : TransferEvent

    /**
     * The transferee answered. This leg is released locally, not by the peer.
     *
     * The call disappears from [SipCallController.activeCalls] immediately after, so this
     * is the only notice that it left because the transfer worked.
     */
    data class Succeeded(override val callId: CallId) : TransferEvent

    /**
     * The transfer did not happen, and the original call is still up (§5.2).
     *
     * [cause] is why, so the screen can say "that extension is busy" rather than
     * "transfer failed" — the caller is still on the line and can be told something they
     * can act on.
     */
    data class Failed(override val callId: CallId, val cause: SipError) : TransferEvent
}
