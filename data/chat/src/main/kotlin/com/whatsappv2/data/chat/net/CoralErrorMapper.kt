package com.whatsappv2.data.chat.net

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.whatsappv2.domain.chat.ChatAuthError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a Coral UC platform failure into a [ChatAuthError].
 *
 * ## Two backends, two error models — this one is the platform's
 *
 * `{origin}/services/` is Spring Security and writes `{"error":"…"}`. chat-node, at
 * `{origin}/chat/`, writes `{"correlationId","code","retryable","message"}`. One host,
 * two services, two shapes (`docs/chat-auth-and-contacts-plan.md` §1.2). A mapper that
 * assumed one shape would read the other's body as empty and report every chat-node
 * failure with no message at all, so they are deliberately kept apart.
 *
 * ## Why 401 is not one answer
 *
 * A 401 on **login** means the credentials are wrong; a 401 on **anything else** means the
 * token aged out. They drive different screens — one is a message under the password box,
 * the other is a return to sign-in — so the caller says which call it was making rather
 * than letting this class guess from a URL.
 */
@Singleton
class CoralErrorMapper @Inject constructor(private val gson: Gson) {

    /** Whether the failing call was the login itself. It is the only caller that can be. */
    enum class Call { LOGIN, AUTHENTICATED }

    fun fromStatus(status: Int, errorBody: String?, call: Call): ChatAuthError = when {
        status == HTTP_UNAUTHORIZED && call == Call.LOGIN -> ChatAuthError.InvalidCredentials
        status == HTTP_UNAUTHORIZED -> ChatAuthError.SessionExpired
        // 403 on a bearer-token platform is "this token is not good enough", which for a
        // user is the same dead end as an expired one: sign in again. Reporting it as a
        // generic server error would leave them re-reading a message and retrying forever.
        status == HTTP_FORBIDDEN -> ChatAuthError.SessionExpired
        else -> ChatAuthError.Server(status, messageIn(errorBody))
    }

    /**
     * A transport failure — no response at all.
     *
     * [IOException] covers DNS, connection refused, a dropped socket and a timeout, and the
     * user's response to every one of them is the same: check the network and try again.
     * Splitting them would produce four messages and one action.
     *
     * [cause] is taken and not read. It is there so the call site reads as a mapping rather
     * than a constant, and so a future diagnostic has the exception to hand; its message is
     * deliberately never surfaced, because an OkHttp timeout names the host and the route
     * and neither means anything to the person looking at the screen.
     */
    @Suppress("UNUSED_PARAMETER")
    fun fromTransport(cause: IOException): ChatAuthError = ChatAuthError.Network

    /** The platform's own wording, or null when the body is empty, foreign or not JSON. */
    private fun messageIn(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null

        return try {
            gson.fromJson(errorBody, CoralErrorEnvelope::class.java)?.error?.takeIf { it.isNotBlank() }
        } catch (_: JsonSyntaxException) {
            // A proxy, a load balancer or a 502 page answers HTML. Showing the first line
            // of an HTML document to a user is worse than showing the status code alone.
            null
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/**
 * `{"error":"Full authentication is required to access this resource"}`.
 *
 * Verified against the live deployment on 2026-09-30, not assumed: an unauthenticated
 * `POST /services/api/v2/uc/phoneBook/listByDepartment` answers exactly this.
 */
internal data class CoralErrorEnvelope(val error: String?)
