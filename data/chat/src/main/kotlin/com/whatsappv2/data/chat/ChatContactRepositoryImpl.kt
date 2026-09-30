package com.whatsappv2.data.chat

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.chat.net.CoralClientFactory
import com.whatsappv2.data.chat.net.CoralErrorMapper
import com.whatsappv2.data.chat.net.dto.PhoneBookRequest
import com.whatsappv2.data.chat.net.dto.PhoneBookRow
import com.whatsappv2.data.chat.net.dto.SearchRequest
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatContact
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
            sessions.currentSession() ?: return@withContext failure(ChatAuthError.SessionExpired)

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

            publish(envelope.data.orEmpty().mapNotNull { it.toContact() }, query)
            success(Unit)
        }

    private fun publish(fetched: List<ChatContact>, query: String?) {
        lastFetched = fetched
        contacts.value = fetched.narrowedBy(query).sortedBy { it.displayName.lowercase() }
    }

    private fun List<ChatContact>.narrowedBy(query: String?): List<ChatContact> {
        if (query.isNullOrBlank()) return this
        return filter {
            it.displayName.contains(query, ignoreCase = true) ||
                it.extension?.contains(query, ignoreCase = true) == true
        }
    }

    /**
     * A directory row, or null when it carries nothing to show or reach.
     *
     * ## Which field becomes the conversation id
     *
     * [PhoneBookRow.offExtension] — the extension — rather than the numeric `id`. The
     * numeric one is a database row in the UC platform's `extension` table and means
     * nothing to chat-node; the extension is what chat-node's guest mode keys an identity
     * by (`deviceKey` is "the PPDR username"). **Provisional until a conversation is
     * actually opened against it**, which is phase 3 — and this mapper is the single place
     * that decides, so changing the answer is a one-file change.
     */
    private fun PhoneBookRow.toContact(): ChatContact? {
        val extension = offExtension?.takeIf { it.isNotBlank() } ?: return null
        val display = name?.takeIf { it.isNotBlank() } ?: extension

        return ChatContact(
            id = extension,
            displayName = display,
            extension = extension,
            department = department?.takeIf { it.isNotBlank() },
            // The platform's phonebook carries no avatar. Null lets the design system's
            // Avatar fall back to initials, which is what it is built to do.
            avatarUrl = null,
        )
    }

    private companion object {
        const val TAG = "ChatContactRepository"
    }
}
