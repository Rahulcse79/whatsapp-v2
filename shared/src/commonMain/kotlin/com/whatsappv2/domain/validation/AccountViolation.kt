package com.whatsappv2.domain.validation

/** Which form field a violation belongs to, so the UI can place the message on it. */
enum class AccountField {
    LABEL,
    USERNAME,
    EXTENSION,
    AUTH_USERNAME,
    PASSWORD,
    DISPLAY_NAME,
    DOMAIN,
    REGISTRAR,
    OUTBOUND_PROXY,
    PORT,
    TRANSPORT,
    REGISTRATION_EXPIRY,
    STUN_SERVER,
    TURN_SERVER,
    TURN_USERNAME,
    TURN_PASSWORD,
    KEEPALIVE_INTERVAL,
    AUDIO_CODECS,
    VIDEO_CODECS,
}

/**
 * One problem with one field.
 *
 * Typed rather than a message string: the UI owns wording and translation, and a
 * validator that returns English sentences cannot be localised or asserted on
 * precisely in a test.
 */
sealed interface AccountViolation {
    val field: AccountField

    /** The field is mandatory and was left blank. */
    data class Required(override val field: AccountField) : AccountViolation

    /** A numeric field did not parse. */
    data class NotANumber(override val field: AccountField, val value: String) : AccountViolation

    /** A numeric field parsed but fell outside its permitted range. */
    data class OutOfRange(
        override val field: AccountField,
        val value: Int,
        val min: Int,
        val max: Int,
    ) : AccountViolation

    /** A structured field (host, URI, user part) did not parse. */
    data class Malformed(
        override val field: AccountField,
        val value: String,
        val detail: String,
    ) : AccountViolation

    /** Two fields are individually valid but disagree with each other. */
    data class Conflict(override val field: AccountField, val detail: String) : AccountViolation
}

/**
 * A validated account, plus any non-blocking concerns.
 *
 * Warnings exist so an unusual-but-legal configuration is flagged rather than refused.
 * TLS on port 5060 is almost always a mistake, but it is not impossible, and blocking
 * a config the server actually accepts would be worse than a nudge.
 */
data class ValidatedAccount(
    val account: com.whatsappv2.domain.model.SipAccount,
    val warnings: List<AccountViolation>,
)
