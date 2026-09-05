package com.whatsappv2.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The call screen's state and actions (Tasks 37 and 39).
 *
 * ## Everything comes from the FSM
 *
 * There is no local notion of "in a call" here. The screen renders whatever
 * [SipCallController.activeCalls] says, and the buttons take their enabled state from
 * [CallControlAvailability], which is a function of the phase. That is Task 39's
 * requirement stated as code: a button cannot be offered for an action the state machine
 * would reject, because nothing but the state decides whether it is offered.
 *
 * ## The timer is a subtraction, not a counter
 *
 * A ticker emits the current time once a second and the duration is recomputed from the
 * call's connect timestamp each time. A counter would drift on every dropped tick, reset
 * on rotation, and disagree with the call log; a subtraction cannot. The clock is injected
 * for the same reason — a timer read from `System.currentTimeMillis()` is a timer no test
 * can assert.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val calls: SipCallController,
    private val media: SipMediaController,
    private val clock: Clock,
) : ViewModel() {

    private val watched = MutableStateFlow<CallId?>(null)

    private val eventChannel = Channel<CallEvent>(Channel.BUFFERED)
    val events: Flow<CallEvent> = eventChannel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<CallUiState> = watched
        .filterNotNull()
        .flatMapLatest { callId -> stateFor(callId) }
        .stateIn(
            scope = viewModelScope,
            // Kept alive briefly across a rotation, so the screen does not fall back to
            // Loading and flash the call's identity away and back again.
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
            initialValue = CallUiState.Loading,
        )

    /**
     * Points the screen at a call.
     *
     * Called from the composition rather than injected, because this screen is opened by
     * a notification and a full-screen intent, neither of which goes through navigation
     * arguments.
     */
    fun watch(callId: CallId) {
        watched.value = callId
    }

    private fun stateFor(callId: CallId): Flow<CallUiState> {
        // Local to this flow, so watching a second call starts from Loading again rather
        // than inheriting the first call's history.
        var seen = false

        return combine(calls.activeCalls, ticker()) { active, now ->
            val call = active.firstOrNull { it.callId == callId }
            if (call != null) seen = true

            when {
                call != null -> CallUiState.Active(call.toDisplay(now))
                // Absent after it was present means the call ended. Absent before it was
                // ever present means the engine has not published it yet, which happens
                // for a frame when the screen is opened from a notification. Telling the
                // two apart is the whole reason this flag exists.
                seen -> CallUiState.Finished
                else -> CallUiState.Loading
            }
        }
    }

    /**
     * A tick a second, and one immediately.
     *
     * The immediate emission matters: without it the screen would wait a second before
     * showing anything at all, which on a call that connects instantly is a visible pause.
     */
    private fun ticker(): Flow<Long> = flow {
        while (true) {
            emit(clock.nowEpochMillis())
            delay(TICK_MILLIS)
        }
    }

    // ---------------------------------------------------------------- actions

    fun answer(withVideo: Boolean = false) {
        val callId = watched.value ?: return
        val profile = if (withVideo) MediaProfile.AUDIO_VIDEO else MediaProfile.AUDIO
        act(CallAction.ANSWER) { calls.answer(callId, profile) }
    }

    /** Declines. 603, not 486: the user was there and said no (§5.2). */
    fun reject() {
        val callId = watched.value ?: return
        act(CallAction.REJECT) { calls.reject(callId, HangupReason.LOCAL_REJECTED) }
    }

    fun hangUp() {
        val callId = watched.value ?: return
        act(CallAction.HANG_UP) { calls.hangup(callId, HangupReason.LOCAL_HANGUP) }
    }

    fun setMuted(muted: Boolean) {
        val callId = watched.value ?: return
        act(CallAction.MUTE) { media.setMuted(callId, muted) }
    }

    /**
     * Toggles the speaker.
     *
     * A route rather than a boolean, because "off" is not a thing: turning the speaker off
     * means going back to the earpiece, and with a headset connected that is the wrong
     * answer — which is why [AudioRoute] exists and why the engine may refuse.
     */
    fun setSpeakerOn(on: Boolean) {
        val callId = watched.value ?: return
        val route = if (on) AudioRoute.SPEAKER else AudioRoute.EARPIECE
        act(CallAction.SPEAKER) { media.setAudioRoute(callId, route) }
    }

    fun setHold(held: Boolean) {
        val callId = watched.value ?: return
        act(CallAction.HOLD) { calls.setHold(callId, held) }
    }

    private fun act(action: CallAction, block: suspend () -> Outcome<*, SipError>) {
        viewModelScope.launch {
            val result = block()
            if (result is Outcome.Failure) {
                eventChannel.send(CallEvent.ActionFailed(action, result.error.describe()))
            }
        }
    }

    /**
     * The one-line reason an action failed.
     *
     * Deliberately short and non-technical: this appears in a snackbar over a live call,
     * where a response code helps nobody. The full error is already in the log.
     */
    private fun SipError.describe(): String = when (this) {
        is SipError.EngineUnavailable -> "That is not available yet"
        is SipError.InvalidState -> "Not possible right now"
        is SipError.UnknownCall -> "The call has already ended"
        is SipError.NetworkUnavailable, is SipError.TransportFailure -> "No connection"
        else -> "That did not work"
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /** One second, which is the resolution a call timer is read at. */
        const val TICK_MILLIS = 1_000L
    }
}
