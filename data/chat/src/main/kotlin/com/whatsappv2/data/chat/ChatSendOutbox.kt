package com.whatsappv2.data.chat

import com.whatsappv2.data.chat.sdk.ChatSdkHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A message this device tried to send, and how it went. */
internal data class OutboxEntry(
    val clientId: String,
    val conversationId: String,
    val text: String,
    val failed: Boolean,
)

/**
 * Closes finding 1.3-3: **a send with no socket is dropped silently**.
 *
 * `WsClient.send` is `if (current != null) current.send(...)`. There is no queue, no
 * retry and no error — the message goes into the SDK's `pending` map, renders as
 * "sending", and stays there until the process dies. Nothing below this class can tell
 * the difference between a message in flight and one that never left.
 *
 * So this one asks `isConnected` **first**:
 *
 *  - connected → hand it to the SDK and record it as in flight,
 *  - not connected → record it as **failed** and do not call the SDK at all.
 *
 * A failed entry is what lets a bubble offer "retry" instead of spinning for ever, and
 * [resendFailed] re-offers them the next time the socket comes up.
 *
 * ## It also cleans up, which the SDK does not
 *
 * Finding 1.3-4: only a matching ack removes an entry from the SDK's `pending` map, so a
 * long session grows one permanently-pending entry per dropped send. [acknowledge] drops
 * ours on the ack, and a failed entry is replaced rather than accumulated on retry.
 */
@Singleton
internal class ChatSendOutbox @Inject constructor(
    private val sdk: ChatSdkHandle,
) {

    private val entries = MutableStateFlow<Map<String, OutboxEntry>>(emptyMap())

    /** Everything still in flight or failed, keyed by client message id. */
    fun observe(): Flow<Map<String, OutboxEntry>> = entries.asStateFlow()

    /**
     * Offers a message. Returns its client id, or null when there was no socket to take it.
     *
     * A null return is a **failure the caller must report**, not a message that will
     * arrive later. The entry is kept either way so the thread can draw it.
     */
    fun offer(conversationId: String, text: String): String? {
        if (!sdk.isConnected) {
            // Recorded under an id of our own so the thread has something to draw and to
            // retry. The SDK never saw it, so it has no id of its own to offer.
            val clientId = "$LOCAL_PREFIX${entries.value.size}-${System.nanoTime()}"
            put(OutboxEntry(clientId, conversationId, text, failed = true))
            return null
        }

        val clientId = sdk.sendText(conversationId, text) ?: run {
            val local = "$LOCAL_PREFIX${entries.value.size}-${System.nanoTime()}"
            put(OutboxEntry(local, conversationId, text, failed = true))
            return null
        }
        put(OutboxEntry(clientId, conversationId, text, failed = false))
        return clientId
    }

    /** Re-offers one failed message under a fresh attempt. Returns false if it failed again. */
    fun retry(clientId: String): Boolean {
        val entry = entries.value[clientId] ?: return false
        remove(clientId)
        return offer(entry.conversationId, entry.text) != null
    }

    /**
     * Re-offers everything that failed. Called when the socket comes back up.
     *
     * Oldest first, so a conversation reads in the order it was typed rather than in map
     * order — which would be arbitrary and would look like the messages had been shuffled.
     */
    fun resendFailed() {
        entries.value.values
            .filter { it.failed }
            .sortedBy { it.clientId }
            .forEach { retry(it.clientId) }
    }

    /** The server accepted it. Ours to forget, which is the cleanup the SDK never does. */
    fun acknowledge(clientId: String?) {
        clientId?.let(::remove)
    }

    private fun put(entry: OutboxEntry) {
        entries.value = entries.value + (entry.clientId to entry)
    }

    private fun remove(clientId: String) {
        entries.value = entries.value - clientId
    }

    private companion object {
        /** Marks an id this app minted because the SDK never got the chance to. */
        const val LOCAL_PREFIX = "local-"
    }
}
