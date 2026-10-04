package com.whatsappv2.feature.chat.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatContactRepository
import com.whatsappv2.domain.repository.ChatGroupError
import com.whatsappv2.domain.repository.ChatGroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Making a group: a name, and up to three other people.
 *
 * ## Creating is several requests and is not atomic
 *
 * chat-node makes the group first and takes members one at a time, so a member that fails
 * leaves a smaller group behind rather than nothing. The repository reports what it actually
 * built; this navigates to it either way, because a group that exists is a group the user
 * should be looking at — finding one person missing in the thread is clearer than a failure
 * message beside a group that was silently created anyway.
 */
@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val contacts: ChatContactRepository,
    private val groups: ChatGroupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateGroupUiState())
    val state: StateFlow<CreateGroupUiState> = _state.asStateFlow()

    private val _created = Channel<ConversationId>(Channel.BUFFERED)

    /** Where to go once the group exists. A channel, because navigating is a one-shot. */
    val created: Flow<ConversationId> = _created.receiveAsFlow()

    init {
        viewModelScope.launch {
            contacts.observeContacts().collect { rows -> _state.update { it.copy(contacts = rows) } }
        }
        refresh()
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val outcome = contacts.refresh(_state.value.query.takeIf { it.isNotBlank() })) {
                is Outcome.Success -> _state.update { it.copy(isLoading = false) }
                is Outcome.Failure -> _state.update { it.copy(isLoading = false, error = outcome.error) }
            }
        }
    }

    /**
     * Adds or removes [username] from the selection.
     *
     * Refuses to add past the cap rather than adding and failing later — the rows are already
     * drawn unselectable there, and this is the same rule stated where it is enforced.
     */
    fun toggle(username: String) = _state.update { current ->
        when {
            current.isSelected(username) -> current.copy(selected = current.selected - username)
            current.remaining > 0 -> current.copy(selected = current.selected + username)
            else -> current
        }
    }

    fun create() {
        val current = _state.value
        if (!current.canCreate) return

        _state.update { it.copy(isCreating = true, failure = null) }
        viewModelScope.launch {
            when (val outcome = groups.create(current.name.trim(), current.selected)) {
                is Outcome.Success -> {
                    _state.update { it.copy(isCreating = false) }
                    _created.send(outcome.value.id)
                }
                is Outcome.Failure -> _state.update { it.copy(isCreating = false, failure = outcome.error) }
            }
        }
    }

    fun dismissFailure() = _state.update { it.copy(failure = null) }
}

/** Why a group could not be made, in the user's words. */
fun ChatGroupError.describe(): String = when (this) {
    is ChatGroupError.TooManyMembers ->
        "A group can have up to ${ChatGroup.MAX_MEMBERS} people, including you."

    ChatGroupError.NoMembers -> "Choose at least one other person."
    ChatGroupError.NoName -> "Give the group a name."
    // Deliberately not the server's words: chat-node answers every failure with the same
    // generic internal error, so repeating it would tell the user nothing they can act on.
    is ChatGroupError.Failed -> "The group could not be created. Try again."
}
