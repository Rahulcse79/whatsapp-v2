package com.whatsappv2.feature.chat.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.chat.isSelfConversation
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.repository.ChatContactRepository
import com.whatsappv2.domain.repository.ChatGroupRepository
import com.whatsappv2.domain.repository.ChatReadRepository
import com.whatsappv2.domain.repository.ChatRepository
import com.whatsappv2.domain.repository.ChatSessionRepository
import com.whatsappv2.domain.usecase.JoinConferenceUseCase
import com.whatsappv2.domain.usecase.PlaceCallError
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import com.whatsappv2.domain.usecase.SendChatMessageUseCase
import com.whatsappv2.domain.usecase.SyncConversationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One conversation.
 *
 * ## The conversation id comes from [SavedStateHandle], and that is allowed
 *
 * Architecture rule 10 forbids restoring **call state** from saved state — a call that
 * ended while the process was dead must not be rebuilt from a stale handle. A conversation
 * id is not call state: it is a navigation argument naming a durable server-side thing,
 * and re-reading it after process death is exactly right. The messages themselves are
 * re-fetched, never restored.
 *
 * ## The draft is here, not in the composable
 *
 * So it survives rotation and a trip to the contact picker. It is deliberately **not** in
 * `SavedStateHandle`: an unsent message is not worth writing to disk, and a half-typed
 * line reappearing days later after a process death is a surprise rather than a feature.
 */
/** One-shot outcomes of the thread. */
sealed interface ChatThreadEvent {
    /** A call is up. The route opens the call screen; the thread stays where it is. */
    data class CallPlaced(val callId: CallId) : ChatThreadEvent

    /** A call could not be placed, with the reason in the user's words. */
    data class CallFailed(val reason: String) : ChatThreadEvent
}

