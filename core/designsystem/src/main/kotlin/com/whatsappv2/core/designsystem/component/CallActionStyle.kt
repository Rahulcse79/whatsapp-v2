package com.whatsappv2.core.designsystem.component

/** What a call button does, which decides how it must look. */
enum class CallActionStyle {
    /** A toggle such as mute or speaker. Tinted when active. */
    TOGGLE,

    /** Answering a call. Always green, never re-tinted by dynamic colour. */
    ANSWER,

    /** Ending or rejecting a call. Always red, and larger. */
    HANG_UP,
}
