package com.whatsappv2.permission

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.di.ROBOLECTRIC_SDK
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The microphone gate, and the one rule that separates it from the camera's.
 *
 * ## The defect
 *
 * The dialler's *Call* button had no gate at all. A call placed without `RECORD_AUDIO`
 * reached the stack, which then could not open a capture device — measured on a TC15
 * (2026-09-19), `AudioFlinger could not create record track, status: -1` a dozen times in
 * a fifth of a second — and the call hung at *Calling* or dropped with nothing on screen
 * to say why.
 *
 * So what is asserted here is narrow and is the whole point: **granted proceeds, missing
 * does not**. [AppPermission.RECORD_AUDIO] declares itself
 * [DenialBehaviour.BLOCKS_FEATURE], and this is the code that makes the declaration true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class MicrophoneGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val coordinator = PermissionCoordinator(context, SharedPreferencesPermissionTracker(context))

    private fun grantMicrophone() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(AppPermission.RECORD_AUDIO.manifestPermission)
    }

    private fun denyMicrophone() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .denyPermissions(AppPermission.RECORD_AUDIO.manifestPermission)
    }

    /** Presses a call button once and reports how often the call was actually placed. */
    private fun placeCallsAttempted(): Int {
        var placed = 0
        compose.setContent {
            CompositionLocalProvider(LocalPermissionCoordinator provides coordinator) {
                val gate = rememberMicrophoneGate()
                androidx.compose.runtime.LaunchedEffect(Unit) { gate { placed++ } }
            }
        }
        compose.waitForIdle()
        return placed
    }

    @Test
    fun `a granted microphone places the call straight away`() {
        // The common case by far, and it must not cost a dialog or a frame: there is
        // nothing to ask, so `proceed` runs inline.
        grantMicrophone()

        assertEquals(1, placeCallsAttempted())
    }

    @Test
    fun `a missing microphone does not place the call`() {
        // The defect, pinned. Placing it anyway is what produced a call that hangs at
        // "Calling" with an AudioRecord error loop behind it and no explanation in front.
        denyMicrophone()

        assertEquals(0, placeCallsAttempted(), "the call was placed without a microphone")
    }

    @Test
    fun `the microphone is required, unlike the camera`() {
        // The two gates exist separately because the two permissions declare different
        // consequences, and this is the assertion that keeps them from being merged back
        // together by someone who notices how similar the code looks.
        assertEquals(DenialBehaviour.BLOCKS_FEATURE, AppPermission.RECORD_AUDIO.denialBehaviour)
        assertEquals(DenialBehaviour.DEGRADES_GRACEFULLY, AppPermission.CAMERA.denialBehaviour)
    }
}
