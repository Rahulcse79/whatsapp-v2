package com.whatsappv2.domain.model

/**
 * The host part of a [SipUri], kept as a type rather than a string.
 *
 * The distinction matters at use time: an IPv6 literal must be re-emitted inside
 * brackets, and NAT traversal treats a literal address differently from a name.
 */
sealed interface SipHost {

    /** The value as it should appear in a URI, brackets included for IPv6. */
    val rendered: String

    /** A DNS name, e.g. `sip.example.com`. */
    data class Hostname(val value: String) : SipHost {
        override val rendered: String get() = value
    }

    /** A dotted-quad IPv4 address. */
    data class IpV4(val value: String) : SipHost {
        override val rendered: String get() = value
    }

    /** An IPv6 address. [value] excludes the brackets; [rendered] adds them back. */
    data class IpV6(val value: String) : SipHost {
        override val rendered: String get() = "[$value]"
    }
}
