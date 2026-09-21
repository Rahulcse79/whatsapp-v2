package com.whatsappv2.feature.calls

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DtmfDigit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The call screen, rendered (Tasks 37 and 39).
 *
 * Driven with literal states rather than an engine: the point of Task 39's first done-when
 * is that every phase renders correctly, and that is a question about the screen, not about
 * the stack behind it. `CallViewModelTest` covers the other direction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CALLS_ROBOLECTRIC_SDK], qualifiers = CALLS_SCREEN)
class CallScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every phase renders something a person can read`() {
        // The done-when is "every FSM state renders a correct, previewable screen". This
        // is the assertable half: no phase renders an empty screen. One composition,
        // driven through the phases, because the rule may only set content once.
        val state = setContent(display(CallPhase.CALLING))

        for (phase in CallPhase.entries) {
            state.value = CallUiState.Active(display(phase))
            compose.waitForIdle()

            compose.onNodeWithTag(TAG_TITLE).assertIsDisplayed()
            compose.onNodeWithTag(TAG_STATUS).assertIsDisplayed()
        }
    }

    @Test
    fun `a merged call shows the conference, not a person`() {
        // The title is the ViewModel's; the screen's part is the face. A group where the
        // avatar was, because initials of "Conference call" would read "CC".
        setContent(
            display(CallPhase.CONNECTED, durationSeconds = 5).copy(
                title = CONFERENCE_TITLE,
                subtitle = "Carol · 1003",
                isMixed = true,
            ),
        )

        compose.onNodeWithTag(TAG_TITLE).assertTextEquals(CONFERENCE_TITLE)
        compose.onNodeWithTag(TAG_CONFERENCE_AVATAR).assertIsDisplayed()
    }

    @Test
    fun `a conference lists its members in a dropdown where the face was`() {
        // A group glyph over a list of the group says nothing the list does not, and on a
        // handset the space is what keeps the End button on screen (TC15, 2026-09-19).
        // Nine rows do not fit above the controls either, so the list is behind a header
        // that says how many are here and drops the names down on request.
        val state = setContent(
            display(CallPhase.CONNECTED, durationSeconds = 5).copy(title = CONFERENCE_TITLE, isMixed = true),
        )
        state.value = (state.value as CallUiState.Active).copy(
            conference = ConferenceUiState(
                participants = listOf(
                    ConferenceParticipantRow("self", "1000", isMuted = true, isSpeaking = false, isSelf = true),
                    ConferenceParticipantRow(
                        id = "a",
                        label = "Carol",
                        isMuted = false,
                        isSpeaking = false,
                        isSelf = false,
                        detail = "1003",
                        status = ParticipantStatus.ON_HOLD,
                    ),
                ),
                rosterAvailable = true,
            ),
        )

        compose.onNodeWithTag(TAG_ROSTER).assertIsDisplayed()
        compose.onNodeWithTag(TAG_ROSTER_SUMMARY, useUnmergedTree = true).assertTextEquals("2 in this call")
        compose.onNodeWithTag(TAG_ROSTER_LIST).assertDoesNotExist()
        compose.onNodeWithTag(TAG_CONFERENCE_AVATAR).assertDoesNotExist()
        compose.onNodeWithTag(TAG_HANG_UP).assertIsDisplayed()

        compose.onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()

        compose.onNodeWithTag(TAG_ROSTER_LIST).assertIsDisplayed()
        compose.onNodeWithText("1000").assertIsDisplayed()
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithContentDescription("Your microphone is off").assertIsDisplayed()
        compose.onNodeWithText("Carol").assertIsDisplayed()
        compose.onNodeWithText("1003").assertIsDisplayed()
        compose.onNodeWithText("On hold").assertIsDisplayed()
        compose.onNodeWithTag(TAG_HANG_UP).assertIsDisplayed()
    }

    @Test
    fun `a bridge that publishes no roster says so, and has nothing to drop down`() {
        val state = setContent(display(CallPhase.CONNECTED, durationSeconds = 5))
        state.value = (state.value as CallUiState.Active).copy(
            conference = ConferenceUiState(participants = emptyList(), rosterAvailable = false),
        )

        compose.onNodeWithTag(TAG_ROSTER_SUMMARY, useUnmergedTree = true)
            .assertTextEquals("This bridge does not publish a participant list")
        compose.onNodeWithTag(TAG_ROSTER_TOGGLE).assertDoesNotExist()
    }

    @Test
    fun `a bridge with no roster still names the people this phone merged, and says where the names came from`() {
        // The fourth case: no roster from the bridge, but the members this device sent into
        // the room. Named rather than counted, expandable, and the list says what it is.
        val state = setContent(display(CallPhase.CONNECTED, durationSeconds = 5))
        state.value = (state.value as CallUiState.Active).copy(
            conference = ConferenceUiState(
                participants = listOf(
                    ConferenceParticipantRow("self", "1001", isMuted = false, isSpeaking = false, isSelf = true),
                    ConferenceParticipantRow("a", "1004", isMuted = false, isSpeaking = false, isSelf = false),
                    ConferenceParticipantRow("b", "1005", isMuted = false, isSpeaking = false, isSelf = false),
                ),
                rosterAvailable = false,
                fromMerge = true,
            ),
        )

        compose.onNodeWithTag(TAG_ROSTER_SUMMARY, useUnmergedTree = true).assertTextEquals("You, 1004 and 1005")
        compose.onNodeWithTag(TAG_CONFERENCE_BADGE).assertDoesNotExist()

        compose.onNodeWithTag(TAG_ROSTER_TOGGLE).performClick()

        compose.onNodeWithTag(TAG_ROSTER_LIST).assertIsDisplayed()
        compose.onNodeWithText("1004").assertIsDisplayed()
        // In the list, below the fold of the bounded, scrolling column on this screen size.
        compose.onNodeWithText("1005").assertExists()
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithTag(TAG_MERGED_NOTE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a ringing inbound call offers answer and decline, and nothing else`() {
        var answeredWithVideo: Boolean? = null
        setContent(
            display(CallPhase.INCOMING, direction = CallDirection.INCOMING),
            CallActions(onAnswer = { answeredWithVideo = it }),
        )

        compose.onNodeWithTag(TAG_ANSWER).assertIsDisplayed().performClick()
        compose.onNodeWithTag(TAG_DECLINE).assertIsDisplayed()
        compose.waitForIdle()

        assertEquals(false, answeredWithVideo, "a lock-screen answer is audio unless asked otherwise")
    }

    @Test
    fun `a video offer swaps the answer button rather than adding a third`() {
        // Two buttons, never three. An audio offer gets Decline and Answer; a video offer
        // gets Decline and Video, and Video is the *only* way to accept it — the plain
        // audio Answer that used to sit beside it made every incoming video call a choice
        // between two nearly identical buttons, one of which quietly dropped the video.
        val state = setContent(display(CallPhase.INCOMING, direction = CallDirection.INCOMING))
        compose.onNodeWithTag(TAG_ANSWER).assertIsDisplayed()
        compose.onNodeWithTag(TAG_ANSWER_VIDEO).assertDoesNotExist()

        state.value = CallUiState.Active(
            display(CallPhase.INCOMING, direction = CallDirection.INCOMING, videoOffered = true),
        )
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_ANSWER_VIDEO).assertIsDisplayed()
        compose.onNodeWithTag(TAG_ANSWER).assertDoesNotExist()
        compose.onNodeWithTag(TAG_DECLINE).assertIsDisplayed()
    }

    @Test
    fun `accepting a video call answers it with video`() {
        // The defect behind the button change: the call was announced as video, accepted,
        // and connected audio-only because the button that was pressed said audio.
        var answeredWithVideo: Boolean? = null
        setContent(
            display(CallPhase.INCOMING, direction = CallDirection.INCOMING, videoOffered = true),
            CallActions(onAnswer = { answeredWithVideo = it }),
        )

        compose.onNodeWithTag(TAG_ANSWER_VIDEO).performClick()
        compose.waitForIdle()

        assertEquals(true, answeredWithVideo, "accepting a video call must answer with video")
    }

    @Test
    fun `hold is disabled while the call is still ringing`() {
        // Task 39's second done-when, on screen: disabled because of the phase, not
        // because someone remembered to pass a flag.
        setContent(display(CallPhase.RINGING))

        compose.onNodeWithTag(TAG_HOLD).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_MUTE).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_HANG_UP).assertIsEnabled()
    }

    @Test
    fun `hold and mute become available once the call connects`() {
        setContent(display(CallPhase.CONNECTED))

        compose.onNodeWithTag(TAG_HOLD).assertIsEnabled()
        compose.onNodeWithTag(TAG_MUTE).assertIsEnabled()
    }

    @Test
    fun `a connected call shows its duration rather than its phase`() {
        setContent(display(CallPhase.CONNECTED, durationSeconds = 125))

        compose.onNodeWithText("2:05").assertIsDisplayed()
    }

    @Test
    fun `an hour-long call is not shown as sixty-something minutes`() {
        assertEquals("1:00:00", formatDuration(3_600))
        assertEquals("2:05", formatDuration(125))
        assertEquals("0:07", formatDuration(7))
    }

    @Test
    fun `pressing a control reports the opposite of its current state`() {
        var muted: Boolean? = null
        setContent(
            display(CallPhase.CONNECTED, controls = CallControls(isMuted = true)),
            CallActions(onToggleMute = { muted = it }),
        )

        compose.onNodeWithTag(TAG_MUTE).performClick()
        compose.waitForIdle()

        assertEquals(false, muted, "pressing a muted call's mute button unmutes it")
    }

    @Test
    fun `the keypad is hidden until it is asked for, and then sends what is pressed`() {
        // Task 43's UI half. Hidden by default because an in-call screen is mostly used
        // without one, and every key press sends its tone immediately.
        val pressed = mutableListOf<DtmfDigit>()
        setContent(display(CallPhase.CONNECTED), CallActions(onDtmf = { pressed += it }))

        compose.onNodeWithTag(TAG_KEYPAD).assertDoesNotExist()
        compose.onNodeWithTag(TAG_KEYPAD_TOGGLE).assertIsEnabled().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_KEYPAD).assertIsDisplayed()
        compose.onNodeWithTag(keypadKeyTag(DtmfDigit.STAR)).performClick()
        compose.onNodeWithTag(keypadKeyTag(DtmfDigit.NINE)).performClick()
        compose.waitForIdle()

        assertEquals(listOf(DtmfDigit.STAR, DtmfDigit.NINE), pressed)
        // What was sent is shown, because the tone itself is the stack's to play and the
        // digits are deliberately never logged.
        compose.onNodeWithTag(TAG_KEYPAD_SENT).assertTextEquals("*9")
    }

    @Test
    fun `A to D are reachable, but not in the way of a keypad`() {
        // Some PBX signalling needs them and no phone has ever shown them, so they are
        // behind a disclosure rather than in the grid.
        setContent(display(CallPhase.CONNECTED))
        compose.onNodeWithTag(TAG_KEYPAD_TOGGLE).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(keypadKeyTag(DtmfDigit.A)).assertDoesNotExist()
        compose.onNodeWithTag(TAG_KEYPAD_LETTERS).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(keypadKeyTag(DtmfDigit.A)).assertIsDisplayed()
        compose.onNodeWithTag(keypadKeyTag(DtmfDigit.D)).assertIsDisplayed()
    }

    @Test
    fun `a held call cannot open the keypad, because its media is paused`() {
        // Disabled rather than absent: a control that disappears moves the buttons beside
        // it under the user's thumb mid-call.
        val state = setContent(display(CallPhase.CONNECTED))
        compose.onNodeWithTag(TAG_KEYPAD_TOGGLE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_KEYPAD).assertIsDisplayed()

        state.value = CallUiState.Active(display(CallPhase.ON_HOLD))
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_KEYPAD_TOGGLE).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_KEYPAD).assertDoesNotExist()
    }

    @Test
    fun `a call that has ended says so instead of showing controls`() {
        val state = setContent(display(CallPhase.CONNECTED))

        state.value = CallUiState.Finished
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_ENDED).assertIsDisplayed()
        compose.onNodeWithTag(TAG_HANG_UP).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Renders one call and hands back the state, so a test can move it.
     *
     * The rule permits exactly one `setContent`, and several of these tests are about what
     * changes when the call does — which is the interesting half.
     */
    private fun setContent(
        call: CallDisplay,
        actions: CallActions = CallActions(),
    ): MutableState<CallUiState> {
        val state = mutableStateOf<CallUiState>(CallUiState.Active(call))

        compose.setContent {
            WhatsAppV2Theme {
                CallScreen(
                    state = state.value,
                    snackbarHostState = SnackbarHostState(),
                    actions = actions,
                )
            }
        }
        // The v2 rule queues work on a StandardTestDispatcher rather than running it as it
        // arrives, so the first frame has to be waited for explicitly.
        compose.waitForIdle()
        return state
    }

    private fun display(
        phase: CallPhase,
        direction: CallDirection = CallDirection.OUTGOING,
        durationSeconds: Long? = null,
        controls: CallControls = CallControls.DEFAULT,
        videoOffered: Boolean = false,
    ) = CallDisplay(
        callId = CallId("call-1"),
        title = "Carol",
        subtitle = "sip:1002@sip.example.com",
        direction = direction,
        phase = phase,
        controls = controls,
        durationSeconds = durationSeconds,
        videoOffered = videoOffered,
    )
}
