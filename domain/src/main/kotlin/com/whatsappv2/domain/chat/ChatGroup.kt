package com.whatsappv2.domain.chat

import com.whatsappv2.domain.engine.ConferenceMesh

/**
 * A group conversation, and who is in it.
 *
 * ## The chat SDK knows nothing about groups
 *
 * `ChatSdk`'s whole surface is connect, me, getConversations, openDirectConversation,
 * getMessages and sendText — there is no group call anywhere in it, and its frame types are
 * only `message.send`, `message.ack` and `message.push`. chat-node, however, serves a
 * complete group API that the SDK simply never exposed. Probed on the live server on
 * 2 Oct 2026:
 *
 * ```
 * POST   /chat/api/groups              {"name"}        201 {conversationId, name, createdBy}
 * GET    /chat/api/groups                              200 [ ... ]
 * GET    /chat/api/groups/{id}/members                 200 [{userId, role, assignedAt}]
 * POST   /chat/api/groups/{id}/members {"userId"}      201 {role: MEMBER}
 * DELETE /chat/api/groups/{id}                         204
 * ```
 *
 * So groups are reached over this app's own HTTP client rather than through the SDK, which
 * is why `:data:chat` grows a chat-node service of its own.
 *
 * ## [name] does not come from the conversation list
 *
 * A group appears in `GET /conversations` with `type: GROUP` and a **null name** — the name
 * lives only on the group endpoints. That is why the Chats list has to join the two, and why
 * a group whose name has not loaded falls back to its members rather than showing blank.
 *
 * ## Members are ULIDs, and there is no lookup
 *
 * `POST /members` takes chat-node's internal `userId` — a ULID — not the designation the
 * directory deals in, and `/chat/api/users*` does not exist (every spelling 500s). The only
 * way to learn somebody's ULID is to open a direct conversation with them and read
 * `otherUserId` off the summary. `openDirectConversation` is create-or-get, so doing that is
 * cheap and idempotent, but it does mean adding somebody to a group creates a direct
 * conversation with them as a side effect.
 */
data class ChatGroup(
    val id: ConversationId,

    /** What the creator called it. Null when it has not been loaded yet — see the KDoc. */
    val name: String?,

    /** chat-node's ULID for whoever created it, which is also its [GroupRole.OWNER]. */
    val createdBy: String?,

    /** Everyone in it, owner included. Empty until the roster loads. */
    val members: List<ChatGroupMember> = emptyList(),
) {

    /** How many people are in it, the owner included. */
    val size: Int get() = members.size

    /**
     * Whether this group is small enough to call.
     *
     * ## The ceiling is a product decision, not a cost this path pays
     *
     * Worth stating precisely, because the obvious reason is the wrong one. A group call
     * here **dials a bridge** — `JoinConferenceUseCase` joins the dial-in MCU ADR-003 chose,
     * so the mixing happens on the server and this handset carries exactly one leg no matter
     * how many people are in the room. Neither of the app's measured four-party ceilings
     * binds on it: [ConferenceMesh.MAX_MESH] counts the N-1 encodes a *mesh* member pays, and
     * `SipConferenceController.MAX_VIDEO_CONFERENCE` counts the sources `pjmedia`'s
     * `vid_conf` composes **locally**, which a bridged leg does not use either.
     *
     * So [MAX_CALLABLE_MEMBERS] is this app holding one four-party ceiling everywhere rather
     * than a limit the transport imposes. It is derived from [ConferenceMesh.MAX_MESH] so
     * there is one number to move: if a group call ever dials each member instead of a room,
     * the mesh cost becomes real and the cap is already the right one.
     *
     * A group above it is a perfectly good place to type; it is not a place to start a call
     * from, and the buttons say so by being absent rather than by failing when pressed.
     */
    val isCallable: Boolean get() = size in 2..MAX_CALLABLE_MEMBERS

    companion object {
        /**
         * The most people a group call carries, counting the signed-in user.
         *
         * [ConferenceMesh.MAX_MESH] rather than a second literal 4 — see [isCallable] for why
         * the bridge does not impose this and why the app holds the number anyway.
         */
        const val MAX_CALLABLE_MEMBERS = ConferenceMesh.MAX_MESH

        /**
         * The most people a group may contain.
         *
         * The same number as [MAX_CALLABLE_MEMBERS] and for the same reason: a group this app
         * creates should always be one it can also call. A group made elsewhere can be larger,
         * which is why the call rule is enforced separately rather than assumed from this.
         */
        const val MAX_MEMBERS = MAX_CALLABLE_MEMBERS
    }
}

/** One person's place in a group. */
data class ChatGroupMember(
    /** chat-node's ULID. Not a designation — see [ChatGroup]. */
    val userId: String,

    val role: GroupRole,

    /**
     * The designation this user is known by, when it is known.
     *
     * Null until something has resolved the ULID back to a person, which only a direct
     * conversation can do. A roster that shows ULIDs is unreadable, so a member with no
     * designation is drawn as "Unknown" rather than as `01M3TB3RME…`.
     */
    val username: String? = null,
)

/** What chat-node calls the two kinds of membership. */
enum class GroupRole {
    OWNER,
    MEMBER,
    ;

    companion object {
        /** Anything unrecognised is a plain member: a new role must not hide somebody. */
        fun parse(value: String?): GroupRole =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: MEMBER
    }
}
