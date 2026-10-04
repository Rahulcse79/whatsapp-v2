package com.whatsappv2.data.chat.net

import com.google.gson.Gson
import com.whatsappv2.domain.chat.CoralServerUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Where the guest identity comes from, read fresh on every request.
 *
 * A one-method interface for the same reason [BearerTokenSource] is one: the session
 * repository implements it, so the two are one object at run time, and naming only the half
 * needed here is what keeps the dependency honest.
 */
internal interface ChatIdentitySource {
    /** chat-node's `deviceKey` — this app's Coral `userName`. Null when nobody is signed in. */
    fun currentDeviceKey(): String?

    /** The Coral `deviceId`, sent as the install id. Null when nobody is signed in. */
    fun currentDeviceId(): String?
}

/**
 * The guest-auth headers chat-node reads when it runs with `auth-required=false`.
 *
 * The same two the SDK's own `IdentityInterceptor` sends, because this talks to the same
 * server as the same person — a different identity here would create a second chat user for
 * one signed-in human, which is precisely the phantom-identity bug this codebase has already
 * been bitten by once.
 */
internal class ChatIdentityInterceptor(
    private val identity: () -> ChatIdentitySource,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val source = identity()
        val builder = chain.request().newBuilder()
        source.currentDeviceKey()?.let { builder.header(DEVICE_KEY, it) }
        source.currentDeviceId()?.let { builder.header(DEVICE_ID, it) }
        return chain.proceed(builder.build())
    }

    private companion object {
        const val DEVICE_KEY = "X-Device-Key"
        const val DEVICE_ID = "X-Device-Id"
    }
}

/**
 * Builds a [ChatNodeApi] for one origin, and rebuilds it when the origin changes.
 *
 * ## Why this exists beside the SDK rather than inside it
 *
 * The SDK owns the socket, the message cache and the direct-conversation endpoints, and it
 * has no groups at all. Vendoring group support into it would mean editing a third-party
 * library this repository does not own; building a second client beside it costs one more
 * `OkHttpClient` and keeps the SDK exactly as shipped.
 *
 * Cached per origin for the reason [CoralClientFactory] documents: an `OkHttpClient` owns a
 * connection pool and two thread pools, and one per request exhausts a device's descriptors.
 *
 * No logging interceptor, in any build — the same rule the Coral client follows.
 */
@Singleton
internal class ChatNodeClientFactory @Inject constructor(
    private val gson: Gson,
    /** A [Provider] to break the same cycle [CoralClientFactory] documents: the session owns both. */
    private val identity: Provider<ChatIdentitySource>,
) {

    private var cached: Pair<String, ChatNodeApi>? = null

    @Synchronized
    fun apiFor(url: CoralServerUrl): ChatNodeApi {
        cached?.let { (origin, api) -> if (origin == url.origin) return api }

        val api = build(url)
        cached = url.origin to api
        return api
    }

    private fun build(url: CoralServerUrl): ChatNodeApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(ChatIdentityInterceptor { identity.get() })
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            // Ends in a slash by construction — CoralServerUrl derives it, and Retrofit
            // throws on a base URL without one.
            .baseUrl(url.chatRestBaseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ChatNodeApi::class.java)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 15L
    }
}
