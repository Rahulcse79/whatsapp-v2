package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.data.sip.registration.StackAccount
import com.whatsappv2.data.sip.registration.StackMediaEncryption
import com.whatsappv2.data.sip.registration.StackPushParameters
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AccountMediaConfig
import org.pjsip.pjsua2.AccountNatConfig
import org.pjsip.pjsua2.AccountVideoConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.SrtpCrypto
import org.pjsip.pjsua2.SrtpCryptoVector
import org.pjsip.pjsua2.SrtpOpt
import org.pjsip.pjsua2.pjmedia_srtp_use
import org.pjsip.pjsua2.pjmedia_vid_stream_rc_method
import org.pjsip.pjsua2.pjsua_stun_use

/**
 * One account, as pjsua2's `AccountConfig` (§5.1, §5.2, §7).
 *
 * ## Why this is not a method on the gateway
 *
 * The same reason `auditCodecs` is not: `RealPjsipCoreGateway` is over detekt's
 * `LargeClass` threshold, and it crossed it here — the SRTP and NAT work of 2026-09-10
 * added two hundred lines to a class that was already at the line. This is a translation
 * from one data class to another with no gateway state in it at all, so it is the piece
 * that comes out cleanly. The gateway keeps what needs the endpoint, the executor and the
 * account map.
 *
 * ## The shadowing trap, and why the settings are parameters
 *
 * The body runs inside `AccountConfig().apply { … }`, so an unqualified name is resolved
 * against `AccountConfig` **first** and only then against the [StackAccount] receiver.
 * `natConfig.apply { iceEnabled = iceEnabled }` therefore assigns the config's own
 * property to itself and silently does nothing — which is a real bug that was written
 * once already. Every setting crosses into a helper as a parameter for that reason: a
 * parameter cannot be shadowed by the thing it is being written into.
 *
 * [pushParameters] is the gateway's, not the account's: RFC 8599's `pn-` parameters are set
 * once for the process by whoever owns the push token, and every account then advertises
 * them. It arrives as a parameter because this function has no gateway to ask.
 */
internal fun StackAccount.toAccountConfig(
    transportParam: String,
    pushParameters: StackPushParameters?,
): AccountConfig =
    AccountConfig().apply {
        // The transport is selected by the URI parameter, which is what RFC 3261 §19.1.1
        // defines it for — NOT by pinning `sipConfig.transportId`. See the note below on
        // why the pinned form sent nothing at all.
        idUri = "sip:$username@$domain$transportParam"

        regConfig.registrarUri = registrarUri + transportParam
        regConfig.timeoutSec = expirySeconds.toLong()
        regConfig.registerOnAdd = registerEnabled
        // Inside the Contact URI, per RFC 8599 §4.1 - not `contactParams`, which is a
        // header parameter the registrar does not store. See PushContactUriParams.kt.
        pushParameters?.let { push -> regConfig.contactUriParams = push.toContactUriParams() }

        sipConfig.authCreds.add(
            // Realm `*` because the registrar names its own realm in the challenge, and
            // pinning ours would fail every deployment that does not happen to match.
            // `0` is PJSIP's data type for a plaintext password rather than a digest.
            AuthCredInfo("Digest", "*", authUsername, 0, password),
        )
        proxyUri?.let { sipConfig.proxies.add(it) }

        // `sipConfig.transportId` is deliberately NEVER set. Not for UDP, and not for TCP
        // or TLS either.
        //
        // For UDP, pinning defeats RFC 3261 §18.1.1: PJSIP rewrites the destination of a
        // request over 1300 bytes to TCP, `pjsip_endpt_acquire_transport2` then refuses it
        // because the pinned transport is a UDP one, and the message falls back to UDP as
        // one oversized, IP-fragmented datagram that routers drop.
        //
        // For TCP and TLS, pinning is worse: it sends **nothing at all**. `transportCreate`
        // returns the id of a *listener* (a `pjsip_tpfactory`), not of a connected
        // transport, and `pjsua_acc_config.transport_id` turns that into a
        // `PJSIP_TPSELECTOR_TRANSPORT` on the dialog. Acquiring a transport for an outbound
        // request against a selector that names a listener yields nothing usable, so the
        // REGISTER is never put on the wire. Observed exactly that way: the account sat in
        // "Registering…" for 32 seconds and timed out, while the registrar's own log showed
        // **no packet of any kind** from the handset — and a raw TCP connection from the
        // same device to the same port succeeded. No error, no retry, no datagram.
        //
        // The transport is chosen by the `;transport=` URI parameter instead. PJSIP
        // resolves it per request, so an account can still upgrade an oversized message to
        // TCP the way §18.1.1 requires.

        natConfig.applyNatPolicy(
            wantIce = iceEnabled,
            wantStun = stunEnabled,
            keepaliveSeconds = keepaliveIntervalSeconds,
        )
        mediaConfig.applyEncryption(mediaEncryption)
        videoConfig.applyMediaDefaults()
    }

/**
 * NAT traversal, from the account's own policy (§5.1).
 *
 * These used to stop at the domain: the gateway hardcoded `iceEnabled = true` and never
 * read the policy at all, so three settings the account form collects were decoration.
 * ICE forced on also puts `a=ice-ufrag`, `a=ice-pwd` and `a=candidate` into every offer
 * this app sends — 198 measured bytes of a request the reference path carries only 1472
 * of, which is why `NatPolicy.DEFAULT` now says false and why an account saved before that
 * is migrated to it.
 *
 * What ICE would need to be worth those bytes is a reflexive or relayed candidate, and
 * this gateway gathers neither: `SipAccount.stunServer` is collected, validated and
 * persisted, and **nothing below `:domain` reads it**, so no STUN server is ever put on
 * `UaConfig` and `PJSUA_STUN_USE_DEFAULT` has nowhere to ask. Wiring that up is a separate
 * change with its own measurement.
 */
