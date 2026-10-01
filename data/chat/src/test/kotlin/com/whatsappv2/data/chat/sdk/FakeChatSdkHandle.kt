package com.whatsappv2.data.chat.sdk

import android.content.Context
import com.chatserver.sdk.ChatCallback
import com.chatserver.sdk.ChatListener
import com.chatserver.sdk.model.Conversation
import com.chatserver.sdk.model.Message
import com.chatserver.sdk.model.User

/**
 * The chat SDK, in memory.
 *
 * This is what [ChatSdkHandle] exists for. `ChatSdk` is a static singleton whose `get()`
 * throws before `init`, whose models are final classes, and which opens a real socket —
 * none of the module's own logic (the re-init guard, the sync loop, the outbox, the
 * callback bridge) could be exercised on a JVM without this.
 *
 * It counts [initCount] rather than merely recording the last config, because the single
 * most valuable assertion in this module is *how many times `init` was called*: every call
 * leaks a thread for the life of the process (finding 1.3-6).
 */
internal class FakeChatSdkHandle : ChatSdkHandle {

    /** **The thread-leak counter.** One per `ChatSdk.init`, and each one is a leaked thread. */
    var initCount = 0
        private set

    var connectCount = 0
        private set

    var disconnectCount = 0
        private set

    val listeners: MutableList<ChatListener> = mutableListOf()
    val bindings: MutableList<String> = mutableListOf()
    val sentTexts: MutableList<Pair<String, String>> = mutableListOf()

    override var isInitialised: Boolean = false
        private set

    /**
     * Marks the SDK up without going through [initialise].
     *
     * For tests about what happens *after* the engine has started — the outbox, the
     * repository — which need a live handle but have no Context and no interest in the
     * binding. Tests about the thread leak use [initialise] and count it.
     */
    fun markInitialised() {
        isInitialised = true
    }

    override var isConnected: Boolean = false

    override var myUserId: String? = null

    /** Pages the "server" will hand back, one call at a time, growing like the real one. */
    var pages: List<List<Message>> = emptyList()
    private var page = 0

    /** When set, every request answers "not dispatched" — the SDK-not-up path. */
    var refuseRequests = false

    var conversationsResult: List<Conversation> = emptyList()
    var meResult: User? = null
    var openResult: String = "conversation-1"

    override fun initialise(
        context: Context,
        restBaseUrl: String,
        wsUrl: String,
        deviceKey: String,
        displayName: String?,
    ) {
        initCount++
        bindings += "$restBaseUrl|$wsUrl|$deviceKey|$displayName"
        isInitialised = true
    }

    override fun connect() {
        connectCount++
    }

    override fun disconnect() {
        disconnectCount++
    }

    override fun addListener(listener: ChatListener) {
        listeners += listener
    }

    override fun me(callback: ChatCallback<User>): Boolean = dispatch {
        meResult?.let(callback::onSuccess)
    }

    override fun conversations(callback: ChatCallback<List<Conversation>>): Boolean = dispatch {
        callback.onSuccess(conversationsResult)
    }

    override fun openDirectConversation(otherUserId: String, callback: ChatCallback<String>): Boolean =
        dispatch { callback.onSuccess(openResult) }

    override fun messages(conversationId: String, callback: ChatCallback<List<Message>>): Boolean =
        dispatch {
            // The real SDK returns its WHOLE snapshot after advancing one page, so the
            // list grows and then stops. A fake that returned one page at a time would
            // let a caller that never loops pass.
            val snapshot = pages.take(minOf(page + 1, pages.size)).flatten()
            if (page < pages.size) page++
            callback.onSuccess(snapshot)
        }

    override fun cachedMessages(conversationId: String): List<Message> =
        pages.take(page).flatten()

    override fun sendText(conversationId: String, text: String): String? {
        if (!isInitialised) return null
        sentTexts += conversationId to text
        return "client-${sentTexts.size}"
    }

    private inline fun dispatch(block: () -> Unit): Boolean {
        if (!isInitialised || refuseRequests) return false
        block()
        return true
    }
}
