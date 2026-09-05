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
 * The call screen, wired to the engine (Tasks 37 and 39).
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
        ),
        modifier = modifier,
    )
}
