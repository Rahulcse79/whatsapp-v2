package com.whatsappv2.data.chat

import com.whatsappv2.data.chat.sdk.FakeChatSdkHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The regression test for the silently dropped send** (finding 1.3-3).
 *
 * `WsClient.send` is `if (current != null) current.send(...)`. With no socket the message
 * is not queued, not retried and not reported — it goes into the SDK's `pending` map,
 * renders as "sending", and stays there until the process dies. Everything below asserts
 * that this app does not behave that way.
 */
class ChatSendOutboxTest {

    private val sdk = FakeChatSdkHandle().apply { markInitialised() }
    private val outbox = ChatSendOutbox(sdk)

    @Test
    fun `a send with no socket fails instead of vanishing`() = runTest {
        sdk.isConnected = false

        val clientId = outbox.offer("c1", "hello")

        assertNull(clientId, "the send reported success with no socket to carry it")
        assertTrue(sdk.sentTexts.isEmpty(), "the SDK was handed a message it would have dropped")
    }

    @Test
    fun `a failed send is kept, so the thread can draw it and offer a retry`() = runTest {
        sdk.isConnected = false
        outbox.offer("c1", "hello")

        val entry = outbox.observe().first().values.single()

        assertTrue(entry.failed)
        assertEquals("hello", entry.text)
        assertEquals("c1", entry.conversationId)
    }

    @Test
    fun `a send with a socket reaches the SDK and is held as in flight`() = runTest {
        sdk.isConnected = true

        val clientId = assertNotNull(outbox.offer("c1", "hello"))

        assertEquals(listOf("c1" to "hello"), sdk.sentTexts)
        assertEquals(false, outbox.observe().first().getValue(clientId).failed)
    }

    @Test
    fun `an ack forgets the entry, which the SDK never does`() = runTest {
        // Finding 1.3-4: only a matching ack removes the SDK's own pending entry, and
        // nothing removes one that never got an ack - so a long session grows for ever.
        sdk.isConnected = true
        val clientId = assertNotNull(outbox.offer("c1", "hello"))

        outbox.acknowledge(clientId)

        assertTrue(outbox.observe().first().isEmpty())
    }

    @Test
    fun `retry re-offers under a fresh attempt and clears the old entry`() = runTest {
        sdk.isConnected = false
        outbox.offer("c1", "hello")
        val failedId = outbox.observe().first().keys.single()

        sdk.isConnected = true
        val sent = outbox.retry(failedId)

        assertTrue(sent)
        assertEquals(listOf("c1" to "hello"), sdk.sentTexts)
        assertTrue(failedId !in outbox.observe().first().keys, "the failed entry was left behind")
    }

    @Test
    fun `retrying while still offline fails again rather than reporting success`() = runTest {
        sdk.isConnected = false
        outbox.offer("c1", "hello")
        val failedId = outbox.observe().first().keys.single()

        assertTrue(!outbox.retry(failedId))
        assertEquals(1, outbox.observe().first().size, "the message was lost rather than kept failed")
    }

    @Test
    fun `everything that failed is re-offered when the socket comes back, oldest first`() = runTest {
        sdk.isConnected = false
        outbox.offer("c1", "first")
        outbox.offer("c1", "second")
        outbox.offer("c1", "third")

        sdk.isConnected = true
        outbox.resendFailed()

        // Order matters: map order is arbitrary, and a conversation whose messages
        // arrived shuffled would look like the app had reordered what somebody typed.
        assertEquals(listOf("first", "second", "third"), sdk.sentTexts.map { it.second })
    }

    @Test
    fun `nothing is re-sent when nothing failed`() = runTest {
        sdk.isConnected = true
        outbox.offer("c1", "hello")
        sdk.sentTexts.clear()

        outbox.resendFailed()

        assertTrue(sdk.sentTexts.isEmpty(), "an in-flight message was sent a second time")
    }
}
