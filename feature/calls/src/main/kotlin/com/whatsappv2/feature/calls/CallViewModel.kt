package com.whatsappv2.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.SecondCallResponse
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.contacts.ContactRepository
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.engine.ConferenceSession
import com.whatsappv2.domain.engine.SipCallController
import com.whatsappv2.domain.engine.SipConferenceController
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.SipMediaController
import com.whatsappv2.domain.engine.TransferEvent
import com.whatsappv2.domain.engine.VideoSurfaceController
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.recording.CallRecorder
import com.whatsappv2.domain.recording.RecordingConsent
import com.whatsappv2.domain.usecase.CallWaitingUseCase
import com.whatsappv2.domain.usecase.TransferCallUseCase
import com.whatsappv2.domain.usecase.TransferError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The call screen's state and actions (Tasks 37, 39, 52-58, 60).
 *
 * ## Everything comes from the FSM
 *
 * There is no local notion of "in a call" here. The screen renders whatever
 * [SipCallController.activeCalls] says, and the buttons take their enabled state from
 * [CallControlAvailability], which is a function of the phase. That is Task 39's
 * requirement stated as code: a button cannot be offered for an action the state machine
 * would reject, because nothing but the state decides whether it is offered.
 *
 * The later tasks added surface without changing that. Video, transfer, a second call, a
 * conference roster and recording are each either read from the engine or held here as a
 * question awaiting an answer — and the questions are *state*, not events, so a rotation
 * does not lose a prompt somebody is halfway through answering.
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
    private val contacts: ContactRepository,
    private val conferences: SipConferenceController,
    private val recorder: CallRecorder,
    private val transfers: TransferCallUseCase,
    private val callWaiting: CallWaitingUseCase,
    private val surfaces: VideoSurfaceController,
    private val clock: Clock,
) : ViewModel() {

    private val watched = MutableStateFlow<CallId?>(null)

    private val eventChannel = Channel<CallEvent>(Channel.BUFFERED)
    val events: Flow<CallEvent> = eventChannel.receiveAsFlow()

    /**
     * The escalation the far end is waiting on, if any (Task 54).
     *
     * State rather than an event: the peer's re-INVITE is deferred until this is answered,
     * so the question has to survive a rotation. An event-driven prompt would vanish and
     * leave the far end waiting for a timeout.
     */
    private val pendingVideo = MutableStateFlow<PendingVideoRequest?>(null)

    private val transferState = MutableStateFlow<TransferUiState>(TransferUiState.Idle)

    /** Whether the consent dialog is up. The recording itself is the recorder's to report. */
    private val askingRecordingConsent = MutableStateFlow(false)

    init {
        watchVideoRequests()
        watchTransfers()
    }

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

    /** Everything the engine says, gathered so the render below is one function. */
    private data class EngineState(
        val calls: List<CallSnapshot>,
        val conference: ConferenceSession?,
        val recording: RecordingUiState,
        val pendingVideo: PendingVideoRequest?,
        val transfer: TransferUiState,
    )

    private fun stateFor(callId: CallId): Flow<CallUiState> {
        // Local to this flow, so watching a second call starts from Loading again rather
        // than inheriting the first call's history.
        var seen = false

        val engine = combine(
            calls.activeCalls,
            conferences.conferences,
            // Combined into one source rather than read inside the block: a value only
            // *read* during a combine does not re-run it, so a consent dialog opened that
            // way would never appear until something else changed (Task 58).
            recordingState(callId),
            pendingVideo,
            transferState,
        ) { active, rooms, recording, video, transfer ->
            EngineState(
                calls = active,
                conference = rooms.firstOrNull { it.callId == callId },
                recording = recording,
                // Only this call's. A prompt for a call the screen is not showing would be
                // answered by a user looking at somebody else's name.
                pendingVideo = video?.takeIf { it.callId == callId },
                transfer = transfer,
            )
        }

        return combine(engine, ticker(), contactFor(callId)) { state, now, contact ->
            val call = state.calls.firstOrNull { it.callId == callId }
            if (call != null) seen = true

            when {
                call != null -> CallUiState.Active(
                    call = call.toDisplay(now, contact),
                    otherCalls = state.calls.filterNot { it.callId == callId }.map { it.toDisplay(now) },
                    pendingVideoRequest = state.pendingVideo,
                    secondCall = state.secondCallPrompt(callId, call),
                    transfer = state.transfer,
                    recording = state.recording,
                    conference = state.conference?.toUiState(UNKNOWN_PARTICIPANT),
                )
                // Absent after it was present means the call ended. Absent before it was
                // ever present means the engine has not published it yet, which happens
                // for a frame when the screen is opened from a notification. Telling the
                // two apart is the whole reason this flag exists.
                seen -> CallUiState.Finished
                else -> CallUiState.Loading
            }
        }
    }

    /** Whether this call is being recorded, and whether the consent dialog is up (Task 58). */
    private fun recordingState(callId: CallId): Flow<RecordingUiState> =
        combine(recorder.active, askingRecordingConsent) { recording, asking ->
            RecordingUiState(isRecording = callId in recording, askingConsent = asking)
        }

    /**
     * A second call ringing while this one is up (Task 56).
     *
     * Derived rather than collected. An incoming call that is not the one on screen, while
     * the one on screen is established, *is* call waiting — there is no extra state to
     * keep, and keeping some would be a second place for it to be wrong.
     */
    private fun EngineState.secondCallPrompt(watchedId: CallId, current: CallSnapshot): SecondCallPrompt? {
        if (!current.state.isEstablished) return null

        val ringing = calls.firstOrNull {
            it.callId != watchedId && it.state is CallState.Incoming
        } ?: return null

        return SecondCallPrompt(
            callId = ringing.callId,
            from = ringing.remoteDisplayName?.takeIf { it.isNotBlank() } ?: ringing.remote.render(),
            currentCallWith = current.remoteDisplayName?.takeIf { it.isNotBlank() }
                ?: current.remote.render(),
        )
    }

    /**
     * Who is calling, if the address book knows (Task 49).
     *
     * Resolved once per address rather than per tick: the ticker fires every second and a
     * provider read on each of them would be a lot of reads of somebody's address book to
     * answer the same question. `null` first, so the screen draws the moment the call
     * arrives rather than waiting on a lookup — the name appears when it appears, which is
     * the right way round for a phone that is already ringing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun contactFor(callId: CallId): Flow<Contact?> = calls.activeCalls
        .map { active -> active.firstOrNull { it.callId == callId }?.remote }
        .filterNotNull()
        .distinctUntilChanged()
        .mapLatest { remote -> contacts.resolve(remote) }
        .onStart { emit(null) }

    /** Turns each escalation into a question on screen, and waits (Task 54). */
    private fun watchVideoRequests() {
        viewModelScope.launch {
            media.videoRequests.collect { request ->
                pendingVideo.value = PendingVideoRequest(
                    callId = request.callId,
                    from = request.fromDisplayName?.takeIf { it.isNotBlank() } ?: request.from.render(),
                )
            }
        }
    }

    /**
     * Follows a transfer so the screen can say what happened (Task 55).
     *
     * A failure is left on screen rather than cleared after a moment: the caller is still
     * on the line, and the user needs long enough to read why and decide what to do about
     * it. It clears when they start another transfer or dismiss it.
     */
    private fun watchTransfers() {
        viewModelScope.launch {
            calls.transferEvents.collect { event ->
                val current = transferState.value
                val target = (current as? TransferUiState.InProgress)?.target.orEmpty()

                transferState.value = when (event) {
                    is TransferEvent.Accepted -> TransferUiState.InProgress(target)
                    is TransferEvent.Progressing -> TransferUiState.InProgress(target, RINGING_DETAIL)
                    // The call goes with it, so the screen is about to close; the state is
                    // reset so a later call does not inherit a finished transfer.
                    is TransferEvent.Succeeded -> TransferUiState.Idle
                    is TransferEvent.Failed -> TransferUiState.Failed(target, event.cause.userMessage())
                }
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

    /**
     * Sends one DTMF digit (Task 43).
     *
     * One digit per press, never a buffered sequence. An IVR reacts to each tone as it
     * arrives — a menu changes under the caller, a timeout ends the prompt — so digits
     * batched and sent together would arrive as a sequence the caller never typed at that
     * speed. The carrier (RFC 4733 or SIP INFO) is the engine's to choose from settings;
     * nothing on this screen knows or should know which is in use.
     */
    fun sendDtmf(digit: DtmfDigit) {
        val callId = watched.value ?: return
        act(CallAction.DTMF) { calls.sendDtmf(callId, digit) }
    }

    // ---------------------------------------------------------------- video

    /**
     * Adds or drops video on this call (Tasks 53, 54).
     *
     * The same button for both directions, and the same re-INVITE: escalating an audio
     * call and un-muting video on a video call are the same request as far as the peer is
     * concerned, and splitting them into two controls would ask the user to know which
     * kind of call they are on.
     */
    fun setVideoEnabled(enabled: Boolean) {
        val callId = watched.value ?: return
        act(CallAction.VIDEO) { media.setVideoEnabled(callId, enabled) }
    }

    /** Front to back, or back to front (Task 53). */
    fun switchCamera() {
        val callId = watched.value ?: return
        act(CallAction.SWITCH_CAMERA) { media.switchCamera(callId) }
    }

    /**
     * Answers the escalation the far end asked for (Task 54).
     *
     * The prompt is cleared first. The engine's answer is a round trip, and leaving the
     * dialog up until it returns invites a second tap that would answer twice.
     */
    fun respondToVideoRequest(accept: Boolean) {
        val request = pendingVideo.value ?: return
        pendingVideo.value = null
        act(CallAction.VIDEO) { media.respondToVideoRequest(request.callId, accept) }
    }

    /**
     * Hands the stack the views to draw into (Task 52).
     *
     * Called from the composable's lifecycle rather than on state change, because the
     * lifetime that matters is the view's, not the call's.
     */
    fun attachVideoSurfaces(remoteView: Any?, localPreview: Any?) {
        surfaces.attach(remoteView, localPreview)
    }

    /** Gives the views back. Must run on dispose, or the stack keeps drawing into them. */
    fun detachVideoSurfaces() {
        surfaces.detach()
    }

    // ---------------------------------------------------------------- transfer

    /** Opens the transfer sheet (Task 55). */
    fun startTransfer() {
        transferState.value = TransferUiState.Choosing()
    }

    fun onTransferTargetChanged(input: String) {
        transferState.value = TransferUiState.Choosing(input)
    }

    /** Closes the sheet, or dismisses a failure the user has read. */
    fun cancelTransfer() {
        transferState.value = TransferUiState.Idle
    }

    /** `REFER` straight to [target] — the transferor drops out (Task 55). */
    fun transferBlind(target: String) {
        val callId = watched.value ?: return
        transferState.value = TransferUiState.InProgress(target)
        actOnTransfer(target) { transfers.blind(callId, target) }
    }

    /** Holds this call and rings [target] so the user can speak to them first (Task 57). */
    fun startConsultation(target: String) {
        val callId = watched.value ?: return
        viewModelScope.launch {
            when (val started = transfers.startConsultation(callId, target)) {
                is Outcome.Failure -> reportTransferFailure(target, started.error)
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
        actOnTransfer(consulting.target) {
            transfers.completeAttended(consulting.callId, consulting.consultationCallId)
        }
    }

    /** Abandons the consultation and brings the first call back (Task 57). */
    fun cancelConsultation() {
        val consulting = transferState.value as? TransferUiState.Consulting ?: return
        transferState.value = TransferUiState.Idle
        actOnTransfer(consulting.target) {
            transfers.cancelConsultation(consulting.callId, consulting.consultationCallId)
        }
    }

    // ---------------------------------------------------------------- call waiting

    /** One of the three answers to a second call (Task 56). */
    fun respondToSecondCall(callId: CallId, response: SecondCallResponse) {
        act(CallAction.ANSWER) { callWaiting.respond(callId, response) }
    }

    /**
     * Makes [callId] the live call and holds the rest (Task 56).
     *
     * Also re-points the screen, because after a swap the call the user is looking at
     * should be the one they are talking to.
     */
    fun swapTo(callId: CallId) {
        viewModelScope.launch {
            when (val result = callWaiting.swapTo(callId)) {
                is Outcome.Failure ->
                    eventChannel.send(CallEvent.ActionFailed(CallAction.SWAP, result.error.userMessage()))
                is Outcome.Success -> watched.value = callId
            }
        }
    }

    // ---------------------------------------------------------------- recording

    /**
     * Asks for consent before anything is recorded (Task 58, §2.6).
     *
     * The only route to [confirmRecording]. There is deliberately no method that starts a
     * recording without going through the dialog this opens.
     */
    fun requestRecording() {
        askingRecordingConsent.value = true
    }

    fun dismissRecordingConsent() {
        askingRecordingConsent.value = false
    }

    /**
     * The user consented, on this call, now (Task 58).
     *
     * The consent is built here from the tap that produced it, so it names the call it was
     * given for and the moment it was given. A consent carried in from anywhere else would
     * be a consent for something else.
     */
    fun confirmRecording() {
        val callId = watched.value ?: return
        askingRecordingConsent.value = false
        act(CallAction.RECORD) {
            recorder.start(
                callId = callId,
                consent = RecordingConsent.GrantedByLocalUser(callId, clock.nowEpochMillis()),
            )
        }
    }

    fun stopRecording() {
        val callId = watched.value ?: return
        viewModelScope.launch { recorder.stop(callId) }
    }

    // ---------------------------------------------------------------- plumbing

    private fun act(action: CallAction, block: suspend () -> Outcome<*, SipError>) {
        viewModelScope.launch {
            val result = block()
            if (result is Outcome.Failure) {
                eventChannel.send(CallEvent.ActionFailed(action, result.error.userMessage()))
            }
        }
    }

    private fun actOnTransfer(target: String, block: suspend () -> Outcome<*, TransferError>) {
        viewModelScope.launch {
            val result = block()
            if (result is Outcome.Failure) reportTransferFailure(target, result.error)
        }
    }

    /**
     * Puts a transfer failure on screen and tells the user why.
     *
     * Both, not either: the state keeps the reason visible while they decide what to do,
     * and the event makes sure they notice it happened at all.
     */
    private suspend fun reportTransferFailure(target: String, error: TransferError) {
        val message = when (error) {
            is TransferError.Rejected -> error.cause.userMessage()
            is TransferError.InvalidTarget -> "That is not an address this call can be sent to"
            is TransferError.UnknownAccount -> "The account this call belongs to is gone"
            is TransferError.UnknownCall -> "That call has already ended"
        }
        transferState.value = TransferUiState.Failed(target, message)
        eventChannel.send(CallEvent.ActionFailed(CallAction.TRANSFER, message))
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L

        /** One second, which is the resolution a call timer is read at. */
        const val TICK_MILLIS = 1_000L

        /** What a sipfrag during a transfer means, in words (Task 55). */
        const val RINGING_DETAIL = "Ringing"

        /** A participant the bridge named without saying anything about (Task 60). */
        const val UNKNOWN_PARTICIPANT = "Unknown participant"
    }
}
