package com.whatsappv2.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.AppSettings
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.SrtpPolicy
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
    ) {
        compose.setContent {
            WhatsAppV2Theme {
                SettingsScreen(
                    state = state,
                    onDtmfModeChange = onDtmf,
                    onSrtpPolicyChange = onSrtp,
                    onAudioRouteChange = {},
                    onSipTraceChange = onTrace,
                )
            }
        }
    }

    @Test
    fun `each setting group is shown with an explanation`() {
        // A bare list of enum names tells a user nothing about which to pick.
        setContent()
        compose.onNodeWithText("DTMF").assertIsDisplayed()
        compose.onNodeWithText("Default media encryption").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Audio route").performScrollTo().assertIsDisplayed()
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
}
