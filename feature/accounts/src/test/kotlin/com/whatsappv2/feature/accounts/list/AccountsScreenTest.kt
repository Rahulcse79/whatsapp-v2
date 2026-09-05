package com.whatsappv2.feature.accounts.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.model.AccountId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The account list, rendered.
 *
 * Uses the stateless overload with a literal state, so it needs no Hilt and no repository.
 * The ViewModel is covered separately; this is about what the list actually shows and what
 * pressing things does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ACCOUNTS_ROBOLECTRIC_SDK])
class AccountsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val work = AccountRow(
        id = AccountId("1"),
        label = "Work",
        identity = "alice@sip.example.com",
        isDefault = true,
        status = AccountStatus.REGISTERED,
    )
    private val home = AccountRow(
        id = AccountId("2"),
        label = "Home",
        identity = "bob@home.example.com",
        isDefault = false,
        status = AccountStatus.FAILED_NEEDS_ATTENTION,
    )

    private val loggedOut = AccountRow(
        id = AccountId("3"),
        label = "Old",
        identity = "carol@old.example.com",
        isDefault = false,
        status = AccountStatus.OFFLINE,
    )

    private fun setContent(
        state: AccountsUiState,
        onAdd: () -> Unit = {},
        onEdit: (AccountId) -> Unit = {},
        onSetDefault: (AccountId) -> Unit = {},
        onDelete: (AccountId, String) -> Unit = { _, _ -> },
        onLogIn: (AccountId, String) -> Unit = { _, _ -> },
        onLogOut: (AccountId, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            WhatsAppV2Theme {
                AccountsScreen(
                    state = state,
                    onAddAccount = onAdd,
                    onEditAccount = onEdit,
                    onSetDefault = onSetDefault,
                    onDelete = onDelete,
                    onLogIn = onLogIn,
                    onLogOut = onLogOut,
                )
            }
        }
    }

    @Test
    fun `an empty list offers a way to add an account`() {
        // A screen that says "nothing here" without a next step is a dead end.
        var added = false
        setContent(AccountsUiState.Empty, onAdd = { added = true })

        compose.onNodeWithText("No SIP accounts").assertIsDisplayed()
        compose.onNodeWithText("Add account").performClick()
        compose.waitForIdle()

        assertTrue(added)
    }

    @Test
    fun `loading is distinct from empty`() {
        // Showing "no accounts" while still reading storage would be a lie the user acts on.
        setContent(AccountsUiState.Loading)
        compose.onNodeWithContentDescription("Loading accounts").assertIsDisplayed()
    }

    @Test
    fun `each account shows its label, identity and status`() {
        setContent(AccountsUiState.Content(listOf(work, home)))

        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("alice@sip.example.com").assertIsDisplayed()
        compose.onNodeWithText("Registered").assertIsDisplayed()

        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Check your details").assertIsDisplayed()
    }

    @Test
    fun `tapping an account opens it for editing`() {
        var edited: AccountId? = null
        setContent(AccountsUiState.Content(listOf(work)), onEdit = { edited = it })

        // The label itself, from the unmerged tree, rather than whatever node happens to
        // carry the text. `Modifier.clickable` merges its descendants, so the merged tree
        // answers "Work" with the whole ROW - and performClick presses a node's centre.
        //
        // On the 320dp default Robolectric screen that centre is x=160, and Task 29 put a
        // third trailing button there: the summary column used to end at 208dp and now
        // ends at exactly 160dp. So the press landed on the default-account star, which
        // for an account that IS the default is disabled, and reached nothing. Pressing
        // the label is what this test means by "tapping an account" anyway.
        compose.onNodeWithText("Work", useUnmergedTree = true).performClick()
        compose.waitForIdle()

        assertEquals(AccountId("1"), edited)
    }

    @Test
    fun `the default account cannot be re-defaulted`() {
        // The control is disabled rather than absent, so the star still communicates which
        // account is default.
        var defaulted: AccountId? = null
        setContent(AccountsUiState.Content(listOf(work)), onSetDefault = { defaulted = it })

        compose.onNodeWithContentDescription("Default account").performClick()
        compose.waitForIdle()

        assertEquals(null, defaulted)
    }

    @Test
    fun `another account can be made the default`() {
        var defaulted: AccountId? = null
        setContent(AccountsUiState.Content(listOf(work, home)), onSetDefault = { defaulted = it })

        compose.onNodeWithContentDescription("Make Home the default account").performClick()
        compose.waitForIdle()

        assertEquals(AccountId("2"), defaulted)
    }

    @Test
    fun `deleting asks first and names the consequence`() {
        // Deleting discards a credential the user may not be able to recover, so the
        // dialog says what is lost rather than asking a vague "are you sure".
        var deleted: String? = null
        setContent(AccountsUiState.Content(listOf(work)), onDelete = { _, label -> deleted = label })

        compose.onNodeWithContentDescription("Delete Work").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Delete Work?").assertIsDisplayed()
        compose.onNodeWithText(
            "The password for alice@sip.example.com will be removed from this device.",
        ).assertIsDisplayed()
        assertEquals(null, deleted, "nothing may be deleted before the user confirms")

        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals("Work", deleted)
    }

    @Test
    fun `cancelling the delete dialog deletes nothing`() {
        var deleted: String? = null
        setContent(AccountsUiState.Content(listOf(work)), onDelete = { _, label -> deleted = label })

        compose.onNodeWithContentDescription("Delete Work").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(null, deleted)
    }

    // ------------------------------------------------------------------ login / logout

    @Test
    fun `a logged-out account offers login, a registered one offers logout`() {
        // The same control, and it must say which of the two it is: an account that is
        // offline because a password expired is one tap from being reachable again, and a
        // button labelled "log out" would be the opposite of what it does.
        setContent(AccountsUiState.Content(listOf(work, loggedOut)))

        compose.onNodeWithContentDescription("Log out of Work").assertIsDisplayed()
        compose.onNodeWithContentDescription("Log in to Old").assertIsDisplayed()
    }

    @Test
    fun `logging in needs no confirmation`() {
        // It takes nothing away, and a dialog in front of a one-tap recovery is friction
        // with no purpose.
        var loggedIn: String? = null
        setContent(
            AccountsUiState.Content(listOf(loggedOut)),
            onLogIn = { _, label -> loggedIn = label },
        )

        compose.onNodeWithContentDescription("Log in to Old").performClick()
        compose.waitForIdle()

        assertEquals("Old", loggedIn)
    }

    @Test
    fun `logging out asks first and says the account survives`() {
        // This is the action people confuse with delete, so the dialog states the
        // difference: calls stop, the account and its stored password stay.
        var loggedOutLabel: String? = null
        setContent(
            AccountsUiState.Content(listOf(work)),
            onLogOut = { _, label -> loggedOutLabel = label },
        )

        compose.onNodeWithContentDescription("Log out of Work").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Log out of Work?").assertIsDisplayed()
        compose.onNodeWithText(
            "Calls to alice@sip.example.com will stop arriving. The account stays on this " +
                "device and you can log back in without entering your password again.",
        ).assertIsDisplayed()
        assertEquals(null, loggedOutLabel, "nothing may happen before the user confirms")

        compose.onNodeWithText("Log out").performClick()
        compose.waitForIdle()
        assertEquals("Work", loggedOutLabel)
    }

    @Test
    fun `cancelling the logout dialog keeps the registration`() {
        var loggedOutLabel: String? = null
        setContent(
            AccountsUiState.Content(listOf(work)),
            onLogOut = { _, label -> loggedOutLabel = label },
        )

        compose.onNodeWithContentDescription("Log out of Work").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        assertEquals(null, loggedOutLabel)
    }
}
