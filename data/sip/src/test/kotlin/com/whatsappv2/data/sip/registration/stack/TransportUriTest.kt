package com.whatsappv2.data.sip.registration.stack

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which transport a SIP URI selects, for registration and for calls alike.
 *
 * The defect these lock down shipped and was invisible: an account registered over TLS
 * placed its INVITEs over **UDP**, because the transport was written onto the account's
 * identity and registrar URIs and never onto the request URI of a call. The call
 * connected, the account said TLS, the status screen said TLS — and the signalling went
 * out in clear text. See [withTransportOf].
 */
class TransportUriTest {

    // --------------------------------------------------------- the parameter itself

    @Test
    fun `TCP and TLS are named, because nothing else would select them`() {
        assertEquals(";transport=tcp", transportUriParameter("TCP"))
        assertEquals(";transport=tls", transportUriParameter("TLS"))
    }

    @Test
    fun `UDP is not named, so an oversized request can still escalate`() {
        // RFC 3261 §18.1.1: UDP is the default. Saying so pins the request to UDP even
        // when it no longer fits a datagram, which is the fragmentation this app has
        // already been bitten by.
        assertEquals("", transportUriParameter("UDP"))
    }

    @Test
    fun `an unrecognised transport is treated as the default rather than guessed at`() {
        assertEquals("", transportUriParameter("sctp"))
        assertEquals("", transportUriParameter(""))
    }

    @Test
    fun `the token is matched whatever case it is stored in`() {
        assertEquals(";transport=tls", transportUriParameter("tls"))
        assertEquals(";transport=tcp", transportUriParameter("Tcp"))
    }

    // --------------------------------------------------------- the dial target

    @Test
    fun `a call from a TLS account names TLS on the request URI`() {
        assertEquals(
            "sip:55006@connect.sks.net.in;transport=tls",
            "sip:55006@connect.sks.net.in".withTransportOf("TLS"),
        )
    }

    @Test
    fun `a call from a TCP account names TCP on the request URI`() {
        assertEquals(
            "sip:55006@connect.sks.net.in;transport=tcp",
            "sip:55006@connect.sks.net.in".withTransportOf("TCP"),
        )
    }

    @Test
    fun `a call from a UDP account is left exactly as dialled`() {
        assertEquals(
            "sip:55006@connect.sks.net.in",
            "sip:55006@connect.sks.net.in".withTransportOf("UDP"),
        )
    }

    @Test
    fun `a destination that already names a transport is never given a second one`() {
        // Two `;transport=` parameters is a URI no registrar will parse, and the one the
        // user typed is the one they meant.
        val typed = "sip:55006@connect.sks.net.in;transport=udp"

        assertEquals(typed, typed.withTransportOf("TLS"))
    }

    @Test
    fun `an existing transport parameter is recognised whatever case it was typed in`() {
        val typed = "sip:55006@connect.sks.net.in;TRANSPORT=TCP"

        assertEquals(typed, typed.withTransportOf("TLS"))
    }

    @Test
    fun `an account the stack does not know leaves the target untouched`() {
        // No transport to speak for. Inventing one here would be a guess on the single
        // path where guessing wrong is a silent downgrade to clear text.
        assertEquals(
            "sip:55006@connect.sks.net.in",
            "sip:55006@connect.sks.net.in".withTransportOf(null),
        )
    }
}
