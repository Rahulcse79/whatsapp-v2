package com.whatsappv2.feature.calls

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.engine.TransferEvent
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.usecase.TransferCallUseCase
import com.whatsappv2.domain.usecase.TransferError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything about transferring a call, held apart from the rest of the screen
 * (Tasks 55, 57, DoD 10).
 *
 * ## Why this is not on the ViewModel
 *
 * Because a transfer is a *conversation*, not an action. A blind one is a single REFER,
 * but an attended one spans however long somebody spends talking to a third party — hold
 * A, ring B, converse, then decide — and that whole stretch has a state of its own that
 * nothing else on the screen cares about. Folding six functions and a state machine into
 * the ViewModel put it well past the point where a reader could see what the call screen
 * actually does.
 *
 * ## The failure stays on screen
 *
 * Deliberately not cleared after a moment. A failed transfer means the caller is *still
 * on the line*, and the user needs long enough to read "that line is busy" and decide what
 * to do in the next few seconds. It clears when they start another transfer or dismiss it.
 */
internal class CallTransferController(
    private val scope: CoroutineScope,
    private val transfers: TransferCallUseCase,
    /**
     * The call the screen is showing *now*, read at the moment of acting.
     *
     * A provider rather than a parameter, and that is the point. This used to take a
     * `CallId` from the caller, and the caller was the composition — which closed over
     * the id its route was opened with. A second call, a swap, or the active call of a
     * pair ending all re-point the screen, and the transfer then went to a call the user
     * was no longer looking at, usually one that had ended. There is now no way to hand
     * this controller the wrong call, because there is no way to hand it one at all.
     */
    private val currentCall: () -> CallId?,
    /** How a failure reaches the user as well as the screen — see [reportFailure]. */
    private val onFailure: suspend (String) -> Unit,
) {

    private val transferState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)
    val state: StateFlow<TransferUiState> = transferState.asStateFlow()

    /** Opens the sheet. */
    fun start() {
        transferState.value = TransferUiState.Choosing()
    }

    fun onTargetChanged(input: String) {
        transferState.value = TransferUiState.Choosing(input)
    }

    /** Closes the sheet, or dismisses a failure the user has read. */
    fun cancel() {
        transferState.value = TransferUiState.Idle
    }

    /** `REFER` straight to [target] — the transferor drops out (Task 55). */
    fun blind(target: String) {
        val callId = currentCall() ?: return
        transferState.value = TransferUiState.InProgress(target)
        run(target) { transfers.blind(callId, target) }
    }

    /** Holds this call and rings [target] so the user can speak to them first (Task 57). */
    fun startConsultation(target: String) {
        val callId = currentCall() ?: return
        scope.launch {
            when (val started = transfers.startConsultation(callId, target)) {
                is Outcome.Failure -> reportFailure(target, started.error)
                is Outcome.Success -> transferState.value = TransferUiState.Consulting(
                    callId = callId,
                    consultationCallId = started.value,
                    target = target,
                )
            }
        }
    }

    /** Completes the attended transfer: `REFER` with `Replaces` (Task 57). */
    fun completeConsultation() {
        val consulting = transferState.value as? TransferUiState.Consulting ?: return
        transferState.value = TransferUiState.InProgress(consulting.target)
        run(consulting.target) {
            transfers.completeAttended(consulting.callId, consulting.consultationCallId)
        }
    }

    /** Abandons the consultation and brings the first call back (Task 57). */
    fun cancelConsultation() {
        val consulting = transferState.value as? TransferUiState.Consulting ?: return
        transferState.value = TransferUiState.Idle
        run(consulting.target) {
            transfers.cancelConsultation(consulting.callId, consulting.consultationCallId)
        }
    }

    /**
     * Folds one engine event into the state (Task 55).
     *
     * `Succeeded` resets to `Idle` rather than saying so: the call goes with the transfer,
     * so the screen is about to close, and leaving a finished transfer behind would greet
     * the next call with it.
     */
    fun onEvent(event: TransferEvent) {
        val target = (transferState.value as? TransferUiState.InProgress)?.target.orEmpty()

        transferState.value = when (event) {
            is TransferEvent.Accepted -> TransferUiState.InProgress(target)
            is TransferEvent.Progressing -> TransferUiState.InProgress(target, RINGING_DETAIL)
            is TransferEvent.Succeeded -> TransferUiState.Idle
            is TransferEvent.Failed -> TransferUiState.Failed(target, event.cause.userMessage())
        }
    }

    private fun run(target: String, block: suspend () -> Outcome<*, TransferError>) {
        scope.launch {
            val result = block()
            if (result is Outcome.Failure) reportFailure(target, result.error)
        }
    }

    /**
     * Puts the failure on screen **and** tells the user.
     *
     * Both, not either: the state keeps the reason visible while they decide, and the
     * one-shot event makes sure they notice it happened at all.
     */
    private suspend fun reportFailure(target: String, error: TransferError) {
        val message = when (error) {
            is TransferError.Rejected -> error.cause.userMessage()
            is TransferError.InvalidTarget -> "That is not an address this call can be sent to"
            is TransferError.UnknownAccount -> "The account this call belongs to is gone"
            is TransferError.UnknownCall -> "That call has already ended"
        }
        transferState.value = TransferUiState.Failed(target, message)
        onFailure(message)
    }

    private companion object {
        /** What a sipfrag during a transfer means, in words (Task 55). */
        const val RINGING_DETAIL = "Ringing"
    }
}
