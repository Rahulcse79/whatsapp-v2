package com.whatsappv2.feature.calls

import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.VideoSize
import com.whatsappv2.domain.engine.VideoSizes
import com.whatsappv2.domain.model.CallId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The floating self-view, rendered (Task 61).
 *
 * The one thing here that a pure test cannot reach, and the requirement that matters most:
 * **minimising must not touch the camera.** The preview is an `AndroidView` wrapping a
 * `TextureView`, and if minimising swapped it for an avatar the view would leave the tree,
 * the texture would be destroyed and [CallVideo]'s listener would hand a null to the
 * stack — stopping the preview window mid-call to make a picture smaller. Asserting that
 * the camera node survives the toggle is what pins that.
 *
 * `SelfPreviewPlacementTest` covers the arithmetic; this covers only what needs a tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CALLS_ROBOLECTRIC_SDK], qualifiers = CALLS_SCREEN)
class SelfPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun render(localFrame: VideoSize = VideoSize(720, 1280)) {
        compose.setContent {
            WhatsAppV2Theme {
                val context = LocalContext.current
                val previewView = remember { TextureView(context) }
                Box(modifier = Modifier.fillMaxSize()) {
                    SelfPreview(previewView = previewView, localFrame = localFrame)
                }
            }
        }
    }

    @Test
    fun `the same camera view survives the preview leaving and coming back`() {
        // The `TextureView` is remembered above the preview and reused by every container
        // built for it. Disposing a container does not take the child out of it, so the
        // next `addView` met a view that still had a parent and the process died —
        // "The specified child already has a parent", TC15, 2026-09-22 14:03, on the
        // way back to a call from the launcher. A hold does the same to the preview
        // deliberately now, so this has to be routine.
        val shown = mutableStateOf(true)
        lateinit var camera: TextureView
        compose.setContent {
            WhatsAppV2Theme {
                val context = LocalContext.current
                val previewView = remember { TextureView(context).also { camera = it } }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (shown.value) SelfPreview(previewView = previewView, localFrame = VideoSize(720, 1280))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_PREVIEW_PICTURE).assertIsDisplayed()

        repeat(3) {
            shown.value = false
            compose.waitForIdle()
            compose.onNodeWithTag(TAG_PREVIEW_PICTURE).assertDoesNotExist()

            shown.value = true
            compose.waitForIdle()
            compose.onNodeWithTag(TAG_PREVIEW_PICTURE).assertIsDisplayed()
        }
        assertTrue(camera.parent != null, "the camera is inside the container that is on screen")
    }

    @Test
    fun `it parks above the call controls while they are up, and where it was when they go`() {
        val clearance = mutableStateOf(0)
        compose.setContent {
            WhatsAppV2Theme {
                val context = LocalContext.current
                val previewView = remember { TextureView(context) }
                Box(modifier = Modifier.fillMaxSize()) {
                    SelfPreview(
                        previewView = previewView,
                        localFrame = VideoSize(720, 1280),
                        bottomClearance = clearance.value,
                    )
                }
            }
        }
        compose.waitForIdle()
        val parked = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()

        // The chrome is up: the buttons occupy the bottom third of the screen. A
        // minimised card fits above them, so that is where it goes.
        val controls = root.height * CONTROLS_SHARE
        clearance.value = with(compose.density) { controls.roundToPx() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SNAP_ANIMATION_MS)
        compose.waitForIdle()
        val raised = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()
        val controlsTop = root.bottom - controls
        assertTrue(raised.bottom <= controlsTop, "raised to ${raised.bottom}, controls start at $controlsTop")
        assertTrue(raised.bottom < parked.bottom)

        // The chrome fades: back to the corner it was in.
        clearance.value = 0
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SNAP_ANIMATION_MS)
        compose.waitForIdle()
        assertEquals(parked, compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot())
    }

    @Test
    fun `the clearance is used only when a card still fits above it`() {
        val area = VideoSize(720, 1408)
        val token = 256f
        val margin = 32f
        val minimised = VideoSize(198, 352)
        val maximised = VideoSize(396, 704)

        // Nothing to clear: the token.
        assertEquals(token, controlsInsetFor(minimised, area, margin, token, clearance = 0f))
        // Controls 876px tall: a minimised card (352) fits in the 1408 - 876 = 532 above them.
        assertEquals(876f, controlsInsetFor(minimised, area, margin, token, clearance = 876f))
        // A maximised card (704) does not, and stays on the token rather than covering the title.
        assertEquals(token, controlsInsetFor(maximised, area, margin, token, clearance = 876f))
        // A clearance smaller than the token is not a reason to come closer to the End button.
        assertEquals(token, controlsInsetFor(minimised, area, margin, token, clearance = 100f))
    }

    private fun sizeOf(tag: String): DpSize =
        compose.onNodeWithTag(tag).getBoundsInRoot().let { DpSize(it.width, it.height) }

    @Test
    fun `it shows the camera and a size control, and the grip once maximised`() {
        render()

        compose.onNodeWithTag(TAG_PREVIEW).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PREVIEW_PICTURE).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).assertIsDisplayed()
        // It opens minimised, and minimised is already the floor the grip stops at, so
        // there is nothing for the grip to do until the card has been grown.
        compose.onNodeWithTag(TAG_PREVIEW_GRIP).assertDoesNotExist()

        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()

        compose.onNodeWithTag(TAG_PREVIEW_GRIP).assertIsDisplayed()
    }

    @Test
    fun `maximising doubles the card and minimising brings it back`() {
        // The control's whole job, and what it could not be seen to do before: the two
        // ends were the same size unless the grip had been used first.
        render()
        val minimised = sizeOf(TAG_PREVIEW)

        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()
        val maximised = sizeOf(TAG_PREVIEW)

        assertTrue(
            kotlin.math.abs(minimised.width.value * 2 - maximised.width.value) <= 1f,
            "maximised is not twice minimised: $maximised from $minimised",
        )

        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()
        assertEquals(minimised, sizeOf(TAG_PREVIEW), "minimising did not return to the small card")
    }

    @Test
    fun `minimising shrinks an enlarged preview to the floor and keeps the camera in the tree`() {
        render()
        val opened = sizeOf(TAG_PREVIEW)

        // The box opens minimised, so maximise it before the grip exists to be dragged.
        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()

        // Drag the grip outward — it sits at the box's inward corner, so outward is up and
        // left for the default bottom-right parking — and let go.
        compose.onNodeWithTag(TAG_PREVIEW_GRIP).performTouchInput {
            swipe(start = center, end = Offset(center.x - width * 20, center.y - height * 20), durationMillis = 200)
        }
        val enlarged = sizeOf(TAG_PREVIEW)
        assertTrue(enlarged.width > opened.width, "the grip did not enlarge it: $enlarged from $opened")

        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()

        val minimised = sizeOf(TAG_PREVIEW)
        assertTrue(minimised.width < enlarged.width, "minimising did not shrink it: $minimised")
        assertEquals(opened, minimised, "minimised is not the size the box opened at, the floor")

        // The whole point. A surface that leaves the tree is destroyed, and a destroyed
        // surface is reported to the stack as "stop drawing" — so the camera would be
        // interrupted every time somebody tidied their screen.
        compose.onNodeWithTag(TAG_PREVIEW_PICTURE).assertIsDisplayed()
    }

    @Test
    fun `restoring gives back the size it had, and the grip with it`() {
        render()

        // Maximise first — the preview opens minimised now — and that is the size the
        // round trip below has to give back.
        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()
        val full = sizeOf(TAG_PREVIEW)
        compose.onNodeWithTag(TAG_PREVIEW_GRIP).assertIsDisplayed()

        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()
        // No grip while minimised: minimised is already the floor the grip stops at.
        compose.onNodeWithTag(TAG_PREVIEW_GRIP).assertDoesNotExist()

        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()

        assertEquals(full, sizeOf(TAG_PREVIEW), "restoring did not return to the size it had")
        compose.onNodeWithTag(TAG_PREVIEW_GRIP).assertIsDisplayed()
    }

    @Test
    fun `the preview is 9-16 portrait, and the camera's window is the whole of it`() {
        // A landscape camera, deliberately: the box's shape is its own and takes nothing
        // from the frame, so a 1280x720 sensor must still produce a tall card.
        render(localFrame = VideoSize(1280, 720))

        val box = sizeOf(TAG_PREVIEW)
        val window = sizeOf(TAG_PREVIEW_PICTURE)

        val expectedHeight = box.width.value *
            VideoLayout.PREVIEW_ASPECT_HEIGHT / VideoLayout.PREVIEW_ASPECT_WIDTH
        assertTrue(
            kotlin.math.abs(box.height.value - expectedHeight) <= 1f,
            "not 9:16: $box",
        )
        // The camera's visible window is the box itself, with no margin inside it. The
        // view *within* that window is larger — `VideoLayout.previewPicture` covers rather
        // than fits — and the surplus is cropped by the clipping container, which is an
        // Android view and therefore not something a Compose test can measure. That the
        // picture covers is asserted in `VideoLayoutTest`; that it is cropped rather than
        // overflowing depends on the camera being a `TextureView` (a `SurfaceView` drew
        // across the whole screen, 2026-09-17), which only a handset shows.
        assertEquals(box, window, "the camera does not fill its box")
    }

    @Test
    fun `it parks clear of the screen edges and of the call controls`() {
        render()

        val root = compose.onRoot().getBoundsInRoot()
        val preview = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()

        // Bottom-right by default, and inset above the End button — the one control every
        // user has to be able to find in a hurry.
        assertTrue(preview.left.value > root.left.value, "flush against the left edge")
        assertTrue(preview.right.value < root.right.value, "flush against the right edge")
        assertTrue(
            preview.bottom.value < root.bottom.value - CONTROLS_CLEARANCE_DP,
            "the preview reaches down into the call controls: $preview in $root",
        )
    }

    // =========================================================== inside the real screen

    /**
     * The whole call screen, with a video call up, so the preview is competing with
     * everything that is actually on top of it.
     *
     * [CallScreen] puts a full-screen `clickable` over the video to toggle the chrome, and
     * it is composed *after* the video layer. A drag has to reach the preview through it —
     * which is the difference between a self-view you can move and one that only toggles
     * the call controls when you try.
     */
    private fun renderCallScreen() {
        val call = CallDisplay(
            callId = CallId("call-1"),
            title = "Carol",
            subtitle = "sip:1002@sip.example.com",
            direction = CallDirection.OUTGOING,
            phase = CallPhase.CONNECTED,
            controls = CallControls(isVideoEnabled = true),
            durationSeconds = 5,
            videoOffered = false,
            videoActive = true,
        )
        compose.setContent {
            WhatsAppV2Theme {
                CallScreen(
                    state = CallUiState.Active(call, videoSizes = VideoSizes(local = VideoSize(720, 1280))),
                    snackbarHostState = SnackbarHostState(),
                    actions = CallActions(),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a drag reaches the preview through the screen's chrome layer, and moves it`() {
        renderCallScreen()
        val before = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()

        // Bottom-right by default; flick it across to the opposite corner.
        compose.onNodeWithTag(TAG_PREVIEW).performTouchInput {
            swipe(start = center, end = Offset(center.x - width * 4, center.y - height * 8), durationMillis = 200)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SNAP_ANIMATION_MS)
        compose.waitForIdle()

        val after = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()
        assertTrue(
            after.left.value < before.left.value && after.top.value < before.top.value,
            "the preview did not move: $before -> $after",
        )
    }

    @Test
    fun `ending the drag snaps it to a corner rather than leaving it adrift`() {
        renderCallScreen()
        val root = compose.onRoot().getBoundsInRoot()

        // A short drag towards the middle. Released there, a preview that did not snap
        // would sit over somebody's face for the rest of the call.
        compose.onNodeWithTag(TAG_PREVIEW).performTouchInput {
            swipe(start = center, end = Offset(center.x - width, center.y - height), durationMillis = 200)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SNAP_ANIMATION_MS)
        compose.waitForIdle()

        val after = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()
        val nearLeft = after.left.value - root.left.value
        val nearRight = root.right.value - after.right.value
        assertTrue(
            kotlin.math.min(nearLeft, nearRight) < SNAP_MARGIN_DP,
            "released mid-screen and stayed there: $after in $root",
        )
    }

    @Test
    fun `tapping the picture still toggles the chrome, and dragging the preview does not`() {
        // The two halves of one layering rule, and breaking either breaks the other. The
        // chrome toggle sits between the remote picture and the self-view: above the
        // `SurfaceView`, which takes any pointer laid over it, and below the preview, so a
        // drag moves the preview instead of hiding the controls.
        renderCallScreen()

        // Controls are up to begin with; a tap on the picture away from the preview hides
        // them. If the remote SurfaceView were on top this would do nothing at all.
        compose.onNodeWithTag(TAG_HANG_UP).assertIsDisplayed()
        // Tapped near the top of the picture rather than at its centre: the self-view is
        // large enough now to sit over the middle of the screen, and a centre tap would land
        // on the preview's own gesture surface instead of the picture behind it.
        compose.onNodeWithTag(TAG_PICTURE_TAP).performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_HANG_UP).assertDoesNotExist()

        // And a drag on the preview moves it rather than bringing the controls back.
        val before = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()
        compose.onNodeWithTag(TAG_PREVIEW).performTouchInput {
            swipe(start = center, end = Offset(center.x - width * 4, center.y - height * 8), durationMillis = 200)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(SNAP_ANIMATION_MS)
        compose.waitForIdle()

        val after = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()
        assertTrue(after.left.value < before.left.value, "the drag did not move the preview")
        compose.onNodeWithTag(TAG_HANG_UP).assertDoesNotExist()
    }

    @Test
    fun `a tap outside a maximised preview minimises it and leaves the controls alone`() {
        // Asked for on 2026-09-21: the maximised self-view is nine tenths of the screen and
        // sits over somebody's tile; a tap on the rest of the screen should put it away.
        // One gesture, one effect — the controls stay exactly as they were, and the next
        // tap is theirs again.
        renderCallScreen()
        compose.onNodeWithTag(TAG_PREVIEW_TOGGLE).performClick()
        compose.waitForIdle()
        val maximised = sizeOf(TAG_PREVIEW)
        compose.onNodeWithTag(TAG_PREVIEW_GRIP).assertIsDisplayed()
        compose.onNodeWithTag(TAG_HANG_UP).assertIsDisplayed()

        compose.onNodeWithTag(TAG_PICTURE_TAP).performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
        compose.waitForIdle()

        val minimised = sizeOf(TAG_PREVIEW)
        assertTrue(minimised.width < maximised.width, "the tap outside did not minimise the preview: $minimised")
        compose.onNodeWithTag(TAG_PREVIEW_GRIP).assertDoesNotExist()
        compose.onNodeWithTag(TAG_PREVIEW_PICTURE).assertIsDisplayed()
        compose.onNodeWithTag(TAG_HANG_UP).assertIsDisplayed()

        // Minimised, the same tap is the chrome's again.
        compose.onNodeWithTag(TAG_PICTURE_TAP).performTouchInput { click(Offset(width * 0.5f, height * 0.1f)) }
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_HANG_UP).assertDoesNotExist()
    }

    @Test
    fun `the gesture surface covers the whole preview, camera included`() {
        // The defect this pins: the gestures used to live on the box *behind* the camera,
        // and Compose's AndroidView interop takes the pointer for the camera view first.
        // Once the box matched the camera there was no bare Compose surface left, and the
        // preview stopped being draggable anywhere on a handset.
        render()

        val box = compose.onNodeWithTag(TAG_PREVIEW).getBoundsInRoot()
        val surface = compose.onNodeWithTag(TAG_PREVIEW_SURFACE).getBoundsInRoot()

        assertEquals(box, surface, "the gesture surface does not cover the preview")
    }

    private companion object {
        const val RATIO_TOLERANCE = 0.02f

        /** Less than `Sizing.videoPreviewControlsInset`, so the assertion is about clearing them. */
        const val CONTROLS_CLEARANCE_DP = 100f

        /** `Spacing.large` plus slack: a snapped preview is against a margin, not adrift. */
        const val SNAP_MARGIN_DP = 24f

        /** Comfortably past the corner-snap spring, so bounds are the settled ones. */
        const val SNAP_ANIMATION_MS = 2_000L

        /** How much of the screen's height the in-call controls take, near enough for this test. */
        const val CONTROLS_SHARE = 0.36f
    }
}
