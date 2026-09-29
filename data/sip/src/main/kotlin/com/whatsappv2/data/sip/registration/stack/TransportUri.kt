package com.whatsappv2.data.sip.registration.stack

/**
 * How an account's transport is named on the SIP URIs that select it (RFC 3261 §19.1.1).
 *
 * Top-level and `internal` rather than private to the gateway, because the same rule has
 * to hold in two places that were written months apart and did not agree: the account's
 * identity and registrar URI, and the request URI of every INVITE it places. They
 * disagreed in production — see [withTransportOf].
 */

/**
 * The `;transport=` parameter for an account's transport, or `""` for UDP.
 *
 * UDP is SIP's default transport (RFC 3261 §18.1.1), so naming it adds nothing and costs
 * the ability to upgrade an oversized request — a URI that says `transport=udp` is a URI
 * PJSIP will keep on UDP even when the message no longer fits a datagram.
 *
 * TCP and TLS must be named, because nothing else would select them: neither the registrar
 * URI nor the account id carries the information otherwise, and the alternative — pinning
 * `sipConfig.transportId` — silently sends nothing at all (see `AccountConfigFactory`).
 */
internal fun transportUriParameter(transport: String): String =
    when (transport.uppercase()) {
        RealPjsipCoreGateway.TRANSPORT_TCP -> ";transport=tcp"
        RealPjsipCoreGateway.TRANSPORT_TLS -> ";transport=tls"
        else -> ""
    }

/**
 * A dial target named for the transport its account registered on.
 *
 * ## The defect this closes
 *
 * An account registered over TLS placed its calls over **UDP**. The registration was
 * correct — `idUri` and `regConfig.registrarUri` both carried `;transport=tls` — but the
 * INVITE's request URI is the number the user dialled, and nothing had ever put the
 * transport on it. Measured on a handset (2026-09-29): `55002@connect.sks.net.in`
 * registered over TLS on 5061, and then
 *
 *     TX 1264 bytes Request msg INVITE/cseq=26242 to UDP 122.165.197.93:5060
 *     INVITE sip:55006@connect.sks.net.in SIP/2.0
 *
 * The call connected, which is precisely what makes this worth a test. Nothing looked
 * wrong: the user had chosen TLS, the account said TLS, the status screen said TLS — and
 * every call's signalling, its `From`, its `To` and the digits dialled, went out in clear
 * text. A transport choice that quietly applies to registration only is worse than no
 * choice at all, because it is believed.
 *
 * ## What is left alone
 *
 * A destination that already names a transport: the user typed a full SIP URI and meant
 * it, and appending a second `;transport=` would build a URI no registrar will parse. And
 * an unknown account — there is no transport to speak for, and inventing one would be a
 * guess on the one path where a guess is a silent downgrade.
 */
internal fun String.withTransportOf(transport: String?): String {
    if (transport == null) return this
    if (contains(TRANSPORT_PARAM, ignoreCase = true)) return this
    return this + transportUriParameter(transport)
}

/** Enough to recognise a transport the caller already chose. */
private const val TRANSPORT_PARAM = ";transport="
