package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.recording.PlaybackState
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import com.whatsappv2.domain.recording.RecordingPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A [RecordingPlayer] that plays nothing and keeps the score.
 *
 * Time does not pass by itself: [advanceBy] is what moves the position, so a test that
 * checks "the row shows 0:05" is exact rather than approximate. What is modelled is the
 * state machine a screen renders from — idle, preparing, loaded, paused, finished — and
 * the one rule the real player has, that [play] stops whatever was playing. What is not
 * modelled is audio, decryption, or a file, none of which a screen can see.
 */
class FakeRecordingPlayer : RecordingPlayer {

    private val playback = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = playback.asStateFlow()

    /** Every id [play] was asked for, in order — including ones it then refused. */
    val played: MutableList<RecordingId> = mutableListOf()

    /** How many times [stop] was called; the screen's leave and the delete both must. */
    var stops: Int = 0

    /** Length given to everything played, so a test can assert against a known duration. */
    var durationMillis: Long = DEFAULT_DURATION_MILLIS

    /** What [play] should fail with, or null to succeed. */
    var refuseWith: RecordingError? = null

    override suspend fun play(id: RecordingId): Outcome<Unit, RecordingError> {
        played += id
        refuseWith?.let {
            playback.value = PlaybackState.Idle
            return failure(it)
        }
        playback.value = PlaybackState.Loaded(id, positionMillis = 0, durationMillis = durationMillis, playing = true)
        return success(Unit)
    }

    override fun pause() = playback.update { if (it is PlaybackState.Loaded) it.copy(playing = false) else it }

    override fun resume() = playback.update {
        when {
            it !is PlaybackState.Loaded -> it
            it.finished -> it.copy(positionMillis = 0, playing = true)
            else -> it.copy(playing = true)
        }
    }

    override fun seekTo(positionMillis: Long) = playback.update {
        if (it is PlaybackState.Loaded) it.copy(positionMillis = positionMillis.coerceIn(0, it.durationMillis)) else it
    }

    override fun stop() {
        stops++
        playback.value = PlaybackState.Idle
    }

    /** Moves the loaded recording on, finishing it at the end. Nothing happens while paused. */
    fun advanceBy(millis: Long) = playback.update {
        if (it is PlaybackState.Loaded && it.playing) {
            val position = (it.positionMillis + millis).coerceAtMost(it.durationMillis)
            it.copy(positionMillis = position, playing = position < it.durationMillis)
        } else {
            it
        }
    }

    private companion object {
        const val DEFAULT_DURATION_MILLIS = 30_000L
    }
}
