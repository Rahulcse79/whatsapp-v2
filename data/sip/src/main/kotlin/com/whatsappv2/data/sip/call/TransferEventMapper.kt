package com.whatsappv2.data.sip.call

import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.TransferEvent
import com.whatsappv2.domain.engine.TransportFailureKind
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.TransferType

/**
 * Turns the stack's transfer states into the events the app publishes (Task 55, DoD 10).
 *
 * Pure, and here rather than in the gateway for the same reason as [CallStateMapper]:
 * PJSIP does not run on the JVM, so a mapping that lived beside the SDK could only
 * ever be exercised on a device.
 *
 * ## The one thing worth knowing about REFER
 *
 * The transferor is told *twice* and the two answers mean different things. The `202
 * Accepted` says only that the transferee agreed to try. The NOTIFY sipfrags that follow
 * carry the real outcome — `180` while it rings, `200` when it is answered, `486` when it
 * is not. Reporting the 202 as success is the classic transfer bug: the UI says
 * "transferred", the transferor hangs up, and the caller they promised to hand over is
 * dropped into a dead call.
 *
 * So [toDomain] never reports success from an accept. Only [StackCallState.CONNECTED] —
 * the sipfrag saying the transferee answered — becomes [TransferEvent.Succeeded].
 */
internal object TransferEventMapper {

    /**
     * The stack state for one `on_call_transfer_status` report, from the code it carries
     * and whether the REFER subscription ended with it.
     *
     * pjsua reports three different things through one callback (`pjsua_call.c:6019`,
     * `:6119`): `100 Accepted` when the REFER itself is accepted, then every NOTIFY's
     * sipfrag status — `100 Trying`, `180 Ringing`, `200 OK`, `486 Busy Here` — with
     * [isFinal] set when the subscription terminates. The first version of the gateway
     * read all of it as "200 means connected, anything else means failed", so the accept
     * was a failure: the call was put back to `Connected` before the far end had even been
     * tried, and the `200` that followed found nothing to complete (TC15, blind transfer
     * to a FreeSWITCH tone extension, 2026-09-11).
     *
     * A final report below 200 is a subscription that ended without the transferee ever
     * answering — [StackCallState.ENDED], which [toDomain] reports as a failure with the
     * code, so the user is told rather than left holding a call that went nowhere.
     */
    fun stateOf(statusCode: Int, isFinal: Boolean): StackCallState = when {
        statusCode >= SIP_ERROR_FLOOR -> StackCallState.ERROR
        statusCode >= SIP_OK -> StackCallState.CONNECTED
        isFinal -> StackCallState.ENDED
        statusCode == SIP_RINGING || statusCode == SIP_SESSION_PROGRESS -> StackCallState.OUTGOING_RINGING
        statusCode == SIP_TRYING && !isFinal -> StackCallState.OUTGOING_INIT
        else -> StackCallState.OUTGOING_PROGRESS
    }

    private const val SIP_TRYING = 100
    private const val SIP_RINGING = 180
    private const val SIP_SESSION_PROGRESS = 183
    private const val SIP_OK = 200
    private const val SIP_ERROR_FLOOR = 300

    /**
     * The domain event for [event], or null when the state carries no news.
     *
     * @param type only needed for the accept, which is the one event that says *how* the
     *   transfer is being done; everything after it is the same either way.
     */
    fun toDomain(event: StackTransferEvent, type: TransferType): TransferEvent? {
        val callId = CallId(event.callKey)

        return when (event.state) {
            // The REFER has been sent and accepted. Not success — see above.
            StackCallState.OUTGOING_INIT -> TransferEvent.Accepted(callId, type)

            // sipfrags while the transferee is being tried. 180 is the usual one; the code
            // is carried through because "ringing" and "trying" read differently on screen.
            StackCallState.OUTGOING_PROGRESS,
            StackCallState.OUTGOING_RINGING,
            StackCallState.OUTGOING_EARLY_MEDIA,
            -> TransferEvent.Progressing(callId, event.statusCode)

            // The transferee answered. The only state that means the transfer worked.
            StackCallState.CONNECTED, StackCallState.STREAMS_RUNNING ->
                TransferEvent.Succeeded(callId)

            StackCallState.ERROR -> TransferEvent.Failed(callId, toError(event))

            // A transfer that "ends" without ever connecting did not happen. Reported as a
            // failure rather than silence, because the call has come back to the user and
            // they are owed an explanation for why it did.
            StackCallState.ENDED -> TransferEvent.Failed(callId, toError(event))

            // Nothing a transfer does maps to these; a call being held or referred is not
            // a fact about the REFER in flight.
            StackCallState.INCOMING_RECEIVED,
            StackCallState.PAUSED,
            StackCallState.PAUSED_BY_REMOTE,
            StackCallState.RESUMING,
            StackCallState.RESUME_FAILED,
            StackCallState.UPDATED_BY_REMOTE,
            StackCallState.REFERRED,
            -> null
        }
    }

    /**
     * Why the transfer failed, through the one [SipError] taxonomy.
     *
     * A sipfrag code goes through [SipError.fromResponseCode] so a busy transferee reads
     * as busy — the transferor is still on the line and can be told something they can act
     * on, which is Task 55's second done-when. No code at all means the NOTIFY never
     * arrived, which is a transport problem rather than a refusal.
     */
    private fun toError(event: StackTransferEvent): SipError {
        val code = event.statusCode
        return if (code != null && code > 0) {
            SipError.fromResponseCode(code)
        } else {
            SipError.TransportFailure(TransportFailureKind.CONNECTION_LOST)
        }
    }
}
