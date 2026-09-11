package com.whatsappv2.feature.accounts.status

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.feature.accounts.list.ACCOUNTS_ROBOLECTRIC_SDK
import com.whatsappv2.feature.accounts.list.AccountStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The top-bar indicator, rendered from literal state (item 5.5).
 *
 * What is asserted is what a person sees and what a press does; the ViewModel is covered
 * separately. Colour is not asserted — it cannot be, from the semantics tree — which is
 * exactly why every state here has words, and the words are what the tests read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ACCOUNTS_ROBOLECTRIC_SDK])
class RegistrationIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    private val local = AccountIndicatorRow(AccountId("1"), "Local", "1001", AccountStatus.REGISTERED, isDefault = true)
    private val office = AccountIndicatorRow(
        AccountId("2"),
        "Office",
        "7001",
        AccountStatus.FAILED_NEEDS_ATTENTION,
        isDefault = false,
    )

    private fun setContent(
        state: RegistrationStatusUiState,
        onSetDefault: (AccountId) -> Unit = {},
        onManageAccounts: () -> Unit = {},
    ) {
        compose.setContent {
            WhatsAppV2Theme {
                RegistrationIndicator(state, onSetDefault, onManageAccounts)
            }
        }
    }

    @Test
    fun `the default extension and its state are shown in words`() {
        setContent(RegistrationStatusUiState.Content(listOf(local, office), default = local))

        // The dot is decorative; the chip reads as one sentence that carries the state.
        compose.onNodeWithContentDescription(
            "Account 1001, Registered. Default account; tap to change",
        ).assertIsDisplayed()
    }

    @Test
    fun `a failing default says so, in the account list's own words`() {
        setContent(RegistrationStatusUiState.Content(listOf(office), default = office))

        compose.onNodeWithContentDescription(
            "Account 7001, Check your details. Default account; tap to change",
        ).assertIsDisplayed()
    }

    @Test
    fun `tapping lists every account, and picking one makes it the default`() {
        var chosen: AccountId? = null
        setContent(
            RegistrationStatusUiState.Content(listOf(local, office), default = local),
            onSetDefault = { chosen = it },
        )

        compose.onNodeWithTag(TAG_INDICATOR).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(accountMenuTag(office.id)).assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(office.id, chosen)
    }

    @Test
    fun `picking the account that is already the default changes nothing`() {
        // A no-op rather than a redundant write: the repository would clear and re-set the
        // same row, and every observer would be told about a change that was not one.
        var chosen: AccountId? = null
        setContent(
            RegistrationStatusUiState.Content(listOf(local, office), default = local),
            onSetDefault = { chosen = it },
        )

        compose.onNodeWithTag(TAG_INDICATOR).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(accountMenuTag(local.id)).performClick()
        compose.waitForIdle()

        assertNull(chosen)
    }

    @Test
    fun `the menu leads to the account list`() {
        var managed = false
        setContent(
            RegistrationStatusUiState.Content(listOf(local), default = local),
            onManageAccounts = { managed = true },
        )

        compose.onNodeWithTag(TAG_INDICATOR).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TAG_MANAGE_ACCOUNTS).performClick()
        compose.waitForIdle()

        assertTrue(managed)
    }

    @Test
    fun `with no account the chip offers to set one up`() {
        var managed = false
        setContent(RegistrationStatusUiState.NoAccounts, onManageAccounts = { managed = true })

        compose.onNodeWithText("No account").assertIsDisplayed()
        compose.onNodeWithTag(TAG_INDICATOR).performClick()
        compose.waitForIdle()

        assertTrue(managed)
    }

    @Test
    fun `while loading there is nothing, not a spinner`() {
        setContent(RegistrationStatusUiState.Loading)
        compose.onNodeWithTag(TAG_INDICATOR).assertDoesNotExist()
    }
}
