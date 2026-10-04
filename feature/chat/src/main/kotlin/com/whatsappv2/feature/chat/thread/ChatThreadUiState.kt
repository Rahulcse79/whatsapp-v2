package com.whatsappv2.feature.chat.thread

import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage

/**
 * What one conversation is showing.
 *
 * ## Why [identity] is here rather than folded into a boolean
 *
 * Because the thread needs it for two different things: deciding which side each bubble
 * sits on, and deciding whether the composer may be used at all. A message sent before
 * `me()` resolves carries a null sender and renders on the **wrong side** of its own
 * thread (finding 1.3-5), so the composer waits for this rather than for the socket.
 */
data class ChatThreadUiState(
    val title: String = "",

    /**
     * What the avatar takes its initials from — a name, never [title].
     *
     * [title] is `8102 (mcx8102)` once the directory has loaded, and its initials are
     * `8(`, which reads as a rendering fault rather than as a person.
     */
    val avatarName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val identity: ChatIdentity? = null,
    val connection: ChatConnectionState = ChatConnectionState.NotConfigured,
    val isLoading: Boolean = false,
    val error: ChatFailure? = null,

    /**
     * The other party's **dialable** extension, or null when there is nobody to dial.
     *
     * Null until the conversation is known, so the call buttons are absent rather than
     * present-and-broken on the moment a brand-new thread opens.
     *
     * It comes from the directory, not from the conversation. A conversation is addressed
     * to the designation — `mcx8102` — and `ChatConversation.callableExtension` can only
     * report that string, which no PBX can dial. The directory is the one thing that knows
     * `mcx8102` is on `8102`, so the ViewModel joins the two and this field holds the
     * result; the conversation's own answer is the fallback, for a party outside the
     * directory where the handle is the best guess available.
     */
    val callableExtension: String? = null,

    /** A call is being placed. The buttons lock, because a double tap is two INVITEs. */
    val isPlacingCall: Boolean = false,

    /**
     * Whether this thread has exactly one other party.
     *
     * It decides whether an incoming bubble is labelled with who sent it. In a one-to-one
     * conversation the title already says who that is, and the only label the SDK can
     * offer is a shortened user id — there is no display name on a message and no roster
     * to look one up in — so naming the sender there prints a ULID fragment above every
     * run for no information at all.
     *
     * Defaults to true because that is what the app can open, and because a thread whose
     * conversation summary has not arrived yet should not show the id and then drop it.
     */
    val isDirect: Boolean = true,

    /**
     * The group this thread is, when it is one. Null for a direct conversation.
     *
     * Carries the roster, which is the only thing that can answer whether a group is small
     * enough to call — see [ChatGroup.isCallable].
     */
    val group: ChatGroup? = null,

    /**
     * chat-node's ULIDs mapped to what to call the person, for the sender line on a group row.
     *
     * Needed because a message carries only `senderId`, which is a 26-character ULID. In a
     * direct thread that never showed — the bar already names the one person who can be
     * speaking — but a group draws a sender on every incoming bubble, and without this join
     * every one of them reads `01M3TB3R`. Built from the roster and the directory, so it says
     * `8102 (mcx8102)` exactly as the Chats list and the bar do.
     *
     * Partial by nature: a member this device has no direct conversation with cannot be
     * resolved, and [senderLabelOf] falls back rather than showing nothing.
     */
    val senderNames: Map<String, String> = emptyMap(),
) {

    /**
     * Whether the composer accepts input.
     *
     * Three conditions, and the identity one is the subtle one: a socket can be up before
     * the SDK has resolved who we are, and a message sent in that window is drawn as
     * somebody else's. Waiting costs a moment; not waiting costs a thread that looks wrong
     * until it is reloaded.
     */
    val canSend: Boolean
        get() = identity != null && connection.isUsable && draft.isNotBlank()

    /** Why the composer is disabled, when it is, so the user is not left guessing. */
    val composerHint: String?
        get() = when {
            identity == null && connection.isUsable -> "Signing in…"
            !connection.isUsable -> "Waiting for the connection…"
            else -> null
        }

    /**
     * What to write above an incoming bubble in a group.
     *
     * The directory's label when the ULID could be resolved, and the shortened id when it
     * could not — a member of a group somebody else made is not in this device's conversation
     * list, so there is nothing to resolve them against. Eight characters of ULID is a poor
     * label, but it is a stable one, and it tells two unknown people apart.
     */
    fun senderLabelOf(message: ChatMessage): String {
        val senderId = message.senderId.orEmpty()
        return senderNames[senderId] ?: senderId.take(SHORTENED_SENDER_LENGTH)
    }

    val isEmpty: Boolean get() = messages.isEmpty() && !isLoading && error == null

    /**
     * Whether the top bar offers to call at all.
     *
     * Three different answers, and they are genuinely different questions:
     *
     * - a **direct** conversation offers a call when the directory gave it a dialable
     *   extension — see [callableExtension];
     * - a **group** offers one when it has between two and [ChatGroup.MAX_CALLABLE_MEMBERS]
     *   people in it, which is the app's four-party ceiling rather than a cost the conference
     *   bridge imposes — see [ChatGroup.isCallable];
     * - a group that is too big, or whose roster has not loaded, offers nothing.
     *
     * Absent rather than disabled throughout. A greyed-out call button on a nine-person group
     * invites a tap and then has to explain itself; no button needs no explanation.
     */
    val canOfferCall: Boolean
        get() = if (group != null) group.isCallable else callableExtension != null

    /** Whether the call buttons are enabled right now. */
    val canCall: Boolean get() = canOfferCall && !isPlacingCall

    /**
     * The line under the name — what the connection is doing, or nothing.
     *
     * Null when connected, which is the point: a bar that permanently reads "online"
     * spends a line saying what the absence of a warning already says. It is also where
     * a messaging app puts "typing…", which this SDK cannot report (no typing API), so
     * the slot exists and stays honest about what it knows.
     */
    val presenceLabel: String?
        get() = when (connection) {
            ChatConnectionState.Connected -> null
            ChatConnectionState.Connecting -> "connecting…"
            is ChatConnectionState.Disconnected -> "reconnecting…"
            ChatConnectionState.NotConfigured -> "not signed in"
        }

    private companion object {
        /** Enough ULID to tell two unresolved people apart without printing all 26 characters. */
        const val SHORTENED_SENDER_LENGTH = 8
    }
}
