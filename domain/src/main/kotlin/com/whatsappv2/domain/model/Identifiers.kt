package com.whatsappv2.domain.model

/**
 * Identifies a configured SIP account (§5.1).
 *
 * A value class rather than a `String`: the app is multi-account, and passing an
 * account id where a call id belongs is otherwise a silent bug the compiler cannot see.
 */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "AccountId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Identifies one call within the SIP engine.
 *
 * Deliberately opaque: it is whatever the engine uses to address a dialog, and the
 * domain must not assume it is a SIP `Call-ID` or parse anything out of it.
 */
@JvmInline
value class CallId(val value: String) {
    init {
        require(value.isNotBlank()) { "CallId must not be blank" }
    }

    override fun toString(): String = value
}