private fun AccountNatConfig.applyNatPolicy(
    wantIce: Boolean,
    wantStun: Boolean,
    keepaliveSeconds: Int,
) {
    iceEnabled = wantIce
    val stunUse = if (wantStun) {
        pjsua_stun_use.PJSUA_STUN_USE_DEFAULT
    } else {
        pjsua_stun_use.PJSUA_STUN_USE_DISABLED
    }
    sipStunUse = stunUse
    mediaStunUse = stunUse
    udpKaIntervalSec = keepaliveSeconds.toLong()
}

/**
 * Media encryption, per account and genuinely so (§7, DoD 13).
 *
 * A core-wide setting would let the last account added decide encryption for every other
 * one, which is the limitation `docs/security.md` used to record.
 *
 * ## Two crypto suites, not the four PJSIP offers
 *
 * A size decision rather than a security one (§18.1.1, `SdpBudget`). Every `a=crypto:`
 * line carries a base64 key sized by its suite, so the two AES_256 suites cost 108 bytes
 * each against 84 for the AES_128 pair — 216 bytes of an offer measured at 270 bytes OVER
 * what the network delivers. Those two figures were estimated at 116 and 76 until they
 * were counted off the real INVITE, and the difference matters: the trim saves 216, not
 * 232, which leaves the request at 1526 and still over the limit. **ICE off is what closes
 * the remaining 54 bytes**, so this trim is a necessary part of the fix and not the whole
 * of it. The 1742-byte INVITE was retransmitted seven times across 32 seconds and drew no
 * response of any kind, because the path drops IP-fragmented datagrams and the server
 * refuses the TCP that §18.1.1 would otherwise escalate to; the 781-byte INVITE sent
 * minutes earlier was answered on the first attempt.
 *
 * What is given up is nothing a peer needs. RFC 4568 §6.2 makes `AES_CM_128_HMAC_SHA1_80`
 * mandatory to implement and the `_32` variant its low-bandwidth companion; the AES_256
 * suites are an extension, and the reference server does not offer them. An account set to
 * MANDATORY still gets real SRTP — 128-bit AES in counter mode with an 80-bit tag — so
 * this narrows the offer without weakening the guarantee.
 */
private fun AccountMediaConfig.applyEncryption(encryption: StackMediaEncryption) {
    srtpUse = when (encryption) {
        StackMediaEncryption.NONE -> pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
        StackMediaEncryption.OPTIONAL -> pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
        StackMediaEncryption.MANDATORY -> pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
    }
    srtpSecureSignaling = if (encryption == StackMediaEncryption.MANDATORY) 1 else 0

    if (encryption == StackMediaEncryption.NONE) return

    srtpOpt = SrtpOpt().apply {
        cryptos = SrtpCryptoVector().apply {
            OFFERED_CRYPTO_SUITES.forEach { suite ->
                // An empty key means PJSIP generates a random one per session, which is
                // the only correct answer: a key written here would be the same for every
                // call this build ever places.
                add(SrtpCrypto().apply { name = suite })
            }
        }
    }
}

/**
 * Video defaults for an account (§5.2).
 *
 * Nothing automatic. `autoTransmitOutgoing` left on would add a camera stream to every
 * call somebody places, and the Task 54 escalation prompt exists precisely because the far
 * end asking for video is a question, not an instruction.
 */
private fun AccountVideoConfig.applyMediaDefaults() {
    autoShowIncoming = false
    autoTransmitOutgoing = false

    // The ceiling the encoder parameters are allowed to reach, and the thing that actually
    // holds them there. Without rate control PJSIP encodes at the format's bitrate whatever
    // the link is doing, and a 2.5 Mbit stream on a cell connection does not degrade - it
    // stalls, because the packets it needs are the ones being dropped.
    rateControlMethod = pjmedia_vid_stream_rc_method.PJMEDIA_VID_STREAM_RC_SIMPLE_BLOCKING
    rateControlBandwidth = VIDEO_MAX_BPS

    // A few keyframes up front. The first frame a decoder can actually show is a keyframe,
    // and one every two seconds means up to two seconds of grey.
    startKeyframeCount = VIDEO_START_KEYFRAMES
    startKeyframeInterval = VIDEO_START_KEYFRAME_INTERVAL_MS
}

/**
 * The video bitrate ceiling, shared with the gateway's own codec format tuning.
 *
 * `internal` and top-level rather than in the gateway's private companion, because both
 * this file and `RealPjsipCoreGateway.applyVideoFormat` write it and one number written in
 * two places is one number that drifts.
 */
internal const val VIDEO_MAX_BPS = 2_500_000L

private const val VIDEO_START_KEYFRAMES = 3L
private const val VIDEO_START_KEYFRAME_INTERVAL_MS = 1_000L

/**
 * The SRTP suites this app offers, in preference order.
 *
 * RFC 4568 §6.2: `AES_CM_128_HMAC_SHA1_80` is mandatory to implement, and the `_32`
 * variant is its low-bandwidth companion. PJSIP would otherwise offer the two AES_256_CM
 * suites in front of these, costing 216 bytes of an SDP that has none to spare — see
 * [applyEncryption] and `SdpBudget`.
 */
private val OFFERED_CRYPTO_SUITES = listOf(
    "AES_CM_128_HMAC_SHA1_80",
    "AES_CM_128_HMAC_SHA1_32",
)
