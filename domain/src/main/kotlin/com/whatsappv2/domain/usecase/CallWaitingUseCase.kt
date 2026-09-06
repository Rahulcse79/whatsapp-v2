package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.call.CallStep
import com.whatsappv2.domain.call.CallWaitingPolicy
import com.whatsappv2.domain.call.SecondCallResponse
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import javax.inject.Inject

/**
 * A second call, and swapping between two (Task 56, §5.2).
 *
 * ## Why this exists rather than three buttons calling the engine
 *
 * Each of the three responses is two or three engine calls in a specific order, and the
 * order is the requirement: hold before answering, hang up before answering, hold every
 * other call before resuming this one. Spread across a ViewModel's click handlers, those
 * orders are three separate opportunities to briefly have two live calls — and the user
 * hears both callers at once when it happens.
 *
 * [CallWaitingPolicy] decides the sequence and this executes it, stopping at the first
 * refusal. Stopping matters: if the hold fails, answering anyway is the two-live-calls
 * bug arriving by a different route, so the answer is not attempted and the caller is
 * told which step refused.
 */
class CallWaitingUseCase @Inject constructor(
    private val calls: SipCallController,
    private val camera: CameraAvailability,
) {

    /**
     * Responds to [incoming] arriving while other calls are in progress.
     *
     * @param withVideo answer with video. Downgraded to audio when the camera cannot be
     *   used, exactly as an ordinary answer is (Task 51) — a permission the user declined
     *   must not turn into a call they cannot take.
     */
    suspend fun respond(
        incoming: CallId,
        response: SecondCallResponse,
        withVideo: Boolean = false,
    ): Outcome<Unit, SipError> {
        val steps = CallWaitingPolicy.respondTo(calls.activeCalls.value, incoming, response)
        return run(steps, withVideo)
    }

    /**
     * Makes [activate] the live call and holds the rest (Task 56).
     *
     * A swap that changes nothing produces no steps and succeeds: pressing swap on the
     * call that is already active must not send a re-INVITE for the sake of it.
     */
    suspend fun swapTo(activate: CallId): Outcome<Unit, SipError> =
        run(CallWaitingPolicy.swapTo(calls.activeCalls.value, activate), withVideo = false)

    /**
     * Performs the steps in order, stopping at the first failure.
     *
     * Sequential and never concurrent. Two of these steps compete for the same audio
     * path, and issuing them together is exactly the race the ordering exists to prevent.
     */
    private suspend fun run(steps: List<CallStep>, withVideo: Boolean): Outcome<Unit, SipError> {
        val media = MediaProfile.of(audio = true, video = withVideo)
            ?.downgradedWhenCameraUnavailable(camera.isCameraUsable())
            ?: MediaProfile.AUDIO

        steps.forEach { step ->
            val result = when (step) {
                is CallStep.Hold -> calls.setHold(step.callId, held = true)
                is CallStep.Resume -> calls.setHold(step.callId, held = false)
                is CallStep.Answer -> calls.answer(step.callId, media)
                is CallStep.Reject -> calls.reject(step.callId, step.reason)
                is CallStep.Hangup -> calls.hangup(step.callId, step.reason)
            }
            if (result is Outcome.Failure) return failure(result.error)
        }
        return success(Unit)
    }
}
