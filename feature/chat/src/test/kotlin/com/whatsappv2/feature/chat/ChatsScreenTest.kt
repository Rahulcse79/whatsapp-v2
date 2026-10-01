package com.whatsappv2.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * The assertion that matters is the negative one: the tab keeps its gear and its
 * registration indicator while signed out. Sign-in gates this tab, not the app — a user
 * with no chat account still has a phone, and must still be able to reach Settings and see
 * whether they are registered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CHAT_ROBOLECTRIC_SDK])
class ChatsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var signInTaps = 0
    private var newConversationTaps = 0
    private var settingsTaps = 0

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
                    onNewConversation = { newConversationTaps++ },
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
    fun `the gear works signed out, because chat does not gate the phone`() {
        setContent(isSignedIn = false)

        compose.onNodeWithTag(TAG_CHATS_SETTINGS).assertIsDisplayed()
        compose.onNodeWithTag(TAG_CHATS_SETTINGS).performClick()

        assertEquals(1, settingsTaps)
    }

    @Test
    fun `the gear keeps the tag the placeholder used, so navigation tests do not move`() {
        // AppRootNavigationTest reaches for this string. The tab changing hands is not a
        // reason for a navigation test to change.
        assertEquals("chats-settings", TAG_CHATS_SETTINGS)
    }
}
