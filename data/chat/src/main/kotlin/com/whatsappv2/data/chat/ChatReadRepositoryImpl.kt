package com.whatsappv2.data.chat

import com.whatsappv2.data.chat.store.ChatSessionStore
import com.whatsappv2.domain.chat.ChatReadMark
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read marks, in the same store as the identity they belong to.
 *
 * Not a store of its own, for the reason pins are not: these are cleared by sign-out, and
 * putting them in the file that already knows what signing out means is what keeps that
 * true. Nothing here talks to the SDK — there is nothing to talk to (see [ChatReadRepository]).
 */
@Singleton
internal class ChatReadRepositoryImpl @Inject constructor(
    private val store: ChatSessionStore,
) : ChatReadRepository {

    override fun observeReadMarks(): Flow<Map<ConversationId, ChatReadMark>> =
        store.observeReadMarks().map { marks ->
            marks.entries.associate { (id, mark) ->
                ConversationId(id) to ChatReadMark(readUpToMs = mark.first, serverUnreadAtRead = mark.second)
            }
        }

    override suspend fun markRead(id: ConversationId, uptoMs: Long, serverUnreadCount: Int) =
        store.markRead(id.value, uptoMs, serverUnreadCount)

    override suspend fun clearRead(id: ConversationId) = store.clearRead(id.value)
}
