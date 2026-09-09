package com.whatsappv2.data.sip.registration

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The forms a peer actually sends, and the one that broke every inbound call.
 *
 * pjsua2 returns `CallInfo.getRemoteUri()` as it arrived on the wire, so the gateway sees
 * a name-addr rather than a bare URI. `SipUri.parse` reads up to the first `:` and asks
 * whether that is a scheme — on `"Alice" <sip:…>` that is `"Alice" <sip`, which it
 * rightly refuses. The result was an inbound INVITE declined before it ever rang.
 *
 * The last test is the one that matters: it asserts the output actually parses, rather
 * than merely that the brackets came off.
 */
class NameAddrTest {

    @Test
    fun `a quoted display name is split from the uri`() {
        val addr = NameAddr.of("\"Alice\" <sip:1001@10.174.125.214>")

        assertEquals("sip:1001@10.174.125.214", addr.uri)
        assertEquals("Alice", addr.displayName)
    }

    @Test
    fun `an unquoted display name is split from the uri`() {
        val addr = NameAddr.of("Bob <sip:2001@example.test>")

        assertEquals("sip:2001@example.test", addr.uri)
        assertEquals("Bob", addr.displayName)
    }

    @Test
    fun `angle brackets with no display name yield no name`() {
        val addr = NameAddr.of("<sip:1001@10.174.125.214>")

        assertEquals("sip:1001@10.174.125.214", addr.uri)
        assertNull(addr.displayName)
    }

    @Test
    fun `an empty quoted name is no name, not an empty one`() {
        // `"" <sip:...>` is legal and means the peer sent no name.
        assertNull(NameAddr.of("\"\" <sip:1001@host.test>").displayName)
    }

    @Test
    fun `a bare uri is returned unchanged`() {
        // What pjsua2 produces for a peer sending neither brackets nor a name. This form
        // already worked, and must keep working.
        val addr = NameAddr.of("sip:1001@10.174.125.214")

        assertEquals("sip:1001@10.174.125.214", addr.uri)
        assertNull(addr.displayName)
    }

    @Test
    fun `uri parameters inside the brackets are kept`() {
        // The transport belongs to the URI, not to the name-addr wrapper, and dropping it
        // would send the reply over the wrong one.
        val addr = NameAddr.of("Bob <sip:2001@host.test;transport=tcp>")

        assertEquals("sip:2001@host.test;transport=tcp", addr.uri)
        assertEquals("Bob", addr.displayName)
    }

    @Test
    fun `null and blank are handled without throwing`() {
        assertEquals("", NameAddr.of(null).uri)
        assertEquals("", NameAddr.of("   ").uri)
        assertNull(NameAddr.of(null).displayName)
    }

    @Test
    fun `an unterminated bracket is left alone for the parser to reject`() {
        // Not second-guessed here: SipUri.parse gives a reason, and inventing one would
        // hide a genuinely malformed header behind a plausible-looking address.
        assertEquals("<sip:1001@host.test", NameAddr.of("<sip:1001@host.test").uri)
    }

    @Test
    fun `every form a peer sends produces something SipUri can parse`() {
        // The regression this whole class exists for. Splitting the brackets off is only
        // useful if what comes out is addressable, so that is what is asserted.
        val wireForms = listOf(
            "\"Alice\" <sip:1001@10.174.125.214>",
            "Bob <sip:2001@example.test>",
            "<sip:1001@10.174.125.214>",
            "sip:1001@10.174.125.214",
            "Bob <sip:2001@host.test;transport=tcp>",
            "<sip:1001@10.174.125.214:5060>",
        )

        wireForms.forEach { raw ->
            assertNotNull(
                SipUri.parse(NameAddr.of(raw).uri).getOrNull(),
                "could not parse the uri out of: $raw",
            )
        }
    }
}
