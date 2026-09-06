package com.whatsappv2.feature.calls

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.CallRecorder
import com.whatsappv2.domain.recording.RecordingConsent
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingRefusal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The consent dialog and the recording it gates (Task 58, §2.6).
 *
 * ## Why the consent is built here and nowhere else
 *
 * `RecordingConsent.GrantedByLocalUser` names the call it was given for and the moment it
 * was given, and the only place both of those are known is the tap that produced it. Built
 * anywhere else it would be a consent for something else — which is precisely the failure
 * §2.6 is about.
 *
 * [confirm] is reachable only after [request], because there is deliberately no method
 * that starts a recording without opening the dialog first.
 */
internal class CallRecordingController(
    private val scope: CoroutineScope,
    private val recorder: CallRecorder,
    private val clock: Clock,
    private val onFailure: suspend (String) -> Unit,
) {

    private val askingConsent = MutableStateFlow(false)

    /**
     * What the screen renders, for one call.
     *
     * A combined flow rather than a value read inside somebody else's `combine`: a value
     * only *read* during a combine does not re-run it, so a dialog opened that way would
     * never appear until something unrelated changed.
     */
    fun stateFor(callId: CallId): Flow<RecordingUiState> =
        combine(recorder.active, askingConsent) { recording, asking ->
            RecordingUiState(isRecording = callId in recording, askingConsent = asking)
        }

    /** Opens the consent dialog. The only route to [confirm]. */
    fun request() {
        askingConsent.value = true
    }

    fun dismiss() {
        askingConsent.value = false
    }

    /** The user consented, on this call, now. */
    fun confirm(callId: CallId) {
        askingConsent.value = false
        scope.launch {
            val result = recorder.start(
                callId = callId,
                consent = RecordingConsent.GrantedByLocalUser(callId, clock.nowEpochMillis()),
            )
            if (result is Outcome.Failure) onFailure(result.error.describe())
        }
    }

    fun stop(callId: CallId) {
        scope.launch { recorder.stop(callId) }
    }

    /**
     * Why a recording could not start, in words the user can act on.
     *
     * The consent cases are specific rather than "recording failed": the commonest of them
     * is a confirmation belonging to a call that has since ended, and saying so is the
     * difference between tapping again and giving up.
     */
    private fun RecordingError.describe(): String = when (this) {
        is RecordingError.Refused -> when (refusal) {
            is RecordingRefusal.NoConsent -> "Recording needs your confirmation first"
            is RecordingRefusal.ConsentForAnotherCall ->
                "That confirmation was for a different call — confirm again for this one"
            is RecordingRefusal.CallNotEstablished -> "There is no audio to record yet"
            is RecordingRefusal.NotSupportedOnThisPlatform ->
                "This device will not let the app record a call"
        }
        is RecordingError.StorageUnavailable -> "The recording could not be saved"
        is RecordingError.EngineRefused -> "That call cannot be recorded"
    }
}
