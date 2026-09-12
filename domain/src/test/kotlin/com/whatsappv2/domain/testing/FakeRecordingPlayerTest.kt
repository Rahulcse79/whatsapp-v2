package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.isSuccess
import com.whatsappv2.domain.recording.PlaybackState
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The player fake, held to the state machine the real one drives.
 *
 * The recordings screen's tests see only this fake, so its transitions — play stops
 * whatever was playing, pause freezes, resume from the end restarts, seek clamps, stop
 * clears — must match [com.whatsappv2.domain.recording.RecordingPlayer]'s promises, or
 * those tests pass against behaviour the device would not reproduce.
 */
class FakeRecordingPlayerTest {

    private val player = FakeRecordingPlayer()
    private val first = RecordingId("rec-1")
    private val second = RecordingId("rec-2")

    @Test
    fun `playing loads the recording and records what was asked`() = runTest {
        assertTrue(player.play(first).isSuccess)

        val state = assertIs<PlaybackState.Loaded>(player.state.value)
        assertEquals(first, state.id)
        assertTrue(state.playing)
        assertEquals(listOf(first), player.played)
    }

    @Test
    fun `playing another recording replaces the first`() = runTest {
        player.play(first)
        player.play(second)

        assertEquals(second, player.state.value.recordingId)
        assertEquals(listOf(first, second), player.played)
    }

    @Test
    fun `a refusal is reported and leaves nothing loaded`() = runTest {
        player.refuseWith = RecordingError.StorageUnavailable("the recording key is unavailable")

        val result = player.play(first)

        assertEquals(RecordingError.StorageUnavailable("the recording key is unavailable"), result.errorOrNull())
        assertIs<PlaybackState.Idle>(player.state.value)
        // Still recorded as asked-for, even though it was refused.
        assertEquals(listOf(first), player.played)
    }

    @Test
    fun `pause freezes and resume continues`() = runTest {
        player.play(first)
        player.advanceBy(FIVE_SECONDS)

        player.pause()
        var state = assertIs<PlaybackState.Loaded>(player.state.value)
        assertFalse(state.playing)
        // Time does not move while paused.
        player.advanceBy(FIVE_SECONDS)
        assertEquals(FIVE_SECONDS, (player.state.value as PlaybackState.Loaded).positionMillis)

        player.resume()
        state = assertIs<PlaybackState.Loaded>(player.state.value)
        assertTrue(state.playing)
    }

    @Test
    fun `a recording that runs to the end is finished, and resume restarts it`() = runTest {
        player.play(first)

        player.advanceBy(player.durationMillis)
        val ended = assertIs<PlaybackState.Loaded>(player.state.value)
        assertTrue(ended.finished)

        player.resume()
        val restarted = assertIs<PlaybackState.Loaded>(player.state.value)
        assertEquals(0, restarted.positionMillis)
        assertTrue(restarted.playing)
    }

    @Test
    fun `seek moves within the recording and clamps to its bounds`() = runTest {
        player.play(first)

        player.seekTo(FIVE_SECONDS)
        assertEquals(FIVE_SECONDS, (player.state.value as PlaybackState.Loaded).positionMillis)

        player.seekTo(-1)
        assertEquals(0, (player.state.value as PlaybackState.Loaded).positionMillis)

        player.seekTo(player.durationMillis + FIVE_SECONDS)
        assertEquals(player.durationMillis, (player.state.value as PlaybackState.Loaded).positionMillis)
    }

    @Test
    fun `stop clears playback and counts`() = runTest {
        player.play(first)

        player.stop()

        assertIs<PlaybackState.Idle>(player.state.value)
        assertEquals(1, player.stops)
    }

    @Test
    fun `pause, resume, seek and advance do nothing with nothing loaded`() = runTest {
        // No crash, no state change: every control is safe before anything plays.
        player.pause()
        player.resume()
        player.seekTo(FIVE_SECONDS)
        player.advanceBy(FIVE_SECONDS)

        assertIs<PlaybackState.Idle>(player.state.value)
    }

    private companion object {
        const val FIVE_SECONDS = 5_000L
    }
}
