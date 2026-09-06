package com.whatsappv2.window

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.whatsappv2.core.designsystem.window.SecureScreen
import com.whatsappv2.di.ROBOLECTRIC_SDK
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 21's third done-when, deferred to Task 63 and now met: **the screen sets
 * `FLAG_SECURE`.**
 *
 * ## Why the flag being *cleared* is the assertion that matters
 *
 * Setting it is easy and any implementation gets that right. The bug is the other half: a
 * flag set on the way into the account editor and left behind makes every later screen
 * un-screenshotable, and nobody reports it as a bug — they report that screenshots
 * "randomly stop working", months later, with no idea which screen did it.
 *
 * The `DisposableEffect`'s `onDispose` is what prevents that, and this test is the only
 * thing that would notice if it were removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SecureScreenTest {

    // A plain ComponentActivity, not the Hilt one: nothing here is injected, and a
    // @AndroidEntryPoint activity would need a Hilt application to attach to for no gain.
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val flags: Int
        get() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test
    fun `a screen that declares itself secure marks the window`() {
        compose.setContent { SecureScreen() }
        compose.waitForIdle()

        assertTrue(flags != 0, "FLAG_SECURE was not set while the secure screen was composed")
    }

    @Test
    fun `leaving the screen clears the flag again`() {
        var showSecure by mutableStateOf(true)
        compose.setContent { if (showSecure) SecureScreen() }
        compose.waitForIdle()
        assertTrue(flags != 0, "FLAG_SECURE was not set to begin with")

        showSecure = false
        compose.waitForIdle()

        // Otherwise every screen after the account editor is silently un-screenshotable.
        assertFalse(flags != 0, "FLAG_SECURE outlived the screen that asked for it")
    }

    @Test
    fun `a window with no secure screen on it is not marked`() {
        compose.setContent { }
        compose.waitForIdle()

        assertFalse(flags != 0, "FLAG_SECURE was set with no secure screen composed")
    }
}
