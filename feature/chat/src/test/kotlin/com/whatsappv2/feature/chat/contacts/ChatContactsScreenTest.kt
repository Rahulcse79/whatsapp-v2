package com.whatsappv2.feature.chat.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.feature.chat.CHAT_ROBOLECTRIC_SDK
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The directory's states, rendered.
 *
 * The pair of assertions worth having: "no contacts yet" and "could not load contacts" are
 * different screens, and a failed refresh over an existing list shows the list *and* the
 * error rather than replacing one with the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CHAT_ROBOLECTRIC_SDK])
class ChatContactsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var selected: ChatContact? = null
    private var retries = 0

    /** A directory row the way the live one arrives: a designation, and no `name`. */
    private fun contact(id: String, name: String, extension: String? = null) = ChatContact(
        id = id,
        username = id,
        displayName = name,
        extension = extension,
        department = "coral-test",
        avatarUrl = null,
    )

    private fun setContent(state: ChatContactsUiState) {
        compose.setContent {
            WhatsAppV2Theme {
                ChatContactsScreen(
                    state = state,
                    onQueryChange = {},
                    onContactSelected = { selected = it },
                    onRetry = { retries++ },
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `a row is labelled with the extension AND the designation, and then its department`() {
        setContent(ChatContactsUiState(contacts = listOf(contact("mcx8102", "8102", extension = "8102"))))

        // Both halves: the extension is what somebody recognises, the designation is what
        // the chat server addresses. Neither alone tells you who you are about to message.
        compose.onNodeWithText("8102 (mcx8102)").assertIsDisplayed()
        // The department on its own line. The extension used to be joined onto it and is
        // now in the label above, and printing it twice reads as two different numbers.
        compose.onNodeWithText("coral-test").assertIsDisplayed()
    }

    @Test
    fun `choosing a row hands back the contact, not a field off it`() {
        val row = contact("mcx8102", "8102", extension = "8102")
        setContent(ChatContactsUiState(contacts = listOf(row)))

        compose.onNodeWithText("8102 (mcx8102)").performClick()

        assertEquals(row, selected)
    }

    @Test
    fun `an empty directory says so, and is not an error`() {
        setContent(ChatContactsUiState())

        compose.onNodeWithTag(TAG_EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(TAG_ERROR).assertDoesNotExist()
    }

    @Test
    fun `a search that matches nobody says that, not 'no contacts yet'`() {
        setContent(ChatContactsUiState(query = "zzz"))

        compose.onNodeWithText("Nobody matches “zzz”").assertIsDisplayed()
    }

    @Test
    fun `a failure with no rows is the error state, with a retry when retrying can help`() {
        setContent(ChatContactsUiState(error = ChatAuthError.Network))

        compose.onNodeWithTag(TAG_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `an expired session offers no retry, because retrying cannot fix it`() {
        setContent(ChatContactsUiState(error = ChatAuthError.SessionExpired))

        compose.onNodeWithTag(TAG_ERROR).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a failure over existing rows is a strip, and the rows stay`() {
        setContent(
            ChatContactsUiState(
                contacts = listOf(contact("mcx8102", "8102", extension = "8102")),
                error = ChatAuthError.Network,
            ),
        )

        compose.onNodeWithTag(TAG_ERROR_STRIP).assertIsDisplayed()
        compose.onNodeWithText("8102 (mcx8102)").assertIsDisplayed()
        compose.onNodeWithTag(TAG_ERROR).assertDoesNotExist()
    }

    @Test
    fun `search is absent below a screenful and present above it`() {
        setContent(ChatContactsUiState(contacts = List(3) { contact("100$it", "Person $it") }))
        compose.onNodeWithTag(TAG_SEARCH).assertDoesNotExist()
    }

    @Test
    fun `search appears once the list is long enough to need it`() {
        setContent(ChatContactsUiState(contacts = List(12) { contact("100$it", "Person $it") }))
        compose.onNodeWithTag(TAG_SEARCH).assertIsDisplayed()
    }

    @Test
    fun `the list is shown rather than a spinner once there is something to show`() {
        setContent(
            ChatContactsUiState(contacts = listOf(contact("1001", "Rahul Singh")), isLoading = true),
        )

        compose.onNodeWithTag(TAG_LIST).assertIsDisplayed()
    }
}
