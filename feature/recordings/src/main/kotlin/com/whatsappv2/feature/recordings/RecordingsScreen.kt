package com.whatsappv2.feature.recordings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatsappv2.core.designsystem.component.AppTopBar
import com.whatsappv2.core.designsystem.component.ConfirmDialog
import com.whatsappv2.core.designsystem.component.EmptyState
import com.whatsappv2.core.designsystem.component.LoadingState
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.PlaybackState
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingId
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The recordings screen, wired to its ViewModel. */
@Composable
fun RecordingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // A call that ended while this screen was behind another wrote its recording with
    // nobody listing the directory; coming back has to ask.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordingsEvent.Notice -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    val actions = remember(viewModel) {
        RecordingsActions(
            onPlayPressed = viewModel::onPlayPressed,
            onSeek = viewModel::onSeek,
            onLongPress = viewModel::onLongPress,
            onToggleSelected = viewModel::onToggleSelected,
            onSelectionCleared = viewModel::onSelectionCleared,
            onDeleteRequested = viewModel::onDeleteRequested,
            onDeleteSelectedRequested = viewModel::onDeleteSelectedRequested,
            onDeleteDismissed = viewModel::onDeleteDismissed,
            onDeleteConfirmed = viewModel::onDeleteConfirmed,
        )
    }

    RecordingsScreen(
        state = state,
        actions = actions,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        modifier = modifier,
    )
}

