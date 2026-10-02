package com.whatsappv2.data.chat

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.chat.net.CoralClientFactory
import com.whatsappv2.data.chat.net.CoralErrorMapper
import com.whatsappv2.data.chat.net.dto.PhoneBookRequest
import com.whatsappv2.data.chat.net.dto.SearchRequest
import com.whatsappv2.data.chat.net.dto.toChatContact
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.chat.isSelfConversation
import com.whatsappv2.domain.repository.ChatContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The company directory, from `POST {origin}/services/api/v2/uc/phoneBook/listByDepartment`.
 *
 * ## The departments come from the sign-in, not from an endpoint
 *
 * There is no "list my departments" call. `AuthenticationRequestResponseModel` carries
 * `departmentList`, so [ChatSessionRepositoryImpl] keeps it and this class reads it. A
 * signed-in user with **no** departments therefore gets an empty directory rather than an
 * error — which is correct, and is why the empty list is not treated as "ask for everything".
 *
 * ## The query is applied here, and that is the server's fault not a shortcut
 *
 * `PhoneBookService.getAllByDepartment` reads only `departmentList`; it never looks at the
 * `searchRequest` it insists on being sent. So server-side search does not exist on this
 * endpoint, and filtering locally is the only filtering there is. It is honest because the
 * response is the **whole** department list rather than a truncated page — there is no
 * hidden remainder for a local filter to misrepresent.
 *
 * ## Read-and-refresh, not fetch
 *
 * A failed refresh leaves the previous directory on screen with an error beside it rather
 * than replacing it with an empty one.
 */
@Singleton
internal class ChatContactRepositoryImpl @Inject constructor(
    private val sessions: ChatSessionRepositoryImpl,
    private val clients: CoralClientFactory,
    private val errors: CoralErrorMapper,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : ChatContactRepository {

    private val contacts = MutableStateFlow<List<ChatContact>>(emptyList())

    /** The last full directory, so a local query can narrow it without another round trip. */
    private var lastFetched: List<ChatContact> = emptyList()

    override fun observeContacts(): Flow<List<ChatContact>> = contacts.asStateFlow()

    override suspend fun refresh(query: String?): Outcome<Unit, ChatAuthError> =
        withContext(dispatchers.io) {
            // No session means no bearer token. Letting the request go out unauthenticated
            // would return the platform's own 401 — the same answer one round trip later,
            // and indistinguishable from a token that had genuinely aged out.
            val session = sessions.currentSession()
                ?: return@withContext failure(ChatAuthError.SessionExpired)

            val departments = sessions.currentDepartments()
            if (departments.isEmpty()) {
                // Not an error. This account can see nobody, which the screen shows as its
                // empty state — the same thing a department with no members looks like.
                logger.info(TAG, "The signed-in user has no departments; the directory is empty")
                publish(emptyList(), query)
                return@withContext success(Unit)
            }

            val url = sessions.currentServerUrl()
            val response = try {
                clients.apiFor(url).listByDepartment(
                    PhoneBookRequest(searchRequest = SearchRequest(), departmentList = departments),
                )
            } catch (e: IOException) {
                return@withContext failure(errors.fromTransport(e))
            }

            if (!response.isSuccessful) {
                val body = response.errorBody()?.string()
                return@withContext failure(
                    errors.fromStatus(response.code(), body, CoralErrorMapper.Call.AUTHENTICATED),
                )
            }

            val envelope = response.body()
            if (envelope == null || !envelope.isOk) {
                return@withContext failure(ChatAuthError.Server(response.code(), envelope?.detail))
            }

            publish(
                envelope.data.orEmpty()
                    .mapNotNull { it.toChatContact() }
                    // Not yourself. Picking your own row opened a conversation with
                    // yourself, which chat-node provisions quite happily and which can
                    // never be answered — and whose call button rings the handset doing
                    // the dialling. See `isSelfConversation` for the ones already made.
                    .filterNot { isSelfConversation(it.id, session.userId, session.extension?.number) },
                query,
            )
            success(Unit)
        }

    private fun publish(fetched: List<ChatContact>, query: String?) {
        lastFetched = fetched
        contacts.value = fetched.narrowedBy(query).sortedBy { it.displayName.lowercase() }
    }

    /**
     * The local filter, matched on what the row actually shows.
     *
     * [ChatContact.label] rather than the display name, because the row reads
     * `8102 (mcx8102)` and a search for `mcx` that returned nothing would be a filter
     * disagreeing with the list in front of it. The label already contains the extension,
     * so it is not matched separately.
     */
    private fun List<ChatContact>.narrowedBy(query: String?): List<ChatContact> {
        if (query.isNullOrBlank()) return this
        val needle = query.trim()
        return filter {
            it.label.contains(needle, ignoreCase = true) ||
                it.displayName.contains(needle, ignoreCase = true)
        }
    }

    private companion object {
        const val TAG = "ChatContactRepository"
    }
}
