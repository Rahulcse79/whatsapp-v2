package com.whatsappv2.data.chat

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.chat.crypto.CoralCipherMaterial
import com.whatsappv2.data.chat.net.BearerTokenSource
import com.whatsappv2.data.chat.net.CoralClientFactory
import com.whatsappv2.data.chat.net.CoralErrorMapper
import com.whatsappv2.data.chat.net.dto.LoginData
import com.whatsappv2.data.chat.net.dto.LoginRequest
import com.whatsappv2.data.chat.store.ChatSessionStore
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatCredentials
import com.whatsappv2.domain.chat.ChatExtension
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.repository.ChatSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The chat identity, over the Coral UC platform at `{origin}/services/`.
 *
 * ## The process holds one session, and it is this one
 *
 * [live] is the source of truth while the app runs; [ChatSessionStore] is what survives a
 * restart. They are kept in step by going through this class for every change — read once
 * at seed, written on sign-in, cleared on sign-out — rather than by two observers of one
 * file racing to agree.
 *
 * That shape is not stylistic. It is what lets [currentToken] answer **synchronously**,
 * which an OkHttp interceptor requires: an interceptor cannot suspend to read a file or
 * decrypt a blob. The token is in memory the moment any request carries it; `Secret` keeps
 * it out of a log from there, and the at-rest copy stays encrypted.
 *
 * ## Two different failures both arrive as HTTP 200
 *
 * The platform wraps everything in `{status, message, messageDetail, data}`, and a
 * rejected credential is frequently `200 OK` with `status: ERROR`. Reading only the HTTP
 * code would report a wrong password as a successful sign-in with a null token, which
 * fails later and somewhere else. Both are checked, in that order.
 */
