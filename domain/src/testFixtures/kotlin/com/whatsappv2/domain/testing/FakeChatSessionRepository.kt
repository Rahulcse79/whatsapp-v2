package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatCredentials
import com.whatsappv2.domain.chat.ChatExtension
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.repository.ChatSessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * A chat identity that lives in memory and answers whatever a test taught it.
 *
 * Signed out by default, which is what a fresh install looks like — so the ordinary case
 * in a test is the case the app has to keep working for, exactly as
 * [FakeContactRepository] starts knowing nobody.
 *
 * The one behaviour it reproduces rather than fakes is decision D2: [signOut] clears the
 * session and **keeps** the URL. A fake that forgot the URL would let a screen expecting
 * three fields on the second sign-in pass its test and fail in someone's hand.
 */
class FakeChatSessionRepository(
    initialUrl: CoralServerUrl = CoralServerUrl.DEFAULT,
    initialSession: ChatSession? = null,
) : ChatSessionRepository {

    private val session = MutableStateFlow(initialSession)
    private val serverUrl = MutableStateFlow(initialUrl)

    /** What the next [signIn] answers. Success by default, with a session built from the username. */
    var nextResult: Outcome<ChatSession, ChatAuthError>? = null

    /** Every sign-in attempt, in order, so a test can assert what reached the wire. */
    val signInAttempts: MutableList<Attempt> = mutableListOf()

    /** How many times [signOut] was called. */
    var signOutCount: Int = 0
        private set

    /** One recorded call to [signIn]. The password is kept as a [Secret] so a fake cannot leak one either. */
    data class Attempt(val url: CoralServerUrl, val credentials: ChatCredentials)

    override fun observeSession(): Flow<ChatSession?> = session

    override suspend fun currentSession(): ChatSession? = session.first()

    override fun observeServerUrl(): Flow<CoralServerUrl> = serverUrl

    override suspend fun currentServerUrl(): CoralServerUrl = serverUrl.first()

    /** Every origin remembered, in order, so a test can assert a failed attempt kept it. */
    val rememberedUrls: MutableList<CoralServerUrl> = mutableListOf()

    override suspend fun rememberServerUrl(url: CoralServerUrl) {
        rememberedUrls += url
        serverUrl.value = url
    }

    override suspend fun signIn(
        url: CoralServerUrl,
        credentials: ChatCredentials,
    ): Outcome<ChatSession, ChatAuthError> {
        signInAttempts += Attempt(url, credentials)

        return when (val result = nextResult ?: success(sessionFor(credentials))) {
            is Outcome.Success -> {
                // The session is persisted only on success, which is still the contract.
                // The URL is not part of that any more — `rememberServerUrl` stores it on
                // the way in, so a failed attempt keeps the address the user typed. Written
                // here as well because the real store writes both together on success.
                session.value = result.value
                serverUrl.value = url
                result
            }
            is Outcome.Failure -> result
        }
    }

    override suspend fun signOut() {
        signOutCount++
        session.value = null
        // The URL deliberately survives — decision D2.
    }

    /** Teaches it that somebody is already signed in. */
    fun givenSignedIn(session: ChatSession): FakeChatSessionRepository = apply {
        this.session.value = session
    }

    /** Teaches it to refuse the next attempt with [error]. */
    fun givenSignInFails(error: ChatAuthError): FakeChatSessionRepository = apply {
        nextResult = failure(error)
    }

    /**
     * What the platform returns as this person's PBX extension.
     *
     * Deliberately unlike the username, with a domain and password of its own. They are
     * all different values in the real login payload, and a fixture that reused the chat
     * ones would let a caller that confuses them pass every test and then register an
     * account the switch has never heard of. Set it to null for an account with no
     * telephony, or null its password for one the server gave no SIP credential.
     */
    var extension: ChatExtension? = ChatExtension(
        number = FAKE_EXTENSION,
        // Null, which is the live case — `extensionName` comes back null on real accounts,
        // so anything registering a name has to fall back to the number.
        name = null,
        sipPassword = Secret("not-a-real-sip-password"),
        domain = "pbx.example",
        port = 5061,
        secure = false,
    )

    private fun sessionFor(credentials: ChatCredentials) = ChatSession(
        userId = credentials.username,
        displayName = credentials.username,
        token = Secret("token-for-${credentials.username}"),
        expiresAtMs = null,
        deviceId = FAKE_DEVICE_ID,
        extension = extension,
    )

    private companion object {
        /** Nothing like a username, so a test that mixes them up fails loudly. */
        const val FAKE_EXTENSION = "4021"

        /** 32 upper hex, the shape the Coral platform's sample uses. Fixed, so assertions can name it. */
        const val FAKE_DEVICE_ID = "0123456789ABCDEF0123456789ABCDEF"
    }
}