/** Everything the screen can do, in one value, so a preview and a test hand over one thing. */
data class RecordingsActions(
    val onPlayPressed: (Recording) -> Unit,
    val onSeek: (Long) -> Unit,
    val onLongPress: (Recording) -> Unit,
    val onToggleSelected: (Recording) -> Unit,
    val onSelectionCleared: () -> Unit,
    val onDeleteRequested: (Recording) -> Unit,
    val onDeleteSelectedRequested: () -> Unit,
    val onDeleteDismissed: () -> Unit,
    val onDeleteConfirmed: () -> Unit,
) {
    companion object {
        /** For previews and tests that are not about what the buttons do. */
        val NONE = RecordingsActions({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * The stateless screen: every recording on the phone, and the one playing.
 *
 * A flat list rather than a group per day — recordings are rare enough that a heading
 * per day would outnumber the rows under it — and each row says when, how long, and how
 * big, which is everything the filename knows. The call it belongs to is not shown,
 * because the call log does not keep the stack's call id and nothing can join the two;
 * the time is what a person uses to place it anyway.
 *
 * Long-pressing a row enters selection mode; the top bar then counts what is picked and
 * carries the one delete that acts on all of it.
 */
@Composable
fun RecordingsScreen(
    state: RecordingsUiState,
    actions: RecordingsActions,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (state.inSelection) {
                SelectionBar(count = state.selected.size, actions = actions)
            } else {
                AppTopBar(
                    title = "Call recordings",
                    navigationIcon = {
                        onBack?.let { back ->
                            IconButton(onClick = back, modifier = Modifier.testTag(TAG_BACK)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val content = Modifier.fillMaxSize().padding(innerPadding)
        when {
            !state.loaded -> LoadingState(modifier = content)
            state.recordings.isEmpty() -> EmptyState(
                title = "No recordings",
                description = "Recordings you make during a call are kept here, encrypted on this phone.",
                icon = Icons.Filled.Mic,
                modifier = content.testTag(TAG_EMPTY),
            )
            else -> LazyColumn(
                modifier = content.testTag(TAG_LIST),
                contentPadding = PaddingValues(AppTheme.spacing.large),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
            ) {
                items(state.recordings, key = { it.id.value }) { recording ->
                    RecordingRow(
                        recording = recording,
                        playback = state.playback.takeIf { it.recordingId == recording.id },
                        inSelection = state.inSelection,
                        selected = recording.id in state.selected,
                        actions = actions,
                        zone = zone,
                    )
                }
            }
        }
    }

    state.pendingDelete?.let { request ->
        DeleteConfirmation(request = request, actions = actions, zone = zone)
    }
}

/**
 * The top bar while selecting: how many, a way out, and the delete.
 *
 * Close on the left rather than Back, because the gesture here is "stop selecting", not
 * "leave the screen" — a Back that left the screen would throw away a selection the user
 * built on purpose.
 */
@Composable
private fun SelectionBar(count: Int, actions: RecordingsActions) {
    AppTopBar(
        title = "$count selected",
        navigationIcon = {
            IconButton(onClick = actions.onSelectionCleared, modifier = Modifier.testTag(TAG_SELECTION_CLOSE)) {
                Icon(Icons.Filled.Close, contentDescription = "Stop selecting")
            }
        },
        actions = {
            IconButton(
                onClick = actions.onDeleteSelectedRequested,
                modifier = Modifier.testTag(TAG_DELETE_SELECTED),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
            }
        },
    )
}

/** The confirmation for a single recording or for the whole selection. */
@Composable
private fun DeleteConfirmation(request: PendingDelete, actions: RecordingsActions, zone: ZoneId) {
    val message = when (request) {
        is PendingDelete.Single ->
            "The recording from ${request.recording.startedLabel(zone)} is removed from this phone. " +
                "This cannot be undone."
        is PendingDelete.Selection -> {
            val what = if (request.count == 1) "1 recording" else "${request.count} recordings"
            "$what will be removed from this phone. This cannot be undone."
        }
    }
    val plural = request is PendingDelete.Selection && request.count > 1
    ConfirmDialog(
        title = if (plural) "Delete recordings?" else "Delete recording?",
        message = message,
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = actions.onDeleteConfirmed,
        onDismiss = actions.onDeleteDismissed,
        modifier = Modifier.testTag(TAG_CONFIRM_DELETE),
    )
}

/**
 * One recording: its transport when it is loaded, or a checkbox when selecting.
 *
 * [playback] is null for every row but the one the player is on, so a row does not have
 * to know about any other row to draw itself. In selection mode the play and delete
 * controls give way to a checkbox, and the whole row toggles — a half-selectable row that
 * still played would be two gestures fighting over one tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingRow(
    recording: Recording,
    playback: PlaybackState?,
    inSelection: Boolean,
    selected: Boolean,
    actions: RecordingsActions,
    zone: ZoneId,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (inSelection) actions.onToggleSelected(recording) },
                onLongClick = { actions.onLongPress(recording) },
            )
            .testTag(rowTag(recording.id)),
    ) {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.large),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.medium),
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(recording.startedLabel(zone), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${formatClock(recording.durationMillis)} · ${formatSize(recording.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RowTrailing(recording, playback, inSelection, selected, actions)
            }
            if (!inSelection && playback is PlaybackState.Loaded) {
                Transport(playback = playback, onSeek = actions.onSeek)
            }
        }
    }
}

/**
 * The right-hand controls of a row: a checkbox while selecting, otherwise play and delete.
 */
@Composable
private fun RowTrailing(
    recording: Recording,
    playback: PlaybackState?,
    inSelection: Boolean,
    selected: Boolean,
    actions: RecordingsActions,
) {
    if (inSelection) {
        Checkbox(
            checked = selected,
            onCheckedChange = { actions.onToggleSelected(recording) },
            modifier = Modifier.testTag(checkTag(recording.id)),
        )
    } else {
        PlayButton(recording = recording, playback = playback, onPress = actions.onPlayPressed)
        IconButton(
            onClick = { actions.onDeleteRequested(recording) },
            modifier = Modifier.testTag(deleteTag(recording.id)),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Play, pause, or a spinner — one control whose meaning is the playback state's.
 *
 * The spinner is the decryption: a whole recording goes through the Keystore before the
 * first sample can be heard, and a button that looked pressed and did nothing for a
 * second would be pressed again.
 */
@Composable
private fun PlayButton(recording: Recording, playback: PlaybackState?, onPress: (Recording) -> Unit) {
    when {
        playback is PlaybackState.Preparing -> CircularProgressIndicator(
            modifier = Modifier.size(AppTheme.spacing.extraLarge).testTag(preparingTag(recording.id)),
        )
        else -> {
            val playing = playback is PlaybackState.Loaded && playback.playing
            IconButton(onClick = { onPress(recording) }, modifier = Modifier.testTag(playTag(recording.id))) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Position within the loaded recording, and a way to move it.
 *
 * The slider follows the player until a finger is on it, then follows the finger and
 * seeks once on release: seeking on every pixel of a drag makes the player stutter, and
 * a slider that snaps back to the player mid-drag fights the hand holding it.
 */
@Composable
private fun Transport(playback: PlaybackState.Loaded, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMillis.coerceAtLeast(1)
    val fraction = dragging ?: (playback.positionMillis.toFloat() / duration)
    val shown = dragging?.let { (it * duration).toLong() } ?: playback.positionMillis

    Column {
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * duration).toLong()) }
                dragging = null
            },
            modifier = Modifier.fillMaxWidth().testTag(TAG_SLIDER),
        )
        Text(
            text = "${formatClock(shown)} / ${formatClock(playback.durationMillis)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "Fri 12 Sep · 15:30", in the phone's zone. */
internal fun Recording.startedLabel(zone: ZoneId): String =
    Instant.ofEpochMilli(startedAtEpochMillis).atZone(zone).format(STARTED_FORMAT)

/** `m:ss`, or `h:mm:ss` past the hour. A 75-minute recording is not 75:00. */
internal fun formatClock(millis: Long): String {
    val seconds = millis / MILLIS_PER_SECOND
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val remainder = seconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "$hours:${minutes.padded()}:${remainder.padded()}"
    } else {
        "$minutes:${remainder.padded()}"
    }
}

/** "820 kB" or "1.4 MB": the number a person compares against the space they have left. */
internal fun formatSize(bytes: Long): String = when {
    bytes >= BYTES_PER_MB -> {
        val tenths = bytes * TENTHS / BYTES_PER_MB
        "${tenths / TENTHS}.${tenths % TENTHS} MB"
    }
    else -> "${bytes / BYTES_PER_KB} kB"
}

private fun Long.padded(): String = toString().padStart(2, '0')

private val STARTED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val BYTES_PER_KB = 1_000L
private const val BYTES_PER_MB = 1_000_000L
private const val TENTHS = 10L

internal const val TAG_BACK = "recordings-back"
internal const val TAG_LIST = "recordings-list"
internal const val TAG_EMPTY = "recordings-empty"
internal const val TAG_SLIDER = "recordings-slider"
internal const val TAG_CONFIRM_DELETE = "recordings-confirm-delete"
internal const val TAG_SELECTION_CLOSE = "recordings-selection-close"
internal const val TAG_DELETE_SELECTED = "recordings-delete-selected"

internal fun rowTag(id: RecordingId) = "recordings-row-${id.value}"
internal fun playTag(id: RecordingId) = "recordings-play-${id.value}"
internal fun preparingTag(id: RecordingId) = "recordings-preparing-${id.value}"
internal fun deleteTag(id: RecordingId) = "recordings-delete-${id.value}"
internal fun checkTag(id: RecordingId) = "recordings-check-${id.value}"

@ThemePreviews
@Composable
private fun RecordingsScreenPreview() = PreviewSurface {
    val first = Recording(
        id = RecordingId("a"),
        callId = CallId("call-1"),
        startedAtEpochMillis = PREVIEW_STARTED_AT,
        endedAtEpochMillis = PREVIEW_STARTED_AT + PREVIEW_DURATION_MILLIS,
        sizeBytes = PREVIEW_SIZE_BYTES,
    )
    val second = first.copy(id = RecordingId("b"), startedAtEpochMillis = PREVIEW_STARTED_AT - PREVIEW_DURATION_MILLIS)
    RecordingsScreen(
        state = RecordingsUiState(
            recordings = listOf(first, second),
            loaded = true,
            playback = PlaybackState.Loaded(
                id = first.id,
                positionMillis = PREVIEW_POSITION_MILLIS,
                durationMillis = PREVIEW_DURATION_MILLIS,
                playing = true,
            ),
        ),
        actions = RecordingsActions.NONE,
        snackbarHostState = SnackbarHostState(),
        zone = ZoneId.of("UTC"),
    )
}

private const val PREVIEW_STARTED_AT = 1_757_700_000_000L
private const val PREVIEW_DURATION_MILLIS = 185_000L
private const val PREVIEW_POSITION_MILLIS = 42_000L
private const val PREVIEW_SIZE_BYTES = 5_900_000L
