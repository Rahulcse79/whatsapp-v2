package com.whatsappv2.core.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 23 done-when: trace output must redact auth headers.
 *
 * The digest response is computed from the user's password, and the nonce makes an
 * offline attack against it practical. Neither may reach a log file (§7, DoD 12).
 */
class SipTraceRedactorTest {

    private val register = """
        REGISTER sip:example.com SIP/2.0
        Via: SIP/2.0/TLS 10.0.0.2:5061;branch=z9hG4bK123
        From: <sip:alice@example.com>;tag=abc
        To: <sip:alice@example.com>
        Call-ID: 1234567890
        CSeq: 2 REGISTER
        Authorization: Digest username="alice", realm="example.com", $NONCE, $RESPONSE, algorithm=MD5
        Content-Length: 0
    """.trimIndent()

    @Test
    fun `the digest response never survives redaction`() {
        val redacted = SipTraceRedactor.redact(register)
        assertFalse(
            "6629fae49393a05397450978507c4ef1" in redacted,
            "the digest response reached the log: " + redacted,
        )
    }

    @Test
    fun `the nonce never survives redaction`() {
        // With the nonce and the response, the password can be attacked offline.
        assertFalse("dcd98b7102dd2f0e" in SipTraceRedactor.redact(register))
    }

    @Test
    fun `the username in an auth header is masked`() {
        assertFalse("""username="alice"""" in SipTraceRedactor.redact(register))
    }

    @Test
    fun `the realm and algorithm survive, because they diagnose without disclosing`() {
        // A trace with the headers stripped entirely cannot answer the question it
        // exists to answer.
        val redacted = SipTraceRedactor.redact(register)
        assertTrue("""realm="example.com"""" in redacted)
        assertTrue("algorithm=MD5" in redacted)
        assertTrue("Authorization:" in redacted, "the header name is kept")
    }

    @Test
    fun `every sensitive header is covered`() {
        val headers = listOf(
            """Authorization: Digest response="secret"""",
            """Proxy-Authorization: Digest response="secret"""",
            """WWW-Authenticate: Digest nonce="secret"""",
            """Proxy-Authenticate: Digest nonce="secret"""",
            """Authentication-Info: rspauth="secret"""",
        )
        for (header in headers) {
            assertFalse("secret" in SipTraceRedactor.redact(header), "not redacted: " + header)
        }
    }

    @Test
    fun `header matching is case-insensitive`() {
        assertFalse("""secret""" in SipTraceRedactor.redact("""AUTHORIZATION: Digest response="secret""""))
        assertFalse("""secret""" in SipTraceRedactor.redact("""authorization: Digest response="secret""""))
    }

    @Test
    fun `an unfamiliar auth scheme is masked entirely rather than guessed at`() {
        assertEquals("Authorization: ***", SipTraceRedactor.redact("Authorization: Bearer abc.def.ghi"))
    }

    @Test
    fun `non-sensitive headers pass through untouched`() {
        val redacted = SipTraceRedactor.redact(register)
        assertTrue("Call-ID: 1234567890" in redacted)
        assertTrue("CSeq: 2 REGISTER" in redacted)
        assertTrue("REGISTER sip:example.com SIP/2.0" in redacted)
    }

    @Test
    fun `the message keeps its shape so it still reads as SIP`() {
        assertEquals(
            register.lines().size,
            SipTraceRedactor.redact(register).lines().size,
        )
    }

    @Test
    fun `empty and malformed input do not throw`() {
        assertEquals("", SipTraceRedactor.redact(""))
        assertEquals("not a header", SipTraceRedactor.redact("not a header"))
    }

    private companion object {
        const val NONCE = """nonce="dcd98b7102dd2f0e""""
        const val RESPONSE = """response="6629fae49393a05397450978507c4ef1""""
    }
}
