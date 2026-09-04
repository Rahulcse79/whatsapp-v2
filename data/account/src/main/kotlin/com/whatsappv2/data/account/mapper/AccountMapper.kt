package com.whatsappv2.data.account.mapper

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.account.db.SipAccountEntity
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.AudioCodec
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.HostPort
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.model.TurnConfiguration
import com.whatsappv2.domain.model.VideoCodec

/**
 * Translation between the stored row and the domain entity.
 *
 * The domain types are validated — `HostPort` cannot be constructed invalid, and
 * `CodecPreferences` rejects an empty audio list — so the row is *parsed* on the way out
 * rather than trusted. A corrupt value is then caught at this boundary instead of
 * reaching a SIP request, and [toDomain] returns null so the caller can decide what to
 * do about it rather than the mapper throwing from inside a Flow.
 */
internal object AccountMapper {

    private const val CODEC_SEPARATOR = ","

    /**
     * Builds the domain entity, or null when the row cannot be trusted.
     *
     * The password is deliberately [Secret.EMPTY]: accounts are observed through
     * long-lived flows, and a decrypted credential must not outlive the operation that
     * needs it. `SipAccountRepository.credentialsFor` supplies the real value.
     */
    fun toDomain(entity: SipAccountEntity): SipAccount? {
        val transport = Transport.entries.firstOrNull { it.name == entity.transport } ?: return null
        val srtp = SrtpPolicy.entries.firstOrNull { it.name == entity.srtpPolicy } ?: return null
        val audio = entity.audioCodecs.toAudioCodecs() ?: return null
        val video = entity.videoCodecs.toVideoCodecs() ?: return null

        return runCatching {
            SipAccount(
                id = AccountId(entity.id),
                label = entity.label,
                username = entity.username,
                extension = entity.extension,
                authUsername = entity.authUsername,
                password = Secret.EMPTY,
                displayName = entity.displayName,
                domain = entity.domain,
                registrar = entity.registrar?.toHostPort(),
                outboundProxy = entity.outboundProxy?.toHostPort(),
                port = entity.port,
                transport = transport,
                registrationExpirySeconds = entity.registrationExpirySeconds,
                stunServer = entity.stunServer?.toHostPort(),
                turn = entity.toTurnConfiguration(),
                natPolicy = NatPolicy(
                    iceEnabled = entity.iceEnabled,
                    stunEnabled = entity.stunEnabled,
                    keepaliveIntervalSeconds = entity.keepaliveIntervalSeconds,
                ),
                srtpPolicy = srtp,
                codecs = CodecPreferences(audio, video),
                isDefault = entity.isDefault,
            )
            // The domain constructor enforces invariants a stored row could violate after
            // a bad migration. Catching here keeps that a dropped row rather than a crash
            // inside a Flow the UI is collecting.
        }.getOrNull()
    }

    /**
     * Builds the stored row.
     *
     * Ciphertext is passed in rather than produced here: the mapper has no business
     * holding a key, and keeping encryption in the repository means there is exactly one
     * place where a plaintext credential is handled.
     */
    fun toEntity(
        account: SipAccount,
        passwordCiphertext: String,
        turnPasswordCiphertext: String?,
        createdAtEpochMillis: Long,
    ) = SipAccountEntity(
        id = account.id.value,
        label = account.label,
        username = account.username,
        extension = account.extension,
        authUsername = account.authUsername,
        passwordCiphertext = passwordCiphertext,
        displayName = account.displayName,
        domain = account.domain,
        registrar = account.registrar?.render(),
        outboundProxy = account.outboundProxy?.render(),
        port = account.port,
        transport = account.transport.name,
        registrationExpirySeconds = account.registrationExpirySeconds,
        stunServer = account.stunServer?.render(),
        turnServer = account.turn?.server?.render(),
        turnUsername = account.turn?.username,
        turnPasswordCiphertext = turnPasswordCiphertext,
        iceEnabled = account.natPolicy.iceEnabled,
        stunEnabled = account.natPolicy.stunEnabled,
        keepaliveIntervalSeconds = account.natPolicy.keepaliveIntervalSeconds,
        srtpPolicy = account.srtpPolicy.name,
        audioCodecs = account.codecs.audio.joinToString(CODEC_SEPARATOR) { it.name },
        videoCodecs = account.codecs.video.joinToString(CODEC_SEPARATOR) { it.name },
        isDefault = account.isDefault,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private fun String.toHostPort(): HostPort? = HostPort.parse(this).getOrNull()

    private fun SipAccountEntity.toTurnConfiguration(): TurnConfiguration? {
        val server = turnServer?.toHostPort() ?: return null
        val user = turnUsername?.takeIf { it.isNotBlank() } ?: return null
        // The password is filled in by credentialsFor when it is actually needed.
        return TurnConfiguration(server, user, Secret.EMPTY)
    }

    /** Null on an unknown codec name: silently dropping one would change the SDP offer. */
    private fun String.toAudioCodecs(): List<AudioCodec>? = split(CODEC_SEPARATOR)
        .filter { it.isNotBlank() }
        .map { name -> AudioCodec.entries.firstOrNull { it.name == name } ?: return null }
        .takeIf { it.isNotEmpty() }

    private fun String.toVideoCodecs(): List<VideoCodec>? = split(CODEC_SEPARATOR)
        .filter { it.isNotBlank() }
        .map { name -> VideoCodec.entries.firstOrNull { it.name == name } ?: return null }
}
