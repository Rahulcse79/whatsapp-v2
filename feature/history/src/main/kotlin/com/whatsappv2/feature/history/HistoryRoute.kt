package com.whatsappv2.feature.history

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CallLogEntry

/**
 * Call history, wired to its ViewModel (Task 48).
 *
 * [onCallPlaced] is how a redial leaves this module — the call screen is an activity that
 * `:app` owns, exactly as it is for the dialler, and one call screen reached two ways
 * would be two screens. The screen itself takes literal state, so a test renders it with
 * no ViewModel at all.
 */
@Composable
fun HistoryRoute(
    onCallPlaced: (CallId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rows = viewModel.rows.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HistoryEvent.CallPlaced -> onCallPlaced(event.callId)
                // A redial that was refused says why, rather than looking like a button
                // that did nothing.
                is HistoryEvent.Refused -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val actions = remember(viewModel) {
        HistoryActions(
            onFilterChanged = viewModel::onFilterChanged,
            onEntryOpened = viewModel::onEntryOpened,
            onDetailDismissed = viewModel::onDetailDismissed,
            onDelete = { entry: CallLogEntry -> viewModel.onDelete(entry.id) },
            onClearAllRequested = viewModel::onClearAllRequested,
            onClearAllDismissed = viewModel::onClearAllDismissed,
            onClearAllConfirmed = viewModel::onClearAllConfirmed,
            onCallBack = viewModel::onCallBack,
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        HistoryScreen(
            state = state,
            rows = rows,
            actions = actions,
            modifier = Modifier.padding(padding),
        )
    }
}
