package com.whatsappv2.data.chat.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer …` to every call that is not the login itself.
 *
 * ## Why login is excluded rather than simply having no token yet
 *
 * Because a stale token usually *is* present when somebody signs in again — they were
 * signed in, their session aged out, and the screen came back. Sending it on the login
 * request would hand the platform a credential the request is trying to replace, and a
 * server entitled to reject it would fail a sign-in that had correct credentials.
 *
 * ## Why the token is read per call
 *
 * [token] is a lambda, not a value. Interceptors live for the life of the `OkHttpClient`
 * and the token changes underneath — on sign-in, on sign-out, on a refresh — so a captured
 * value would be the one that was current when the client was built. That is the same bug
 * as caching a session in a field, moved somewhere nobody looks.
 */
internal class BearerInterceptor(private val token: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val bearer = token()

        if (bearer == null || request.url.encodedPath.endsWith(LOGIN_PATH)) {
            return chain.proceed(request)
        }

        return chain.proceed(
            request.newBuilder().header(AUTHORIZATION, "$BEARER $bearer").build(),
        )
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer"

        /** Matched on the suffix, so it holds whatever the origin's context path turns out to be. */
        const val LOGIN_PATH = "/app/v2/auth/login"
    }
}
