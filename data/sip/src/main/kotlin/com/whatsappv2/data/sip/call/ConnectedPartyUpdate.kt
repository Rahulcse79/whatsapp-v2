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
 * ## The headers can go missing in transit, and that is not this parser's fault
 *
 * `mod_sofia` builds that INFO in exactly one place (`mod_sofia.c:2017-2035`), and that
 * place **always** writes both `X-FS-Display-*` headers into the message before
 * `nua_info` — there is no branch in FreeSWITCH that emits this content type without them.
 * An INFO of this type that arrives carrying neither header therefore did not leave
 * FreeSWITCH that way: something between stripped them. On the deployment this was
 * measured against (2026-10-08) that is the SBC at `.56`, which re-originates the INFO
 * from the `.57` FreeSWITCH behind it — the received message carries both Vias.
 *
 * So the no-identity case is real, routine on some paths, and must be survivable rather
 * than exceptional: see [hasIdentity]. What it must **not** do is reach for `From`, which
 * after a transfer still names the party that has gone (measured: `From: "tccs4"
 * <sip:4022@…>` on an INFO whose whole purpose was to say the far end is no longer 4022).
 *
 * @property displayName the name to show, from `X-FS-Display-Name`, or null if absent.
 * @property number the new party's user part, from `X-FS-Display-Number`, or null if absent.
 */
