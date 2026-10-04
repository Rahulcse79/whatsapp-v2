package com.whatsappv2.domain.repository

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.chat.ConversationId
import kotlinx.coroutines.flow.Flow

/** Why a group could not be created. */
sealed interface ChatGroupError {

    /** More people than [ChatGroup.MAX_MEMBERS] were asked for. Carries both numbers so the UI can say which. */
    data class TooManyMembers(val requested: Int, val max: Int) : ChatGroupError

    /** A group needs somebody in it besides its creator. */
    data object NoMembers : ChatGroupError

    /** A group needs a name — chat-node rejects a payload without one. */
    data object NoName : ChatGroupError

    /** The server or the network refused. */
    data class Failed(val cause: ChatFailure) : ChatGroupError
}

/**
 * Group conversations, over chat-node's own HTTP API.
 *
 * Not over the chat SDK, which has no notion of groups at all — see [ChatGroup] for the
 * endpoints this is built on and how they were established.
 *
 * ## Creating a group is several requests, and they are not all undoable
 *
 * chat-node creates the group first and takes members one at a time afterwards, so a group
 * whose third member fails is already on the server with two. [create] reports that rather
 * than pretending otherwise: it returns the group it managed to build, and a caller that
 * wanted four people in it can see it got three. Rolling back would mean deleting a
 * conversation somebody may already have been told about.
 */
interface ChatGroupRepository {

    /**
     * Every group this user is in, by conversation id.
     *
     * Keyed rather than a list because its only consumer is a join: the conversation list
     * carries `type: GROUP` rows with **no name**, and this is what supplies it.
     */
    fun observeGroups(): Flow<Map<ConversationId, ChatGroup>>

    /** Re-reads the groups and their rosters. */
    suspend fun refresh(): Outcome<Unit, ChatFailure>

    /**
     * Makes a group called [name] containing [memberUsernames] plus the signed-in user.
     *
     * [memberUsernames] are **designations** — what the directory deals in. Resolving each to
     * the ULID chat-node wants means opening a direct conversation with them, which is
     * create-or-get and therefore cheap, but which does leave a direct conversation behind.
     * That is the only route available: chat-node serves no user lookup.
     *
     * Refused above [ChatGroup.MAX_MEMBERS] counting the creator, so the group this app makes
     * is always one it can also place a call from.
     */
    suspend fun create(name: String, memberUsernames: List<String>): Outcome<ChatGroup, ChatGroupError>

    /** Deletes a group. One of the few things chat-node will undo — direct conversations it will not. */
    suspend fun delete(id: ConversationId): Outcome<Unit, ChatFailure>
}
