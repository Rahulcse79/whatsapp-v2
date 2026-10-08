package com.whatsappv2.data.sip.call

/**
 * Who the far end of an established call has become, as FreeSWITCH announces it mid-dialog.
 *
 * ## The problem this exists to solve
 *
 * A transferred call keeps its dialog. 4023 calls 4022, 4022 transfers the call to 4021,
 * and from the transferee's point of view nothing in SIP has changed: same `Call-ID`, same
 * `From`, same `To`, same tags. The name on screen is read from the `From` header of an
 * INVITE that arrived before the transfer happened, so it keeps naming whoever was on the
 * other end when the call started — and the user is told they are talking to somebody they
 * are no longer talking to.
 *
 * FreeSWITCH's answer is an in-dialog `INFO` with no body and `Content-Type:
 * message/update_display`, carrying the new party in two private headers:
 *
 * ```
 * INFO sip:6001@192.168.30.202:5088;ob SIP/2.0
 * From: "tccs4" <sip:4022@192.168.20.56>;tag=H296pKUZ8mtjS
 * Content-Type: message/update_display
 * Content-Length: 0
 * X-FS-Display-Name: 4021
 * X-FS-Display-Number: 4021
 * X-FS-Lazy-Attended-Transfer: true
 * ```
 *
 * It only sends it to an endpoint that said it would understand one, by offering
 * [SUPPORT_HEADER] on the INVITE or its answer. That is the whole handshake, and it is why
 * this is a setting: an endpoint that stays quiet is never sent one and behaves exactly as
 * it did before.
 *
 * ## What is deliberately not read
 *
 * `X-FS-Lazy-Attended-Transfer` says *why* the display changed — FreeSWITCH's own term for
 * a transfer that has been committed before the target answered. Nothing here branches on
 * it: the display is correct whether the transfer was lazy, attended or blind, and reading
 * it would make this parser refuse an update from a server that worded the reason
 * differently.
 *
 * @property displayName the name to show, from `X-FS-Display-Name`, or null if absent.
 * @property number the new party's user part, from `X-FS-Display-Number`, or null if absent.
 */
internal data class ConnectedPartyUpdate(
    val displayName: String?,
    val number: String?,
) {

    internal companion object {

        /** The content type that marks an `INFO` as a display update. */
        const val CONTENT_TYPE = "message/update_display"

        /** Tells the server this endpoint understands [CONTENT_TYPE] and may be sent one. */
        const val SUPPORT_HEADER = "X-FS-Support"

        /**
         * What [SUPPORT_HEADER] carries.
         *
         * Both tokens, because they are what a FreeSWITCH-aware endpoint on this
         * deployment advertises and what the server's own INVITEs offer back. They are a
         * pair in every capture taken of a working transfer, so they are sent as one.
         */
        const val SUPPORT_VALUE = "update_display,send_info"

        private const val CONTENT_TYPE_HEADER = "content-type"

        /** RFC 3261 §7.3.3's compact form of `Content-Type`. Cheap to accept, silent to miss. */
        private const val CONTENT_TYPE_COMPACT = "c"

        private const val DISPLAY_NAME_HEADER = "x-fs-display-name"
        private const val DISPLAY_NUMBER_HEADER = "x-fs-display-number"

        /**
         * Reads [rawMessage] as a display update, or answers null for anything else.
         *
         * Null for every message that is not one, which is almost all of them: this is fed
         * from a callback that sees every transaction on every call. The content type is
         * checked first and decides it — the caller has already established the method, and
         * a message that claims this type and carries neither header is as useless as one
         * that never claimed it.
         *
         * Folded continuation lines are not handled, for the same reason
         * `conferenceHeaderOf` does not handle them: these are three short single-token
         * values written by one server, and a parser that guessed at folding would be
         * guessing on every call rather than failing on none of them.
         */
        fun parse(rawMessage: String): ConnectedPartyUpdate? {
            // The headers end at the first blank line. A body that happened to contain one
            // of these names must not be read as a header — and `message/update_display`
            // arrives with `Content-Length: 0`, so anything after that line is not ours.
            val headers = rawMessage.lineSequence().takeWhile { it.isNotBlank() }.toList()
            if (!headers.any { it.names(CONTENT_TYPE_HEADER, CONTENT_TYPE_COMPACT) && it.value() == CONTENT_TYPE }) {
                return null
            }

            val name = headers.valueOf(DISPLAY_NAME_HEADER)
            val number = headers.valueOf(DISPLAY_NUMBER_HEADER)
            // Neither header means nothing to apply. Returning an empty update would make
            // every caller re-check what this already knows.
            if (name == null && number == null) return null

            return ConnectedPartyUpdate(displayName = name, number = number)
        }

        /** Whether this header line is one of [names], compared case-insensitively. */
        private fun String.names(vararg names: String): Boolean {
            val field = substringBefore(':', missingDelimiterValue = "").trim().lowercase()
            return field.isNotEmpty() && names.any { it == field }
        }

        /** The header's value, with the field name and surrounding space removed. */
        private fun String.value(): String = substringAfter(':', missingDelimiterValue = "").trim()

        /** The first value for [name], or null when the header is absent or empty. */
        private fun List<String>.valueOf(name: String): String? = firstOrNull { it.names(name) }
            ?.value()
            // Quoted is legal for a display name and is how a name with a space arrives.
            ?.removeSurrounding("\"")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
