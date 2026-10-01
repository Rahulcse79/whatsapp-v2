package com.whatsappv2.data.chat.sdk

import android.content.Context
import com.chatserver.sdk.ChatCallback
import com.chatserver.sdk.ChatConfig
import com.chatserver.sdk.ChatListener
import com.chatserver.sdk.ChatSdk
import com.chatserver.sdk.model.Conversation
import com.chatserver.sdk.model.Message
import com.chatserver.sdk.model.User
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one seam this design adds, and the thing that makes `:data:chat` testable at all.
 *
 * ## Why it has to exist
 *
 * `ChatSdk` is a **static singleton**: `ChatSdk.get()` throws before `init`, `init` is
 * `static synchronized`, and every model type it hands back is a final class with public
 * fields. None of that can be substituted in a JVM test, so without an interface here the
 * repository, the mapper, the outbox and the re-init guard could only be exercised on a
 * device against a live chat server — which in practice means not exercised.
 *
 * It is deliberately a **thin** seam: every method below is one call. Anything cleverer
 * would be logic that the fake and the real implementation could disagree about, which is
 * the failure mode a seam is supposed to prevent.
 *
 * ## It is also the boundary architecture rule 13 protects
 *
 * `com.chatserver.sdk` may be imported only under `data/chat/`. This file and
 * [ChatModelMapper][com.whatsappv2.data.chat.ChatModelMapper] are where that import earns
 * its keep; everything above them speaks the domain's types.
 */
internal interface ChatSdkHandle {

    /** True once [initialise] has been called, so nothing calls `get()` into an exception. */
    val isInitialised: Boolean

    val isConnected: Boolean

    /** Null until `me()` has resolved — the window finding 1.3-5 describes. */
    val myUserId: String?

    /**
     * Builds the SDK for this identity.
     *
     * **Every call leaks a thread** (finding 1.3-6): `init` disconnects the old instance
     * but never shuts down `WsClient`'s single-thread scheduler, and `disconnect()`
     * cancels the heartbeat task rather than the executor. Callers must guard on a
     * distinct, valid value — [com.whatsappv2.data.chat.ChatEngineLifecycle] is the only
     * one, and that is why.
     */
    fun initialise(context: Context, restBaseUrl: String, wsUrl: String, deviceKey: String, displayName: String?)

    fun connect()
    fun disconnect()

    fun addListener(listener: ChatListener)

    /**
     * Each returns **whether the request was dispatched**, and false means the SDK is not
     * up yet.
     *
     * A `Boolean` rather than an error through the callback, because `ChatError`'s
     * constructor is package-private — the SDK does not let a host fabricate one, which
     * is fair enough. So "not started" is reported as a value here and turned into
     * [com.whatsappv2.domain.chat.ChatFailure.NotConfigured] by the repository, which is
     * the layer that owns domain failures anyway.
     *
     * It also closes the race that an `isInitialised` check by the caller would leave: the
     * check and the call are one operation here, under the same guard.
     */
    fun me(callback: ChatCallback<User>): Boolean
    fun conversations(callback: ChatCallback<List<Conversation>>): Boolean
    fun openDirectConversation(otherUserId: String, callback: ChatCallback<String>): Boolean
    fun messages(conversationId: String, callback: ChatCallback<List<Message>>): Boolean
    fun cachedMessages(conversationId: String): List<Message>

    /** The client message id, or null when the SDK is not up. The ack arrives on the listener. */
    fun sendText(conversationId: String, text: String): String?
}

/**
 * [ChatSdkHandle] over the real static singleton.
 *
 * Holds [initialised] itself rather than asking the SDK, because the SDK has no way to be
 * asked: `get()` throws when it has not been initialised, and catching an exception to
 * answer a question is not a state check.
 */
@Singleton
internal class RealChatSdkHandle @Inject constructor() : ChatSdkHandle {

    @Volatile
    private var initialised = false

    override val isInitialised: Boolean get() = initialised

    override val isConnected: Boolean get() = initialised && ChatSdk.get().isConnected

    override val myUserId: String? get() = if (initialised) ChatSdk.get().myUserId() else null

    override fun initialise(
        context: Context,
        restBaseUrl: String,
        wsUrl: String,
        deviceKey: String,
        displayName: String?,
    ) {
        ChatSdk.init(
            context,
            ChatConfig.builder()
                .restBaseUrl(restBaseUrl)
                .wsUrl(wsUrl)
                .deviceKey(deviceKey)
                .displayName(displayName ?: deviceKey)
                .build(),
        )
        initialised = true
    }

    override fun connect() {
        if (initialised) ChatSdk.get().connect()
    }

    override fun disconnect() {
        if (initialised) ChatSdk.get().disconnect()
    }

    override fun addListener(listener: ChatListener) {
        if (initialised) ChatSdk.get().addListener(listener)
    }

    override fun me(callback: ChatCallback<User>): Boolean =
        dispatched { ChatSdk.get().me(callback) }

    override fun conversations(callback: ChatCallback<List<Conversation>>): Boolean =
        dispatched { ChatSdk.get().getConversations(callback) }

    override fun openDirectConversation(otherUserId: String, callback: ChatCallback<String>): Boolean =
        dispatched { ChatSdk.get().openDirectConversation(otherUserId, callback) }

    override fun messages(conversationId: String, callback: ChatCallback<List<Message>>): Boolean =
        dispatched { ChatSdk.get().getMessages(conversationId, callback) }

    override fun cachedMessages(conversationId: String): List<Message> =
        if (initialised) ChatSdk.get().cachedMessages(conversationId) else emptyList()

    override fun sendText(conversationId: String, text: String): String? =
        if (initialised) ChatSdk.get().sendText(conversationId, text) else null

    private inline fun dispatched(block: () -> Unit): Boolean {
        if (!initialised) return false
        block()
        return true
    }
}
