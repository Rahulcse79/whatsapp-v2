package com.whatsappv2.domain.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PlaybackState] — the small state machine the recordings screen renders from.
 *
 * Two derived answers carry the UI: which recording a state is about ([PlaybackState.recordingId],
 * so a list can find its one active row) and whether the loaded one has run out
 * ([PlaybackState.Loaded.finished], which a row shows as "finished" rather than as a pause
 * someone chose). Both are asserted here so the screen's rendering cannot drift from them.
 */
class PlaybackStateTest {

    private val id = RecordingId("rec-1")

    @Test
    fun `recordingId names the recording each state is about, and is null only when idle`() {
        assertNull(PlaybackState.Idle.recordingId)
        assertEquals(id, PlaybackState.Preparing(id).recordingId)
        assertEquals(id, loaded(position = 0, playing = true).recordingId)
    }

    @Test
    fun `finished is true only once a stopped-at-the-end recording has been reached`() {
        // Playing, part-way: not finished.
        assertFalse(loaded(position = HALF, playing = true).finished)
        // Paused part-way: not finished either — the user stopped it, it did not run out.
        assertFalse(loaded(position = HALF, playing = false).finished)
        // At the end and no longer playing: finished.
        assertTrue(loaded(position = DURATION, playing = false).finished)
        // At the end but still flagged playing: not yet finished.
        assertFalse(loaded(position = DURATION, playing = true).finished)
    }

    @Test
    fun `a zero-length recording is never finished`() {
        // Guards the divide-by-nothing case: duration 0 must not read as "done".
        assertFalse(
            PlaybackState.Loaded(id, positionMillis = 0, durationMillis = 0, playing = false).finished,
        )
    }

    private fun loaded(position: Long, playing: Boolean) =
        PlaybackState.Loaded(id, positionMillis = position, durationMillis = DURATION, playing = playing)

    private companion object {
        const val DURATION = 30_000L
        const val HALF = 15_000L
    }
}
