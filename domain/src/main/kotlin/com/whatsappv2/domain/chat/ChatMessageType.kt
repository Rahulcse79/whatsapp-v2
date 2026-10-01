package com.whatsappv2.domain.chat

/**
 * What a message carries.
 *
 * ## Why [UNKNOWN] exists
 *
 * The server may add a type this build has never heard of, and an inbound push is not a
 * place to crash: `valueOf` on an unrecognised name throws, and it would throw on the
 * socket's callback thread while rendering somebody else's message. Falling back is the
 * only acceptable failure mode, so the fallback is a named value rather than an exception.
 *
 * ## Only [TEXT] can be sent
 *
 * `WsClient.sendMessage` hardcodes it (finding 1.3-9). Every other value is receive-only,
 * which is why the renderer must handle types the composer can never produce.
 */
enum class ChatMessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    SYSTEM,

    /** A type this build does not know. Rendered as "unsupported", never dropped. */
    UNKNOWN,
    ;

    companion object {
        /** Maps the wire's string, case-insensitively, and never throws. */
        fun ofWire(raw: String?): ChatMessageType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}
