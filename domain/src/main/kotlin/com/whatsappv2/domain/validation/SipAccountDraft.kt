package com.whatsappv2.domain.validation

import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.AudioCodec
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.model.VideoCodec

/**
 * Raw account form input, before validation.
 *
 * Numeric and host fields are [String] because that is what the user typed; turning
 * them into numbers and hosts is [AccountValidator]'s job. Keeping the draft
 * stringly-typed and the entity strongly-typed is what lets validation happen exactly
 * once, at the boundary.
 *
 * Every field has a default so a new-account form starts from something sensible and
 * tests can vary one field at a time.
 */
data class SipAccountDraft(
    val id: AccountId,
    val label: String = "",
    val username: String = "",
    val extension: String = "",
    val authUsername: String = "",
    val password: Secret = Secret.EMPTY,
    val displayName: String = "",
    val domain: String = "",
    val registrar: String = "",
    val outboundProxy: String = "",
    val port: String = "",
    val transport: Transport = Transport.UDP,
    val registrationExpirySeconds: String = SipAccount.DEFAULT_EXPIRY_SECONDS.toString(),
    val stunServer: String = "",
    val turnServer: String = "",
    val turnUsername: String = "",
    val turnPassword: Secret = Secret.EMPTY,
    /**
     * ICE and STUN as [NatPolicy.DEFAULT] has them, rather than repeated literals.
     *
     * The form's opening position is the app's default policy, so the reason ICE is off —
     * 198 bytes of an offer that already does not fit, gathering candidates no peer can
     * use (see [NatPolicy.DEFAULT]) — is stated once and cannot drift out of step with a
     * new account created anywhere else.
     */
    val iceEnabled: Boolean = NatPolicy.DEFAULT.iceEnabled,
    val stunEnabled: Boolean = NatPolicy.DEFAULT.stunEnabled,
    val keepaliveIntervalSeconds: String = NatPolicy.DEFAULT_KEEPALIVE_SECONDS.toString(),
    // DISABLED, matching AppSettings.defaultSrtpPolicy and for the same measured reason.
    val srtpPolicy: SrtpPolicy = SrtpPolicy.DISABLED,
    val audioCodecs: List<AudioCodec> = CodecPreferences.DEFAULT.audio,
    val videoCodecs: List<VideoCodec> = CodecPreferences.DEFAULT.video,
    val isDefault: Boolean = false,
)

/**
 * Switches the draft's transport, carrying the port across rather than stranding it.
 *
 * ## The trap this closes
 *
 * SIP's default port depends on the transport: 5060 for UDP and TCP, **5061** for TLS
 * (RFC 3261 §19.1.2, and [Transport.defaultPort]). The port field is optional and falls
 * back to the transport's default when it is blank — but an account that was set up on
 * UDP very often holds an explicit `5060`, either because the user typed the number they
 * were given or because a provisioning step filled it in.
 *
 * Flipping such an account to TLS used to keep that `5060`, so the client opened a TLS
 * connection to the registrar's **plaintext** SIP port. The TCP connection succeeds — the
 * port is open, it is just not speaking TLS — so nothing fails fast: the server never
 * answers the ClientHello, the handshake times out, and the account reports the registrar
 * as unreachable. Measured against connect.sks.net.in, whose 5060 accepts the connection
 * and then says nothing at all.
 *
 * The form even told the user the right answer while doing the wrong thing: the port
 * field's hint switches to "Defaults to 5061" the moment TLS is chosen, and the explicit
 * 5060 sitting in the box silently overrode it.
 *
 * ## The rule
 *
 * A port that is exactly the **old** transport's default was never a deliberate choice —
 * it is the default, spelled out — so it follows the transport and becomes the new one's
 * default. Anything else the user typed is a real decision and is left alone: a registrar
 * genuinely listening for TLS on 5080 keeps 5080. A blank port stays blank and keeps
 * resolving through [SipAccount.effectivePort], which is the cleanest state of all.
 */
fun SipAccountDraft.withTransport(next: Transport): SipAccountDraft {
    if (next == transport) return this
    val carried = if (port.trim() == transport.defaultPort.toString()) {
        next.defaultPort.toString()
    } else {
        port
    }
    return copy(transport = next, port = carried)
}
