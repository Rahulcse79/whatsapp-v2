package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.repository.ChatContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A company directory that knows only what a test told it.
 *
 * Empty until a [refresh], like the real one: the screen's empty state is reached by the
 * ordinary path here rather than by a special case, so a test that never refreshes is
 * testing what a user with no contacts sees.
 *
 * [refresh] applies [query] the way the server would — it narrows what comes back — so a
 * screen that forgets to pass the query fails here instead of on a device.
 */
class FakeChatContactRepository(
    private val directory: MutableList<ChatContact> = mutableListOf(),
) : ChatContactRepository {

    private val contacts = MutableStateFlow(emptyList<ChatContact>())

    /** Every query passed to [refresh], in order. Null is "no filter". */
    val refreshQueries: MutableList<String?> = mutableListOf()

    /** What the next [refresh] answers. Success by default. */
    var nextResult: Outcome<Unit, ChatAuthError>? = null

    override fun observeContacts(): Flow<List<ChatContact>> = contacts

    override suspend fun refresh(query: String?): Outcome<Unit, ChatAuthError> {
        refreshQueries += query

        return when (val result = nextResult ?: success(Unit)) {
            is Outcome.Success -> {
                contacts.value = directory
                    .filter { query.isNullOrBlank() || it.matches(query) }
                    .sortedBy { it.displayName }
                result
            }
            // A failed refresh leaves the previous list on screen — the repository's own
            // stated behaviour, and the reason the screen can show an error beside data.
            is Outcome.Failure -> result
        }
    }

    /** Teaches it one person. */
    fun given(contact: ChatContact): FakeChatContactRepository = apply { directory += contact }

    /** Teaches it to refuse the next [refresh] with [error]. */
    fun givenRefreshFails(error: ChatAuthError): FakeChatContactRepository = apply {
        nextResult = failure(error)
    }

    private fun ChatContact.matches(query: String): Boolean =
        displayName.contains(query, ignoreCase = true) ||
            extension?.contains(query, ignoreCase = true) == true
}
