package com.whatsappv2.data.chat

import com.chatserver.sdk.ChatListener
import com.chatserver.sdk.model.Message

/**
 * The **one** `ChatListener` in the process.
 *
 * ## Why exactly one, owned here
 *
 * Listeners live on `ChatSdk`, which is a process-lifetime singleton, and the SDK offers
 * `removeListener` that nobody reliably calls. A ViewModel that registered directly would
 * be retained for the life of the app (finding 1.3-7) — LeakCanary would catch it, but
 * only after somebody shipped it. Registering once, from
 * [ChatEngineLifecycle], and never removing, makes that leak impossible by construction
 * rather than by discipline: no ViewModel can reach `addListener`, because architecture
 * rule 13 stops it importing the type at all.
 *
 * ## Pure translation, no logic
 *
 * Every method turns an SDK callback into a [ChatEvent] and publishes it. Anything else
 * here would be logic running on the **main thread**, which is where these arrive.
 */
internal class SdkChatListener(private val bus: ChatEventBus) : ChatListener {

    override fun onConnected() = bus.publish(ChatEvent.Connected)

    override fun onDisconnected(code: Int, reason: String?) =
        bus.publish(ChatEvent.Disconnected(code, reason))

    override fun onMessage(message: Message) =
        bus.publish(ChatEvent.Received(ChatModelMapper.toDomain(message)))

    /**
     * The server accepted something this device sent.
     *
     * Matched to the send by `clientMessageId`, which the SDK's own KDoc calls out — it is
     * the only handle that exists across a send, because the server id does not exist
     * until this callback.
     */
    override fun onMessageSent(message: Message) =
        bus.publish(ChatEvent.Acknowledged(ChatModelMapper.toDomain(message)))
}
