package com.whatsappv2.domain.model

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SipUriTest {

    private fun parsed(input: String): SipUri =
        SipUri.parse(input).getOrNull() ?: fail("expected '$input' to parse")

    private fun error(input: String): SipUriError =
        SipUri.parse(input).errorOrNull() ?: fail("expected '$input' to be rejected")

    // ---------------------------------------------------------------- valid inputs

    @Test
    fun `parses the accepted shapes`() {
        data class Case(
            val input: String,
            val scheme: SipScheme,
            val user: String?,
            val host: SipHost,
            val port: Int?,
        )

        val cases = listOf(
            Case("sip:alice@example.com", SipScheme.SIP, "alice", SipHost.Hostname("example.com"), null),
            Case("sips:bob@example.com", SipScheme.SIPS, "bob", SipHost.Hostname("example.com"), null),
            Case("sip:example.com", SipScheme.SIP, null, SipHost.Hostname("example.com"), null),
            Case("sip:1001@pbx.local:5060", SipScheme.SIP, "1001", SipHost.Hostname("pbx.local"), 5060),
            Case("sip:alice@192.168.1.10", SipScheme.SIP, "alice", SipHost.IpV4("192.168.1.10"), null),
            Case("sip:alice@192.168.1.10:5061", SipScheme.SIP, "alice", SipHost.IpV4("192.168.1.10"), 5061),
            Case("sip:a@[2001:db8::1]", SipScheme.SIP, "a", SipHost.IpV6("2001:db8::1"), null),
            Case("sip:a@[2001:db8::1]:5060", SipScheme.SIP, "a", SipHost.IpV6("2001:db8::1"), 5060),
            Case("SIP:Alice@Example.COM", SipScheme.SIP, "Alice", SipHost.Hostname("Example.COM"), null),
            Case("sip:*21@example.com", SipScheme.SIP, "*21", SipHost.Hostname("example.com"), null),
            Case("sip:alice@example.com.", SipScheme.SIP, "alice", SipHost.Hostname("example.com."), null),
        )

        for (case in cases) {
            val uri = parsed(case.input)
            assertEquals(case.scheme, uri.scheme, "scheme of '${case.input}'")
            assertEquals(case.user, uri.user, "user of '${case.input}'")
            assertEquals(case.host, uri.host, "host of '${case.input}'")
            assertEquals(case.port, uri.port, "port of '${case.input}'")
        }
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(SipHost.Hostname("example.com"), parsed("  sip:alice@example.com  ").host)
    }

    // ---------------------------------------------------------------- parameters

    @Test
    fun `parses parameters and lower-cases their names`() {
        val uri = parsed("sip:alice@example.com;transport=TLS;Lr;user=phone")
        assertEquals(mapOf("transport" to "TLS", "lr" to "", "user" to "phone"), uri.parameters)
    }

    @Test
    fun `exposes the transport parameter case-insensitively`() {
        assertEquals(Transport.TLS, parsed("sip:a@b.com;transport=TLS").transport)
        assertEquals(Transport.UDP, parsed("sip:a@b.com;transport=udp").transport)
        assertNull(parsed("sip:a@b.com;transport=sctp").transport, "unknown transport is not guessed")
        assertNull(parsed("sip:a@b.com").transport)
    }

    @Test
    fun `parameters do not confuse host parsing`() {
        val uri = parsed("sip:alice@example.com:5061;transport=tls")
        assertEquals(SipHost.Hostname("example.com"), uri.host)
        assertEquals(5061, uri.port)
    }

    // ---------------------------------------------------------------- effective port

    @Test
    fun `effective port prefers explicit, then transport, then scheme`() {
        assertEquals(5080, parsed("sip:a@b.com:5080;transport=tls").effectivePort)
        assertEquals(5061, parsed("sip:a@b.com;transport=tls").effectivePort)
        assertEquals(5060, parsed("sip:a@b.com").effectivePort)
        assertEquals(5061, parsed("sips:a@b.com").effectivePort)
    }

    // ---------------------------------------------------------------- invalid inputs

    @Test
    fun `rejects malformed input with a specific reason`() {
        data class Case(val input: String, val expected: SipUriError)

        val cases = listOf(
            Case("", SipUriError.Empty),
            Case("   ", SipUriError.Empty),
            Case("alice@example.com", SipUriError.MissingScheme),
            Case(":example.com", SipUriError.MissingScheme),
            Case("http://example.com", SipUriError.UnsupportedScheme("http")),
            Case("tel:+15551234", SipUriError.UnsupportedScheme("tel")),
            Case("sip:", SipUriError.MissingHost),
            Case("sip:alice@", SipUriError.MissingHost),
            Case("sip:alice@example.com:0", SipUriError.InvalidPort("0")),
            Case("sip:alice@example.com:65536", SipUriError.InvalidPort("65536")),
            Case("sip:alice@example.com:abc", SipUriError.InvalidHost("example.com")),
            Case("sip:alice@-bad.com", SipUriError.InvalidHost("-bad.com")),
            Case("sip:alice@bad-.com", SipUriError.InvalidHost("bad-.com")),
            Case("sip:alice@ex ample.com", SipUriError.InvalidHost("ex ample.com")),
            Case("sip:alice@example..com", SipUriError.InvalidHost("example..com")),
            Case("sip:a@[2001:db8::1", SipUriError.InvalidHost("[2001:db8::1")),
            Case("sip:a@[not:ipv6:!]", SipUriError.InvalidHost("not:ipv6:!")),
            Case("sip:a@[2001:db8::1::2]", SipUriError.InvalidHost("2001:db8::1::2")),
            Case("sip:ali ce@example.com", SipUriError.InvalidUser("ali ce")),
            Case("sip:alice@example.com;", SipUriError.InvalidParameter("")),
            Case("sip:alice@example.com;=value", SipUriError.InvalidParameter("=value")),
        )

        for (case in cases) {
            assertEquals(case.expected, error(case.input), "for input '${case.input}'")
        }
    }

    @Test
    fun `rejects IPv4 octets that are out of range or zero-padded`() {
        // 256 is not a valid octet, so this falls through to hostname rules and fails
        // there too - either way it must not parse as an address.
        assertTrue(SipUri.parse("sip:a@1.2.3.256") is Outcome.Failure)
        assertTrue(SipUri.parse("sip:a@01.2.3.4") is Outcome.Failure)
        assertTrue(SipUri.parse("sip:a@1.2.3") is Outcome.Failure)
    }

    // ---------------------------------------------------------------- rendering

    @Test
    fun `render round-trips every accepted input`() {
        val inputs = listOf(
            "sip:alice@example.com",
            "sips:bob@example.com:5061",
            "sip:example.com",
            "sip:alice@192.168.1.10:5060",
            "sip:a@[2001:db8::1]:5060",
            "sip:alice@example.com;transport=TLS",
        )
        for (input in inputs) {
            val once = parsed(input).render()
            assertEquals(once, parsed(once).render(), "re-parsing '$once' must be stable")
        }
    }

    @Test
    fun `render brackets an IPv6 host`() {
        assertEquals("sip:a@[2001:db8::1]:5060", parsed("sip:a@[2001:db8::1]:5060").render())
    }

    @Test
    fun `render emits a valueless parameter without an equals sign`() {
        assertEquals("sip:alice@example.com;lr", parsed("sip:alice@example.com;lr").render())
    }

    // ---------------------------------------------------------------- safety

    @Test
    fun `toString redacts the user part`() {
        val text = parsed("sip:alice@example.com").toString()
        assertTrue("alice" !in text, "toString must not leak the user part: $text")
        assertTrue("example.com" in text, "the host is diagnostic and should survive: $text")
    }

    @Test
    fun `equality is by value`() {
        assertEquals(parsed("sip:alice@example.com"), parsed("sip:alice@example.com"))
        assertEquals(parsed("sip:alice@example.com").hashCode(), parsed("sip:alice@example.com").hashCode())
        assertTrue(parsed("sip:alice@example.com") != parsed("sip:bob@example.com"))
    }
}
