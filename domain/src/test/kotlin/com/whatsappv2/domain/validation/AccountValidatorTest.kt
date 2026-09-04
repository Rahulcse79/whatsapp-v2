package com.whatsappv2.domain.validation

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.AudioCodec
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.model.VideoCodec
import com.whatsappv2.core.common.secret.Secret
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class AccountValidatorTest {

    /** A draft that passes, so each test can break exactly one thing. */
    private fun valid(
        block: SipAccountDraft.() -> SipAccountDraft = { this },
    ): SipAccountDraft = SipAccountDraft(
        id = AccountId("acct-1"),
        label = "Work",
        username = "alice",
        password = Secret("hunter22"),
        domain = "sip.example.com",
    ).block()

    private fun accept(draft: SipAccountDraft): ValidatedAccount =
        AccountValidator.validate(draft).getOrNull() ?: fail(
            "expected the draft to validate, got ${AccountValidator.validate(draft).errorOrNull()}",
        )

    private fun reject(draft: SipAccountDraft): List<AccountViolation> =
        AccountValidator.validate(draft).errorOrNull() ?: fail("expected the draft to be rejected")

    // ------------------------------------------------------------------ happy path

    @Test
    fun `a minimal draft validates and applies defaults`() {
        val account = accept(valid()).account
        assertEquals("Work", account.label)
        assertEquals("alice", account.username)
        assertEquals("sip.example.com", account.domain)
        assertEquals(Transport.UDP, account.transport)
        assertEquals(SipAccount.DEFAULT_EXPIRY_SECONDS, account.registrationExpirySeconds)
        assertEquals(NatPolicy.DEFAULT_KEEPALIVE_SECONDS, account.natPolicy.keepaliveIntervalSeconds)
        assertEquals(SrtpPolicy.OPTIONAL, account.srtpPolicy)
        assertTrue(account.codecs.audio.isNotEmpty())
    }

    @Test
    fun `optional fields left blank become null rather than empty strings`() {
        val account = accept(valid()).account
        assertNull(account.extension)
        assertNull(account.authUsername)
        assertNull(account.displayName)
        assertNull(account.registrar)
        assertNull(account.outboundProxy)
        assertNull(account.port)
        assertNull(account.stunServer)
        assertNull(account.turn)
    }

    @Test
    fun `values are trimmed`() {
        val account = accept(valid { copy(label = "  Work  ", username = "  alice  ") }).account
        assertEquals("Work", account.label)
        assertEquals("alice", account.username)
    }

    // ------------------------------------------------------------------ auth username fallback

    @Test
    fun `auth username falls back to username when omitted`() {
        assertEquals("alice", accept(valid()).account.effectiveAuthUsername)
    }

    @Test
    fun `auth username falls back to username when blank`() {
        val account = accept(valid { copy(authUsername = "   ") }).account
        assertNull(account.authUsername, "a blank auth username is stored as unset")
        assertEquals("alice", account.effectiveAuthUsername)
    }

    @Test
    fun `an explicit auth username is kept`() {
        val account = accept(valid { copy(authUsername = "alice-auth") }).account
        assertEquals("alice-auth", account.effectiveAuthUsername)
    }

    // ------------------------------------------------------------------ required fields

    @Test
    fun `label username domain and password are required`() {
        val violations = reject(
            SipAccountDraft(id = AccountId("acct-1")),
        )
        assertContains(violations, AccountViolation.Required(AccountField.LABEL))
        assertContains(violations, AccountViolation.Required(AccountField.USERNAME))
        assertContains(violations, AccountViolation.Required(AccountField.DOMAIN))
        assertContains(violations, AccountViolation.Required(AccountField.PASSWORD))
    }

    @Test
    fun `every problem is reported at once rather than one per submission`() {
        val violations = reject(
            valid { copy(label = "", port = "abc", registrationExpirySeconds = "0") },
        )
        assertTrue(violations.size >= 3, "expected several violations, got $violations")
        assertTrue(violations.any { it.field == AccountField.LABEL })
        assertTrue(violations.any { it.field == AccountField.PORT })
        assertTrue(violations.any { it.field == AccountField.REGISTRATION_EXPIRY })
    }

    // ------------------------------------------------------------------ syntax

    @Test
    fun `a malformed username is rejected`() {
        val violations = reject(valid { copy(username = "ali ce") })
        assertTrue(violations.any { it is AccountViolation.Malformed && it.field == AccountField.USERNAME })
    }

    @Test
    fun `a malformed domain is rejected`() {
        for (bad in listOf("-bad.com", "ex ample.com", "1.2.3.256")) {
            val violations = reject(valid { copy(domain = bad) })
            assertTrue(
                violations.any { it is AccountViolation.Malformed && it.field == AccountField.DOMAIN },
                "expected '$bad' to be rejected, got $violations",
            )
        }
    }

    @Test
    fun `an IP address is a valid domain`() {
        assertEquals("192.168.1.10", accept(valid { copy(domain = "192.168.1.10") }).account.domain)
    }

    // ------------------------------------------------------------------ port

    @Test
    fun `port must be a number in range`() {
        assertTrue(reject(valid { copy(port = "abc") }).contains(AccountViolation.NotANumber(AccountField.PORT, "abc")))
        assertContains(
            reject(valid { copy(port = "0") }),
            AccountViolation.OutOfRange(AccountField.PORT, 0, SipAccount.MIN_PORT, SipAccount.MAX_PORT),
        )
        assertContains(
            reject(valid { copy(port = "65536") }),
            AccountViolation.OutOfRange(AccountField.PORT, 65_536, SipAccount.MIN_PORT, SipAccount.MAX_PORT),
        )
    }

    @Test
    fun `an omitted port falls back to the transport default`() {
        assertEquals(5060, accept(valid()).account.effectivePort)
        assertEquals(5061, accept(valid { copy(transport = Transport.TLS) }).account.effectivePort)
        assertEquals(5080, accept(valid { copy(port = "5080") }).account.effectivePort)
    }

    // ------------------------------------------------------------------ expiry and keepalive

    @Test
    fun `registration expiry must be within bounds`() {
        assertContains(
            reject(valid { copy(registrationExpirySeconds = "10") }),
            AccountViolation.OutOfRange(
                AccountField.REGISTRATION_EXPIRY,
                10,
                SipAccount.MIN_EXPIRY_SECONDS,
                SipAccount.MAX_EXPIRY_SECONDS,
            ),
        )
        assertTrue(reject(valid { copy(registrationExpirySeconds = "99999") }).isNotEmpty())
        assertContains(
            reject(valid { copy(registrationExpirySeconds = "") }),
            AccountViolation.Required(AccountField.REGISTRATION_EXPIRY),
        )
    }

    @Test
    fun `keepalive interval must be within bounds`() {
        assertTrue(reject(valid { copy(keepaliveIntervalSeconds = "1") }).isNotEmpty())
        assertTrue(reject(valid { copy(keepaliveIntervalSeconds = "6000") }).isNotEmpty())
        assertEquals(45, accept(valid { copy(keepaliveIntervalSeconds = "45") }).account.natPolicy.keepaliveIntervalSeconds)
    }

    // ------------------------------------------------------------------ hosts

    @Test
    fun `optional hosts are parsed when present`() {
        val account = accept(
            valid {
                copy(
                    registrar = "registrar.example.com:5070",
                    outboundProxy = "proxy.example.com",
                    stunServer = "stun:stun.example.com:3478",
                )
            },
        ).account
        assertEquals("registrar.example.com:5070", account.registrar?.render())
        assertEquals("proxy.example.com", account.outboundProxy?.render())
        assertEquals("stun.example.com:3478", account.stunServer?.render(), "a stun: prefix is accepted and dropped")
    }

    @Test
    fun `a malformed host is reported on its own field`() {
        val violations = reject(valid { copy(stunServer = "not a host") })
        assertTrue(violations.any { it is AccountViolation.Malformed && it.field == AccountField.STUN_SERVER })
    }

    @Test
    fun `registrar falls back to the domain when unset`() {
        assertEquals("sip.example.com:5060", accept(valid()).account.effectiveRegistrar)
        assertEquals(
            "registrar.example.com:5070",
            accept(valid { copy(registrar = "registrar.example.com:5070") }).account.effectiveRegistrar,
        )
    }

    // ------------------------------------------------------------------ TURN

    @Test
    fun `a TURN server requires credentials`() {
        val violations = reject(valid { copy(turnServer = "turn.example.com:3478") })
        assertContains(violations, AccountViolation.Required(AccountField.TURN_USERNAME))
        assertContains(violations, AccountViolation.Required(AccountField.TURN_PASSWORD))
    }

    @Test
    fun `TURN credentials without a server are a conflict`() {
        val violations = reject(valid { copy(turnUsername = "relay-user") })
        assertTrue(violations.any { it is AccountViolation.Conflict && it.field == AccountField.TURN_SERVER })
    }

    @Test
    fun `a complete TURN configuration is accepted`() {
        val account = accept(
            valid {
                copy(
                    turnServer = "turn.example.com:3478",
                    turnUsername = "relay-user",
                    turnPassword = Secret("relay-pass"),
                )
            },
        ).account
        assertEquals("turn.example.com:3478", account.turn?.server?.render())
        assertEquals("relay-user", account.turn?.username)
    }

    // ------------------------------------------------------------------ codecs

    @Test
    fun `at least one audio codec is required`() {
        assertContains(
            reject(valid { copy(audioCodecs = emptyList()) }),
            AccountViolation.Required(AccountField.AUDIO_CODECS),
        )
    }

    @Test
    fun `duplicate codecs are rejected`() {
        assertTrue(
            reject(valid { copy(audioCodecs = listOf(AudioCodec.OPUS, AudioCodec.OPUS)) })
                .any { it.field == AccountField.AUDIO_CODECS },
        )
        assertTrue(
            reject(valid { copy(videoCodecs = listOf(VideoCodec.VP8, VideoCodec.VP8)) })
                .any { it.field == AccountField.VIDEO_CODECS },
        )
    }

    @Test
    fun `codec order is preserved because it is the SDP offer order`() {
        val order = listOf(AudioCodec.PCMA, AudioCodec.OPUS, AudioCodec.G722)
        assertEquals(order, accept(valid { copy(audioCodecs = order) }).account.codecs.audio)
    }

    @Test
    fun `an empty video codec list means audio only`() {
        val account = accept(valid { copy(videoCodecs = emptyList()) }).account
        assertTrue(account.codecs.video.isEmpty())
        assertTrue(!account.codecs.supportsVideo)
    }

    // ------------------------------------------------------------------ warnings

    @Test
    fun `TLS on the plain SIP port warns but still validates`() {
        val result = accept(valid { copy(transport = Transport.TLS, port = "5060") })
        assertEquals(1, result.warnings.size)
        assertEquals(AccountField.PORT, result.warnings.single().field)
    }

    @Test
    fun `a non-TLS transport on the TLS port warns but still validates`() {
        val result = accept(valid { copy(transport = Transport.UDP, port = "5061") })
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `coherent transport and port produce no warnings`() {
        assertTrue(accept(valid { copy(transport = Transport.TLS, port = "5061") }).warnings.isEmpty())
        assertTrue(accept(valid { copy(transport = Transport.UDP, port = "5060") }).warnings.isEmpty())
        assertTrue(accept(valid()).warnings.isEmpty(), "no explicit port means nothing to disagree with")
    }

    @Test
    fun `validation is a pure function of its input`() {
        val draft = valid { copy(port = "5080") }
        assertEquals(AccountValidator.validate(draft), AccountValidator.validate(draft))
    }

    @Test
    fun `a rejected draft yields no account`() {
        assertTrue(AccountValidator.validate(SipAccountDraft(AccountId("x"))) is Outcome.Failure)
    }
}
