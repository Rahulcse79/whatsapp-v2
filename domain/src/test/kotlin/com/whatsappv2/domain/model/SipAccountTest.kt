package com.whatsappv2.domain.model

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SipAccountTest {

    private fun account(
        username: String = "alice",
        domain: String = "sip.example.com",
        port: Int? = null,
        transport: Transport = Transport.UDP,
        authUsername: String? = null,
        srtpPolicy: SrtpPolicy = SrtpPolicy.OPTIONAL,
        registrar: HostPort? = null,
        expiry: Int = SipAccount.DEFAULT_EXPIRY_SECONDS,
    ) = SipAccount(
        id = AccountId("acct-1"),
        label = "Work",
        username = username,
        extension = null,
        authUsername = authUsername,
        password = Secret("hunter22"),
        displayName = "Alice Example",
        domain = domain,
        registrar = registrar,
        outboundProxy = null,
        port = port,
        transport = transport,
        registrationExpirySeconds = expiry,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = srtpPolicy,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    // ------------------------------------------------------------------ redaction

    @Test
    fun `toString cannot leak the password`() {
        val text = account().toString()
        assertFalse("hunter22" in text, "toString leaked the password: $text")
    }

    @Test
    fun `string interpolation cannot leak the password`() {
        val text = "account=${account()}"
        assertFalse("hunter22" in text, "interpolation leaked the password: $text")
    }

    @Test
    fun `toString redacts the username but keeps diagnostic fields`() {
        val text = account().toString()
        assertFalse("alice" in text, "toString leaked the user part: $text")
        assertTrue("sip.example.com" in text, "the domain is diagnostic and should survive: $text")
        assertTrue("acct-1" in text)
    }

    @Test
    fun `the password itself masks on its own`() {
        assertFalse("hunter22" in account().password.toString())
    }

    // ------------------------------------------------------------------ derived values

    @Test
    fun `effective auth username falls back to the username`() {
        assertEquals("alice", account().effectiveAuthUsername)
        assertEquals("alice", account(authUsername = "   ").effectiveAuthUsername)
        assertEquals("alice-auth", account(authUsername = "alice-auth").effectiveAuthUsername)
    }

    @Test
    fun `effective port follows the transport when unset`() {
        assertEquals(5060, account(transport = Transport.UDP).effectivePort)
        assertEquals(5060, account(transport = Transport.TCP).effectivePort)
        assertEquals(5061, account(transport = Transport.TLS).effectivePort)
        assertEquals(5080, account(port = 5080, transport = Transport.TLS).effectivePort)
    }

    @Test
    fun `effective registrar falls back to the domain and effective port`() {
        assertEquals("sip.example.com:5060", account().effectiveRegistrar)
        assertEquals("sip.example.com:5061", account(transport = Transport.TLS).effectiveRegistrar)
    }

    @Test
    fun `an explicit registrar overrides the domain`() {
        val registrar = checkNotNull(HostPort.parse("registrar.example.com:5070").getOrNull())
        assertEquals("registrar.example.com:5070", account(registrar = registrar).effectiveRegistrar)
    }

    @Test
    fun `address of record uses sips only under TLS`() {
        assertEquals("sip:alice@sip.example.com", account().addressOfRecord)
        assertEquals("sips:alice@sip.example.com", account(transport = Transport.TLS).addressOfRecord)
    }

    @Test
    fun `fully secure requires both TLS signalling and mandatory SRTP`() {
        assertTrue(account(transport = Transport.TLS, srtpPolicy = SrtpPolicy.MANDATORY).isFullySecure)
        assertFalse(account(transport = Transport.TLS, srtpPolicy = SrtpPolicy.OPTIONAL).isFullySecure)
        assertFalse(account(transport = Transport.UDP, srtpPolicy = SrtpPolicy.MANDATORY).isFullySecure)
    }

    // ------------------------------------------------------------------ invariants

    @Test
    fun `structural invariants are enforced by the constructor`() {
        assertFailsWith<IllegalArgumentException> { account(username = "  ") }
        assertFailsWith<IllegalArgumentException> { account(domain = "") }
        assertFailsWith<IllegalArgumentException> { account(port = 0) }
        assertFailsWith<IllegalArgumentException> { account(port = 70_000) }
        assertFailsWith<IllegalArgumentException> { account(expiry = 1) }
        assertFailsWith<IllegalArgumentException> { account(expiry = 999_999) }
    }
}
