package com.whatsappv2.feature.chat.group

import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.repository.ChatGroupError

/**
 * What the new-group screen is showing.
 *
 * ## The limit is enforced here, not reported by the server
 *
 * chat-node will happily take a fifth member. The cap is this app's — it keeps a group it
 * creates to the same four-party ceiling the rest of the app holds, so a group it makes is
 * always one it can also call; see [ChatGroup.isCallable] for why the bridge does not impose
 * that number and the app keeps it anyway. Enforced by making the rows unselectable at the
 * limit rather than by refusing on submit: a tick that goes on and then a failure afterwards
 * is a worse way to learn a rule than a tick that will not go on.
 */
data class CreateGroupUiState(
    val name: String = "",
    val query: String = "",
    val contacts: List<ChatContact> = emptyList(),

    /** The designations chosen, in the order they were tapped. */
    val selected: List<String> = emptyList(),

    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val error: ChatAuthError? = null,
    val failure: ChatGroupError? = null,
) {

    /** The creator counts, because they are in the group and on the call. */
    val memberCount: Int get() = selected.size + 1

    /** How many more may be added. Zero at the cap. */
    val remaining: Int get() = (ChatGroup.MAX_MEMBERS - memberCount).coerceAtLeast(0)

    /** Whether [username] is chosen. */
    fun isSelected(username: String): Boolean = username in selected

    /**
     * Whether tapping [username] would do anything.
     *
     * Already-selected rows stay enabled so they can be un-selected; it is only *adding*
     * that stops at the cap.
     */
    fun isSelectable(username: String): Boolean = isSelected(username) || remaining > 0

    /** A group needs a name and somebody else in it. */
    val canCreate: Boolean
        get() = name.isNotBlank() && selected.isNotEmpty() && !isCreating

    /** The count, written the way the screen shows it: `3 of 4`. */
    val countLabel: String get() = "$memberCount of ${ChatGroup.MAX_MEMBERS}"
}
