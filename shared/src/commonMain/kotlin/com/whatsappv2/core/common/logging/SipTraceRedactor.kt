package com.whatsappv2.core.common.logging

/**
 * Strips credentials from a SIP message before it reaches a log.
 *
 * A SIP trace is the single most useful diagnostic for registration problems and the
 * single most dangerous thing to write to a log file. `Authorization` carries the digest
 * response computed from the user's password; `WWW-Authenticate` carries the nonce that
 * makes an offline attack against it practical. Neither may be written verbatim (§7,
 * DoD 12).
 *
 * Header *names* are kept, and so is enough structure to diagnose a failure - which
 * realm was challenged, which algorithm was used - because a trace with the headers
 * removed entirely cannot answer the question it exists to answer.
 *
 * This is a safety net, not permission to trace in production: the toggle is off by
 * default and absent from release builds.
 */
object SipTraceRedactor {

    /** Headers whose value is, or reveals, a credential. */
    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "proxy-authorization",
        "www-authenticate",
        "proxy-authenticate",
        "authentication-info",
    )

    /**
     * Parameters kept in full inside an otherwise redacted header.
     *
     * They identify *what* was challenged rather than *how* it was answered, which is
     * exactly what a support engineer needs and an attacker cannot use.
     */
    private val SAFE_PARAMETERS = setOf("realm", "algorithm", "qop", "scheme", "stale", "opaque")

    private const val MASK = "***"

    /** Header lines are `Name: value`; SIP allows any spacing around the colon. */
    private val HEADER = Regex("""^([A-Za-z-]+)\s*:\s*(.*)$""")

    /** `name=value` or `name="value"` inside an auth header. */
    private val PARAMETER = Regex("""([A-Za-z-]+)\s*=\s*("[^"]*"|[^,\s]+)""")

    /**
     * Returns [message] with every credential replaced.
     *
     * Line endings are preserved so the result still reads as a SIP message.
     */
    fun redact(message: String): String =
        message.lineSequence().joinToString("\n", transform = ::redactLine)

    private fun redactLine(line: String): String {
        val match = HEADER.matchEntire(line.trimEnd()) ?: return line
        val (name, value) = match.destructured
        if (name.lowercase() !in SENSITIVE_HEADERS) return line

        return "$name: ${redactCredentialValue(value)}"
    }

    /**
     * Keeps the scheme and the safe parameters, masks everything else.
     *
     * A value with no recognisable parameters is masked entirely rather than passed
     * through - an unfamiliar auth scheme is exactly the case where guessing is wrong.
     */
    private fun redactCredentialValue(value: String): String {
        val scheme = value.substringBefore(' ', missingDelimiterValue = "").trim()
        val parameters = PARAMETER.findAll(value).toList()
        if (parameters.isEmpty()) return MASK

        val rendered = parameters.joinToString(", ") { parameter ->
            val (key, raw) = parameter.destructured
            if (key.lowercase() in SAFE_PARAMETERS) "$key=$raw" else "$key=$MASK"
        }
        return if (scheme.isEmpty()) rendered else "$scheme $rendered"
    }
}
