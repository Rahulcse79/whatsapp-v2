package com.whatsappv2.feature.calls

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
@Config(sdk = [CALLS_ROBOLECTRIC_SDK])
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
    fun `a video answer is offered only when video was offered`() {
        // Answering with video an offer that was audio-only is an escalation the peer
        // never asked for, so the button is not there to press.
        val state = setContent(display(CallPhase.INCOMING, direction = CallDirection.INCOMING))
        compose.onNodeWithTag(TAG_ANSWER_VIDEO).assertDoesNotExist()

        state.value = CallUiState.Active(
            display(CallPhase.INCOMING, direction = CallDirection.INCOMING, videoOffered = true),
        )
        compose.waitForIdle()

        compose.onNodeWithTag(TAG_ANSWER_VIDEO).assertIsDisplayed()
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
