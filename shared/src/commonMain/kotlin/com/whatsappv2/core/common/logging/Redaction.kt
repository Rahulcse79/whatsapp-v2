package com.whatsappv2.core.common.logging

/**
 * Redaction helpers for anything that must never reach a log verbatim (§7, DoD 12).
 *
 * These are a safety net, not a licence to log sensitive values: the primary rule is
 * that credentials and SIP signalling are not logged at all in release builds.
 */

private const val VISIBLE_TAIL = 2
private const val MASK = "***"

/** Fully masks a value, keeping only its length so a log can still say "it was empty". */
fun redact(value: String?): String = when {
    value == null -> "null"
    value.isEmpty() -> "<empty>"
    else -> "$MASK(${value.length})"
}

/**
 * Masks all but the last [VISIBLE_TAIL] characters — enough to correlate two log lines
 * without disclosing the value. Short values are masked completely.
 */
fun redactPartial(value: String?): String = when {
    value == null -> "null"
    value.length <= VISIBLE_TAIL -> MASK
    else -> MASK + value.takeLast(VISIBLE_TAIL)
}

/**
 * Masks the user part of a SIP URI while keeping the host, so registration problems
 * stay diagnosable without logging who is calling whom.
 *
 * `sip:alice@example.com:5061` becomes `sip:***(5)@example.com:5061`.
 */
fun redactSipUri(uri: String?): String {
    if (uri == null) return "null"
    val at = uri.lastIndexOf('@')
    if (at <= 0) return redact(uri)
    val scheme = uri.substringBefore(':', missingDelimiterValue = "")
    val user = uri.substring(scheme.length + 1, at)
    return buildString {
        if (scheme.isNotEmpty()) append(scheme).append(':')
        append(redact(user))
        append(uri.substring(at))
    }
}
