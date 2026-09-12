package com.whatsappv2.feature.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.recording.CallRecorder
import com.whatsappv2.domain.recording.PlaybackState
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingError
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
    /** The recording a delete has been asked for and not yet confirmed. */
    val pendingDelete: Recording? = null,
)

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
    private val pendingDelete = MutableStateFlow<Recording?>(null)

    private val eventChannel = Channel<RecordingsEvent>(Channel.BUFFERED)
    val events: Flow<RecordingsEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<RecordingsUiState> = combine(
        recordings,
        player.state,
        pendingDelete,
    ) { list, playback, pending ->
        RecordingsUiState(
            recordings = list.orEmpty(),
            loaded = list != null,
            playback = playback,
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

    fun onDeleteRequested(recording: Recording) {
        pendingDelete.value = recording
    }

    fun onDeleteDismissed() {
        pendingDelete.value = null
    }

    fun onDeleteConfirmed() {
        val recording = pendingDelete.value ?: return
        pendingDelete.value = null
        // Stopped first if it is the one playing: the player holds a decrypted copy, and
        // deleting the sealed file underneath it would leave that copy the only one.
        if (player.state.value.recordingId == recording.id) player.stop()
        viewModelScope.launch {
            when (val deleted = recorder.delete(recording.id)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> eventChannel.send(RecordingsEvent.Notice(deleted.error.describe()))
            }
            recordings.value = recorder.recordings()
        }
    }

    // Public rather than protected so the test can do what the framework does when the
    // screen leaves the back stack; nothing else has a reason to call it.
    public override fun onCleared() {
        player.stop()
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
