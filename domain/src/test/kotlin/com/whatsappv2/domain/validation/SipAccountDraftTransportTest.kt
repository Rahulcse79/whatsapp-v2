package com.whatsappv2.domain.validation

import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.Transport
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Switching transport must carry the port, not strand it (`withTransport`).
 *
 * The defect these cover shipped: an account set up on UDP with an explicit `5060` kept
 * that port when it was switched to TLS, so the client opened a TLS connection to the
 * registrar's plaintext SIP port. The TCP connect succeeds and the server then never
 * answers the ClientHello, so it presents as "registrar unreachable" rather than as a
 * configuration mistake.
 */
class SipAccountDraftTransportTest {

    private fun draft(transport: Transport, port: String) = SipAccountDraft(
        id = AccountId(UUID.randomUUID().toString()),
        username = "55006",
        domain = "connect.sks.net.in",
        transport = transport,
        port = port,
    )

    @Test
    fun `UDP default port follows the switch to TLS`() {
        val moved = draft(Transport.UDP, "5060").withTransport(Transport.TLS)

        assertEquals(Transport.TLS, moved.transport)
        assertEquals("5061", moved.port)
    }

    @Test
    fun `TCP default port follows the switch to TLS`() {
        val moved = draft(Transport.TCP, "5060").withTransport(Transport.TLS)

        assertEquals("5061", moved.port)
    }

    @Test
    fun `TLS default port follows the switch back to UDP`() {
        val moved = draft(Transport.TLS, "5061").withTransport(Transport.UDP)

        assertEquals(Transport.UDP, moved.transport)
        assertEquals("5060", moved.port)
    }

    @Test
    fun `UDP to TCP keeps 5060, which is the default for both`() {
        val moved = draft(Transport.UDP, "5060").withTransport(Transport.TCP)

        assertEquals(Transport.TCP, moved.transport)
        assertEquals("5060", moved.port)
    }

    @Test
    fun `a deliberately chosen port is never rewritten`() {
        val moved = draft(Transport.UDP, "5080").withTransport(Transport.TLS)

        assertEquals(Transport.TLS, moved.transport)
        assertEquals("5080", moved.port)
    }

    @Test
    fun `a blank port stays blank and keeps resolving from the transport`() {
        val moved = draft(Transport.UDP, "").withTransport(Transport.TLS)

        assertEquals("", moved.port)
    }

    @Test
    fun `surrounding whitespace does not hide the default`() {
        val moved = draft(Transport.UDP, " 5060 ").withTransport(Transport.TLS)

        assertEquals("5061", moved.port)
    }

    @Test
    fun `selecting the transport already in force changes nothing at all`() {
        val original = draft(Transport.TLS, "5061")

        assertSame(original, original.withTransport(Transport.TLS))
    }
}
