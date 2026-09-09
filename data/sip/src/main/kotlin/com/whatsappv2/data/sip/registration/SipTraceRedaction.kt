package com.whatsappv2.data.sip.registration

/**
 * Strips credential material out of a SIP trace before it reaches a log (§7, DoD 12).
 *
 * ## Why a trace needs this at all
 *
 * A SIP message log is the only way to answer "why did that call end", and it is also the
 * one place this app's own credentials appear on the wire. A REGISTER carries
 * `Authorization: Digest username="1001", realm="…", nonce="…", response="…"`, and the
 * `response` is computed from the password. It is a hash rather than the password itself,
 * but it is replayable against that nonce, and §7 does not distinguish: a credential does
 * not reach a log.
 *
 * So the choice is not "trace or safety". It is "redact, or fly blind" — and flying blind
 * is what made every call failure so far a guessing game.
 *
 * ## What is removed, and what is deliberately kept
 *
 * Removed: the value of every `Authorization` and `Proxy-Authorization` header, plus any
 * stray `response=` or `cnonce=` parameter wherever it appears.
 *
 * Kept: **everything else**, including `WWW-Authenticate` and `Proxy-Authenticate`. Those
 * are the server's challenge — a realm and a nonce it just published in the clear — and
 * they are exactly what you need to see when authentication is failing. Redacting them
 * would remove the diagnosis without removing a secret.
 *
 * Also kept: request lines, response codes, `Via`, `Contact`, `From`, `To`, `Call-ID`,
 * and SDP. That is the whole of what a call trace is for.
 */
internal object SipTraceRedaction {

    private const val MASK = "<redacted>"

    /**
     * Header values that are replaced wholesale.
     *
     * The credential-bearing request headers only. A challenge is not a credential.
     */
    private val CREDENTIAL_HEADERS = listOf("Authorization", "Proxy-Authorization")

    /**
     * `Header: <anything>` up to the end of that line, case-insensitively.
     *
     * Anchored to a line start so a header *name* appearing inside an SDP body or a
     * quoted display name cannot swallow the rest of the message.
     */
    private val headerPatterns = CREDENTIAL_HEADERS.map { header ->
        Regex("(?im)^($header\\s*:).*$")
    }

    /**
     * The digest parameters worth removing even out of context.
     *
     * `response` is the credential. `cnonce` is the client's contribution to it and is
     * worth as little to an attacker as it is to a reader, so it goes too rather than
     * being argued about.
     */
    private val parameterPattern = Regex("(?i)\\b(response|cnonce)\\s*=\\s*\"?[^\"\\s,]*\"?")

    /** [trace] with every credential replaced by a marker that says one was there. */
    fun redact(trace: String): String {
        if (trace.isEmpty()) return trace

        var out = trace
        headerPatterns.forEach { pattern ->
            out = pattern.replace(out) { match -> "${match.groupValues[1]} $MASK" }
        }
        return parameterPattern.replace(out) { match -> "${match.groupValues[1]}=$MASK" }
    }
}
