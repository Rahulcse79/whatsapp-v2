package com.whatsappv2.feature.group

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
 * The group-call page, wired to its ViewModel (Task 78).
 *
 * [onCallPlaced] is the same hand-off the dialler and the history list use: the call
 * screen is an activity `:app` owns, and one call screen reached three ways would be
 * three screens. Nothing about a conference changes that.
 */
@Composable
fun GroupCallRoute(
    onCallPlaced: (CallId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupCallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GroupCallEvent.CallPlaced -> onCallPlaced(event.callId)
                is GroupCallEvent.Refused -> snackbarHostState.showSnackbar(event.message)
                // The call went out; this only says which kind it turned out to be.
                is GroupCallEvent.Notice -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val actions = remember(viewModel, onBack) {
        GroupCallActions(
            onNameChanged = viewModel::onNameChanged,
            onAddressChanged = viewModel::onAddressChanged,
            onQueryChanged = viewModel::onQueryChanged,
            onAddMember = viewModel::onAddMember,
            onRemoveMember = viewModel::onRemoveMember,
            onStartAudioCall = viewModel::onStartAudioCall,
            onStartVideoCall = viewModel::onStartVideoCall,
            onBack = onBack,
        )
    }

    GroupCallScreen(
        state = state,
        actions = actions,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}
