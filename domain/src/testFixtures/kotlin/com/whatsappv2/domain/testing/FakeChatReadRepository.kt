package com.whatsappv2.domain.testing

import com.whatsappv2.domain.chat.ChatReadMark
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Read marks in memory, with the one rule the real one has: a mark never moves backwards.
 *
 * Reproducing that rather than just storing what it is told is the point. A fake that let a
 * mark go backwards would let a caller that marks read from a stale page pass its test and
 * then put the badge back on a conversation the user is looking at.
 */
class FakeChatReadRepository : ChatReadRepository {

    private val marks = MutableStateFlow<Map<ConversationId, ChatReadMark>>(emptyMap())

    /** Every (id, timestamp) handed to [markRead], in order — including ones that moved nothing. */
    val writes: MutableList<Pair<ConversationId, Long>> = mutableListOf()

    override fun observeReadMarks(): Flow<Map<ConversationId, ChatReadMark>> = marks

    override suspend fun markRead(id: ConversationId, uptoMs: Long, serverUnreadCount: Int) {
        writes += id to uptoMs
        val current = marks.value[id]?.readUpToMs ?: 0L
        if (uptoMs > current) marks.value = marks.value + (id to ChatReadMark(uptoMs, serverUnreadCount))
    }

    override suspend fun clearRead(id: ConversationId) {
        marks.value = marks.value - id
    }

    /** Seeds a mark without going through [markRead], for arranging a test. */
    fun given(id: ConversationId, uptoMs: Long, serverUnreadCount: Int = 0) = apply {
        marks.value = marks.value + (id to ChatReadMark(uptoMs, serverUnreadCount))
    }
}
