package com.whatsappv2.data.chat

import com.whatsappv2.domain.chat.ChatMessage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Something the chat server did, on its way from a main-thread callback to a Flow. */
internal sealed interface ChatEvent {
    data object Connected : ChatEvent
    data class Disconnected(val code: Int, val reason: String?) : ChatEvent

    /** A message from somebody else. */
    data class Received(val message: ChatMessage) : ChatEvent

    /** A message this device sent, now carrying its real id and sequence number. */
    data class Acknowledged(val message: ChatMessage) : ChatEvent
}

/**
 * The bridge from the SDK's main-thread callbacks to coroutines.
 *
 * ## Why a buffer that drops rather than one that suspends
 *
 * Every `ChatListener` call arrives on the **main thread** — the SDK posts them to a
 * `Handler(Looper.getMainLooper())`. A `MutableSharedFlow` with no buffer suspends its
 * emitter when a collector is slow, and suspending the main thread is an ANR. So the
 * buffer is generous and overflow drops the **oldest**: a burst of pushes during a slow
 * recomposition costs the oldest event, and the thread screen re-reads the SDK's own
 * snapshot anyway, so nothing is permanently lost.
 *
 * `extraBufferCapacity` with `DROP_OLDEST` is also what makes `tryEmit` always succeed,
 * which is what lets the listener stay non-suspending.
 */
@Singleton
internal class ChatEventBus @Inject constructor() {

    private val events = MutableSharedFlow<ChatEvent>(
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    fun events(): Flow<ChatEvent> = events.asSharedFlow()

    /** Never suspends, never fails. Called from the main thread; see the class comment. */
    fun publish(event: ChatEvent) {
        events.tryEmit(event)
    }

    private companion object {
        const val BUFFER = 64
    }
}
