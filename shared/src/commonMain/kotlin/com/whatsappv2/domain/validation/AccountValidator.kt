package com.whatsappv2.domain.validation

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.HostPort
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.model.TurnConfiguration

/**
 * Turns a [SipAccountDraft] into a [SipAccount], or reports what is wrong with it.
 *
 * Pure: no I/O, no Android, no clock. That is what lets every rule below be tested
 * directly, and it is why this lives in `:domain` rather than in the ViewModel.
 *
 * **All** violations are collected rather than stopping at the first. A form that
 * reveals one problem per submission is how a user ends up submitting five times.
 */
object AccountValidator {

    private const val SIP_DEFAULT_PORT = 5060
    private const val SIPS_DEFAULT_PORT = 5061

    fun validate(draft: SipAccountDraft): Outcome<ValidatedAccount, List<AccountViolation>> {
        val violations = mutableListOf<AccountViolation>()

        validateIdentity(draft, violations)
        val port = validatePort(draft, violations)
        val expiry = validateExpiry(draft, violations)
        val keepalive = validateKeepalive(draft, violations)
        val hosts = validateHosts(draft, violations)
        val turn = validateTurn(draft, hosts.turn, violations)
        val codecs = validateCodecs(draft, violations)

        if (violations.isNotEmpty()) return failure(violations)

        val account = SipAccount(
            id = draft.id,
            label = draft.label.trim(),
            username = draft.username.trim(),
            extension = draft.extension.trim().ifBlank { null },
            authUsername = draft.authUsername.trim().ifBlank { null },
            password = draft.password,
            displayName = draft.displayName.trim().ifBlank { null },
            domain = draft.domain.trim(),
            registrar = hosts.registrar,
            outboundProxy = hosts.outboundProxy,
            port = port,
            transport = draft.transport,
            registrationExpirySeconds = checkNotNull(expiry),
            stunServer = hosts.stun,
            turn = turn,
            natPolicy = NatPolicy(
                iceEnabled = draft.iceEnabled,
                stunEnabled = draft.stunEnabled,
                keepaliveIntervalSeconds = checkNotNull(keepalive),
            ),
            srtpPolicy = draft.srtpPolicy,
            codecs = checkNotNull(codecs),
            isDefault = draft.isDefault,
        )
        return success(ValidatedAccount(account, warningsFor(draft.transport, port)))
    }

    // ------------------------------------------------------------------ identity

    private fun validateIdentity(draft: SipAccountDraft, into: MutableList<AccountViolation>) {
        if (draft.label.isBlank()) into += AccountViolation.Required(AccountField.LABEL)
        if (draft.password.isEmpty) into += AccountViolation.Required(AccountField.PASSWORD)

        when {
            draft.username.isBlank() -> into += AccountViolation.Required(AccountField.USERNAME)
            !isValidUserPart(draft.username.trim()) -> into += AccountViolation.Malformed(
                AccountField.USERNAME,
                draft.username,
                "contains characters not allowed in a SIP user part",
            )
        }

        when {
            draft.domain.isBlank() -> into += AccountViolation.Required(AccountField.DOMAIN)
            !isValidDomain(draft.domain.trim()) -> into += AccountViolation.Malformed(
                AccountField.DOMAIN,
                draft.domain,
                "is not a valid hostname or IP address",
            )
        }
    }

    /** Reuses [SipUri]'s own parser, so a user part valid here is valid on the wire. */
    private fun isValidUserPart(user: String): Boolean =
        SipUri.parse("sip:$user@example.com") is Outcome.Success

    private fun isValidDomain(domain: String): Boolean =
        SipUri.parse("sip:$domain") is Outcome.Success

    // ------------------------------------------------------------------ numbers

    private fun validatePort(draft: SipAccountDraft, into: MutableList<AccountViolation>): Int? =
        optionalInt(
            raw = draft.port,
            field = AccountField.PORT,
            min = SipAccount.MIN_PORT,
            max = SipAccount.MAX_PORT,
            into = into,
        )

    private fun validateExpiry(draft: SipAccountDraft, into: MutableList<AccountViolation>): Int? =
        requiredInt(
            raw = draft.registrationExpirySeconds,
            field = AccountField.REGISTRATION_EXPIRY,
            min = SipAccount.MIN_EXPIRY_SECONDS,
            max = SipAccount.MAX_EXPIRY_SECONDS,
            into = into,
        )

    private fun validateKeepalive(draft: SipAccountDraft, into: MutableList<AccountViolation>): Int? =
        requiredInt(
            raw = draft.keepaliveIntervalSeconds,
            field = AccountField.KEEPALIVE_INTERVAL,
            min = NatPolicy.MIN_KEEPALIVE_SECONDS,
            max = NatPolicy.MAX_KEEPALIVE_SECONDS,
            into = into,
        )

