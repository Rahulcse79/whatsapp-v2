package com.whatsappv2.feature.accounts.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.validation.AccountField
import com.whatsappv2.domain.validation.AccountViolation
import com.whatsappv2.domain.validation.SipAccountDraft
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor form, rendered.
 *
 * Uses the stateless [AccountEditorScreen] with a literal state, so it needs no Hilt, no
 * repository and no database - it is testing the form, not the plumbing, and the
 * ViewModel is covered separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AccountEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun draft() = SipAccountDraft(id = AccountId("acct-1"))

    private fun setContent(
        initial: AccountEditorUiState = AccountEditorUiState(draft(), isNewAccount = true),
        onSave: () -> Unit = {},
    ): () -> AccountEditorUiState {
        var state by mutableStateOf(initial)
        compose.setContent {
            WhatsAppV2Theme {
                AccountEditorScreen(
                    state = state,
                    onDraftChange = { transform -> state = state.copy(draft = transform(state.draft)) },
                    onSave = onSave,
                    onBack = {},
                )
            }
        }
        return { state }
    }

    @Test
    fun `every section of the form is present`() {
        // A flat list of eighteen fields makes the four that matter impossible to find,
        // so the grouping is part of the design rather than decoration.
        setContent()

        compose.onNodeWithText("Identity").assertIsDisplayed()
        compose.onNodeWithText("Server").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Transport and NAT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Media and security").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `typing into a field updates the draft`() {
        val state = setContent()

        compose.onNodeWithText("Label").performTextInput("Work")
        compose.waitForIdle()

        assertEquals("Work", state().draft.label)
    }

    @Test
    fun `the identity fields are all editable`() {
        val state = setContent()

        compose.onNodeWithText("Label").performTextInput("Work")
        compose.onNodeWithText("Username").performTextInput("alice")
        compose.onNodeWithText("SIP domain").performScrollTo().performTextInput("sip.example.com")
        compose.waitForIdle()

        with(state().draft) {
            assertEquals("Work", label)
            assertEquals("alice", username)
            assertEquals("sip.example.com", domain)
        }
    }

    @Test
    fun `a field error is shown against its own field`() {
        // A banner would not say which of eighteen fields to change.
        setContent(
            AccountEditorUiState(
                draft = draft(),
                isNewAccount = true,
                fieldErrors = mapOf(
                    AccountField.LABEL to AccountViolation.Required(AccountField.LABEL),
                ),
            ),
        )

        compose.onNodeWithText("Required").assertIsDisplayed()
    }

    @Test
    fun `an existing account explains that a blank password means unchanged`() {
        // The repository never returns a decrypted credential, so the field starts empty
        // and the user needs to know that is not a request to retype it.
        setContent(AccountEditorUiState(draft = draft(), isNewAccount = false))

        compose.onNodeWithText("Leave blank to keep the current password")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `mandatory encryption states that calls will fail rather than downgrade`() {
        // DoD 13: the consequence is a failed call, and the UI must say so.
        setContent()

        compose.onNodeWithText("Mandatory").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Calls will fail rather than connect without encryption.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `saving invokes the callback`() {
        var saved = false
        setContent(onSave = { saved = true })

        compose.onNodeWithText("Save account").performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(saved)
    }

    @Test
    fun `the save button is disabled while a save is in flight`() {
        // Otherwise a second tap creates a second account.
        var saved = 0
        setContent(
            initial = AccountEditorUiState(draft(), isNewAccount = true, isSaving = true),
            onSave = { saved++ },
        )

        compose.onNodeWithText("Saving...").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(0, saved)
    }

    @Test
    fun `the title reflects whether the account is new`() {
        setContent(AccountEditorUiState(draft(), isNewAccount = true))
        compose.onNodeWithText("Add account").assertIsDisplayed()
    }
}
