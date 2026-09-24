package com.whatsappv2.feature.calls

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.domain.model.CallId
import kotlinx.coroutines.delay

/**
 * The call screen, wired to the engine (Tasks 37, 39, 52-60).
 *
 * The route is where the ViewModel is obtained and where one-shot events become snackbars;
 * [CallScreen] stays a function of its arguments so it can be previewed and driven by a
 * UI test with no engine at all.
 *
 * [onCallFinished] fires when the call leaves `activeCalls`. The hosting activity's whole
 * lifetime is one call, so this closes it — a call screen for a call that has ended is a
 * screen the user has to dismiss for no reason.
 */
@Composable
fun CallRoute(
    callId: CallId,
    onCallFinished: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the dialler for a second leg (ADR-009). Supplied by the host, like [onCallFinished]. */
    onAddCall: () -> Unit = {},
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Keyed on the id, so a second call arriving on the same screen re-points it rather
    // than leaving it watching a call that has gone.
    LaunchedEffect(callId) { viewModel.watch(callId) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CallEvent.ActionFailed -> snackbarHostState.showSnackbar(event.detail)
            }
        }
    }

    LaunchedEffect(state) {
        val finished = state as? CallUiState.Finished ?: return@LaunchedEffect
        // The engine drops the call from its list before it says why the call ended, so
        // `Finished` arrives twice: once bare, and a beat later with the reason, which
        // restarts this effect. The bare one waits that beat so the reason can land; the
        // one with a reason stays long enough for it to be read. An ordinary hang-up has
        // no reason and closes in the time the first wait takes.
        delay(if (finished.reason == null) FINISHED_REASON_GRACE_MILLIS else FINISHED_REASON_SHOW_MILLIS)
        onCallFinished()
    }

    CallScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        actions = CallActions(
            onAnswer = viewModel::answer,
            onReject = viewModel::reject,
            onHangUp = viewModel::hangUp,
            onToggleMute = viewModel::setMuted,
            onToggleSpeaker = viewModel::setSpeakerOn,
            onToggleHold = viewModel::setHold,
            onDtmf = viewModel::sendDtmf,
            onToggleVideo = viewModel::setVideoEnabled,
            onSwitchCamera = viewModel::switchCamera,
            onRespondToVideoRequest = viewModel::respondToVideoRequest,
            onVideoSurfaces = viewModel::attachVideoSurfaces,
            onReleaseVideoSurfaces = viewModel::detachVideoSurfaces,
            onDisplayRotation = viewModel::reportDisplayRotation,
            // None of these take a call id any more. They used to close over the route's
            // `callId`, on the reasoning that the screen looks at exactly one call — true
            // at any instant, but which one changes: a second call, a swap, or the active
            // call of a pair ending all re-point the screen, and the argument then named a
            // call the user was not looking at. The controllers read the watched call at
            // the moment of acting, so there is no id here to be stale.
            onStartTransfer = viewModel.transfer::start,
            onTransferTargetChanged = viewModel.transfer::onTargetChanged,
            onCancelTransfer = viewModel.transfer::cancel,
            onTransferBlind = viewModel.transfer::blind,
            onStartConsultation = viewModel.transfer::startConsultation,
            onCompleteConsultation = viewModel.transfer::completeConsultation,
            onCancelConsultation = viewModel.transfer::cancelConsultation,
            onSecondCall = viewModel::respondToSecondCall,
            onSwapTo = viewModel::swapTo,
            onAddCall = onAddCall,
            onMerge = viewModel::merge,
            onRemoveParticipant = viewModel::removeParticipant,
            onRequestRecording = viewModel.recording::request,
            onConfirmRecording = viewModel.recording::confirm,
            onDismissRecordingConsent = viewModel.recording::dismiss,
            onStopRecording = viewModel.recording::stop,
        ),
        modifier = modifier,
    )
}

/** How long a bare `Finished` waits for its reason before the screen closes. */
private const val FINISHED_REASON_GRACE_MILLIS = 250L

/** How long "That line was busy" stays on screen. Long enough to read, short enough not to trap. */
private const val FINISHED_REASON_SHOW_MILLIS = 2_000L
