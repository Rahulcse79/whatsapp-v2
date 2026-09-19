package com.whatsappv2.background

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.whatsappv2.di.ROBOLECTRIC_SDK
import com.whatsappv2.feature.settings.BackgroundAccessLink
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settings row's battery-optimisation state, and when it is re-read.
 *
 * ## The defect this is written against
 *
 * The row used to be built from `access.isExempt()` evaluated inline in the nav graph,
 * under a comment claiming the state was re-read "each time the screen composes, so coming
 * back from the system dialog shows the new answer". Granting the exemption leaves this
 * activity for a system one and comes back, which is a *resume* and not a recomposition —
 * nothing invalidated, nothing re-read, and the row went on saying **Restricted** over a
 * phone that had just been told to allow it. The only way to see the truth was to leave
 * the screen and come back, which is the one thing somebody who has just done what the row
 * asked has no reason to do.
 *
 * So what is asserted below is exactly that: a value that changed while the composition
 * sat still is picked up by `ON_RESUME` alone, with nothing else nudging it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class BackgroundAccessLinkTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * A lifecycle the test drives, standing in for the activity behind the system dialog.
     *
     * Starts at `CREATED` and is moved to `STARTED` by hand, so the first `ON_RESUME` the
     * test sends is a real transition rather than a no-op on an owner that was already
     * resumed — `LifecycleRegistry` collapses a repeat of the state it is in.
     */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry

        init {
            registry.currentState = Lifecycle.State.STARTED
        }

        /** What returning from another activity does. */
        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
            registry.currentState = Lifecycle.State.STARTED
        }
    }

    /** The platform's answer, movable, and counting how often it was asked. */
    private class Exemption(var granted: Boolean = false) {
        var reads = 0

        fun read(): Boolean {
            reads++
            return granted
        }
    }

    @Test
    fun `an exemption granted while the screen sat still is shown on resume`() {
        val exemption = Exemption(granted = false)
        val owner = TestOwner()
        var link: BackgroundAccessLink? = null

        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                link = rememberBackgroundAccessLink(isExempt = exemption::read, onOpen = {})
            }
        }
        compose.waitForIdle()
        assertEquals(false, link?.allowed, "the phone starts out restricting the app")

        // What the system dialog does, seen from this composition: the answer changes
        // somewhere else entirely and nothing here recomposes.
        exemption.granted = true
        compose.waitForIdle()
        assertEquals(false, link?.allowed, "nothing has resumed yet, so nothing has re-read")

        owner.resume()
        compose.waitForIdle()

        assertEquals(true, link?.allowed, "returning from the system dialog must show Allowed")
    }

    @Test
    fun `an exemption taken away is shown too`() {
        // The same path in the other direction. Somebody who turns the app's background
        // access off in system settings and comes back must not be told it is still on —
        // that is the state that silently loses calls.
        val exemption = Exemption(granted = true)
        val owner = TestOwner()
        var link: BackgroundAccessLink? = null

        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                link = rememberBackgroundAccessLink(isExempt = exemption::read, onOpen = {})
            }
        }
        compose.waitForIdle()
        assertEquals(true, link?.allowed)

        exemption.granted = false
        owner.resume()
        compose.waitForIdle()

        assertEquals(false, link?.allowed)
    }

    @Test
    fun `the answer is re-read on a resume and not on a recomposition`() {
        // `isExempt` calls PowerManager. Re-reading it on every recomposition would put a
        // system call in the frame path of a screen that is otherwise static — which is
        // what the inline read it replaced was relying on, and never actually got.
        val exemption = Exemption()
        val owner = TestOwner()
        var nudge by mutableStateOf(0)

        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                // Read so that changing it actually recomposes this scope.
                @Suppress("UNUSED_EXPRESSION")
                nudge
                rememberBackgroundAccessLink(isExempt = exemption::read, onOpen = {})
            }
        }
        compose.waitForIdle()
        val afterFirstComposition = exemption.reads

        nudge = 1
        compose.waitForIdle()
        assertEquals(afterFirstComposition, exemption.reads, "a recomposition must not re-read")

        owner.resume()
        compose.waitForIdle()
        assertTrue(exemption.reads > afterFirstComposition, "a resume must re-read")
    }

    @Test
    fun `a composition with no background access has no row to show`() {
        // How a preview, and a build with nothing to show for it, get a settings screen
        // without the row rather than one wired to nothing.
        var link: BackgroundAccessLink? = BackgroundAccessLink(allowed = true, onOpen = {})

        compose.setContent { link = rememberBackgroundAccessLink() }
        compose.waitForIdle()

        assertNull(link)
    }
}
