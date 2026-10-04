package com.whatsappv2.data.chat

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.chat.net.AddMemberRequest
import com.whatsappv2.data.chat.net.ChatNodeApi
import com.whatsappv2.data.chat.net.ChatNodeClientFactory
import com.whatsappv2.data.chat.net.CreateGroupRequest
import com.whatsappv2.data.chat.net.GroupResponse
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.chat.ChatGroupMember
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.chat.GroupRole
import com.whatsappv2.domain.repository.ChatGroupError
import com.whatsappv2.domain.repository.ChatGroupRepository
import com.whatsappv2.domain.repository.ChatRepository
import com.whatsappv2.domain.repository.ChatSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Groups, over chat-node's own HTTP API rather than through the SDK, which has none.
 *
 * See `ChatGroup` for the endpoints and how they were established. Two things about this
 * shape are worth knowing before reading the code:
 *
 * **Creating is not atomic.** chat-node makes the group, then takes members one at a time.
 * A member that fails leaves a real group behind with fewer people in it, so [create]
 * returns what it actually built instead of claiming all-or-nothing. Deleting on a partial
 * failure would throw away a conversation other members may already have been notified of.
 *
 * **Members are ULIDs.** The directory speaks designations and chat-node has no lookup, so
 * each designation is resolved by opening a direct conversation and reading `otherUserId`
 * off the summary — that field is chat-node's internal id, not the handle. It is create-or-
 * get, so the cost is one request and a direct conversation that now exists.
 */
