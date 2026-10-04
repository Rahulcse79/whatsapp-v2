package com.whatsappv2.domain.repository

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatCredentials
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.CoralServerUrl
import kotlinx.coroutines.flow.Flow

/**
 * Who is signed in to the Coral platform, and where that platform is.
 *
 * ## The URL outlives the session, deliberately
 *
 * [observeServerUrl] keeps emitting after [signOut]. A server address is a deployment
 * fact, not a secret, and asking somebody to retype their company's hostname every time
 * they sign out is a tax with nothing behind it — the second sign-in is two fields, not
 * three. [signOut] clears the identity and the token; it does not clear the address.
 *
 * ## Why there is no `ChatSignOutUseCase`
 *
 * §4.2: *"Pass-through use cases that only forward to a repository are noise."* Signing
 * out is [signOut] and nothing else — there is no second collaborator to order, no rule
 * the repository cannot see. Disconnecting the chat socket is `:data:chat`'s business and
 * happens on the far side of this interface, where the session it watches is the trigger.
 * A ViewModel calls this directly, which is the layering working rather than a shortcut.
 *
 * [com.whatsappv2.domain.usecase.ChatSignInUseCase] does earn its place: it composes URL
 * parsing, field validation and the request, which no single collaborator can.
 */
interface ChatSessionRepository {

    /** The signed-in session, or null. Null is the signed-out state, not an error. */
    fun observeSession(): Flow<ChatSession?>

    /** The session once, for a caller that needs it to build one request. */
    suspend fun currentSession(): ChatSession?

    /** The stored origin. Emits [CoralServerUrl.DEFAULT] until the user changes it, and survives [signOut]. */
    fun observeServerUrl(): Flow<CoralServerUrl>

    /** The origin once. */
    suspend fun currentServerUrl(): CoralServerUrl

    /**
     * Remembers [url] as the address to offer next time, with no session behind it.
     *
     * ## This reverses "persist the URL only on success", deliberately
     *
     * That rule said a typo which cannot sign in must not become the address every later
     * attempt uses. The cost was paid on the wrong side: a user who corrected the server
     * address and then got the password wrong — or reached a host that was briefly down —
     * came back to a form prefilled with the **old** address, and had to retype the new one
     * every attempt. The typo it protected against is visible in the field and one edit
     * away; the retyping it caused was invisible and happened every time.
     *
     * So the address the user actually tried is what gets remembered. [signIn] still writes
     * it again on success, which is harmless and keeps the session and its origin written
     * together.
     *
     * Only a **parsed** origin reaches here — `CoralServerUrl` cannot hold an unparseable
     * string — so this stores something that is at least a well-formed address.
     */
    suspend fun rememberServerUrl(url: CoralServerUrl)

    /**
     * Signs in and, on success, persists both the session and [url].
     *
     * The URL is also remembered on a failed attempt, by [rememberServerUrl], which the
     * sign-in use case calls before this. See its KDoc for why that changed.
     */
    suspend fun signIn(url: CoralServerUrl, credentials: ChatCredentials): Outcome<ChatSession, ChatAuthError>

    /** Forgets the session and the token. Keeps the URL — see the class comment. */
    suspend fun signOut()
}