    private fun optionalInt(
        raw: String,
        field: AccountField,
        min: Int,
        max: Int,
        into: MutableList<AccountViolation>,
    ): Int? = if (raw.isBlank()) null else requiredInt(raw, field, min, max, into)

    private fun requiredInt(
        raw: String,
        field: AccountField,
        min: Int,
        max: Int,
        into: MutableList<AccountViolation>,
    ): Int? {
        if (raw.isBlank()) {
            into += AccountViolation.Required(field)
            return null
        }
        val parsed = raw.trim().toIntOrNull()
        if (parsed == null) {
            into += AccountViolation.NotANumber(field, raw)
            return null
        }
        if (parsed !in min..max) {
            into += AccountViolation.OutOfRange(field, parsed, min, max)
            return null
        }
        return parsed
    }

    // ------------------------------------------------------------------ hosts

    private data class Hosts(
        val registrar: HostPort?,
        val outboundProxy: HostPort?,
        val stun: HostPort?,
        val turn: HostPort?,
    )

    private fun validateHosts(draft: SipAccountDraft, into: MutableList<AccountViolation>) = Hosts(
        registrar = optionalHost(draft.registrar, AccountField.REGISTRAR, into),
        outboundProxy = optionalHost(draft.outboundProxy, AccountField.OUTBOUND_PROXY, into),
        stun = optionalHost(draft.stunServer, AccountField.STUN_SERVER, into),
        turn = optionalHost(draft.turnServer, AccountField.TURN_SERVER, into),
    )

    private fun optionalHost(
        raw: String,
        field: AccountField,
        into: MutableList<AccountViolation>,
    ): HostPort? {
        if (raw.isBlank()) return null

        return when (val parsed = HostPort.parse(raw)) {
            is Outcome.Success -> parsed.value
            is Outcome.Failure -> {
                into += AccountViolation.Malformed(field, raw, "is not a valid host[:port]")
                null
            }
        }
    }

    // ------------------------------------------------------------------ TURN

    /** TURN credentials are only meaningful once a server is configured. */
    private fun validateTurn(
        draft: SipAccountDraft,
        server: HostPort?,
        into: MutableList<AccountViolation>,
    ): TurnConfiguration? {
        if (draft.turnServer.isBlank()) {
            if (draft.turnUsername.isNotBlank() || !draft.turnPassword.isEmpty) {
                into += AccountViolation.Conflict(
                    AccountField.TURN_SERVER,
                    "TURN credentials were provided without a TURN server",
                )
            }
            return null
        }

        if (draft.turnUsername.isBlank()) into += AccountViolation.Required(AccountField.TURN_USERNAME)
        if (draft.turnPassword.isEmpty) into += AccountViolation.Required(AccountField.TURN_PASSWORD)
        if (server == null || draft.turnUsername.isBlank() || draft.turnPassword.isEmpty) return null

        return TurnConfiguration(server, draft.turnUsername.trim(), draft.turnPassword)
    }

    // ------------------------------------------------------------------ codecs

    private fun validateCodecs(
        draft: SipAccountDraft,
        into: MutableList<AccountViolation>,
    ): CodecPreferences? {
        if (draft.audioCodecs.isEmpty()) {
            into += AccountViolation.Required(AccountField.AUDIO_CODECS)
        }
        if (draft.audioCodecs.distinct().size != draft.audioCodecs.size) {
            into += AccountViolation.Conflict(AccountField.AUDIO_CODECS, "contains duplicates")
        }
        if (draft.videoCodecs.distinct().size != draft.videoCodecs.size) {
            into += AccountViolation.Conflict(AccountField.VIDEO_CODECS, "contains duplicates")
        }
        if (into.any { it.field == AccountField.AUDIO_CODECS || it.field == AccountField.VIDEO_CODECS }) {
            return null
        }
        return CodecPreferences(draft.audioCodecs, draft.videoCodecs)
    }

    // ------------------------------------------------------------------ warnings

    /**
     * Transport/port coherence (§5.1). These are warnings, not errors: a server really
     * can run TLS on 5060, and refusing a configuration the registrar accepts would be
     * worse than pointing it out.
     */
    private fun warningsFor(transport: Transport, port: Int?): List<AccountViolation> {
        if (port == null) return emptyList()

        val detail = when {
            transport == Transport.TLS && port == SIP_DEFAULT_PORT ->
                "TLS usually listens on $SIPS_DEFAULT_PORT, not $SIP_DEFAULT_PORT"
            transport != Transport.TLS && port == SIPS_DEFAULT_PORT ->
                "port $SIPS_DEFAULT_PORT is normally TLS, but the transport is $transport"
            else -> return emptyList()
        }
        return listOf(AccountViolation.Conflict(AccountField.PORT, detail))
    }
}
