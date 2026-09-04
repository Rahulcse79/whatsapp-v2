package com.whatsappv2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.di.ROBOLECTRIC_SDK
import com.whatsappv2.ui.navigation.AppDestination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 15 done-when #1: every top-level destination is reachable, and the shell survives
 * a configuration change.
 *
 * Route uniqueness is unit-tested elsewhere, but that proves the routes differ - not
 * that tapping a tab actually shows its screen. Only rendering the shell and clicking
 * does that, and it is the check that would catch a destination registered in the bar
 * but missing from the nav graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AppRootNavigationTest {

    // The v2 rule, not the deprecated original: it uses StandardTestDispatcher, which
    // queues work rather than running it immediately, so the assertions below need the
    // explicit waitForIdle() calls that the old rule made unnecessary.
    @get:Rule
    val compose = createComposeRule()

    /** The heading each destination renders, so a tap can be checked against something. */
    private val headingFor = mapOf(
        AppDestination.DIALER to "Dialer",
        AppDestination.HISTORY to "Call history",
        AppDestination.ACCOUNTS to "SIP accounts",
        AppDestination.SETTINGS to "Settings",
    )

    @Test
    fun `the app opens on the dialer`() {
        compose.setContent { WhatsAppV2Theme { AppRoot() } }
        compose.onNodeWithText("Dialer").assertIsDisplayed()
    }

    @Test
    fun `every top-level destination is reachable from the bottom bar`() {
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        for (destination in AppDestination.entries) {
            compose.onNodeWithText(destination.label).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(checkNotNull(headingFor[destination])).assertIsDisplayed()
        }
    }

    @Test
    fun `the selected destination survives a configuration change`() {
        // A recreation is what rotation does. The tab must still be Accounts afterwards:
        // deriving the selection from the nav back stack rather than from a separate
        // variable is exactly what makes this hold.
        val restoration = StateRestorationTester(compose)
        restoration.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithText(AppDestination.ACCOUNTS.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("SIP accounts").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithText("SIP accounts").assertIsDisplayed()
    }
}
