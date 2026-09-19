package com.whatsappv2.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The settings screen, rendered.
 *
 * Uses the stateless overload with a literal state, so it needs no Hilt and no DataStore -
 * it is testing what the screen shows and offers, and the ViewModel is covered separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [SETTINGS_ROBOLECTRIC_SDK])
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setContent(
        state: SettingsUiState = SettingsUiState(AppSettings.DEFAULT, traceToggleAvailable = true),
        onDtmf: (DtmfMode) -> Unit = {},
        onSrtp: (SrtpPolicy) -> Unit = {},
        onTrace: (Boolean) -> Unit = {},
        onTheme: (ThemeMode) -> Unit = {},
        onRetention: (CallHistoryRetention) -> Unit = {},
        backgroundAccess: BackgroundAccessLink? = null,
    ) {
        compose.setContent {
            WhatsAppV2Theme {
                SettingsScreen(
                    state = state,
                    actions = SettingsActions(
                        onDtmfModeChange = onDtmf,
                        onSrtpPolicyChange = onSrtp,
                        onAudioRouteChange = {},
                        onThemeModeChange = onTheme,
                        onSipTraceChange = onTrace,
                        onRetentionChange = onRetention,
                    ),
                    links = SettingsLinks(onOpenAccounts = {}, backgroundAccess = backgroundAccess),
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `the background-access row says which way the phone is set, and opens the system screen`() {
        // Restricted is the state that loses calls, and it is said as that rather than as
        // "battery optimisation", which does not sound like something that stops a phone
        // ringing. The row cannot switch it - only the system dialog can - so it opens that.
        var opened = 0
        setContent(backgroundAccess = BackgroundAccessLink(allowed = false, onOpen = { opened++ }))

        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Restricted", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the background-access row shows Allowed once the exemption is granted`() {
        setContent(backgroundAccess = BackgroundAccessLink(allowed = true, onOpen = {}))

        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS).performScrollTo()
        compose.onNodeWithText("Allowed", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the chevron is gone once background access is allowed`() {
        // A `>` promises that tapping leads somewhere worth going. Allowed is a settled
        // state with nothing left to ask for, and a chevron beside it reads as an
        // unfinished errand on a phone that is already set up correctly.
        setContent(backgroundAccess = BackgroundAccessLink(allowed = true, onOpen = {}))

        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS).performScrollTo()
        // The unmerged tree: the row is `clickable`, which merges its children's
        // semantics, so a decorative icon carrying only a test tag is not a node of its
        // own in the merged one whether it is drawn or not.
        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS_CHEVRON, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `the chevron is there while something still needs doing`() {
        // The other half: Restricted ends in "Tap to allow", and the chevron is what makes
        // those words look like a control rather than a complaint.
        setContent(backgroundAccess = BackgroundAccessLink(allowed = false, onOpen = {}))

        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS).performScrollTo()
        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS_CHEVRON, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `an allowed row still opens the system screen, so it can be turned back off`() {
        // Losing the chevron loses the invitation, not the door. The system screen is the
        // only place background access can be revoked, and the app must not be the one
        // route to a setting it then refuses to offer.
        var opened = 0
        setContent(backgroundAccess = BackgroundAccessLink(allowed = true, onOpen = { opened++ }))

        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS).performScrollTo().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a build with no background-access switch shows no row for it`() {
        setContent(backgroundAccess = null)
        compose.onNodeWithTag(TAG_BACKGROUND_ACCESS).assertDoesNotExist()
    }

    @Test
    fun `each setting group is shown with an explanation`() {
        // A bare list of enum names tells a user nothing about which to pick.
        setContent()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithText("DTMF").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Default media encryption").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Audio route").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Call history").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the current retention is shown without opening the list`() {
        // "Clearly show the currently selected retention period": the value is in the
        // field itself, readable on the way past, not behind a tap.
        setContent(
            state = SettingsUiState(
                AppSettings.DEFAULT.copy(callHistoryRetention = CallHistoryRetention.ofDays(NINETY)),
                traceToggleAvailable = true,
            ),
        )

        compose.onNodeWithText("90 days").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a fresh install shows seven days`() {
        setContent()

        compose.onNodeWithText("7 days").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `choosing a retention reports it`() {
        var chosen: CallHistoryRetention? = null
        setContent(onRetention = { chosen = it })

        // A length other than the one a fresh install starts at, so what is asserted is
        // that the tap was reported and not that the field already held it.
        compose.onNodeWithTag(TAG_RETENTION).performScrollTo().performClick()
        compose.onNodeWithTag(retentionOptionTag(CallHistoryRetention.ofDays(NINETY))).performClick()
        compose.waitForIdle()

        assertEquals(CallHistoryRetention.ofDays(NINETY), chosen)
    }

    @Test
    fun `the retention list ends with forever, in words`() {
        // Zero days is the stored value; "0 days" on screen would read as "keep nothing",
        // which is the opposite of what it does.
        setContent()

        compose.onNodeWithTag(TAG_RETENTION).performScrollTo().performClick()
        // Last of nine, so below the fold of the menu until scrolled to.
        compose.onNodeWithTag(retentionOptionTag(CallHistoryRetention.KEEP_EVERYTHING))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Forever").assertIsDisplayed()
    }

    @Test
    fun `choosing an appearance reports it`() {
        // Tagged rather than found by text: "Dark" is also a word the DTMF description
        // could grow, and a chip found by tag is the chip meant.
        var chosen: ThemeMode? = null
        setContent(onTheme = { chosen = it })

        compose.onNodeWithTag(themeChipTag(ThemeMode.DARK)).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(ThemeMode.DARK, chosen)
    }

    @Test
    fun `choosing a DTMF mode reports it`() {
        var chosen: DtmfMode? = null
        setContent(onDtmf = { chosen = it })

        compose.onNodeWithText("SIP INFO").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(DtmfMode.SIP_INFO, chosen)
    }

    @Test
    fun `mandatory encryption warns that calls will fail rather than downgrade`() {
        // DoD 13. Choosing it changes whether calls connect at all, so the UI says so.
        setContent(
            state = SettingsUiState(
                AppSettings.DEFAULT.copy(defaultSrtpPolicy = SrtpPolicy.MANDATORY),
                traceToggleAvailable = true,
            ),
        )
        compose.onNodeWithText("Calls will fail rather than connect without encryption.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the trace toggle is absent when the build does not allow it`() {
        // Absent, not disabled: a disabled control invites someone to make it enableable.
        setContent(
            state = SettingsUiState(AppSettings.DEFAULT, traceToggleAvailable = false),
        )
        compose.onNodeWithText("SIP trace").assertDoesNotExist()
    }

    @Test
    fun `the trace toggle explains what is and is not written`() {
        // "Enable logging" tells a user nothing about what they are exposing.
        setContent()
        compose.onNodeWithText("SIP trace").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "Writes SIP signalling to the device log for diagnosis. Passwords and " +
                "authentication headers are always removed. Debug builds only.",
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the screen is titled for what it is`() {
        setContent()
        compose.onNodeWithText("App settings").assertIsDisplayed()
    }

    @Test
    fun `the installed version is shown at the foot of the screen`() {
        // Robolectric reports the version of the package under test, so what is asserted
        // is that the footer is there and reachable — the formatting is AppVersionTest's.
        setContent()

        compose.onNodeWithTag(TAG_APP_VERSION).performScrollTo().assertIsDisplayed()
    }

    private companion object {
        const val NINETY = 90
    }
}
