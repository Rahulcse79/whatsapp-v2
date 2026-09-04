package com.whatsappv2.domain.model

/** The URI scheme. `sips` implies TLS signalling end to end. */
enum class SipScheme(val token: String, val defaultPort: Int) {
    SIP("sip", DEFAULT_SIP_PORT),
    SIPS("sips", DEFAULT_SIPS_PORT),
    ;

    companion object {
        fun fromToken(token: String): SipScheme? =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) }
    }
}
