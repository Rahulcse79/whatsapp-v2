package com.whatsappv2.data.sip.recording.stack

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.sip.di.SipStackScope
import com.whatsappv2.data.sip.recording.PlaybackCopy
import com.whatsappv2.data.sip.recording.RecordingStore
import com.whatsappv2.domain.recording.PlaybackState
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import com.whatsappv2.domain.recording.RecordingPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays sealed recordings through the platform's `MediaPlayer` (Task 58, the other half).
 *
 * ## In the `stack` package, for the same reason the store is
 *
 * `MediaPlayer` does not run on the JVM, so this is verified on a device and kept out of
 * the package whose coverage gate means something. What *is* testable — what a screen
 * does with [PlaybackState] — is tested against `FakeRecordingPlayer` instead.
 *
 * ## The copy lives exactly as long as the session
 *
 * [play] asks the store for a decrypted copy and [stop] gives it back to be destroyed;
 * there is no path between the two where the player is gone and the copy is not. A
 * screen that leaves without stopping is covered by the store's own sweep on the next
 * start, which is the same net that catches a recording the stack was writing when the
 * process died.
 *
 * ## Threads
 *
 * The decryption and `prepare()` are file I/O and go to [DispatcherProvider.io]. The
 * player is created there, on a thread with no `Looper`, so its callbacks arrive on the
 * main one — which is where the screen's own calls into [pause], [resume] and [stop] come
 * from. [state] is a `StateFlow` and may be written from either.
 */
@Singleton
internal class MediaPlayerRecordingPlayer @Inject constructor(
    private val store: RecordingStore,
    private val logger: Logger,
    @SipStackScope private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
) : RecordingPlayer {

    private val playback = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = playback.asStateFlow()

    /** The player and the copy it reads, together: neither exists without the other. */
    private class Session(val copy: PlaybackCopy, val player: MediaPlayer) {
        var ticker: Job? = null
    }

    private var session: Session? = null

    override suspend fun play(id: RecordingId): Outcome<Unit, RecordingError> {
        stop()
        playback.value = PlaybackState.Preparing(id)

        return when (val opened = withContext(dispatchers.io) { open(id) }) {
            is Outcome.Failure -> {
                // Only if this is still the play being waited on: a stop or another play
                // meanwhile owns the state now.
                if (playback.value == PlaybackState.Preparing(id)) playback.value = PlaybackState.Idle
                opened
            }
            is Outcome.Success -> begin(id, opened.value)
        }
    }

    /** Decrypts and prepares, on the I/O thread; a copy that will not prepare is destroyed. */
    private fun open(id: RecordingId): Outcome<Session, RecordingError> =
        when (val copy = store.openForPlayback(id)) {
            is Outcome.Failure -> copy
            is Outcome.Success -> when (val player = prepare(copy.value)) {
                is Outcome.Failure -> {
                    store.closePlayback(copy.value)
                    player
                }
                is Outcome.Success -> success(Session(copy.value, player.value))
            }
        }

    /** Starts a prepared session — unless something superseded it while it was preparing. */
    private fun begin(id: RecordingId, current: Session): Outcome<Unit, RecordingError> {
        if (playback.value != PlaybackState.Preparing(id)) {
            // A stop, or a play of something else, arrived during the decryption. Not an
            // error for the caller; the copy still goes.
            current.player.release()
            scope.launch(dispatchers.io) { store.closePlayback(current.copy) }
            return success(Unit)
        }

        session = current
        current.player.setOnCompletionListener { onFinished(current) }
        current.player.setOnErrorListener { _, what, extra ->
            logger.error(TAG, "Playback of $id failed: what=$what extra=$extra")
            stop()
            true
        }
        current.player.start()
        publish(current, playing = true)
        current.ticker = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                if (session === current && current.player.isPlaying) publish(current, playing = true)
            }
        }
        return success(Unit)
    }

    override fun pause() {
        val current = session ?: return
        if (current.player.isPlaying) current.player.pause()
        publish(current, playing = false)
    }

    override fun resume() {
        val current = session ?: return
        val loaded = playback.value as? PlaybackState.Loaded
        if (loaded?.finished == true) current.player.seekTo(0)
        current.player.start()
        publish(current, playing = true)
    }

    override fun seekTo(positionMillis: Long) {
        val current = session ?: return
        val clamped = positionMillis.coerceIn(0, current.player.duration.toLong())
        current.player.seekTo(clamped.toInt())
        playback.update { if (it is PlaybackState.Loaded) it.copy(positionMillis = clamped) else it }
    }

    override fun stop() {
        val current = session
        session = null
        playback.value = PlaybackState.Idle
        if (current == null) return

        current.ticker?.cancel()
        current.player.release()
        // Off the caller's thread: this is called from the screen, and a delete is I/O.
        scope.launch(dispatchers.io) { store.closePlayback(current.copy) }
    }

    /** Opens the copy in a player ready to start, or says why it could not. */
    private fun prepare(copy: PlaybackCopy): Outcome<MediaPlayer, RecordingError> {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(copy.plaintextPath)
            player.prepare()
            success(player)
        } catch (e: IOException) {
            player.release()
            failure(RecordingError.StorageUnavailable(e.message.orEmpty()))
        } catch (e: IllegalStateException) {
            player.release()
            failure(RecordingError.EngineRefused(e.message.orEmpty()))
        } catch (e: IllegalArgumentException) {
            player.release()
            failure(RecordingError.EngineRefused(e.message.orEmpty()))
        }
    }

    private fun onFinished(current: Session) {
        if (session !== current) return
        playback.update {
            if (it is PlaybackState.Loaded) it.copy(positionMillis = it.durationMillis, playing = false) else it
        }
    }

    /** The player's own numbers, so the state cannot drift from what is audible. */
    private fun publish(current: Session, playing: Boolean) {
        if (session !== current) return
        playback.value = PlaybackState.Loaded(
            id = current.copy.id,
            positionMillis = current.player.currentPosition.toLong(),
            durationMillis = current.player.duration.toLong(),
            playing = playing,
        )
    }

    private companion object {
        const val TAG = "RecordingPlayer"

        /** Four updates a second: a slider that moves smoothly, without a wake-up per frame. */
        const val TICK_MILLIS = 250L
    }
}
