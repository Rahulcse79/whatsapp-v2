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

/**
 * The URI as a SIP **name-addr** — `<sip:…>` — which is how it must reach PJSIP if the
 * header built from it is to carry angle brackets.
 *
 * ## The 404 this closes
 *
 * `pjsip_dlg_create_uac` parses both the local and the remote URI with option `0`
 * (`sip_dialog.c:262`, `:297`), and `int_parse_uri_or_name_addr` only produces a
 * `pjsip_name_addr` when the text already begins with `<` or `"` (`sip_parser.c:163`).
 * A bare `sip:1004@host` therefore becomes a plain URI and prints **without** brackets,
 * while REGISTER comes out bracketed because `pjsua_acc` parses the account id with
 * `PJSIP_PARSE_URI_AS_NAMEADDR` (`pjsua_acc.c:325`). That is the whole reason one message
 * had brackets and the other did not.
 *
 * RFC 3261 §20 permits the bare form and defines how to read it, so this was legal — and
 * it still did not work. Captured against the deployment at 192.168.7.14:5070, every
 * INVITE this app sent was answered `404 Not Found` while the extension was demonstrably
 * registered (`200 OK … REGISTER to=<sip:1004@192.168.7.14>` in the same capture), and a
 * MicroSIP client on the same server reached the same kind of destination with `200 OK`.
 * The messages differed in one thing:
 *
 *     ours      To: sip:1004@192.168.7.14        -> 404
 *     MicroSIP  To: <sip:74972@192.168.7.14>     -> 200
 *
 * Every 404 in that capture has an unbracketed `To`; every success has a bracketed one.
 * So the server does not implement §20's disambiguation, and the fix is to send the form
 * it — and every other client on it — actually parses.
 *
 * Idempotent: a URI that already is a name-addr, or that carries a display name, is
 * returned untouched rather than wrapped twice.
 */
internal fun String.asNameAddr(): String {
    val trimmed = trim()
    return if (trimmed.startsWith("<") || trimmed.startsWith("\"")) trimmed else "<$trimmed>"
}
