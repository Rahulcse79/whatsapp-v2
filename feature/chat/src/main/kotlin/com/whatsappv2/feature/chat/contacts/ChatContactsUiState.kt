package com.whatsappv2.feature.chat.contacts

import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact

/**
 * What the contacts screen is showing.
 *
 * ## Why [contacts] and [error] can both be set
 *
 * Because a failed refresh should leave the directory that is already on screen where it
 * is, with the error beside it, rather than replacing a useful list with an empty one.
 * That is the repository's stated behaviour and this is the state shape that lets a screen
 * honour it — a sealed Loading/Content/Error hierarchy could not express "both".
 */
data class ChatContactsUiState(
    val contacts: List<ChatContact> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: ChatAuthError? = null,
) {

    /** Nothing to show, nothing loading, nothing wrong — the genuine empty state. */
    val isEmpty: Boolean get() = contacts.isEmpty() && !isLoading && error == null

    /**
     * Whether to offer the search field.
     *
     * Hidden below a screenful, because a search box over six rows is chrome: it costs a
     * row of height to save a scroll that was never needed.
     */
    val isSearchable: Boolean get() = contacts.size >= SEARCHABLE_THRESHOLD || query.isNotEmpty()

    private companion object {
        /** About one screen of rows on the smallest supported device. */
        const val SEARCHABLE_THRESHOLD = 12
    }
}
