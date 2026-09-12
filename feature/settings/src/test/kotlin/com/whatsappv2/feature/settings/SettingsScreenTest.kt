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
        onOpenRecordings: () -> Unit = {},
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
                    links = SettingsLinks(onOpenAccounts = {}, onOpenRecordings = onOpenRecordings),
                    onBack = {},
                )
            }
        }
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
    fun `the recordings row is shown and opens the recordings`() {
        var opened = false
        setContent(onOpenRecordings = { opened = true })

        compose.onNodeWithText("Call recordings").assertIsDisplayed()
        compose.onNodeWithTag(TAG_RECORDINGS).performClick()

        assertEquals(true, opened)
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
    fun `a fresh install shows twenty days`() {
        setContent()

        compose.onNodeWithText("20 days").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `choosing a retention reports it`() {
        var chosen: CallHistoryRetention? = null
        setContent(onRetention = { chosen = it })

        compose.onNodeWithTag(TAG_RETENTION).performScrollTo().performClick()
        compose.onNodeWithTag(retentionOptionTag(CallHistoryRetention.ofDays(SEVEN))).performClick()
        compose.waitForIdle()

        assertEquals(CallHistoryRetention.ofDays(SEVEN), chosen)
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
        const val SEVEN = 7
        const val NINETY = 90
    }
}
