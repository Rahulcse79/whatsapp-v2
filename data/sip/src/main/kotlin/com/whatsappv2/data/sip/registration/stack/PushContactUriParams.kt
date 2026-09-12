package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.data.sip.registration.StackPushParameters

/**
 * The RFC 8599 push parameters as a `Contact` **URI** parameter string (ADR-004, Task 38).
 *
 * ## URI parameters, not header parameters
 *
 * RFC 8599 §4.1 places `pn-provider`, `pn-param` and `pn-prid` inside the `Contact` URI:
 *
 * ```
 * Contact: <sip:1001@10.0.0.5:46020;ob;pn-provider=fcm;pn-param=…;pn-prid=…>
 * ```
 *
 * pjsua2 has two fields that look alike. `AccountRegConfig.contactParams` is appended
 * *after* the closing `>` — a header parameter, like `;expires=` — and
 * `contactUriParams` goes inside the brackets (`pjsua_acc.c`, `update_regc_contact`).
 * The difference decides whether the server ever sees the token: FreeSWITCH rebuilds the
 * contact it stores from the URI and its parameters (`;ob` survives into
 * `show registrations`) and drops header parameters on the floor. The first version of
 * this used `contactParams`, which is why the gateway had nothing to look up.
 *
 * ## Escaping
 *
 * pjsua2 inserts the string verbatim, so this is where RFC 3261 §25.1 is enforced: a value
 * may contain `paramchar` only, and everything else is percent-encoded. An FCM token is
 * alphanumerics plus `:`, `-` and `_`, all of which pass unchanged — the escaping is for
 * the day a provider hands out something else, so that day is a longer Contact and not a
 * REGISTER the registrar rejects as malformed.
 */
internal fun StackPushParameters.toContactUriParams(): String =
    ";pn-provider=${escapeUriParamValue(provider)}" +
        ";pn-param=${escapeUriParamValue(param)}" +
        ";pn-prid=${escapeUriParamValue(prid)}"

/**
 * Percent-encodes everything RFC 3261 §25.1 does not allow in a URI parameter value.
 *
 * `paramchar = param-unreserved / unreserved / escaped`, where `param-unreserved` is
 * `[ ] / : & + $` and `unreserved` is alphanumerics plus `- _ . ! ~ * ' ( )`. Encoded as
 * UTF-8 bytes, upper-case hex, which is the form `pjsip_parse_uri` decodes.
 */
internal fun escapeUriParamValue(value: String): String = buildString(value.length) {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val char = byte.toInt().toChar()
        if (byte >= 0 && char.isParamChar()) append(char) else append("%%%02X".format(byte.toInt() and BYTE_MASK))
    }
}

private fun Char.isParamChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in PARAM_PUNCTUATION

private const val PARAM_PUNCTUATION = "-_.!~*'()[]/:&+$"
private const val BYTE_MASK = 0xFF
