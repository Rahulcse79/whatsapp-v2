package com.whatsappv2.feature.recordings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.recording.PlaybackState
import com.whatsappv2.domain.recording.Recording
import com.whatsappv2.domain.recording.RecordingId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * The recordings screen, rendered from literal states.
 *
 * The stateless overload, so no Hilt, no player and no Keystore: what is checked is what
 * the screen shows for each state and which action a press produces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [RECORDINGS_ROBOLECTRIC_SDK])
class RecordingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val recording = Recording(
        id = RecordingId("rec-1"),
        callId = CallId("call-1"),
        startedAtEpochMillis = STARTED_AT,
        endedAtEpochMillis = STARTED_AT + DURATION_MILLIS,
        sizeBytes = SIZE_BYTES,
    )

    private fun setContent(
        state: RecordingsUiState,
        actions: RecordingsActions = RecordingsActions.NONE,
    ) {
        compose.setContent {
            WhatsAppV2Theme {
                RecordingsScreen(
                    state = state,
                    actions = actions,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    zone = ZoneId.of("UTC"),
                )
            }
        }
    }

    @Test
    fun `before the first listing nothing is claimed`() {
        // Not "No recordings" — that would be a claim about a directory nobody has read.
        setContent(RecordingsUiState(loaded = false))
        compose.onNodeWithTag(TAG_EMPTY).assertDoesNotExist()
        compose.onNodeWithTag(TAG_LIST).assertDoesNotExist()
    }

    @Test
    fun `an empty phone says so, and says where recordings come from`() {
        setContent(RecordingsUiState(loaded = true))
        compose.onNodeWithTag(TAG_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("No recordings").assertIsDisplayed()
    }

    @Test
    fun `a row says when, how long and how big`() {
        setContent(RecordingsUiState(recordings = listOf(recording), loaded = true))
        compose.onNodeWithTag(rowTag(recording.id)).assertIsDisplayed()
        // 14 Nov 2023 22:13 UTC; 3:05 long; 5.9 MB.
        compose.onNodeWithText("Tue 14 Nov · 22:13").assertIsDisplayed()
        compose.onNodeWithText("3:05 · 5.9 MB").assertIsDisplayed()
    }

    @Test
    fun `pressing play reports the row`() {
        var pressed: Recording? = null
        setContent(
            RecordingsUiState(recordings = listOf(recording), loaded = true),
            RecordingsActions.NONE.copy(onPlayPressed = { pressed = it }),
        )

        compose.onNodeWithTag(playTag(recording.id)).performClick()

        assertEquals(recording, pressed)
    }

    @Test
    fun `the playing row shows pause and its position, the others show play`() {
        val other = recording.copy(id = RecordingId("rec-2"))
        setContent(
            RecordingsUiState(
                recordings = listOf(recording, other),
                loaded = true,
                playback = PlaybackState.Loaded(recording.id, POSITION_MILLIS, DURATION_MILLIS, playing = true),
            ),
        )

        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.onNodeWithTag(TAG_SLIDER).assertIsDisplayed()
        compose.onNodeWithText("0:42 / 3:05").assertIsDisplayed()
    }

    @Test
    fun `a row being decrypted shows a spinner where the button was`() {
        // A button that looked pressed and did nothing for a second would be pressed again.
        setContent(
            RecordingsUiState(
                recordings = listOf(recording),
                loaded = true,
                playback = PlaybackState.Preparing(recording.id),
            ),
        )
        compose.onNodeWithTag(preparingTag(recording.id)).assertIsDisplayed()
        compose.onNodeWithTag(playTag(recording.id)).assertDoesNotExist()
    }

    @Test
    fun `delete asks first`() {
        var requested: Recording? = null
        setContent(
            RecordingsUiState(recordings = listOf(recording), loaded = true),
            RecordingsActions.NONE.copy(onDeleteRequested = { requested = it }),
        )

        compose.onNodeWithTag(deleteTag(recording.id)).performClick()

        assertEquals(recording, requested)
    }

    @Test
    fun `the confirmation names the recording, and confirming reports it`() {
        var confirmed = false
        setContent(
            RecordingsUiState(recordings = listOf(recording), loaded = true, pendingDelete = recording),
            RecordingsActions.NONE.copy(onDeleteConfirmed = { confirmed = true }),
        )

        compose.onNodeWithTag(TAG_CONFIRM_DELETE).assertIsDisplayed()
        compose.onNodeWithText(
            "The recording from Tue 14 Nov · 22:13 is removed from this phone. This cannot be undone.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(true, confirmed)
    }

    @Test
    fun `the clock reads as minutes and seconds, and hours only past one`() {
        assertEquals("0:05", formatClock(FIVE_SECONDS_MILLIS))
        assertEquals("3:05", formatClock(DURATION_MILLIS))
        assertEquals("1:15:00", formatClock(SEVENTY_FIVE_MINUTES_MILLIS))
    }

    @Test
    fun `sizes read in kB below a megabyte and in MB with one decimal above`() {
        assertEquals("820 kB", formatSize(SMALL_SIZE_BYTES))
        assertEquals("5.9 MB", formatSize(SIZE_BYTES))
    }

    private companion object {
        const val STARTED_AT = 1_700_000_000_000L
        const val DURATION_MILLIS = 185_000L
        const val POSITION_MILLIS = 42_000L
        const val SIZE_BYTES = 5_900_000L
        const val SMALL_SIZE_BYTES = 820_000L
        const val FIVE_SECONDS_MILLIS = 5_000L
        const val SEVENTY_FIVE_MINUTES_MILLIS = 4_500_000L
    }
}
