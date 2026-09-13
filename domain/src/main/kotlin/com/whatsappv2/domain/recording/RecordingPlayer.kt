package com.whatsappv2.domain.recording

import com.whatsappv2.core.common.result.Outcome
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays back one recording at a time.
 *
 * ## Why this is a port and not a screen's own MediaPlayer
 *
 * A recording is sealed: AES-GCM under a Keystore key, in a directory nothing above
 * `:data:sip` can name. Playing it means decrypting it, and the decrypted bytes are the
 * one thing Task 58 exists to keep off the disk — so whatever decrypts them must also be
 * what destroys them, in the same place, under the same rules. A screen handed a path
 * would have a plaintext copy of a phone call and no obligation to delete it. This
 * interface hands out no path at all: a screen asks for a recording to be *played*, and
 * the implementation owns the copy for exactly as long as playback lasts.
 *
 * ## One at a time
 *
 * [play] on a second recording stops the first. Two recordings of two calls playing over
 * each other is not a feature anyone asked for, and a single [state] is what lets a list
 * render "this row is playing" without each row keeping its own idea.
 */
interface RecordingPlayer {

    /** What is playing, if anything, and how far through it is. */
    val state: StateFlow<PlaybackState>

    /**
     * Decrypts [id] and starts it from the beginning, stopping whatever was playing.
     *
     * Suspends for the decryption, which is a file rewrite through the Keystore; [state]
     * shows [PlaybackState.Preparing] meanwhile so a row can say so rather than looking
     * like a button that did nothing. Fails if the recording is gone or cannot be read.
     */
    suspend fun play(id: RecordingId): Outcome<Unit, RecordingError>

    /** Holds the current recording where it is. A no-op with nothing loaded. */
    fun pause()

    /** Continues a paused recording; one that had finished starts again from the top. */
    fun resume()

    /** Moves within the current recording. Clamped to its length; a no-op with nothing loaded. */
    fun seekTo(positionMillis: Long)

    /**
     * Stops playback and destroys the decrypted copy.
     *
     * The copy's lifetime is playback's lifetime and nothing longer: a screen that leaves
     * calls this, and an implementation that finds it was not called still sweeps the copy
     * on its next start.
     */
    fun stop()
}

/** Where playback is. */
sealed interface PlaybackState {

    /** Nothing loaded. The only state in which no decrypted copy exists. */
    data object Idle : PlaybackState

    /** [id] is being decrypted. Nothing can be heard yet. */
    data class Preparing(val id: RecordingId) : PlaybackState

    /**
     * [id] is loaded and either running or paused.
     *
     * [positionMillis] advances while [playing]; at the end it equals [durationMillis]
     * and [playing] is false, which is what a row renders as "finished" rather than as
     * a pause somebody chose.
     */
    data class Loaded(
        val id: RecordingId,
        val positionMillis: Long,
        val durationMillis: Long,
        val playing: Boolean,
    ) : PlaybackState {
        val finished: Boolean get() = !playing && durationMillis > 0 && positionMillis >= durationMillis
    }

    /** The recording this state is about, or null when idle. */
    val recordingId: RecordingId?
        get() = when (this) {
            Idle -> null
            is Preparing -> id
            is Loaded -> id
        }
}
