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
    val iceEnabled: Boolean = true,
    val stunEnabled: Boolean = true,
    val keepaliveIntervalSeconds: String = NatPolicy.DEFAULT_KEEPALIVE_SECONDS.toString(),
    val srtpPolicy: SrtpPolicy = SrtpPolicy.OPTIONAL,
    val audioCodecs: List<AudioCodec> = CodecPreferences.DEFAULT.audio,
    val videoCodecs: List<VideoCodec> = CodecPreferences.DEFAULT.video,
    val isDefault: Boolean = false,
)
