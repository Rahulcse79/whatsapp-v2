package com.whatsappv2.data.chat.net

import com.google.gson.Gson
import com.whatsappv2.domain.chat.CoralServerUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Builds a [CoralApi] for one origin, and rebuilds it when the origin changes.
 *
 * ## Nothing static, and one client reused
 *
 * The origin is a value the user types, so it can change inside a running process — at
 * sign-in, and again at the next sign-in after a sign-out. A client built once at
 * construction would keep talking to the old host with no symptom but a connection
 * refused. So the origin is an argument, and [apiFor] caches exactly one client, keyed by
 * the origin it was built for.
 *
 * Caching matters more than it looks: an `OkHttpClient` owns a connection pool and two
 * thread pools, and building one per request is the standard way to exhaust a device's
 * file descriptors. One per origin is the documented correct number.
 *
 * ## No logging interceptor, in any build
 *
 * The bodies on this API are a password and a company directory. An HTTP logging
 * interceptor would put both in logcat, and `HttpLoggingInterceptor` is not in this
 * repository's catalog for that reason.
 */
@Singleton
internal class CoralClientFactory @Inject constructor(
    private val gson: Gson,
    /**
     * A [Provider], and it has to be.
     *
     * The token source **is** the session repository, and the session repository needs
     * this factory to make its login request — so asking for the value directly is a
     * dependency cycle, which Dagger reports as an unhelpful "cannot be provided" trace
     * against a generated file several modules away. A `Provider` breaks it: nothing is
     * resolved until the first request actually needs a header, by which point both
     * objects exist.
     */
    private val tokenSource: Provider<BearerTokenSource>,
) {

    private var cached: Pair<String, CoralApi>? = null

    @Synchronized
    fun apiFor(url: CoralServerUrl): CoralApi {
        cached?.let { (origin, api) -> if (origin == url.origin) return api }

        val api = build(url)
        cached = url.origin to api
        return api
    }

    private fun build(url: CoralServerUrl): CoralApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor { tokenSource.get().currentToken() })
            // Chosen rather than defaulted. OkHttp's read timeout is 10 s, which is short
            // for a directory call on a loaded PBX and long for a login on a dead network.
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            // Already ends in a slash — CoralServerUrl derives it, and Retrofit throws on
            // a base URL without one. That is a constructor guarantee, not a hope.
            .baseUrl(url.servicesBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(CoralApi::class.java)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 15L
    }
}

/**
 * Where [BearerInterceptor] reads the current token from.
 *
 * A one-method interface rather than a direct dependency on the session repository. The
 * repository implements it, so the two are the same object at run time — the interface is
 * what lets the factory name only the half it needs, and the [Provider] above is what
 * keeps that from being a cycle.
 */
internal interface BearerTokenSource {
    /** The token to send, or null when nobody is signed in. Read on every request. */
    fun currentToken(): String?
}
