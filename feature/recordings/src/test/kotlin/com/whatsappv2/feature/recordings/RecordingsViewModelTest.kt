package com.whatsappv2.feature.recordings

import app.cash.turbine.test
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.PlaybackState
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingError
import com.whatsappv2.domain.recording.RecordingId
import com.whatsappv2.domain.testing.FakeCallRecorder
import com.whatsappv2.domain.testing.FakeRecordingPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recordings screen's decisions, against the fakes.
 *
 * What is asserted is the state machine the screen renders from — what the one button
 * means, which row is playing, what a delete does to the player — not audio, which the
 * fake does not have and the screen cannot see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {

    private val recorder = FakeCallRecorder()
    private val player = FakeRecordingPlayer()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = RecordingsViewModel(recorder, player)

    private fun seed(vararg ids: String): List<Recording> = ids.map { id ->
        Recording(
            id = RecordingId(id),
            callId = CallId("call-$id"),
            startedAtEpochMillis = STARTED_AT,
            endedAtEpochMillis = STARTED_AT + ONE_MINUTE,
            sizeBytes = ONE_MB,
        ).also { recorder.recorded += it }
    }

    @Test
    fun `the list is loaded on start, and empty is not the same as not yet loaded`() = runTest(dispatcher) {
        seed("a", "b")
        val model = viewModel()

        model.uiState.test {
            assertFalse(awaitItem().loaded, "nothing has been listed yet")
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertTrue(state.loaded)
            assertEquals(listOf("a", "b"), state.recordings.map { it.id.value })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the play button plays, then pauses, then resumes - one verb, three meanings`() = runTest(dispatcher) {
        val (recording) = seed("a")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()

            model.onPlayPressed(recording)
            advanceUntilIdle()
            assertEquals(listOf(recording.id), player.played)
            assertTrue((expectMostRecentItem().playback as PlaybackState.Loaded).playing)

            model.onPlayPressed(recording)
            advanceUntilIdle()
            assertFalse((expectMostRecentItem().playback as PlaybackState.Loaded).playing)

            model.onPlayPressed(recording)
            advanceUntilIdle()
            assertTrue((expectMostRecentItem().playback as PlaybackState.Loaded).playing)
            // Resumed, not restarted: the player was asked to play exactly once.
            assertEquals(1, player.played.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pressing play on another row plays that one instead`() = runTest(dispatcher) {
        val (first, second) = seed("a", "b")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            model.onPlayPressed(first)
            advanceUntilIdle()

            model.onPlayPressed(second)
            advanceUntilIdle()

            assertEquals(second.id, expectMostRecentItem().playback.recordingId)
            assertEquals(listOf(first.id, second.id), player.played)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a recording that cannot be played says so rather than looking pressed`() = runTest(dispatcher) {
        val (recording) = seed("a")
        player.refuseWith = RecordingError.StorageUnavailable("the recording key is unavailable")
        val model = viewModel()
        advanceUntilIdle()

        model.events.test {
            model.onPlayPressed(recording)
            advanceUntilIdle()
            assertEquals(RecordingsEvent.Notice("This recording could not be read"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertIs<PlaybackState.Idle>(model.uiState.value.playback)
    }

    @Test
    fun `a delete is asked for, then confirmed, and the list is re-read`() = runTest(dispatcher) {
        val (recording, kept) = seed("a", "b")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()

            model.onDeleteRequested(recording)
            advanceUntilIdle()
            assertEquals(PendingDelete.Single(recording), expectMostRecentItem().pendingDelete)

            model.onDeleteConfirmed()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertNull(state.pendingDelete)
            assertEquals(listOf(kept.id), state.recordings.map { it.id })
            assertEquals(listOf(kept), recorder.recorded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a delete that is dismissed deletes nothing`() = runTest(dispatcher) {
        val (recording) = seed("a")
        val model = viewModel()
        advanceUntilIdle()

        model.onDeleteRequested(recording)
        model.onDeleteDismissed()
        advanceUntilIdle()

        assertNull(model.uiState.value.pendingDelete)
        assertEquals(1, recorder.recorded.size)
    }

    @Test
    fun `deleting the recording that is playing stops it first`() = runTest(dispatcher) {
        // The player holds a decrypted copy. Deleting the sealed file underneath it would
        // leave that copy the only one there is.
        val (recording) = seed("a")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            model.onPlayPressed(recording)
            advanceUntilIdle()

            model.onDeleteRequested(recording)
            model.onDeleteConfirmed()
            advanceUntilIdle()

            assertEquals(1, player.stops)
            assertIs<PlaybackState.Idle>(expectMostRecentItem().playback)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a different recording leaves playback alone`() = runTest(dispatcher) {
        val (playing, other) = seed("a", "b")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            model.onPlayPressed(playing)
            advanceUntilIdle()

            model.onDeleteRequested(other)
            model.onDeleteConfirmed()
            advanceUntilIdle()

            assertEquals(0, player.stops)
            assertEquals(playing.id, expectMostRecentItem().playback.recordingId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `leaving the screen stops playback, so no decrypted copy outlives it`() = runTest(dispatcher) {
        val (recording) = seed("a")
        val model = viewModel()
        advanceUntilIdle()
        model.onPlayPressed(recording)
        advanceUntilIdle()

        model.onCleared()

        assertEquals(1, player.stops)
    }

    @Test
    fun `a seek goes to the player`() = runTest(dispatcher) {
        val (recording) = seed("a")
        val model = viewModel()
        advanceUntilIdle()
        model.onPlayPressed(recording)
        advanceUntilIdle()

        model.onSeek(SEEK_TO_MILLIS)

        assertEquals(SEEK_TO_MILLIS, (player.state.value as PlaybackState.Loaded).positionMillis)
    }

    @Test
    fun `a long-press enters selection mode with that recording picked`() = runTest(dispatcher) {
        val (a, b) = seed("a", "b")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()

            model.onLongPress(a)
            advanceUntilIdle()
            var state = expectMostRecentItem()
            assertTrue(state.inSelection)
            assertEquals(setOf(a.id), state.selected)

            // Tapping another adds it; tapping a selected one removes it.
            model.onToggleSelected(b)
            model.onToggleSelected(a)
            advanceUntilIdle()
            state = expectMostRecentItem()
            assertEquals(setOf(b.id), state.selected)
            assertTrue(state.inSelection)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearing the selection leaves selection mode and keeps every recording`() = runTest(dispatcher) {
        val (a) = seed("a", "b")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            model.onLongPress(a)
            advanceUntilIdle()
            assertTrue(expectMostRecentItem().inSelection)

            model.onSelectionCleared()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertFalse(state.inSelection)
            assertEquals(2, state.recordings.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting the selection removes all of them and ends selection`() = runTest(dispatcher) {
        val (a, b, c) = seed("a", "b", "c")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            model.onLongPress(a)
            model.onToggleSelected(c)
            advanceUntilIdle()

            model.onDeleteSelectedRequested()
            advanceUntilIdle()
            assertEquals(PendingDelete.Selection(setOf(a.id, c.id)), expectMostRecentItem().pendingDelete)

            model.onDeleteConfirmed()
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertFalse(state.inSelection)
            assertEquals(listOf(b.id), state.recordings.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a selection that includes the playing one stops it`() = runTest(dispatcher) {
        val (a, b) = seed("a", "b")
        val model = viewModel()
        model.uiState.test {
            advanceUntilIdle()
            model.onPlayPressed(a)
            advanceUntilIdle()

            model.onLongPress(a)
            model.onToggleSelected(b)
            model.onDeleteSelectedRequested()
            model.onDeleteConfirmed()
            advanceUntilIdle()

            assertEquals(1, player.stops)
            assertIs<PlaybackState.Idle>(expectMostRecentItem().playback)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val STARTED_AT = 1_700_000_000_000L
        const val ONE_MINUTE = 60_000L
        const val ONE_MB = 1_000_000L
        const val SEEK_TO_MILLIS = 12_000L
    }
}
