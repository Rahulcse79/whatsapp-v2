package com.whatsappv2.data.sip.registration

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SIP trace must be readable and must not carry a credential (§7, DoD 12).
 *
 * Both halves are asserted, because either one alone is easy and useless: a trace with
 * everything removed is safe and tells you nothing, and a trace with everything kept tells
 * you plenty including the digest response computed from the account password.
 */
class SipTraceRedactionTest {

    private val register = """
        REGISTER sip:10.174.125.214 SIP/2.0
        Via: SIP/2.0/UDP 10.174.125.131:5060;branch=z9hG4bK1234
        From: <sip:1001@10.174.125.214>;tag=abcd
        To: <sip:1001@10.174.125.214>
        Call-ID: 9f8e7d6c@10.174.125.131
        CSeq: 2 REGISTER
        Authorization: Digest username="1001", realm="10.174.125.214", nonce="deadbeef", uri="sip:10.174.125.214", response="5f4dcc3b5aa765d61d8327deb882cf99"
        Contact: <sip:1001@10.174.125.131:5060>
        Content-Length: 0
    """.trimIndent()

    @Test
    fun `the digest response never survives`() {
        val out = SipTraceRedaction.redact(register)

        assertFalse(
            "5f4dcc3b5aa765d61d8327deb882cf99" in out,
            "the digest response reached the log",
        )
    }

    @Test
    fun `the whole Authorization value goes, not just the response`() {
        val out = SipTraceRedaction.redact(register)

        // The username and realm are in there too. Half a redaction is a leak with extra
        // steps, so the entire header value is replaced.
        assertFalse("""username="1001"""" in out, "the auth username reached the log")
        assertFalse("nonce=\"deadbeef\"" in out, "the auth nonce reached the log")
        assertContains(out, "Authorization: <redacted>")
    }

    @Test
    fun `everything needed to read the trace is kept`() {
        val out = SipTraceRedaction.redact(register)

        listOf(
            "REGISTER sip:10.174.125.214 SIP/2.0",
            "Via: SIP/2.0/UDP 10.174.125.131:5060",
            "From: <sip:1001@10.174.125.214>",
            "Call-ID: 9f8e7d6c@10.174.125.131",
            "CSeq: 2 REGISTER",
            "Contact: <sip:1001@10.174.125.131:5060>",
        ).forEach { assertContains(out, it) }
    }

    @Test
    fun `Proxy-Authorization is redacted too`() {
        val out = SipTraceRedaction.redact(
            "Proxy-Authorization: Digest username=\"1001\", response=\"cafebabe\"",
        )

        assertFalse("cafebabe" in out)
        assertContains(out, "Proxy-Authorization: <redacted>")
    }

    @Test
    fun `the server challenge is deliberately kept`() {
        // WWW-Authenticate is what the server published in the clear. It is the single
        // most useful line when registration is failing, and it is not a secret.
        val challenge =
            "WWW-Authenticate: Digest realm=\"10.174.125.214\", nonce=\"1a2b3c\", algorithm=MD5"

        assertEquals(challenge, SipTraceRedaction.redact(challenge))
    }

    @Test
    fun `a stray response parameter is caught wherever it appears`() {
        // Not every stack puts the credential on a line this recognises, so the parameter
        // itself is removed independently of the header it arrived on.
        val out = SipTraceRedaction.redact("X-Odd-Header: something, response=\"secrethash\"")

        assertFalse("secrethash" in out)
        assertContains(out, "response=<redacted>")
    }

    @Test
    fun `cnonce goes as well`() {
        assertFalse("clientnonce" in SipTraceRedaction.redact("cnonce=\"clientnonce\""))
    }

    @Test
    fun `a header name inside a body cannot swallow the rest of the message`() {
        // The patterns are anchored to a line start. Without that, an SDP line or a quoted
        // display name mentioning the word would redact everything after it.
        val trace = "To: \"Authorization: test\" <sip:2@h>\r\nCSeq: 1 INVITE"
        val out = SipTraceRedaction.redact(trace)

        assertContains(out, "CSeq: 1 INVITE")
    }

    @Test
    fun `an empty trace is returned unchanged`() {
        assertEquals("", SipTraceRedaction.redact(""))
    }

    @Test
    fun `an INVITE trace survives intact`() {
        // The case this whole facility exists for: reading why a call ended.
        val invite = """
            SIP/2.0 488 Not Acceptable Here
            Via: SIP/2.0/UDP 192.168.80.145:5060
            CSeq: 1 INVITE
            Warning: 304 incompatible media format
        """.trimIndent()

        assertEquals(invite, SipTraceRedaction.redact(invite))
        assertTrue("488" in SipTraceRedaction.redact(invite))
    }
}
