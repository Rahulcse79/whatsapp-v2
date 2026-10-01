package com.whatsappv2.domain.repository

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact
import kotlinx.coroutines.flow.Flow

/**
 * The company directory, from the Coral platform.
 *
 * Read-and-refresh rather than a single suspending fetch: the screen should draw the last
 * known list the moment it opens and update underneath, rather than showing a spinner over
 * data it already has. That also means a failed [refresh] leaves the previous list on
 * screen with an error beside it, which is almost always more useful than an empty one.
 *
 * **Server data, not the device address book.** See [ChatContact] for why that distinction
 * is load-bearing and how it is enforced.
 */
interface ChatContactRepository {

    /** Everyone known so far, ordered by display name. Empty until the first [refresh]. */
    fun observeContacts(): Flow<List<ChatContact>>

    /**
     * Re-reads the directory, optionally narrowed by [query].
     *
     * [query] is passed to the server rather than applied here: the directory is the
     * company's, not this device's, and filtering a page that was already truncated would
     * show a short list and call it complete.
     */
    suspend fun refresh(query: String? = null): Outcome<Unit, ChatAuthError>
}
