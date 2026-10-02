package com.whatsappv2.data.chat

import com.whatsappv2.data.chat.store.ChatSessionStore
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatPinRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pins, kept in the same store as the identity they belong to.
 *
 * Not a store of its own: pins are cleared by sign-out, and putting them in the file that
 * already knows what signing out means is what keeps that true. Nothing here talks to the
 * SDK — pinning never leaves the device (see [ChatPinRepository]).
 */
@Singleton
internal class ChatPinRepositoryImpl @Inject constructor(
    private val store: ChatSessionStore,
) : ChatPinRepository {

    override fun observePinned(): Flow<List<ConversationId>> =
        store.observePinned().map { ids -> ids.map(::ConversationId) }

    override suspend fun pin(id: ConversationId): Boolean =
        store.pin(id.value, ChatPinRepository.MAX_PINNED)

    override suspend fun unpin(id: ConversationId) = store.unpin(id.value)
}