@Singleton
internal class ChatGroupRepositoryImpl @Inject constructor(
    // The PORT, not `ChatSessionRepositoryImpl`: all this needs is "who is signed in" and
    // "which origin", both of which the interface answers. Depending on the implementation
    // would drag the whole login path — DataStore, the cipher, the SDK handle — into every
    // test of a group request.
    private val sessions: ChatSessionRepository,
    private val chat: ChatRepository,
    private val clients: ChatNodeClientFactory,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : ChatGroupRepository {

    private val groups = MutableStateFlow<Map<ConversationId, ChatGroup>>(emptyMap())

    override fun observeGroups(): Flow<Map<ConversationId, ChatGroup>> = groups.asStateFlow()

    override suspend fun refresh(): Outcome<Unit, ChatFailure> = withContext(dispatchers.io) {
        val api = api() ?: return@withContext failure(ChatFailure.NotConfigured)

        val listed = call { api.groups() }
        when (listed) {
            is Outcome.Failure -> listed
            is Outcome.Success -> {
                // One roster request per group. There is no endpoint that returns them
                // together, and a group list with no members cannot answer the only two
                // questions asked of it: what to call the row, and whether it can be rung.
                val withMembers = listed.value.orEmpty().mapNotNull { group ->
                    val id = group.conversationId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ConversationId(id) to group.toDomain(id, membersOf(api, id))
                }
                groups.value = withMembers.toMap()
                success(Unit)
            }
        }
    }

    override suspend fun create(
        name: String,
        memberUsernames: List<String>,
    ): Outcome<ChatGroup, ChatGroupError> = withContext(dispatchers.io) {
        if (name.isBlank()) return@withContext failure(ChatGroupError.NoName)

        val wanted = memberUsernames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return@withContext failure(ChatGroupError.NoMembers)
        // Counting the creator, who is added as OWNER by the act of creating it.
        if (wanted.size + 1 > ChatGroup.MAX_MEMBERS) {
            return@withContext failure(
                ChatGroupError.TooManyMembers(requested = wanted.size + 1, max = ChatGroup.MAX_MEMBERS),
            )
        }

        val api = api() ?: return@withContext failure(ChatGroupError.Failed(ChatFailure.NotConfigured))

        val created = when (val result = call { api.createGroup(CreateGroupRequest(name.trim())) }) {
            is Outcome.Failure -> return@withContext failure(ChatGroupError.Failed(result.error))
            is Outcome.Success -> result.value
        }
        val id = created?.conversationId?.takeIf { it.isNotBlank() }
            ?: return@withContext failure(
                ChatGroupError.Failed(ChatFailure.Unknown("the server created a group with no id")),
            )

        wanted.forEach { username ->
            val userId = resolve(username)
            if (userId == null) {
                // Named, and not fatal. The group exists and the others are in it; a member
                // that could not be resolved is better reported as a smaller group than as a
                // failure that leaves the user unsure whether anything was created.
                logger.warn(TAG, "Could not resolve a member to a chat user id; they were left out")
                return@forEach
            }
            when (call { api.addMember(id, AddMemberRequest(userId)) }) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> logger.warn(TAG, "A member could not be added to the new group")
            }
        }

        val group = created.toDomain(id, membersOf(api, id))
        groups.value = groups.value + (group.id to group)
        success(group)
    }

    override suspend fun delete(id: ConversationId): Outcome<Unit, ChatFailure> =
        withContext(dispatchers.io) {
            val api = api() ?: return@withContext failure(ChatFailure.NotConfigured)

            when (val result = call { api.deleteGroup(id.value) }) {
                is Outcome.Failure -> result
                is Outcome.Success -> {
                    groups.value = groups.value - id
                    success(Unit)
                }
            }
        }

    /**
     * chat-node's ULID for [username], or null when it cannot be learned.
     *
     * The only route there is. `/chat/api/users` does not exist in any spelling, and the
     * company directory knows nothing of chat-node's ids — so a direct conversation is
     * opened (create-or-get) and its `otherUserId` read, which **is** the ULID.
     */
    private suspend fun resolve(username: String): String? {
        val conversationId = when (val opened = chat.openDirectConversation(username)) {
            is Outcome.Failure -> return null
            is Outcome.Success -> opened.value
        }

        // The summary carries the id; a conversation just created may not be in the cached
        // list yet, so it is re-read rather than assumed present.
        if (chat.refreshConversations() is Outcome.Failure) return null
        return chat.observeConversations().first()
            .firstOrNull { it.id == conversationId }
            ?.otherUserId
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun membersOf(api: ChatNodeApi, id: String): List<ChatGroupMember> =
        when (val result = call { api.members(id) }) {
            is Outcome.Failure -> emptyList()
            is Outcome.Success -> {
                val designations = designationsByUserId()
                result.value.orEmpty().mapNotNull { member ->
                    member.userId?.takeIf { it.isNotBlank() }?.let {
                        ChatGroupMember(
                            userId = it,
                            role = GroupRole.parse(member.role),
                            username = designations[it],
                        )
                    }
                }
            }
        }

    /**
     * chat-node's ULIDs mapped back to the designations people are known by.
     *
     * The reverse of [resolve], and the conversation list is the only thing that can do it:
     * a direct summary carries BOTH the ULID (`otherUserId`) and the designation
     * (`otherUserContactIdentifier`), so the list this device already holds is a lookup table
     * for everyone it has ever opened a conversation with — which, because adding a member
     * opens one, is everyone in a group this device created.
     *
     * Partial by nature. A member of a group somebody else made is not in it, and that member
     * keeps a null username, which is the state [ChatGroupMember.username] documents.
     */
    private suspend fun designationsByUserId(): Map<String, String> =
        chat.observeConversations().first()
            .mapNotNull { conversation ->
                val userId = conversation.otherUserId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val designation = conversation.otherUserContactIdentifier?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                userId to designation
            }
            .toMap()

    private suspend fun api(): ChatNodeApi? {
        sessions.currentSession() ?: return null
        return clients.apiFor(sessions.currentServerUrl())
    }

    /**
     * One Retrofit call as an [Outcome].
     *
     * chat-node answers every wrong path with the same generic 500, so a failure here says
     * no more than "it did not work" — which is all a caller can act on anyway.
     */
    private suspend fun <T> call(request: suspend () -> Response<T>): Outcome<T?, ChatFailure> = try {
        val response = request()
        if (response.isSuccessful) {
            success(response.body())
        } else {
            failure(ChatFailure.Server(response.code(), null))
        }
    } catch (e: IOException) {
        logger.debug(TAG, "A group request failed: ${e.message}")
        failure(ChatFailure.Network)
    }

    private fun GroupResponse.toDomain(id: String, members: List<ChatGroupMember>) = ChatGroup(
        id = ConversationId(id),
        name = name?.takeIf { it.isNotBlank() },
        createdBy = createdBy?.takeIf { it.isNotBlank() },
        members = members,
    )

    private companion object {
        const val TAG = "ChatGroupRepository"
    }
}
