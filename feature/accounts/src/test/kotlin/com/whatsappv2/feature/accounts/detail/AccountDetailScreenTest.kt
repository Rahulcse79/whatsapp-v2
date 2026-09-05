package com.whatsappv2.feature.accounts.detail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.feature.accounts.list.ACCOUNTS_ROBOLECTRIC_SDK
import com.whatsappv2.feature.accounts.list.AccountStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registration detail screen, rendered (Task 31).
 *
 * Every case is a literal state, so a wrong password and a lost network are two arguments
 * rather than two server configurations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ACCOUNTS_ROBOLECTRIC_SDK])
class AccountDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun content(
        status: AccountStatus = AccountStatus.REGISTERED,
        failure: RegistrationFailure? = null,
        nextRetryAt: Long? = null,
        grantedExpiry: Int? = null,
    ) = AccountDetailUiState.Content(
        id = AccountId("1"),
        label = "Work",
        identity = "alice@sip.example.com",
        transport = "TLS",
        isDefault = true,
        status = status,
        failure = failure,
        nextRetryAtEpochMillis = nextRetryAt,
        grantedExpirySeconds = grantedExpiry,
    )

    private fun setContent(
        state: AccountDetailUiState,
        now: Long = NOW,
        onRegisterNow: () -> Unit = {},
    ) {
        compose.setContent {
            WhatsAppV2Theme {
                AccountDetailScreen(
                    state = state,
                    nowEpochMillis = now,
                    onRegisterNow = onRegisterNow,
                    onEdit = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `a registered account shows what the server granted`() {
        setContent(content(grantedExpiry = 3_600))

        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("Registered").assertIsDisplayed()
        compose.onNodeWithText("Registration granted for 3600s").assertIsDisplayed()
    }

    @Test
    fun `a wrong password says so, and says what to do about it`() {
        // Task 31 done-when 2. The generic version of this screen says "registration
        // failed", which is true of every one of the seven reasons and useful for none.
        setContent(
            content(
                status = AccountStatus.FAILED_NEEDS_ATTENTION,
                failure = RegistrationFailure.AUTHENTICATION_FAILED,
            ),
        )

        compose.onNodeWithText("Authentication failed").assertIsDisplayed()
        compose.onNodeWithText("Check the username and password, then register again.")
            .assertIsDisplayed()
    }

    @Test
    fun `no network is a distinct state, not a failure to act on`() {
        // Task 31 done-when 3, and §6. The status is the retrying one rather than the
        // needs-attention one, and the remedy says the app handles it - so nobody goes
        // and changes a password that was correct.
        setContent(
            content(
                status = AccountStatus.FAILED_RETRYING,
                failure = RegistrationFailure.NETWORK_UNAVAILABLE,
            ),
        )

        compose.onNodeWithContentDescription("Status: FAILED_RETRYING").assertIsDisplayed()
        compose.onNodeWithText("No network").assertIsDisplayed()
        compose.onNodeWithText("Waiting for a network. This will recover on its own.")
            .assertIsDisplayed()
    }

    @Test
    fun `a scheduled retry is shown as a countdown`() {
        setContent(
            content(
                status = AccountStatus.FAILED_RETRYING,
                failure = RegistrationFailure.TIMEOUT,
                nextRetryAt = NOW + 42_000L,
            ),
        )

        compose.onNodeWithText("Next attempt in 42s").assertIsDisplayed()
    }

    @Test
    fun `register now reaches the caller`() {
        var pressed = false
        setContent(content(status = AccountStatus.OFFLINE), onRegisterNow = { pressed = true })

        compose.onNodeWithText("Register now").performClick()
        compose.waitForIdle()

        assertTrue(pressed)
    }

    @Test
    fun `register now is withheld while a REGISTER is already in flight`() {
        // A second REGISTER does not arrive any sooner, and a button that appears to do
        // nothing reads as a broken app. Its own test because a ComposeTestRule takes one
        // setContent, not two.
        setContent(content(status = AccountStatus.REGISTERING))

        compose.onNodeWithText("Register now").assertIsNotEnabled()
    }

    @Test
    fun `an account deleted underneath the screen says so`() {
        setContent(AccountDetailUiState.Gone)

        compose.onNodeWithText("This account is gone").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- countdown maths

    @Test
    fun `a due time in the past reads as imminent rather than negative`() {
        // The schedule clears when the attempt fires, but the screen can be drawn in the
        // gap. "-3s" would be the alternative, which reads as a bug.
        assertEquals("Next attempt: any moment now", retryCountdown(NOW - 3_000L, NOW))
        assertEquals("Next attempt: any moment now", retryCountdown(NOW, NOW))
    }

    @Test
    fun `a part-second remainder rounds up, so the countdown never shows zero`() {
        // Rounding down would display "in 0s" for a whole second before the attempt.
        assertEquals("Next attempt in 1s", retryCountdown(NOW + 1L, NOW))
        assertEquals("Next attempt in 2s", retryCountdown(NOW + 1_001L, NOW))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
