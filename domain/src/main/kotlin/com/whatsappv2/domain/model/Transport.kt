package com.whatsappv2.domain.model

/**
 * SIP signalling transport (§5.1).
 *
 * [defaultPort] is the port the registrar is assumed to listen on when an account
 * does not override it. TLS differs from the others, which is a common source of
 * "works on UDP, silently fails on TLS" misconfiguration.
 */
enum class Transport(val token: String, val defaultPort: Int) {
    UDP("udp", DEFAULT_SIP_PORT),
    TCP("tcp", DEFAULT_SIP_PORT),
    TLS("tls", DEFAULT_SIPS_PORT),
    ;

    /** True when the transport encrypts signalling. Media is governed by [SrtpPolicy]. */
    val isSecure: Boolean get() = this == TLS

    companion object {
        /**
         * Parses a transport token case-insensitively, as it appears in a
         * `;transport=` URI parameter. Returns `null` when unrecognised — the caller
         * decides whether that is a validation error or a reason to fall back.
         */
        fun fromToken(token: String): Transport? =
            entries.firstOrNull { it.token.equals(token.trim(), ignoreCase = true) }
    }
}

internal const val DEFAULT_SIP_PORT = 5060
internal const val DEFAULT_SIPS_PORT = 5061
