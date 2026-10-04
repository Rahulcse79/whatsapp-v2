package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.chat.ChatGroupMember
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.chat.GroupRole
import com.whatsappv2.domain.repository.ChatGroupError
import com.whatsappv2.domain.repository.ChatGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Groups in memory, enforcing the one rule that matters: the cap.
 *
 * Reproducing the limit rather than accepting whatever it is told, because a fake that let a
 * fifth member through would let a screen that forgot the cap pass its test and then build a
 * group the app cannot call.
 */
class FakeChatGroupRepository : ChatGroupRepository {

    private val groups = MutableStateFlow<Map<ConversationId, ChatGroup>>(emptyMap())

    /** Every (name, members) handed to [create], in order. */
    val creates: MutableList<Pair<String, List<String>>> = mutableListOf()

    var refreshCount: Int = 0
        private set

    /** What the next [create] answers. Success by default. */
    var nextFailure: ChatGroupError? = null

    /**
     * What a [refresh] will find, as a server would hold it.
     *
     * A fake whose `refresh` only counted calls could not model the thing a caller cares
     * about — that refreshing makes a group KNOWN — so a test asserting "it is not asked
     * twice" passed only because the first ask changed nothing.
     */
    var serverGroups: List<ChatGroup> = emptyList()

    override fun observeGroups(): Flow<Map<ConversationId, ChatGroup>> = groups

    override suspend fun refresh(): Outcome<Unit, ChatFailure> {
        refreshCount++
        // Published, not merged: a refresh is the server's whole answer, and a group deleted
        // elsewhere has to disappear rather than linger.
        groups.value = serverGroups.associateBy { it.id }
        return success(Unit)
    }

    override suspend fun create(
        name: String,
        memberUsernames: List<String>,
    ): Outcome<ChatGroup, ChatGroupError> {
        creates += name to memberUsernames
        nextFailure?.let { nextFailure = null; return failure(it) }

        if (name.isBlank()) return failure(ChatGroupError.NoName)
        if (memberUsernames.isEmpty()) return failure(ChatGroupError.NoMembers)
        if (memberUsernames.size + 1 > ChatGroup.MAX_MEMBERS) {
            return failure(
                ChatGroupError.TooManyMembers(memberUsernames.size + 1, ChatGroup.MAX_MEMBERS),
            )
        }

        val group = ChatGroup(
            id = ConversationId("group-${groups.value.size + 1}"),
            name = name,
            createdBy = "owner",
            members = listOf(ChatGroupMember("owner", GroupRole.OWNER)) +
                memberUsernames.map { ChatGroupMember("ulid-$it", GroupRole.MEMBER, username = it) },
        )
        groups.value = groups.value + (group.id to group)
        return success(group)
    }

    override suspend fun delete(id: ConversationId): Outcome<Unit, ChatFailure> {
        groups.value = groups.value - id
        return success(Unit)
    }

    /** Seeds a group without going through [create], for arranging a test. */
    fun given(group: ChatGroup) = apply { groups.value = groups.value + (group.id to group) }
}
