package com.whatsappv2.permission

import android.content.Context
import android.os.PowerManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.background.BackgroundAccess
import com.whatsappv2.di.ROBOLECTRIC_SDK
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The last first-run step: "run in the background".
 *
 * ## Why it is asked for here at all
 *
 * It used to be asked for only after the first login, by `BackgroundAccessPrompt`. That
 * left a real gap: somebody who installs the app, walks through first run and puts the
 * phone down has never been asked, and battery optimisation is precisely what stops the
 * app ringing later. So it is a step in the first-run flow now — and, because it is a
 * system switch rather than a runtime permission, it is not an `AppPermission` and gets a
 * step of its own shape.
 *
 * Every runtime permission is granted in these tests, so what is left on screen is the one
 * step under examination and the step numbering is unambiguous.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PermissionOnboardingBackgroundStepTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val coordinator = PermissionCoordinator(context, SharedPreferencesPermissionTracker(context))
    private val access = BackgroundAccess(context)

    /** Nothing runtime left to ask, so the background step is the whole of the screen. */
    private fun grantEveryRuntimePermission() {
        val shadow = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        AppPermission.entries.forEach { shadow.grantPermissions(it.manifestPermission) }
    }

    private fun setExempt(exempt: Boolean) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, exempt)
    }

    private fun setContent(onFinished: () -> Unit = {}) {
        compose.setContent {
            PermissionOnboarding(
                coordinator = coordinator,
                onFinished = onFinished,
                backgroundAccess = access,
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun `a phone that restricts the app is asked, as the last step`() {
        grantEveryRuntimePermission()
        setExempt(false)

        setContent()

        compose.onNodeWithText("Run in the background").assertIsDisplayed()
        // Last of however many there are. With every runtime permission granted that is
        // one of one, which is also the assertion that it is counted at all.
        compose.onNodeWithTag(TAG_PROGRESS).assertIsDisplayed()
        compose.onNodeWithText("Step 1 of 1").assertIsDisplayed()
    }

    @Test
    fun `a phone that already allows it is not asked`() {
        // Asking for something the user has already given is the dialog that teaches
        // people this screen wastes their time — the same rule `onboardingPermissions`
        // applies to the runtime permissions.
        grantEveryRuntimePermission()
        setExempt(true)
        var finished = false

        setContent(onFinished = { finished = true })

        compose.onNodeWithText("Run in the background").assertDoesNotExist()
        assertTrue(finished, "with nothing left to ask, the screen must hand over to the app")
    }

    @Test
    fun `answering the step stops it being asked again after the first login`() {
        // `BackgroundAccessPrompt` shows once, gated on `hasBeenAsked`. Without this the
        // user would be asked here and then asked again the moment an account registered,
        // which is the nagging the one-shot rule exists to prevent.
        grantEveryRuntimePermission()
        setExempt(false)
        assertFalse(access.hasBeenAsked(), "nothing has asked yet")

        var finished = false
        setContent(onFinished = { finished = true })
        compose.onNodeWithTag(TAG_SKIP).performClick()
        compose.waitForIdle()

        assertTrue(access.hasBeenAsked(), "'Not now' is an answer, and it must be recorded")
        assertTrue(finished, "answering the last step finishes the flow")
    }

    @Test
    fun `leaving by Skip all does not count as an answer`() {
        // The deliberate asymmetry. "Skip all" is a door out of the whole screen, not a
        // decision about this one thing, so the post-login prompt is still owed to
        // somebody who used it — otherwise skipping first run would silently cost them
        // the question entirely.
        grantEveryRuntimePermission()
        setExempt(false)
        var finished = false

        setContent(onFinished = { finished = true })
        compose.onNodeWithTag(TAG_SKIP_ALL).performClick()
        compose.waitForIdle()

        assertTrue(finished)
        assertFalse(access.hasBeenAsked(), "skipping the screen must leave the question open")
    }

    @Test
    fun `the step says what saying no costs`() {
        // Every other step does, and this one is the only one whose cost is invisible
        // until a call is missed days later.
        grantEveryRuntimePermission()
        setExempt(false)

        setContent()

        compose.onNodeWithText("If you say no:", substring = true).assertIsDisplayed()
    }
}
