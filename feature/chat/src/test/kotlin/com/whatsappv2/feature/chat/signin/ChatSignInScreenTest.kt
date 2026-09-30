package com.whatsappv2.feature.chat.signin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatUrlViolation
import com.whatsappv2.feature.chat.CHAT_ROBOLECTRIC_SDK
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The sign-in form, rendered.
 *
 * Driven through the stateless screen with a literal state, so it needs no Hilt and no
 * repository — what is under test is what the screen offers and where it puts a message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CHAT_ROBOLECTRIC_SDK])
class ChatSignInScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var submitted = 0
    private var urlChanges = mutableListOf<String>()

    private fun setContent(state: ChatSignInUiState) {
        compose.setContent {
            WhatsAppV2Theme {
                ChatSignInScreen(
                    state = state,
                    onServerUrlChange = { urlChanges += it },
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onTogglePasswordVisible = {},
                    onSubmit = { submitted++ },
                    onBack = {},
                )
            }
        }
    }

    /** The state after somebody typed `http://` and submitted it. */
    private fun cleartext() = filled(url = "http://gujlogin.coraltele.com")
        .copy(urlError = ChatUrlViolation.Cleartext("https://gujlogin.coraltele.com"))

    private fun filled(
        url: String = "https://gujlogin.coraltele.com",
        username: String = "sample-user",
        password: String = "not-a-real-password",
    ) = ChatSignInUiState(serverUrl = url, username = username, password = Secret(password))

    @Test
    fun `all three fields are on screen`() {
        setContent(filled())

        compose.onNodeWithTag(TAG_SERVER_URL).assertIsDisplayed()
        compose.onNodeWithTag(TAG_USERNAME).assertIsDisplayed()
        compose.onNodeWithTag(TAG_PASSWORD).assertIsDisplayed()
    }

    @Test
    fun `submit is disabled until all three have something in them`() {
        setContent(ChatSignInUiState(serverUrl = "https://host.example"))

        compose.onNodeWithTag(TAG_SUBMIT).assertIsNotEnabled()
    }

    @Test
    fun `submit is enabled when they do`() {
        setContent(filled())

        compose.onNodeWithTag(TAG_SUBMIT).assertIsEnabled()
    }

    @Test
    fun `while a request is in flight the button is disabled and the fields are locked`() {
        setContent(filled().copy(isSubmitting = true))

        compose.onNodeWithTag(TAG_SUBMIT).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_SERVER_URL).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_USERNAME).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_PASSWORD).assertIsNotEnabled()
    }

    @Test
    fun `the cleartext message names the reason rather than saying 'invalid'`() {
        // The whole point of Cleartext being its own violation. "Invalid" would leave a
        // user with an opaque network error at runtime and no way to reach the cause.
        setContent(cleartext())

        compose.onNodeWithText(
            "This app will not send your password over an unencrypted connection. Use https://.",
        ).assertIsDisplayed()
    }

    @Test
    fun `the https correction is offered as a button and applied only when pressed`() {
        setContent(cleartext())

        compose.onNodeWithTag(TAG_URL_SUGGESTION).performClick()

        assertEquals(listOf("https://gujlogin.coraltele.com"), urlChanges)
    }

    @Test
    fun `no suggestion is offered for a violation that has no single right correction`() {
        setContent(filled().copy(urlError = ChatUrlViolation.Malformed))

        compose.onNodeWithTag(TAG_URL_SUGGESTION).assertDoesNotExist()
    }

    @Test
    fun `a rejected credential is shown under the password, not in a banner`() {
        setContent(filled().copy(passwordError = ChatAuthError.InvalidCredentials))

        compose.onNodeWithText("That username and password were not accepted.").assertIsDisplayed()
        compose.onNodeWithTag(TAG_BANNER).assertDoesNotExist()
    }

    @Test
    fun `an unreachable server is a banner with a retry`() {
        setContent(filled().copy(banner = ChatAuthError.Network))

        compose.onNodeWithTag(TAG_BANNER).assertIsDisplayed()
        compose.onNodeWithTag(TAG_BANNER_RETRY).performClick()

        assertEquals(1, submitted)
    }

    @Test
    fun `a build with no key says so, and offers no retry`() {
        // Retrying cannot help: the build has no key and will not grow one. Offering
        // "Try again" here invites somebody to repeat an action that cannot succeed.
        setContent(filled().copy(banner = ChatAuthError.CryptoUnavailable))

        compose.onNodeWithText("Chat sign-in is not configured in this build.").assertIsDisplayed()
        compose.onNodeWithTag(TAG_BANNER_RETRY).assertDoesNotExist()
    }

    @Test
    fun `the password has a reveal toggle and it announces which way it goes`() {
        setContent(filled())

        compose.onNodeWithTag(TAG_PASSWORD_REVEAL).assertIsDisplayed()
        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()
    }

    @Test
    fun `a revealed password offers to hide itself again`() {
        setContent(filled().copy(isPasswordVisible = true))

        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
    }
}
