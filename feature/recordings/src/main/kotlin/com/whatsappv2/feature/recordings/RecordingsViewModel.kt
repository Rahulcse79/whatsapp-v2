package com.whatsappv2.feature.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.recording.CallRecorder
import com.whatsappv2.domain.recording.PlaybackState
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import com.whatsappv2.domain.recording.RecordingPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the recordings screen renders. */
data class RecordingsUiState(
    /** Newest first, as the recorder lists them. */
    val recordings: List<Recording> = emptyList(),
    /** False until the first listing has come back, so an empty list is not shown as "none". */
    val loaded: Boolean = false,
    val playback: PlaybackState = PlaybackState.Idle,
    /** The recordings picked in selection mode. Empty means selection mode is off. */
    val selected: Set<RecordingId> = emptySet(),
    /** A delete asked for and not yet confirmed — one recording, or the whole selection. */
    val pendingDelete: PendingDelete? = null,
) {
    /** Selection mode is simply "something is selected". */
    val inSelection: Boolean get() = selected.isNotEmpty()
}

/** What a pending delete will remove once confirmed. */
sealed interface PendingDelete {
    /** One recording, from a row's own delete when nothing is selected. */
    data class Single(val recording: Recording) : PendingDelete

    /** Everything selected. [count] is what the dialog says. */
    data class Selection(val ids: Set<RecordingId>) : PendingDelete {
        val count: Int get() = ids.size
    }
}

/** One-off things the screen shows and does not keep. */
sealed interface RecordingsEvent {
    data class Notice(val message: String) : RecordingsEvent
}

/**
 * The recordings held on this phone, and the one being listened to.
 *
 * ## The list is a snapshot, refreshed on purpose
 *
 * `CallRecorder.recordings()` is a listing of a directory, not a flow — there is no
 * change signal to collect, because the only writer is a call ending, and this screen is
 * not open during one. So the list is read on start and again after every delete, and
 * [refresh] is there for the route to call when the screen resumes.
 *
 * ## Selection mode is derived, not a flag
 *
 * There is no separate "am I selecting" boolean to keep in step with the set: selection
 * mode *is* a non-empty [RecordingsUiState.selected]. Clearing the set leaves it, deleting
 * the last one leaves it, and nothing can show the selection bar over an empty selection.
 *
 * ## Playback stops with the screen
 *
 * `onCleared` stops the player. The decrypted copy exists for exactly as long as playback
 * does, and a screen that has gone is not listening; leaving a phone call in the clear in
 * the cache until the next start is the store's safety net, not the plan.
 */
@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val recorder: CallRecorder,
    private val player: RecordingPlayer,
) : ViewModel() {

    private val recordings = MutableStateFlow<List<Recording>?>(null)
    private val selected = MutableStateFlow<Set<RecordingId>>(emptySet())
    private val pendingDelete = MutableStateFlow<PendingDelete?>(null)

    private val eventChannel = Channel<RecordingsEvent>(Channel.BUFFERED)
    val events: Flow<RecordingsEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<RecordingsUiState> = combine(
        recordings,
        player.state,
        selected,
        pendingDelete,
    ) { list, playback, picked, pending ->
        RecordingsUiState(
            recordings = list.orEmpty(),
            loaded = list != null,
            playback = playback,
            // A recording deleted elsewhere must not linger in the selection.
            selected = if (list == null) picked else picked.intersect(list.mapTo(mutableSetOf()) { it.id }),
            pendingDelete = pending,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = RecordingsUiState(),
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { recordings.value = recorder.recordings() }
    }

    /**
     * Plays, pauses or resumes [recording], whichever its button means right now.
     *
     * One verb from the row rather than three, because the row shows one button and the
     * button's meaning is a function of [PlaybackState] — the same function this uses, so
     * what is pressed and what happens cannot disagree.
     */
    fun onPlayPressed(recording: Recording) {
        // The player's own state, not `uiState.value`: that one is shared WhileSubscribed
        // and is only as fresh as its last collector.
        val playback = player.state.value
        when {
            playback is PlaybackState.Loaded && playback.id == recording.id && playback.playing -> player.pause()
            playback is PlaybackState.Loaded && playback.id == recording.id -> player.resume()
            playback is PlaybackState.Preparing && playback.id == recording.id -> Unit
            else -> viewModelScope.launch {
                when (val started = player.play(recording.id)) {
                    is Outcome.Success -> Unit
                    is Outcome.Failure -> eventChannel.send(RecordingsEvent.Notice(started.error.describe()))
                }
            }
        }
    }

    fun onSeek(positionMillis: Long) = player.seekTo(positionMillis)

    // ------------------------------------------------------------------ selection

    /** Enters selection mode with [recording] picked. A long-press is how it begins. */
    fun onLongPress(recording: Recording) {
        selected.value = selected.value + recording.id
    }

    /** Adds or removes [recording] from the selection; removing the last one ends the mode. */
    fun onToggleSelected(recording: Recording) {
        selected.value = selected.value.let {
            if (recording.id in it) it - recording.id else it + recording.id
        }
    }

    /** Leaves selection mode, keeping every recording. The X in the selection bar. */
    fun onSelectionCleared() {
        selected.value = emptySet()
    }

    // ------------------------------------------------------------------ delete

    /** A single recording's own delete, offered only when not selecting. */
    fun onDeleteRequested(recording: Recording) {
        pendingDelete.value = PendingDelete.Single(recording)
    }

    /** The selection bar's delete. Asks about everything currently picked. */
    fun onDeleteSelectedRequested() {
        val picked = selected.value
        if (picked.isNotEmpty()) pendingDelete.value = PendingDelete.Selection(picked)
    }

    fun onDeleteDismissed() {
        pendingDelete.value = null
    }

    fun onDeleteConfirmed() {
        val request = pendingDelete.value ?: return
        pendingDelete.value = null
        val ids = when (request) {
            is PendingDelete.Single -> setOf(request.recording.id)
            is PendingDelete.Selection -> request.ids
        }
        // Stopped first if the one playing is among them: the player holds a decrypted
        // copy, and deleting the sealed file underneath it would leave that copy the only
        // one there is.
        if (player.state.value.recordingId in ids) player.stop()
        // Cleared here rather than after the deletes: the selection is done with either
        // way, and leaving it set would flash the bar over rows about to vanish.
        selected.value = selected.value - ids

        viewModelScope.launch {
            var failed = 0
            for (id in ids) {
                if (recorder.delete(id) is Outcome.Failure) failed++
            }
            if (failed > 0) {
                eventChannel.send(RecordingsEvent.Notice(deleteFailureMessage(failed, ids.size)))
            }
            recordings.value = recorder.recordings()
        }
    }

    // Public rather than protected so the test can do what the framework does when the
    // screen leaves the back stack; nothing else has a reason to call it.
    public override fun onCleared() {
        player.stop()
    }

    private fun deleteFailureMessage(failed: Int, total: Int): String = when {
        total == 1 -> "This recording could not be deleted"
        failed == total -> "Those recordings could not be deleted"
        else -> "$failed of $total recordings could not be deleted"
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * The sentence for a recording that could not be played or deleted.
 *
 * Nothing here is a refusal the user caused — those belong to starting a recording, on
 * the call screen — so every case is the store or the stack, said plainly.
 */
internal fun RecordingError.describe(): String = when (this) {
    is RecordingError.StorageUnavailable -> "This recording could not be read"
    is RecordingError.EngineRefused -> "This recording could not be played"
    is RecordingError.Refused -> "Not possible right now"
}
