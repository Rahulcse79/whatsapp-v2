package com.whatsappv2.domain.repository

import com.whatsappv2.domain.chat.ConversationId
import kotlinx.coroutines.flow.Flow

/**
 * Which conversations sit at the top of the list, and in what order.
 *
 * ## Why this is the client's own list and not the server's
 *
 * A conversation summary arrives with a `pinned` flag, so it looks as though the server
 * keeps this. It does not keep it for us: the SDK exposes no setter (finding 1.3-10), and
 * the socket rejects the obvious frame outright — `conversation.pin` comes back
 * *"Unrecognized or not-yet-implemented frame type"*. So a pin made here is a pin on this
 * device, and the server's flag is read but never written.
 *
 * The cost is honest and worth naming: pins do not follow the user to another phone. The
 * alternative was no pinning at all until chat-node grows the frame.
 *
 * ## Why the limit lives behind this port rather than in a use case
 *
 * `UseCaseRationale` settled this shape for the default-account rule, and it applies
 * unchanged: the cap is a read-then-write, and only the implementation can make the two
 * halves one transaction. A use case would have to read the list, count it, and write —
 * racing a second pin from another screen and ending with six. Here it is one edit.
 */
interface ChatPinRepository {

    /**
     * The pinned conversations, newest pin first.
     *
     * Ordered, which is why it is a `List` and not a `Set`: the newest pin goes to the top
     * of the list, the way a pinned chat does in every app that has them.
     *
     * Ids of conversations that no longer exist are *kept*, not pruned. A conversation can
     * be missing because the list has not loaded yet, and pruning on that would quietly
     * forget a pin every time the app started offline.
     */
    fun observePinned(): Flow<List<ConversationId>>

    /**
     * Pins [id], or reports that there is no room.
     *
     * Returns false when [MAX_PINNED] are already pinned and [id] is not one of them —
     * the caller is expected to say so rather than fail silently. Pinning something
     * already pinned succeeds and changes nothing, so a double tap is not an error.
     */
    suspend fun pin(id: ConversationId): Boolean

    /** Unpins [id]. Unpinning something that is not pinned is not an error. */
    suspend fun unpin(id: ConversationId)

    companion object {
        /**
         * How many conversations may be pinned at once.
         *
         * Five, because a pinned section that fills the screen is just the list again —
         * the point of the section is that it is shorter than what it sits above.
         */
        const val MAX_PINNED: Int = 5
    }
}
