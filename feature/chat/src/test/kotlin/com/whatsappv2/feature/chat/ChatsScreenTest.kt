package com.whatsappv2.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.chat.ChatConnectionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The Chats tab in both of its states.
 *
 * Both are real, which is the point of decision D4: the signed-out state is this screen's
 * own gate rather than something the app draws instead of itself, so a signed-out user still
 * gets the tab, the gear and the registration indicator — and reaches the dialler and the
 * call log through them. Every assertion below about what is absent while signed out is
 * therefore about a screen somebody actually sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CHAT_ROBOLECTRIC_SDK])
class ChatsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var signInTaps = 0
    private var newConversationTaps = 0
    private var newGroupTaps = 0
    private var settingsTaps = 0
    private var signOuts = 0

    private fun setContent(isSignedIn: Boolean) {
        compose.setContent {
            WhatsAppV2Theme {
                ChatsScreen(
                    state = ChatsUiState(
                        isSignedIn = isSignedIn,
                        connection = if (isSignedIn) {
                            ChatConnectionState.Connected
                        } else {
                            ChatConnectionState.NotConfigured
                        },
                    ),
                    onSignIn = { signInTaps++ },
                    onSignOut = { signOuts++ },
                    onNewConversation = { newConversationTaps++ },
                    onNewGroup = { newGroupTaps++ },
                    onOpenConversation = {},
                    onRetry = {},
                    onOpenSettings = { settingsTaps++ },
                )
            }
        }
    }

    @Test
    fun `signed out offers sign-in and no floating button`() {
        setContent(isSignedIn = false)

        compose.onNodeWithTag(TAG_SIGN_IN).performClick()

        assertEquals(1, signInTaps)
        // Nobody to start a conversation with, so the control that starts one is absent
        // rather than disabled - a disabled button invites somebody to make it enableable.
        compose.onNodeWithTag(TAG_NEW_CONVERSATION).assertDoesNotExist()
    }

    @Test
    fun `signed in offers the floating button and no sign-in prompt`() {
        setContent(isSignedIn = true)

        compose.onNodeWithTag(TAG_NEW_CONVERSATION).performClick()

        assertEquals(1, newConversationTaps)
        compose.onNodeWithTag(TAG_SIGN_IN).assertDoesNotExist()
    }

    @Test
    fun `the group button is a second control, not the same one wearing a different icon`() {
        setContent(isSignedIn = true)

        compose.onNodeWithTag(TAG_NEW_GROUP).performClick()

        assertEquals(1, newGroupTaps)
        // The two stack, and tapping one must not fire the other: a Column of buttons is easy
        // to wire so both share a handler, and the symptom is a group screen opening from the
        // everyday button.
        assertEquals(0, newConversationTaps)
    }

    @Test
    fun `signed out, neither floating button is drawn`() {
        setContent(isSignedIn = false)

        // The group button went in beside the conversation one and inherits the same rule:
        // there is nobody to start a group with until somebody is signed in.
        compose.onNodeWithTag(TAG_NEW_GROUP).assertDoesNotExist()
    }

    @Test
    fun `the gear works signed out, because chat does not gate the phone`() {
        setContent(isSignedIn = false)

        compose.onNodeWithTag(TAG_CHATS_SETTINGS).assertIsDisplayed()
        compose.onNodeWithTag(TAG_CHATS_SETTINGS).performClick()

        assertEquals(1, settingsTaps)
    }

    @Test
    fun `there is no overflow menu to sign out of while signed out`() {
        setContent(isSignedIn = false)

        // Three dots over a menu whose one item cannot do anything is a control that
        // invites a tap and then explains nothing.
        compose.onNodeWithTag(TAG_CHATS_OVERFLOW).assertDoesNotExist()
    }

    @Test
    fun `signing out confirms first, and cancelling does nothing`() {
        // The only way back from a sign-out is typing a password again, so it is guarded.
        // This is the app's only sign-out since it left Settings — decision D4.
        setContent(isSignedIn = true)

        compose.onNodeWithTag(TAG_CHATS_OVERFLOW).performClick()
        compose.onNodeWithTag(TAG_CHATS_SIGN_OUT).performClick()
        compose.onNodeWithText("Sign out of chat?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, signOuts)
    }

    @Test
    fun `confirming the dialog signs out`() {
        setContent(isSignedIn = true)

        compose.onNodeWithTag(TAG_CHATS_OVERFLOW).performClick()
        compose.onNodeWithTag(TAG_CHATS_SIGN_OUT).performClick()
        // The dialog's confirm button, not the menu item's — the item reads "Sign out of
        // chat" and the dialog's button reads "Sign out", so the two are distinguishable
        // by text and this reaches for the one inside the dialog.
        compose.onNodeWithText("Sign out").performClick()

        assertEquals(1, signOuts)
    }

    @Test
    fun `the header says which extension is signed in`() {
        compose.setContent {
            WhatsAppV2Theme {
                ChatsScreen(
                    state = ChatsUiState(
                        isSignedIn = true,
                        connection = ChatConnectionState.Connected,
                        myExtensionLabel = "8101 (mcx8101)",
                    ),
                    onSignIn = {},
                    onNewConversation = {},
                    onOpenConversation = {},
                    onRetry = {},
                )
            }
        }

        // On a deployment where one person is `mcx8101` to the platform and `8101` to the
        // switch, "which extension am I" is the question asked before anything else.
        compose.onNodeWithText("8101 (mcx8101)").assertIsDisplayed()
        compose.onNodeWithText("Chats").assertIsDisplayed()
    }

    @Test
    fun `no extension in the login means a bare title, not an empty label`() {
        setContent(isSignedIn = true)

        compose.onNodeWithTag(TAG_MY_EXTENSION).assertDoesNotExist()
        compose.onNodeWithText("Chats").assertIsDisplayed()
    }

    @Test
    fun `the gear keeps the tag the placeholder used, so navigation tests do not move`() {
        // AppRootNavigationTest reaches for this string. The tab changing hands is not a
        // reason for a navigation test to change.
        assertEquals("chats-settings", TAG_CHATS_SETTINGS)
    }
}
