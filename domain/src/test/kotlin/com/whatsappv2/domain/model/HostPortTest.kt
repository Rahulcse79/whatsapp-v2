package com.whatsappv2.domain.model

import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class HostPortTest {

    private fun parsed(input: String): HostPort =
        HostPort.parse(input).getOrNull() ?: fail("expected '$input' to parse")

    private fun error(input: String): HostPortError =
        HostPort.parse(input).errorOrNull() ?: fail("expected '$input' to be rejected")

    @Test
    fun `parses the accepted shapes`() {
        data class Case(val input: String, val host: SipHost, val port: Int?)

        val cases = listOf(
            Case("example.com", SipHost.Hostname("example.com"), null),
            Case("example.com:3478", SipHost.Hostname("example.com"), 3478),
            Case("192.168.1.10", SipHost.IpV4("192.168.1.10"), null),
            Case("192.168.1.10:5060", SipHost.IpV4("192.168.1.10"), 5060),
            Case("[2001:db8::1]", SipHost.IpV6("2001:db8::1"), null),
            Case("[2001:db8::1]:3478", SipHost.IpV6("2001:db8::1"), 3478),
        )

        for (case in cases) {
            val parsed = parsed(case.input)
            assertEquals(case.host, parsed.host, "host of '${case.input}'")
            assertEquals(case.port, parsed.port, "port of '${case.input}'")
        }
    }

    @Test
    fun `accepts and drops a scheme prefix because that is how users paste these`() {
        assertEquals(SipHost.Hostname("stun.example.com"), parsed("stun:stun.example.com").host)
        assertEquals(3478, parsed("turn:turn.example.com:3478").port)
        assertEquals(SipHost.Hostname("turn.example.com"), parsed("turns:turn.example.com").host)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(SipHost.Hostname("example.com"), parsed("  example.com  ").host)
    }

    @Test
    fun `rejects malformed input with a specific reason`() {
        data class Case(val input: String, val expected: HostPortError)

        val cases = listOf(
            Case("", HostPortError.Empty),
            Case("   ", HostPortError.Empty),
            Case("stun:", HostPortError.Empty),
            Case("not a host", HostPortError.InvalidHost("not a host")),
            Case("-bad.com", HostPortError.InvalidHost("-bad.com")),
            Case("1.2.3.256", HostPortError.InvalidHost("1.2.3.256")),
            Case("example.com:abc", HostPortError.InvalidPort("abc")),
            Case("example.com:0", HostPortError.InvalidPort("0")),
            Case("example.com:65536", HostPortError.InvalidPort("65536")),
            Case("[2001:db8::1", HostPortError.InvalidHost("[2001:db8::1")),
            Case("[nonsense]", HostPortError.InvalidHost("nonsense")),
            Case("[2001:db8::1]:abc", HostPortError.InvalidPort("abc")),
        )

        for (case in cases) {
            assertEquals(case.expected, error(case.input), "for input '${case.input}'")
        }
    }

    @Test
    fun `render round-trips and brackets IPv6`() {
        for (input in listOf("example.com", "example.com:3478", "192.168.1.10:5060", "[2001:db8::1]:3478")) {
            assertEquals(input, parsed(input).render())
        }
    }

    @Test
    fun `toString is the rendered form`() {
        assertEquals("example.com:3478", parsed("example.com:3478").toString())
    }

    @Test
    fun `equality is by value`() {
        assertEquals(parsed("example.com:3478"), parsed("example.com:3478"))
        assertEquals(parsed("example.com:3478").hashCode(), parsed("example.com:3478").hashCode())
        assertTrue(parsed("example.com:3478") != parsed("example.com:3479"))
        assertTrue(parsed("example.com") != parsed("other.com"))

        val nothing: Any? = null
        assertTrue(parsed("example.com") != nothing)
    }

    @Test
    fun `a bare host has no port so callers must supply a default`() {
        assertNull(parsed("example.com").port)
    }
}
