package com.whatsappv2.feature.chat.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.whatsappv2.core.designsystem.theme.WhatsAppV2Theme
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageType
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.feature.chat.CHAT_ROBOLECTRIC_SDK
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The thread, rendered.
 *
 * Two of these are about SDK behaviour rather than about layout, and they are the reason
 * the screen is shaped the way it is: the composer waits for an identity (finding 1.3-5),
 * and a failed bubble offers a retry because the SDK has no failure state of its own
 * (finding 1.3-3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [CHAT_ROBOLECTRIC_SDK])
class ChatThreadScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var retried: String? = null
    private var sends = 0

    private val conversation = ConversationId("c1")

    private fun message(
        body: String,
        mine: Boolean = false,
        delivery: ChatMessage.Delivery = ChatMessage.Delivery.Sent,
        type: ChatMessageType = ChatMessageType.TEXT,
        clientId: String? = body,
    ) = ChatMessage(
        id = null,
        clientId = clientId,
        conversationId = conversation,
        senderId = if (mine) "me" else "8102",
        type = type,
        body = body,
        sequenceNumber = 0,
        createdAtMs = 0,
        delivery = delivery,
    )

    private fun setContent(state: ChatThreadUiState) {
        compose.setContent {
            WhatsAppV2Theme {
                ChatThreadScreen(
                    state = state,
                    onDraftChange = {},
                    onSend = { sends++ },
                    onRetry = { retried = it },
                    onBack = {},
                )
            }
        }
    }

    private fun ready(vararg messages: ChatMessage, draft: String = "") = ChatThreadUiState(
        title = "8102",
        messages = messages.toList(),
        draft = draft,
        identity = ChatIdentity("me", "d1"),
        connection = ChatConnectionState.Connected,
    )

    @Test
    fun `messages are shown`() {
        setContent(ready(message("Are you there?")))

        compose.onNodeWithText("Are you there?").assertIsDisplayed()
    }

    @Test
    fun `an empty thread says so rather than showing a blank screen`() {
        setContent(ready())

        compose.onNodeWithTag(TAG_THREAD_EMPTY).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ the composer

    @Test
    fun `the composer is disabled until the identity is known`() {
        // Finding 1.3-5. A message sent in this window carries a null sender and is drawn
        // on the WRONG SIDE of its own thread until the ack arrives and it jumps across.
        // Empty draft, because that IS the state: a disabled composer cannot have been
        // typed into, so the hint is what occupies it.
        setContent(ready().copy(identity = null))

        compose.onNodeWithTag(TAG_COMPOSER).assertIsNotEnabled()
        compose.onNodeWithTag(TAG_SEND).assertIsNotEnabled()
        compose.onNodeWithText("Signing in…").assertIsDisplayed()
    }

    @Test
    fun `send is disabled with no connection, even once the identity is known`() {
        setContent(ready(draft = "hello").copy(connection = ChatConnectionState.Disconnected(1006, null)))

        compose.onNodeWithTag(TAG_SEND).assertIsNotEnabled()
    }

    @Test
    fun `send is disabled on an empty draft`() {
        setContent(ready(draft = "   "))

        compose.onNodeWithTag(TAG_SEND).assertIsNotEnabled()
    }

    @Test
    fun `send works once everything is ready`() {
        setContent(ready(draft = "hello"))

        compose.onNodeWithTag(TAG_SEND).assertIsEnabled()
        compose.onNodeWithTag(TAG_SEND).performClick()

        assertEquals(1, sends)
    }

    // ------------------------------------------------------------------ delivery

    @Test
    fun `a pending message shows a spinner and offers no retry`() {
        setContent(ready(message("sending", mine = true, delivery = ChatMessage.Delivery.Pending)))

        compose.onNodeWithTag(TAG_PENDING).assertIsDisplayed()
        compose.onNodeWithTag(TAG_RETRY).assertDoesNotExist()
    }

    @Test
    fun `a failed message offers a retry carrying its own client id`() {
        // The state the SDK does not have. Without it this bubble would spin for ever.
        setContent(
            ready(message("dropped", mine = true, delivery = ChatMessage.Delivery.Failed, clientId = "c-7")),
        )

        compose.onNodeWithTag(TAG_FAILED).assertIsDisplayed()
        compose.onNodeWithTag(TAG_RETRY).performClick()

        assertEquals("c-7", retried)
    }

    @Test
    fun `a sent message shows neither a spinner nor a retry`() {
        setContent(ready(message("delivered", mine = true)))

        compose.onNodeWithTag(TAG_PENDING).assertDoesNotExist()
        compose.onNodeWithTag(TAG_FAILED).assertDoesNotExist()
    }

    // ------------------------------------------------------------------ inbound types

    @Test
    fun `an attachment type this app cannot send is still named, not left blank`() {
        // Only TEXT can be SENT (finding 1.3-9), but any of these can arrive.
        setContent(ready(message("", type = ChatMessageType.IMAGE)))

        compose.onNodeWithText("📷 Photo").assertIsDisplayed()
    }

    @Test
    fun `a type this build has never heard of renders as unsupported rather than crashing`() {
        setContent(ready(message("", type = ChatMessageType.UNKNOWN)))

        compose.onNodeWithText("Unsupported message").assertIsDisplayed()
    }
}
