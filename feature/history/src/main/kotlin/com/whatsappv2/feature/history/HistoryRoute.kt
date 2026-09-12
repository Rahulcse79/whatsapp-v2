package com.whatsappv2.feature.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CallLogEntry

/**
 * Call history, wired to its ViewModel (Task 48) — the app's home screen since Task 70.
 *
 * The two navigation callbacks are how this module reaches the rest of the app without
 * depending on it. [onCallPlaced] opens the call screen, which is an activity `:app` owns;
 * [onOpenDialer] opens the dialler, which `:feature:history` may not import. The screen
 * itself takes literal state, so a test renders it with no ViewModel at all.
 */
@Composable
fun HistoryRoute(
    onCallPlaced: (CallId) -> Unit,
    onOpenDialer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
    /**
     * Runs a video call only after the camera has been asked for (Task 74).
     *
     * `:app` supplies the real one; the default proceeds straight through so a preview and
     * a test need no permission machinery. It gates the *prompt*, never the call — a
     * declined camera still places an audio call, which is `MediaProfile`'s rule.
     */
    videoGate: (proceed: () -> Unit) -> Unit = { it() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rows = viewModel.rows.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }

    // Task 71: a call that ended while this screen was in the background wrote its row
    // with nobody collecting the store's change signal, so returning has to ask. Driven by
    // the lifecycle rather than a timer — there is exactly one moment the list can be
    // stale, and this is it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HistoryEvent.CallPlaced -> onCallPlaced(event.callId)
                // A redial that was refused says why, rather than looking like a button
                // that did nothing.
                is HistoryEvent.Refused -> snackbarHostState.showSnackbar(event.message)
                // A video redial the camera could not honour still places the call, and
                // says what it did (Tasks 74, 75).
                is HistoryEvent.Notice -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val actions = remember(viewModel, onOpenDialer, videoGate) {
        HistoryActions(
            onFilterChanged = viewModel::onFilterChanged,
            onSearchToggled = viewModel::onSearchToggled,
            onSearchTextChanged = viewModel::onSearchTextChanged,
            onDirectionChanged = viewModel::onDirectionChanged,
            onDateRangeChanged = viewModel::onDateRangeChanged,
            onFiltersCleared = viewModel::onFiltersCleared,
            onEntryOpened = viewModel::onEntryOpened,
            onDetailDismissed = viewModel::onDetailDismissed,
            onDelete = { entry: CallLogEntry -> viewModel.onDelete(entry.id) },
            onClearAllRequested = viewModel::onClearAllRequested,
            onClearAllDismissed = viewModel::onClearAllDismissed,
            onClearAllConfirmed = viewModel::onClearAllConfirmed,
            onCallBack = viewModel::onCallBack,
            onVideoCallBack = { entry -> videoGate { viewModel.onVideoCallBack(entry) } },
            onOpenDialer = onOpenDialer,
        )
    }

    HistoryScreen(
        state = state,
        rows = rows,
        actions = actions,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}