internal data class ConnectedPartyUpdate(
    val displayName: String?,
    val number: String?,
    /**
     * `X-FS-Lazy-Attended-Transfer: true` — FreeSWITCH's own word for a transfer it
     * committed before the target answered.
     *
     * Carried, logged, and deliberately not branched on. The display is correct whether
     * the transfer was lazy, attended or blind, so making it a condition would only create
     * a way for a correct update to be thrown away. It earns its place as the one field
     * that says *why* an update arrived, which is what makes a log line diagnosable.
     */
    val lazyAttendedTransfer: Boolean = false,
) {

    /**
     * Whether this update actually names somebody.
     *
     * False for the bodyless INFO with no `X-FS-Display-*` headers that this deployment's
     * SBC produces. Such a message is still a well-formed display update and is still
     * answered `200 OK` — it simply carries nothing to apply, and the caller must leave the
     * display exactly as it was rather than invent an identity or fall back to `From`,
     * which after a transfer still names the party that has gone.
     */
    val hasIdentity: Boolean get() = displayName != null || number != null

    internal companion object {

        /** The content type that marks an `INFO` as a display update. */
        const val CONTENT_TYPE = "message/update_display"

        /**
         * The log tag every line of this feature is written under, on both sides of the seam.
         *
         * Here rather than in either user, because both the gateway that reads the INFO
         * and the engine that applies it write under it, and the whole point of the tag
         * is that one `logcat -s` shows the feature end to end. Defining it twice would
         * let the two halves drift apart and quietly break that.
         */
        const val LOG_TAG = "CallerIdTransfer"

        /** Tells the server this endpoint understands [CONTENT_TYPE] and may be sent one. */
        const val SUPPORT_HEADER = "X-FS-Support"

        /**
         * What [SUPPORT_HEADER] carries — and, pointedly, what it does **not**.
         *
         * `send_info` only. The obvious value is `update_display,send_info`, and it is
         * wrong here, because `mod_sofia` treats that token as a *mechanism selector* and
         * not a capability:
         *
         * ```c
         * if (switch_stristr("update_display", x_freeswitch_support_remote)) {
         *     …INFO, Content-Type: message/update_display, X-FS-Display-* headers…
         * } else if (ua && switch_stristr("snom", ua)) {
         *     …INFO, message/sipfrag…
         * } else if (update_allowed && ua && (…vendor list…)) {
         *     …UPDATE, P-Asserted-Identity…            ← mod_sofia.c:2068
         * }
         * ```
         *
         * Offering `update_display` therefore *selects the first branch*, whose identity
         * rides on private `X-` headers. Measured on this deployment 2026-10-08: those
         * headers do not survive the hop in front of FreeSWITCH, and the INFO arrives with
         * `Content-Length: 0` and nothing in it. The third branch carries the same identity
         * in `P-Asserted-Identity` on a standard in-dialog `UPDATE`, and that **does**
         * survive — proven in one transfer with two handsets on different builds:
         *
         * ```
         * 4021 (no update_display token)  UPDATE … P-Asserted-Identity: "…" <sip:4023@…>
         * 4023 (update_display token)     INFO … Content-Length: 0, no X-FS-* at all
         * ```
         *
         * So the token is withheld to *deselect* a mechanism that is broken in transit.
         *
         * **Deselecting it is only half the job**, and the missing half is why a transfer
         * still showed the wrong party after that change. The third branch tests `ua`,
         * FreeSWITCH reads `ua` for a leg it dialled out of the *response* to its INVITE,
         * and pjsip names itself on requests only — so on an inbound call every branch
         * fell through and nothing was sent at all. Both surviving parties of a transfer
         * are parties that answered, which is why both kept the departed name. The answer
         * now carries `Server` as well; see `RealPjsipCoreGateway.SERVER_HEADER`.
         *
         * The header itself is still sent, and [parse] still reads the `X-FS-Display-*`
         * form, so a deployment that delivers it intact keeps working.
         */
        const val SUPPORT_VALUE = "send_info"

        private const val CONTENT_TYPE_HEADER = "content-type"

        /** RFC 3261 §7.3.3's compact form of `Content-Type`. Cheap to accept, silent to miss. */
        private const val CONTENT_TYPE_COMPACT = "c"

        private const val DISPLAY_NAME_HEADER = "x-fs-display-name"
        private const val DISPLAY_NUMBER_HEADER = "x-fs-display-number"
        private const val LAZY_TRANSFER_HEADER = "x-fs-lazy-attended-transfer"
        private const val ASSERTED_IDENTITY_HEADER = "p-asserted-identity"

        /**
         * Reads [rawMessage] as a display update, or answers null for anything else.
         *
         * **Null means "not a display update at all"**, which is almost every message: this
         * is fed from a callback that sees every transaction on every call. A non-null
         * result means the content type matched, and [hasIdentity] then says whether there
         * was anything in it — the two questions are separated because they have different
         * answers. A message of this type with no usable headers must still be answered and
         * must still leave the display alone; collapsing it into `null` would make those
         * two cases indistinguishable to the caller and unloggable.
         *
         * Folded continuation lines are not handled, for the same reason
         * `conferenceHeaderOf` does not handle them: these are short single-token values
         * written by one server, and a parser that guessed at folding would be guessing on
         * every call rather than failing on none of them.
         */
        fun parse(rawMessage: String): ConnectedPartyUpdate? {
            // The headers end at the first blank line. A body that happened to contain one
            // of these names must not be read as a header - and this INFO arrives with
            // `Content-Length: 0`, so anything after that line is not ours.
            val headers = rawMessage.lineSequence().takeWhile { it.isNotBlank() }.toList()
            if (!headers.any { it.names(CONTENT_TYPE_HEADER, CONTENT_TYPE_COMPACT) && it.value() == CONTENT_TYPE }) {
                return null
            }

            return ConnectedPartyUpdate(
                displayName = headers.valueOf(DISPLAY_NAME_HEADER),
                number = headers.valueOf(DISPLAY_NUMBER_HEADER),
                lazyAttendedTransfer = headers.valueOf(LAZY_TRANSFER_HEADER).toBoolean(),
            )
        }

        /**
         * Reads the connected party out of an `UPDATE`'s `P-Asserted-Identity` (RFC 3325).
         *
         * The second delivery mechanism, and on this deployment the only one that arrives
         * intact — see [SUPPORT_VALUE] for why, and why we steer FreeSWITCH onto it.
         *
         * ```
         * UPDATE sip:4021@192.168.103.117:53135;ob SIP/2.0
         * From: <sip:4022@192.168.20.56>;tag=99r6rpN4FZvHg      ← still the party LEAVING
         * P-Asserted-Identity: "Outbound Call" <sip:4023@192.168.20.57>
         * ```
         *
         * **The URI is taken and the display name is dropped**, deliberately. FreeSWITCH
         * fills that name from the channel's callee-id, which on this deployment is the
         * profile's own label for the leg — `"Outbound Call"` — and not a party at all.
         * Rendering it would replace one wrong name with another. The URI's user part is
         * the asserted identity, it is what the transfer actually changed, and once it
         * reaches the snapshot the address book is re-queried against it, so a deployment
         * that has real names still shows them — from the phone's own contacts, which are
         * a better source than a trunk label.
         *
         * Null for every message without the header, which is almost all of them.
         */
        fun parseAssertedIdentity(rawMessage: String): ConnectedPartyUpdate? {
            val asserted = rawMessage.lineSequence()
                .takeWhile { it.isNotBlank() }
                .firstOrNull { it.names(ASSERTED_IDENTITY_HEADER) }
                ?.value()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            // `user@host` out of `"Name" <sip:user@host>`; the brackets and any display
            // name come off in NameAddr, which is the one place that syntax is understood.
            val user = asserted.substringAfter('<', missingDelimiterValue = asserted)
                .substringBefore('>')
                .substringAfter(':')
                .substringBefore('@')
                .substringBefore(';')
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return null

            return ConnectedPartyUpdate(displayName = null, number = user)
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
