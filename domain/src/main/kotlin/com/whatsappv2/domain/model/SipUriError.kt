package com.whatsappv2.domain.model

/**
 * Why a string is not a usable SIP URI.
 *
 * Each case names the offending part, so the account editor (Task 21) can put the
 * message on the right field instead of showing one generic "invalid URI".
 */
sealed interface SipUriError {

    /** The input was empty or only whitespace. */
    data object Empty : SipUriError

    /** No `scheme:` prefix at all. */
    data object MissingScheme : SipUriError

    /** A scheme other than `sip` or `sips`. */
    data class UnsupportedScheme(val scheme: String) : SipUriError

    /** The host part was absent. */
    data object MissingHost : SipUriError

    /** The host is not a valid hostname, IPv4 address or bracketed IPv6 literal. */
    data class InvalidHost(val host: String) : SipUriError

    /** The port was not a number, or fell outside 1..65535. */
    data class InvalidPort(val port: String) : SipUriError

    /** The user part contains characters RFC 3261 does not permit there. */
    data class InvalidUser(val user: String) : SipUriError

    /** A `;name=value` parameter was malformed, e.g. an empty name. */
    data class InvalidParameter(val parameter: String) : SipUriError
}