@Singleton
internal class ChatSessionRepositoryImpl @Inject constructor(
    private val store: ChatSessionStore,
    private val clients: CoralClientFactory,
    private val errors: CoralErrorMapper,
    private val deviceIds: CoralDeviceId,
    private val cache: ChatMemoryCache,
    private val outbox: ChatSendOutbox,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : ChatSessionRepository, BearerTokenSource {

    private val live = MutableStateFlow<ChatSession?>(null)

    /**
     * Guards the one-time read from disk.
     *
     * A [Mutex] rather than a volatile flag: two screens observing at once would otherwise
     * both see "not seeded", both decrypt, and the later one could overwrite a session the
     * earlier one had already replaced by signing in.
     */
    private val seedLock = Mutex()
    private var seeded = false

    /** Seeds from disk on the first collection, then follows the in-memory session. */
    override fun observeSession(): Flow<ChatSession?> = flow {
        seed()
        emitAll(live)
    }

    override suspend fun currentSession(): ChatSession? {
        seed()
        return live.value
    }

    override fun observeServerUrl(): Flow<CoralServerUrl> = store.observeServerUrl()

    override suspend fun currentServerUrl(): CoralServerUrl = withContext(dispatchers.io) {
        store.currentServerUrl()
    }

    /**
     * The departments this session may read. Empty when signed out.
     *
     * Read off the session rather than kept beside it, so a restored session carries them
     * too — they are persisted with it precisely so the directory is not empty until the
     * next sign-in.
     */
    fun currentDepartments(): List<String> = live.value?.departments.orEmpty()

    override suspend fun signIn(
        url: CoralServerUrl,
        credentials: ChatCredentials,
    ): Outcome<ChatSession, ChatAuthError> = withContext(dispatchers.io) {
        // Before anything reaches the socket. The platform 500s on a field it cannot
        // decrypt, so a build with no key would otherwise look like a server outage.
        val cipher = CoralCipherMaterial.load()
            ?: return@withContext failure<ChatAuthError>(ChatAuthError.CryptoUnavailable)
                .also { logger.warn(TAG, "No Coral cipher key in this build; sign-in cannot encrypt a credential") }

        val username = cipher.encrypt(credentials.username).getOrNull()
        val password = cipher.encrypt(credentials.password.reveal()).getOrNull()
        if (username == null || password == null) {
            logger.error(TAG, "The Coral cipher could not encrypt the credentials")
            return@withContext failure(ChatAuthError.CryptoUnavailable)
        }

        val deviceId = deviceIds.get()

        val response = try {
            clients.apiFor(url).login(LoginRequest(username, password, deviceId))
        } catch (e: IOException) {
            return@withContext failure(errors.fromTransport(e))
        }

        val envelope = response.body()
        if (!response.isSuccessful) {
            val body = response.errorBody()?.string()
            return@withContext failure(errors.fromStatus(response.code(), body, CoralErrorMapper.Call.LOGIN))
        }
        if (envelope == null || !envelope.isOk) {
            // 200 with status: ERROR. "Login Failed / Bad credentials" belongs on the
            // password field, which is what InvalidCredentials drives.
            logger.warn(TAG, "Coral rejected the sign-in: ${envelope?.detail ?: "no detail"}")
            return@withContext failure(ChatAuthError.InvalidCredentials)
        }

        val session = envelope.data?.toSession(deviceId, fallbackDomain = url.host)
            ?: return@withContext failure(ChatAuthError.Server(response.code(), envelope.detail))

        if (!store.save(session, url)) return@withContext failure(ChatAuthError.CryptoUnavailable)

        // Belt and braces. Sign-out is the only route to this screen and it clears these
        // already, but "whose data is on screen" is not a question to leave to a path.
        cache.clear()
        outbox.clear()
        live.value = session
        seeded = true
        success(session)
    }

    override suspend fun signOut() {
        // In memory first, so nothing can attach the token to a request racing this call.
        // The persisted copy goes next; the origin stays behind, which is decision D2.
        live.value = null
        // Then this person's conversations. They were fetched with the token above and
        // they are not the next person's: without this, signing in as somebody else
        // showed the previous user's chat list until a refresh happened to replace it.
        cache.clear()
        outbox.clear()
        withContext(dispatchers.io) { store.clearSession() }
    }

    override fun currentToken(): String? = live.value?.token?.reveal()

    /**
     * A session from the login payload, or null when the platform said OK without a token.
     *
     * Treated as a server fault rather than as a session with a null token: a signed-in
     * state whose every request 401s is worse than a failure at the point of sign-in.
     */
    private fun LoginData.toSession(deviceId: String, fallbackDomain: String): ChatSession? {
        val bearer = token?.takeIf { it.isNotBlank() } ?: return null

        return ChatSession(
            // userName is what the platform calls this person; userId is a database row.
            // The former is what chat-node's guest mode keys on, so it is the identity.
            userId = userName?.takeIf { it.isNotBlank() } ?: userId?.toString().orEmpty(),
            displayName = fullName?.takeIf { it.isNotBlank() },
            token = Secret(bearer),
            // The platform's model carries no expiry. Null means "good until a 401 says
            // otherwise", which is the only honest answer: a guessed lifetime signs people
            // out while their token still works.
            expiresAtMs = null,
            deviceId = deviceId,
            // The PBX's name for this person, which is NOT userName - see ChatExtension.
            extension = toExtension(fallbackDomain),
            departments = departmentNames(),
        )
    }

    /**
     * The telephony half of the response, or null when there is no extension behind it.
     *
     * Every value is the server's. The fallbacks are for fields the response may omit,
     * and each is the standard rather than a guess at this deployment: SIP's own default
     * port, and the origin's host when the payload names no SIP domain.
     */
    private fun LoginData.toExtension(fallbackDomain: String): ChatExtension? {
        val number = extension?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        return ChatExtension(
            number = number,
            name = extensionName?.trim()?.takeIf { it.isNotEmpty() },
            sipPassword = sipPassword?.takeIf { it.isNotBlank() }?.let(::Secret),
            domain = primaryDomain?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackDomain,
            port = serverPort?.trim()?.toIntOrNull() ?: DEFAULT_SIP_PORT,
            secure = enableSsl == true,
        )
    }

    private fun LoginData.departmentNames(): List<String> =
        departmentList.orEmpty().mapNotNull { it.department?.takeIf(String::isNotBlank) }.distinct()

    private suspend fun seed() {
        if (seeded) return
        seedLock.withLock {
            if (seeded) return
            live.value = withContext(dispatchers.io) { store.observeSession().first() }
            seeded = true
        }
    }

    private companion object {
        const val TAG = "ChatSessionRepository"

        /** Only when the response names no port. The deployment this was built against says 5061. */
        const val DEFAULT_SIP_PORT = 5060
    }
}
