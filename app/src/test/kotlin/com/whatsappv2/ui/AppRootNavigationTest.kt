package com.whatsappv2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Task 15 done-when #1, restated for the shell Tasks 69 and 70 left behind: every
 * top-level destination is still reachable, and the shell survives a configuration change.
 *
 * The bottom bar is gone, so this no longer clicks tab labels — it clicks the affordances
 * that replaced them: the floating buttons on Calls, the settings action in its top bar,
 * and the accounts row inside settings. That is the check worth having. Route uniqueness
 * is unit-tested next door, but that proves the routes differ, not that anything on screen
 * can actually open them — which is exactly what regressed when the bar was removed.
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

    @Test
    fun `the app opens on Calls`() {
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        // "Calls" is on screen twice now — the screen's title and its tab — so the
        // assertion names the tab and its selected state rather than the word.
        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).assertIsSelected()
    }

    @Test
    fun `the bottom bar switches between the three top-level destinations`() {
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithTag(tabTag(AppDestination.CHATS)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Messages are coming").assertIsDisplayed()

        compose.onNodeWithTag(tabTag(AppDestination.SETTINGS)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(tabTag(AppDestination.SETTINGS)).assertIsSelected()

        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).assertIsSelected()
    }

    @Test
    fun `the bar is hidden on a screen you navigated into`() {
        // A dialler is somewhere you went *to*. Offering to switch tabs from inside one
        // invites losing what you were doing — half a number, in this case.
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithContentDescription("Open the dialler").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).assertDoesNotExist()
    }

    @Test
    fun `the floating button opens the dialler`() {
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithContentDescription("Open the dialler").performClick()
        compose.waitForIdle()

        // The dialler's own heading is its input field's label (Task 36): the screen is a
        // keypad, and a screen whose purpose is obvious does not need a title above it.
        compose.onNodeWithText("Number or SIP address").assertIsDisplayed()
    }

    @Test
    fun `settings is reachable from the top bar, and accounts from settings`() {
        // Task 69's whole claim in one path: neither is a tab any more, and both must
        // still be reachable in a couple of taps.
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithContentDescription("Settings and accounts").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("App settings").assertIsDisplayed()

        compose.onNodeWithText("SIP accounts").performClick()
        compose.waitForIdle()
        // The list's own back arrow, not its empty state: the accounts screen opens on
        // Loading while the store is read, so asserting "No SIP accounts" is asserting
        // that a database read has finished rather than that navigation worked.
        compose.onNodeWithContentDescription("Back to settings").assertIsDisplayed()
    }

    @Test
    fun `every setting the Settings tab used to hold is still there`() {
        // Task 69 removed the destination, not the controls. This is the check that would
        // catch "remove the Settings page" being read as "delete what was on it".
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithContentDescription("Settings and accounts").performClick()
        compose.waitForIdle()

        // Scrolled to, not merely present: the settings body is a scrolling column, so
        // the lower two groups exist off-screen whether or not anything renders them.
        compose.onNodeWithText("DTMF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Default media encryption").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Audio route").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the open destination survives a configuration change`() {
        // A recreation is what rotation does. Settings must still be on screen afterwards:
        // the back stack is the single source of truth for where the app is, and
        // rememberNavController is what carries it across the recreation.
        val restoration = StateRestorationTester(compose)
        restoration.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithContentDescription("Settings and accounts").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("App settings").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithText("App settings").assertIsDisplayed()
    }
}
