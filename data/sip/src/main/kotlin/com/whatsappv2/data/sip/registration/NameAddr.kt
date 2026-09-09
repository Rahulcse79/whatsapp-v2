package com.whatsappv2.data.sip.registration

/**
 * A SIP `name-addr`, split into the two things the gateway contract asks for.
 *
 * ## Why this exists
 *
 * pjsua2's `CallInfo.getRemoteUri()` returns the header as it arrived, which for every
 * real peer is a name-addr rather than a bare URI:
 *
 * ```
 * "Alice" <sip:1001@10.174.125.214>
 * <sip:1001@10.174.125.214>
 * ```
 *
 * `SipUri.parse` reads up to the first `:` and asks whether that is a scheme. On the
 * first form it is `"Alice" <sip`, on the second `<sip`, and both are rejected — so
 * **every inbound call was answered with a decline** and logged as "an inbound call whose
 * remote address will not parse". A caller who cannot be addressed cannot be shown, so
 * the engine was right to refuse; it was being handed something it should never have seen.
 *
 * liblinphone gave both halves for free — `asStringUriOnly()` and `displayName` — and the
 * gateway simply passed them on. pjsua2 has no equivalent on `CallInfo`, so the split
 * belongs here: normalising the SDK's representation into this module's contract is what
 * this class is for, and doing it above the seam would put SIP header syntax in `:domain`.
 *
 * @property uri the bare URI, with the angle brackets and any display name removed.
 * @property displayName the quoted or bare display name, or null when there was none.
 */
internal data class NameAddr(val uri: String, val displayName: String?) {

    internal companion object {
        /**
         * Splits [raw], tolerating everything a peer may legitimately send.
         *
         * A value with no angle brackets is already a bare URI and is returned unchanged —
         * that is what pjsua2 produces for a peer that sends no display name and no
         * brackets, and it must keep working. Anything genuinely malformed is left for
         * `SipUri.parse` to reject with a reason, rather than being second-guessed here.
         */
        fun of(raw: String?): NameAddr {
            val trimmed = raw?.trim().orEmpty()
            val open = trimmed.indexOf('<')
            val close = trimmed.lastIndexOf('>')
            if (open < 0 || close <= open) return NameAddr(trimmed, null)

            val uri = trimmed.substring(open + 1, close).trim()
            // The display name is optional, may be quoted, and may be empty once the
            // quotes come off — `"" <sip:...>` is legal and means "no name".
            val name = trimmed.substring(0, open)
                .trim()
                .removeSurrounding("\"")
                .trim()
                .takeIf { it.isNotEmpty() }

            return NameAddr(uri, name)
        }
    }
}
