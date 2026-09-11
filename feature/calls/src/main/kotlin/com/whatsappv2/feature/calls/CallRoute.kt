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
        if (state is CallUiState.Finished) onCallFinished()
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
            // Both take the call id from the route rather than from the ViewModel's own
            // `watched`: the screen is looking at exactly one call, and passing it here
            // keeps the controllers free of a second idea of which one that is.
            onStartTransfer = viewModel.transfer::start,
            onTransferTargetChanged = viewModel.transfer::onTargetChanged,
            onCancelTransfer = viewModel.transfer::cancel,
            onTransferBlind = { target -> viewModel.transfer.blind(callId, target) },
            onStartConsultation = { target -> viewModel.transfer.startConsultation(callId, target) },
            onCompleteConsultation = viewModel.transfer::completeConsultation,
            onCancelConsultation = viewModel.transfer::cancelConsultation,
            onSecondCall = viewModel::respondToSecondCall,
            onSwapTo = viewModel::swapTo,
            onRequestRecording = viewModel.recording::request,
            onConfirmRecording = { viewModel.recording.confirm(callId) },
            onDismissRecordingConsent = viewModel.recording::dismiss,
            onStopRecording = { viewModel.recording.stop(callId) },
        ),
        modifier = modifier,
    )
}