@HiltViewModel
class ChatThreadViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val contacts: ChatContactRepository,
    private val reads: ChatReadRepository,
    private val sessions: ChatSessionRepository,
    private val sendMessage: SendChatMessageUseCase,
    private val sync: SyncConversationUseCase,
    private val placeCall: PlaceCallUseCase,
    private val joinConference: JoinConferenceUseCase,
    private val groups: ChatGroupRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _events = Channel<ChatThreadEvent>(Channel.BUFFERED)

    /** Navigation and failures, as events: a call id left in state would re-open the call screen. */
    val events: Flow<ChatThreadEvent> = _events.receiveAsFlow()

    private val conversationId = ConversationId(
        checkNotNull(savedStateHandle.get<String>(CONVERSATION_ID)) {
            "the thread route was opened with no conversation id"
        },
    )

    private val _state = MutableStateFlow(
        ChatThreadUiState(title = conversationId.value, avatarName = conversationId.value),
    )
    val state: StateFlow<ChatThreadUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                chat.observeMessages(conversationId),
                chat.observeIdentity(),
                chat.observeConnection(),
                chat.observeConversations(),
                // The directory, for the two things the conversation cannot answer: what to
                // write in the bar, and which number to dial. See the join below. Paired
                // with the session because `combine` is typed only to five arguments, and
                // the session is what says whether this thread is the user talking to
                // themselves.
                combine(
                    contacts.observeContacts(),
                    sessions.observeSession(),
                    groups.observeGroups(),
                    ::Triple,
                ),
            ) { messages, identity, connection, conversations, joined ->
                val (directory, session, allGroups) = joined
                val group = allGroups[conversationId]
                val conversation = conversations.firstOrNull { it.id == conversationId }
                val handle = conversation?.title ?: _state.value.title
                val contact = directory.firstOrNull { it.id == handle }
                // A phantom conversation the old extension-keyed build left behind can be
                // addressed to this very user — `guest-8102@guest.local` while signed in as
                // mcx8102. Dialling it rings the handset doing the dialling.
                val self = isSelfConversation(handle, session?.userId, session?.extension?.number)

                _state.value.copy(
                    messages = messages,
                    identity = identity,
                    connection = connection,
                    // `8102 (mcx8102)` once the directory is in, the bare handle until then
                    // — the same join the Chats list does, so a thread and its row agree.
                    // A group is named, and its name is the only sensible title: the handle
                    // for a two-person group is whichever member chat-node put in
                    // `otherUser`, which would label the group as one of the people in it.
                    title = group?.name?.takeIf { it.isNotBlank() } ?: contact?.label ?: handle,
                    group = group,
                    avatarName = group?.name?.takeIf { it.isNotBlank() } ?: contact?.displayName ?: handle,
                    // The directory FIRST, because the conversation's own answer is the
                    // designation — `mcx8102` — and the PBX has never heard of it. The
                    // fallback is for a party the directory does not list, where the handle
                    // is the only candidate there is.
                    // Null for a chat with yourself: a call button that rings your own
                    // handset is never what was wanted, and this is the defect reported
                    // from a device as "the call goes to my own number".
                    callableExtension = if (self) {
                        null
                    } else {
                        contact?.extension ?: conversation?.callableExtension
                    },
                    isDirect = conversation?.isDirect ?: _state.value.isDirect,
                    // ULID -> `8102 (mcx8102)`, in two hops, because no single source has
                    // both ends: the roster turns a ULID into a designation and the directory
                    // turns the designation into a label. Without it a group's sender lines
                    // are truncated ULIDs.
                    senderNames = group?.members.orEmpty()
                        .mapNotNull { member ->
                            val designation = member.username ?: return@mapNotNull null
                            val label = directory.firstOrNull { it.id == designation }?.label ?: designation
                            member.userId to label
                        }
                        .toMap(),
                )
            }.collect { next -> _state.update { next.copy(draft = it.draft) } }
        }

        // Having the thread open IS reading it. There is no other signal available: the SDK
        // has no read API and chat-node refuses `message.read`, so the mark this device
        // writes here is the only thing that will ever clear the row's badge.
        //
        // Driven off the messages rather than off `init`, so a message that lands while the
        // thread is in front of the user is marked read too — otherwise coming back to the
        // list would show a badge for something they just watched arrive.
        viewModelScope.launch {
            combine(
                chat.observeMessages(conversationId).mapNotNull { messages ->
                    messages.maxOfOrNull { it.createdAtMs }
                },
                // The server's running total at this moment. Stored with the mark so a later
                // badge can be the DIFFERENCE rather than the total — chat-node's count never
                // goes down, so raw it reads as "every message ever", which put 7 on a chat
                // with one new message. See ChatReadMark.
                chat.observeConversations().map { conversations ->
                    conversations.firstOrNull { it.id == conversationId }?.unreadCount ?: 0
                },
                ::Pair,
            )
                .distinctUntilChanged()
                .collect { (newest, serverUnread) -> reads.markRead(conversationId, newest, serverUnread) }
        }

        refresh()
    }

    fun setDraft(value: String) = _state.update { it.copy(draft = value) }

    /** Catches the thread up. Loops inside the use case; one call means synced. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val outcome = sync(conversationId)) {
                is Outcome.Success -> _state.update { it.copy(isLoading = false) }
                is Outcome.Failure -> _state.update { it.copy(isLoading = false, error = outcome.error) }
            }
        }
    }

    fun send() {
        val text = _state.value.draft
        if (!_state.value.canSend) return

        // Cleared optimistically. The message is already drawn from the repository's own
        // stream - as Pending, or as Failed if there was no socket - so leaving the text
        // in the box as well would show it twice.
        _state.update { it.copy(draft = "") }

        viewModelScope.launch {
            when (val outcome = sendMessage(conversationId, text)) {
                is Outcome.Success -> Unit
                // Not an error banner: the bubble itself is already Failed and carries the
                // retry. A second report of the same fact is noise.
                is Outcome.Failure -> Unit.also { _ -> outcome.error }
            }
        }
    }

    /** Re-offers a message that could not be sent. */
    fun retry(clientId: String) {
        viewModelScope.launch { chat.retry(conversationId, clientId) }
    }

    /**
     * Calls the other party, over SIP.
     *
     * ## The chat server is not involved
     *
     * This is the app's own calling, pointed at the extension the **directory** names.
     * The chat SDK has no call API and chat-node knows nothing about it; what connects
     * the two is the company directory, which carries one row per person holding both
     * their chat designation and their extension. The handle alone will not do — a
     * conversation is addressed to `mcx8102` and the PBX answers to `8102`.
     *
     * [PlaceCallUseCase] owns everything else — which account, whether to re-register
     * first, and the downgrade from video to audio when the camera cannot be used. A
     * screen that decided any of that would be a second copy of those rules.
     */
    fun call(media: MediaProfile) {
        val current = _state.value
        if (current.isPlacingCall || !current.canOfferCall) return

        _state.update { it.copy(isPlacingCall = true) }
        viewModelScope.launch {
            val outcome = if (current.group != null) {
                // A group goes to the conference rather than to one person. Everybody in the
                // group dials the same room, which is what makes it a group call rather than
                // three separate ones.
                joinConference(
                    input = GROUP_CONFERENCE,
                    withVideo = media == MediaProfile.AUDIO_VIDEO,
                )
            } else {
                placeCall(input = current.callableExtension ?: return@launch, media = media)
            }
            _state.update { it.copy(isPlacingCall = false) }

            when (outcome) {
                is Outcome.Success -> _events.send(ChatThreadEvent.CallPlaced(outcome.value))
                is Outcome.Failure -> _events.send(ChatThreadEvent.CallFailed(outcome.error.wording()))
            }
        }
    }

    /**
     * Why a call could not be placed, in the user's words.
     *
     * Deliberately not shared with the dialler's copy. That screen can offer "add an
     * account" because it is next to the account list; this one is inside a conversation,
     * where the useful thing to say is what went wrong and that the message still sent.
     */
    private fun PlaceCallError.wording(): String = when (this) {
        is PlaceCallError.NoAccountAvailable -> "No calling account is set up yet."
        is PlaceCallError.UnknownAccount -> "That calling account no longer exists."
        is PlaceCallError.InvalidTarget -> "There is no extension to call for this contact."
        // The account was not registered, the app tried, and the server did not answer in
        // time. Worded as what happened rather than as "not registered", which by now is
        // only half the story.
        is PlaceCallError.NotRegistered -> "Could not reach the server for that account."
        is PlaceCallError.Rejected -> cause.userMessage()
    }

    companion object {
        /** The navigation argument's name. `:app`'s route must use the same string. */
        const val CONVERSATION_ID = "conversationId"

        /**
         * The conference room a group call dials.
         *
         * This deployment's number, given on 2 Oct 2026 — not derived from anything, which
         * is why it is one named constant rather than a rule spread across the call path.
         *
         * **One room for every group**, which is the honest limitation of dialling a fixed
         * number: two groups calling at once meet each other. A room per group needs either
         * a number chat-node hands back with the group or a mesh that dials each member, and
         * neither exists yet — the group API carries no conference field.
         */
        const val GROUP_CONFERENCE = "9999999"
    }
}
