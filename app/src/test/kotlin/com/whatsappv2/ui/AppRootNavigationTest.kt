package com.whatsappv2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.HiltTestActivity
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.di.ROBOLECTRIC_SDK
import com.whatsappv2.ui.navigation.AppDestination
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Before
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
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [ROBOLECTRIC_SDK])
class AppRootNavigationTest {

    // Hilt first: the graph must be ready before the Activity is created.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    // An @AndroidEntryPoint host, because the account destination calls hiltViewModel()
    // and a plain ComponentActivity cannot satisfy it.
    //
    // The v2 rule, not the deprecated original: it uses StandardTestDispatcher, which
    // queues work rather than running it immediately, so the assertions below need the
    // explicit waitForIdle() calls that the old rule made unnecessary.
    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() = hilt.inject()

    /**
     * The heading each destination renders, so a tap can be checked against something.
     *
     * Every heading differs from its tab label on purpose: a screen titled the same as
     * its tab is redundant to read, and it makes the text matcher ambiguous between the
     * bar and the content - which is how the first version of this test failed.
     */
    private val headingFor = mapOf(
        // The dialler's own heading is its input field's label (Task 36): the screen is a
        // keypad, and a screen whose purpose is obvious does not need a title above it.
        AppDestination.DIALER to "Number or SIP address",
        AppDestination.HISTORY to "Call history",
        AppDestination.ACCOUNTS to "SIP accounts",
        AppDestination.SETTINGS to "App settings",
    )

    @Test
    fun `the app opens on the dialer`() {
        compose.setContent { WhatsAppV2Theme { AppRoot() } }
        compose.onNodeWithText("Number or SIP address").assertIsDisplayed()
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
