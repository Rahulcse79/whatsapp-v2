package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.mapError
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DialledTarget
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import com.whatsappv2.domain.repository.SipAccountRepository
import javax.inject.Inject

/** Why a transfer could not be started, at the granularity the call screen can act on. */
sealed interface TransferError {

    /** The call being transferred is not one the engine knows about. */
    data object UnknownCall : TransferError

    /** What was typed is not a callable address. */
    data class InvalidTarget(val input: String) : TransferError

    /** The account the call belongs to is no longer configured. */
    data object UnknownAccount : TransferError

    /** The engine refused. Carries the cause so the screen can name it. */
    data class Rejected(val cause: SipError) : TransferError
}

/**
 * Blind and attended transfer (Tasks 55 and 57, §5.2, DoD 10).
 *
 * ## Two transfers, one difference, and it is not the REFER
 *
 * A blind transfer is one step: send `REFER` and drop out. An attended transfer is four,
 * spread over however long the user spends talking to the third party — hold A, call B,
 * *converse*, then `REFER` with `Replaces`. That middle stretch is why this class offers
 * separate operations rather than one `transfer()` with a flag: there is no single call
 * that can span a conversation, and a use case that tried would be holding a call open
 * across a suspension point for minutes.
 *
 * So the consultation is three named steps the UI drives, and the important one is the
 * third: [cancelConsultation] is what makes Task 57's second done-when — "cancelling the
 * consultation returns cleanly to call A" — a thing that exists rather than a thing the
 * screen has to remember to do. Hanging up B without resuming A leaves the user holding a
 * call they cannot hear, which is the failure mode of every hand-rolled version of this.
 *
 * ## Failure returns the call
 *
 * Nothing here terminates the call being transferred. A REFER that fails leaves the call
 * `Connected` (the FSM guarantees it, see `CallStateMachine.fromTransferring`), and this
 * reports the refusal so the screen can say why while the caller is still on the line.
 */
class TransferCallUseCase @Inject constructor(
    private val calls: SipCallController,
    private val accounts: SipAccountRepository,
) {

    /**
     * Blind transfer: `REFER` straight to [input] (Task 55).
     *
     * Success means the REFER was accepted for sending — not that anybody answered, and
     * it cannot mean that: the transferor leaves the dialog and never learns. What happens
     * next arrives on [SipCallController.transferEvents].
     */
    suspend fun blind(callId: CallId, input: String): Outcome<Unit, TransferError> {
        val target = resolve(callId, input) ?: return failure(targetError(callId, input))
        return calls.transfer(callId, target, TransferType.BLIND)
            .mapError { TransferError.Rejected(it) }
    }

    /**
     * Holds [callId] and calls [input] to consult (Task 57, step one).
     *
     * The hold comes first and a failure stops there. Calling B while A is still live
     * would put two live calls on one microphone, which is the same defect
     * [com.whatsappv2.domain.call.CallWaitingPolicy] exists to prevent — and here it
     * would be self-inflicted rather than caused by an incoming call.
     *
     * Audio only: a consultation exists to ask someone a question before handing a call
     * over, and opening the camera for it is a surprise nobody asked for.
     */
    suspend fun startConsultation(callId: CallId, input: String): Outcome<CallId, TransferError> {
        val call = calls.activeCalls.value.firstOrNull { it.callId == callId }
            ?: return failure(TransferError.UnknownCall)
        val target = resolve(callId, input) ?: return failure(targetError(callId, input))

        val held = calls.setHold(callId, held = true)
        if (held is Outcome.Failure) return failure(TransferError.Rejected(held.error))

        return calls.placeCall(call.accountId, target, MediaProfile.AUDIO)
            .mapError { TransferError.Rejected(it) }
    }

    /**
     * Completes the attended transfer: `REFER` with `Replaces` (Task 57, step two).
     *
     * Both legs then terminate locally — the transferor is replaced by the transferee in
     * A's dialog and has no part left to play in either.
     */
    suspend fun completeAttended(
        callId: CallId,
        consultationCallId: CallId,
    ): Outcome<Unit, TransferError> {
        val consultation = calls.activeCalls.value.firstOrNull { it.callId == consultationCallId }
            ?: return failure(TransferError.UnknownCall)

        return calls.transfer(
            callId = callId,
            target = consultation.remote,
            type = TransferType.ATTENDED,
            consultationCallId = consultationCallId,
        ).mapError { TransferError.Rejected(it) }
    }

    /**
     * Abandons the consultation and returns to call A (Task 57's second done-when).
     *
     * The resume is attempted **even if the hangup fails**. The two are independent — B
     * going away badly is no reason to leave A on hold — and the order is the safe one:
     * B is ended before A is brought back, so there is no instant with two live calls.
     *
     * A failure to resume is reported, because that is the one the user would notice: a
     * call they can see and cannot hear.
     */
    suspend fun cancelConsultation(
        callId: CallId,
        consultationCallId: CallId,
    ): Outcome<Unit, TransferError> {
        val hangup = calls.hangup(consultationCallId, HangupReason.LOCAL_HANGUP)
        val resume = calls.setHold(callId, held = false)

        return when {
            resume is Outcome.Failure -> failure(TransferError.Rejected(resume.error))
            hangup is Outcome.Failure -> failure(TransferError.Rejected(hangup.error))
            else -> success(Unit)
        }
    }

    /** The address [input] names, completed against the call's own account domain. */
    private suspend fun resolve(callId: CallId, input: String): SipUri? {
        val call = calls.activeCalls.value.firstOrNull { it.callId == callId } ?: return null
        val account = accounts.findById(call.accountId) ?: return null
        return DialledTarget.resolve(input, account.domain)
    }

    /** Which of the two reasons [resolve] returned null, so the screen can say which. */
    private suspend fun targetError(callId: CallId, input: String): TransferError {
        val call = calls.activeCalls.value.firstOrNull { it.callId == callId }
            ?: return TransferError.UnknownCall
        return if (accounts.findById(call.accountId) == null) {
            TransferError.UnknownAccount
        } else {
            TransferError.InvalidTarget(input)
        }
    }
}
