package com.whatsappv2.feature.chat

/**
 * Something the Chats tab has to say once.
 *
 * A one-shot rather than a field on `ChatsUiState`: these are replies to a gesture, and a
 * flag in state announces itself again on the next recomposition — and again after a
 * rotation, to somebody who has long since moved on.
 */
sealed interface ChatsEvent {

    /** A sixth pin was asked for. Carries the limit so the message states the actual number. */
    data class PinLimitReached(val limit: Int) : ChatsEvent
}
