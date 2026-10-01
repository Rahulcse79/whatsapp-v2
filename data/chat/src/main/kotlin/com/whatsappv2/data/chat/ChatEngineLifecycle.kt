package com.whatsappv2.data.chat

import android.content.Context
import com.chatserver.sdk.ChatCallback
import com.chatserver.sdk.ChatError
import com.chatserver.sdk.model.User
import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.chat.sdk.ChatSdkHandle
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.repository.ChatSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What one running engine is bound to. Two of these being equal is what stops a re-init. */
private data class EngineBinding(val origin: String, val deviceKey: String, val displayName: String?)

/**
 * The two things the repository needs to know about the engine, and nothing else.
 *
 * Extracted so [ChatRepositoryImpl] depends on the *answers* rather than on the object
 * that owns the socket, the Context and the SDK. Beyond tidiness that is what lets the
 * repository's own tests run with no Android at all — a repository that named
 * [ChatEngineLifecycle] would have dragged a `Context` into every one of them.
 */
internal interface ChatEngineState {
    fun observeConnection(): Flow<ChatConnectionState>
    fun observeIdentity(): Flow<ChatIdentity?>
}

/**
 * Owns the chat socket: the one thing that calls `ChatSdk.init`, and the one thing that
 * registers a listener.
 *
 * The shape of `SipEngineLifecycle`, and for the identical reason: nothing above
 * `:data:chat` should be able to name what owns the socket.
 *
 * ## The re-init guard is the whole point of this class
 *
 * `ChatSdk.init()` **leaks one thread per call, for the life of the process** (finding
 * 1.3-6). It disconnects the old instance but never shuts down `WsClient`'s
 * `newSingleThreadScheduledExecutor`, and `disconnect()` cancels the heartbeat *task*,
 * not the executor. So init is called only when **all** of these hold:
 *
 *  1. there is a session — signing out shuts the engine down instead,
 *  2. the value is [distinctUntilChanged] against the last one seen,
 *  3. the binding differs from the one **currently running** ([running]).
 *
 * Steps 2 and 3 are not the same check. `distinctUntilChanged` drops consecutive
 * duplicates; it does not stop A → B → A re-initialising for a value already live. Both
 * are needed, and the doc's own wording — *"typing a 40-character URL into a field wired
 * naively would leak ~40 threads"* — is what they are for.
 *
 * ## Failure here must not stop the app launching
 *
 * This is a SIP client first. A chat server that will not come up is a tab that says so,
 * never a launch that fails — so [start] logs and carries on rather than throwing.
 */
@Singleton
class ChatEngineLifecycle @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val sessions: ChatSessionRepository,
    private val sdk: ChatSdkHandle,
    private val bus: ChatEventBus,
    private val outbox: ChatSendOutbox,
    private val logger: Logger,
) : ChatEngineState {

    private val connection = MutableStateFlow<ChatConnectionState>(ChatConnectionState.NotConfigured)
    private val identity = MutableStateFlow<ChatIdentity?>(null)

    /** The binding the live SDK instance was built with, or null when it has never been built. */
    private var running: EngineBinding? = null

    private var started = false

    override fun observeConnection(): Flow<ChatConnectionState> = connection.asStateFlow()

    override fun observeIdentity(): Flow<ChatIdentity?> = identity.asStateFlow()

    /**
     * Begins following the signed-in session. Called once, from `SipApplication.onCreate`.
     *
     * Idempotent: a second call is ignored rather than starting a second collector, which
     * would double every init decision below.
     */
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        scope.launch {
            combine(sessions.observeSession(), sessions.observeServerUrl()) { session, url ->
                // deviceKey is who chat-node knows this user by - in guest mode, the PPDR
                // username (§10.2). ChatSession.userId IS that username: the Coral login
                // response's `userName`, not its numeric `userId`.
                session?.let {
                    EngineBinding(origin = url.origin, deviceKey = it.userId, displayName = it.displayName)
                }
            }
                .distinctUntilChanged()
                .collect(::apply)
        }

        scope.launch { bus.events().collect(::onEvent) }
    }

    private fun apply(binding: EngineBinding?) {
        if (binding == null) {
            shutDown()
            return
        }
        if (binding == running) {
            // Already live on exactly this binding. Re-initialising would leak a thread
            // and buy nothing; repairing a stale socket is connect()'s job and is cheap.
            sdk.connect()
            return
        }

        val parsed = CoralServerUrl.parse(binding.origin).getOrNull()
        if (parsed == null) {
            // A stored origin this build can no longer parse. Not configured is the
            // honest state; initialising against a guess would be worse.
            logger.warn(TAG, "The stored chat origin does not parse; the engine stays down")
            shutDown()
            return
        }

        connection.value = ChatConnectionState.Connecting
        sdk.initialise(
            context = context,
            restBaseUrl = parsed.chatRestBaseUrl,
            wsUrl = parsed.chatWsUrl,
            deviceKey = binding.deviceKey,
            displayName = binding.displayName,
        )
        // Registered ONCE per SDK instance, and never removed. The instance is new here,
        // so this is the only place a listener is added in the whole application.
        sdk.addListener(SdkChatListener(bus))
        sdk.connect()
        running = binding
        logger.info(TAG, "Chat engine started for ${parsed.chatRestBaseUrl}")
    }

    private fun shutDown() {
        if (running != null) {
            sdk.disconnect()
            running = null
        }
        identity.value = null
        connection.value = ChatConnectionState.NotConfigured
    }

    private fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.Connected -> {
                connection.value = ChatConnectionState.Connected
                resolveIdentity()
                // The socket is back, so anything the outbox holds as failed can go now.
                outbox.resendFailed()
            }
            is ChatEvent.Disconnected ->
                connection.value = ChatConnectionState.Disconnected(event.code, event.reason)
            is ChatEvent.Acknowledged -> outbox.acknowledge(event.message.clientId)
            is ChatEvent.Received -> Unit
        }
    }

    /**
     * Resolves who we are, once the socket is up.
     *
     * It matters before the composer is enabled: a message sent while this is null carries
     * no sender and renders on the wrong side of its own thread (finding 1.3-5).
     */
    private fun resolveIdentity() {
        sdk.me(
            object : ChatCallback<User> {
                override fun onSuccess(result: User) {
                    identity.value = ChatModelMapper.toDomain(result)
                }

                override fun onError(error: ChatError?) {
                    logger.warn(TAG, "Could not resolve the chat identity: ${error?.httpStatus}")
                }
            },
        )
    }

    private companion object {
        const val TAG = "ChatEngine"
    }
}
