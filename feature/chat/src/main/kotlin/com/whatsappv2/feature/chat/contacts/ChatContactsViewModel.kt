package com.whatsappv2.feature.chat.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.repository.ChatContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The company directory.
 *
 * ## The query goes to the server, and it is debounced
 *
 * Filtering here would narrow a page the server had already truncated and present the
 * result as the whole directory — which is why [ChatContactRepository.refresh] takes the
 * query. Debounced, because the alternative is one HTTP request per keystroke.
 *
 * ## A failed refresh keeps the rows
 *
 * [ChatContactsUiState] carries contacts and an error at once so that a directory already
 * on screen stays there when a refresh fails. Blanking a useful list to show an error is
 * worse than showing both.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ChatContactsViewModel @Inject constructor(
    private val contacts: ChatContactRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatContactsUiState())
    val state: StateFlow<ChatContactsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            contacts.observeContacts().collect { rows -> _state.update { it.copy(contacts = rows) } }
        }
        viewModelScope.launch {
            _state
                .map { it.query }
                .distinctUntilChanged()
                // The first emission is the empty query the screen starts with, and
                // refresh() below already covers it. Without the drop, opening the screen
                // fires two identical requests.
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .collect { query -> load(query) }
        }
        refresh()
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun refresh() {
        viewModelScope.launch { load(_state.value.query) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun load(query: String) {
        _state.update { it.copy(isLoading = true, error = null) }

        when (val outcome = contacts.refresh(query.takeIf { it.isNotBlank() })) {
            is Outcome.Success -> _state.update { it.copy(isLoading = false) }
            is Outcome.Failure -> _state.update { it.copy(isLoading = false, error = outcome.error) }
        }
    }

    /** The id the conversation API takes. Named here so the screen never guesses which field it is. */
    fun conversationIdOf(contact: ChatContact): String = contact.id

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}
