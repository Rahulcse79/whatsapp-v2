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
import com.whatsappv2.ui.chats.TAG_CHATS_SETTINGS
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
 * Task 15 done-when #1, restated for the shell as it stands: every top-level destination
 * is still reachable, and the shell survives a configuration change.
 *
 * It clicks what is on screen — the two tabs, the floating button on Calls, the gear on
 * Chats, and the accounts row inside settings. That is the check worth having. Route
 * uniqueness is unit-tested next door, but that proves the routes differ, not that
 * anything on screen can actually open them — which is exactly what regressed when the
 * bar was removed, and what would regress again if the gear went missing.
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
    fun `the app opens on Chats, the bar's first tab`() {
        // It opened on Calls, which meant the first tab of a two-tab bar was never the one
        // you landed on. The assertion names the tab and its selected state rather than
        // the word, because the label is on screen twice — as the title and as the tab.
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithTag(tabTag(AppDestination.CHATS)).assertIsSelected()
        compose.onNodeWithText("Messages are coming").assertIsDisplayed()
    }

    @Test
    fun `the bottom bar switches between the two top-level destinations`() {
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithTag(tabTag(AppDestination.CHATS)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Messages are coming").assertIsDisplayed()
        compose.onNodeWithTag(tabTag(AppDestination.CHATS)).assertIsSelected()

        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).assertIsSelected()
    }

    @Test
    fun `settings is not a tab, so the bar has no way to open it`() {
        // The bar carries the places the app can be. Settings is somewhere you go to and
        // come back from, and a third tab for it was the arrangement this replaced.
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        compose.onNodeWithTag(tabTag(AppDestination.SETTINGS)).assertDoesNotExist()
    }

    @Test
    fun `the bar is hidden on a screen you navigated into`() {
        // A dialler is somewhere you went *to*. Offering to switch tabs from inside one
        // invites losing what you were doing — half a number, in this case.
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        // Via Calls, which is where the dialler's button lives; the app opens on Chats.
        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open the dialler").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).assertDoesNotExist()
    }

    @Test
    fun `the floating button opens the dialler`() {
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        // Via Calls, which is where the dialler's button lives; the app opens on Chats.
        compose.onNodeWithTag(tabTag(AppDestination.HISTORY)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open the dialler").performClick()
        compose.waitForIdle()

        // The dialler's own heading is its input field's label (Task 36): the screen is a
        // keypad, and a screen whose purpose is obvious does not need a title above it.
        compose.onNodeWithText("Number or SIP address").assertIsDisplayed()
    }

    @Test
    fun `settings is behind the gear on Chats, and accounts are still inside it`() {
        // Two taps to settings — the Chats tab, then its gear — and one more to accounts.
        // Accounts did NOT get a shortcut of their own, and this is the path that says so.
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        openSettings()
        compose.onNodeWithText("App settings").assertIsDisplayed()
        // Settings is somewhere you went *to*, so the bar is gone while you are there.
        compose.onNodeWithTag(tabTag(AppDestination.CHATS)).assertDoesNotExist()

        compose.onNodeWithText("SIP accounts").performClick()
        compose.waitForIdle()
        // The list's own back arrow, not its empty state: the accounts screen opens on
        // Loading while the store is read, so asserting "No SIP accounts" is asserting
        // that a database read has finished rather than that navigation worked.
        compose.onNodeWithContentDescription("Back to settings").assertIsDisplayed()
    }

    @Test
    fun `every setting is still there after the screen was restyled`() {
        // The check that would catch a visual pass quietly dropping a control. Task 69
        // wrote it for its own removal; it earns its keep again now the screen has been
        // laid out afresh.
        compose.setContent { WhatsAppV2Theme { AppRoot() } }

        openSettings()

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

        openSettings()
        compose.onNodeWithText("App settings").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithText("App settings").assertIsDisplayed()
    }

    /** The one way in: the Chats tab, then the gear in its top bar. */
    private fun openSettings() {
        compose.onNodeWithTag(tabTag(AppDestination.CHATS)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_CHATS_SETTINGS).performClick()
        compose.waitForIdle()
    }
}
