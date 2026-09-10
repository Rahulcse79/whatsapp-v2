package com.whatsappv2.domain.sdp

/**
 * How large a SIP request may be before the network stops delivering it (RFC 3261 §18.1.1).
 *
 * ## Why a budget exists at all
 *
 * §18.1.1: a UAC that would send a request within 200 bytes of the path MTU **must** use a
 * congestion-controlled transport instead of UDP. PJSIP implements this correctly, and the
 * device trace shows it doing so — it attempted TCP for a 1748-byte INVITE before falling
 * back. What it cannot do is invent a transport the server does not offer.
 *
 * **Measured against the reference server, 2026-09-10**, by sending SIP `OPTIONS` of
 * increasing size and bisecting the point at which answers stop:
 *
 * | SIP payload | Answered |
 * |---|---|
 * | 600, 1200, 1400, 1440, 1460, 1470, **1472** | yes |
 * | **1475**, 1480, 1500, 1742, 1900 | no |
 *
 * 1472 + 8 (UDP header) + 20 (IPv4 header) = **1500**, the Ethernet MTU exactly. The path
 * discards every IP-fragmented datagram silently: no ICMP, no response, nothing to observe.
 * The same probe found TCP 5060 refusing connections and TLS 5061 closed, so there is no
 * congestion-controlled transport to escalate to.
 *
 * The consequence on the wire was a call that "never reaches the destination": a 1742-byte
 * INVITE retransmitted seven times across 32 seconds with no response of any kind, ending at
 * Timer B. The same handset's 781-byte INVITE, minutes earlier, drew a `100 Trying` on the
 * first attempt — the control that turns this from a theory into a measurement.
 *
 * ## What this type is for
 *
 * It is not a wire-level check: nothing in Kotlin sees the datagram PJSIP builds. It is the
 * arithmetic that decides **what may go into an offer**, kept in one place with the numbers
 * that justify it, so a change which re-inflates the SDP fails a test rather than a call.
 * [MAX_UDP_REQUEST_BYTES] is the measurement; everything else is derived from it.
 */
object SdpBudget {

    /**
     * The largest SIP request an IPv4/Ethernet path carries in one unfragmented datagram.
     *
     * 1500 (Ethernet MTU) − 20 (IPv4 header) − 8 (UDP header). Measured to the byte on the
     * reference network, above. It is a property of a *path*, not of this server: any link
     * with a 1500-byte MTU that drops fragments — which is most of them, and every NAT worth
     * worrying about — imposes the same figure.
     */
    const val MAX_UDP_REQUEST_BYTES: Int = 1472

    /**
     * §18.1.1's headroom: a request within 200 bytes of the MTU must not use UDP.
     *
     * PJSIP applies this itself. It is restated here because it is the number that decides
     * whether an offer is *safe* rather than merely *deliverable*: a request between
     * [SAFE_UDP_REQUEST_BYTES] and [MAX_UDP_REQUEST_BYTES] arrives today and stops arriving
     * the moment one router in the path uses a smaller MTU, or the far end adds a
     * `Record-Route` header.
     */
    const val MTU_HEADROOM_BYTES: Int = 200

    /** The size an offer must stay under to be safe rather than lucky. 1472 − 200 = 1272. */
    const val SAFE_UDP_REQUEST_BYTES: Int = MAX_UDP_REQUEST_BYTES - MTU_HEADROOM_BYTES

    /**
     * SIP headers on an INVITE this app sends, measured off the device.
     *
     * The 2026-09-10 INVITE was 1742 bytes carrying 1092 bytes of SDP, so its headers —
     * request line, `Via`, `From`, `To`, `Contact`, `Call-ID`, `CSeq`, `Allow`, `Supported`,
     * `Session-Expires`, `Min-SE`, `User-Agent`, `Content-Type`, `Content-Length` — came to
     * 650 bytes. Treated as fixed, because none of it is ours to shrink: `Allow` and
     * `Supported` are PJSIP's, and a shorter `Contact` is not a thing worth trading for.
     */
    const val TYPICAL_INVITE_HEADER_BYTES: Int = 650

    /** What is left for SDP once the headers are paid for, at the safe bound. */
    const val SAFE_SDP_BYTES: Int = SAFE_UDP_REQUEST_BYTES - TYPICAL_INVITE_HEADER_BYTES

    /**
     * The cost of one `a=crypto:` line for a 256-bit suite, measured from the device's offer.
     *
     * The line is `a=crypto:<tag> <suite> inline:<base64 key>` plus CRLF, and the key length
     * follows the suite's key size — which is why the AES_256 suites cost half as much again
     * as the AES_128 ones, and are the first thing to give up when the budget is tight.
     */
    const val AES_256_CRYPTO_LINE_BYTES: Int = 108

    /** The same, for a 128-bit suite. */
    const val AES_128_CRYPTO_LINE_BYTES: Int = 84

    /**
     * What `a=ice-ufrag`, `a=ice-pwd` and two host `a=candidate` lines cost, per media line.
     *
     * Measured: 198 bytes for one audio stream on a handset with one address. It grows with
     * the number of local addresses, so on a device with Wi-Fi and cellular both up it is
     * larger — which is the case where the budget matters most and the measurement is least
     * reliable, so this is a floor rather than a figure to plan against.
     */
    const val ICE_BYTES_PER_MEDIA_LINE: Int = 198

    /**
     * Whether an offer of [sdpBytes] fits one datagram, with §18.1.1's headroom.
     *
     * @param sdpBytes the SDP body's length.
     * @param headerBytes the SIP headers in front of it; defaults to what this app sends.
     */
    fun fitsOneDatagram(
        sdpBytes: Int,
        headerBytes: Int = TYPICAL_INVITE_HEADER_BYTES,
    ): Boolean = sdpBytes + headerBytes <= SAFE_UDP_REQUEST_BYTES

    /**
     * How far an offer of [sdpBytes] is over the safe bound. Zero when it fits.
     *
     * Returned rather than thrown: the caller's job is to report a number, not to refuse a
     * call it can still attempt. A request over this bound may well arrive — 1742 bytes is
     * over the *hard* limit and got nothing, while 1400 bytes was answered every time — and a
     * client that refused to try would be worse than one that tries and says why it failed.
     */
    fun excessBytes(
        sdpBytes: Int,
        headerBytes: Int = TYPICAL_INVITE_HEADER_BYTES,
    ): Int = (sdpBytes + headerBytes - SAFE_UDP_REQUEST_BYTES).coerceAtLeast(0)
}
