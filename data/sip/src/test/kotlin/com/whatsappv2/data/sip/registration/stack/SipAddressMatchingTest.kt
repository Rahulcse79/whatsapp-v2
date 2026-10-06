package com.whatsappv2.data.sip.registration.stack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Matching the sender of a conference document to the leg it belongs to.
 *
 * The case that matters is the one the deployment actually produces: FreeSWITCH terminates
 * the in-dialog MESSAGE carrying a roster or its acknowledgement and re-originates it
 * towards the sender's registered *contact*, so the `From` names the far handset while the
 * leg records the address this device dialled — the server. Captured on 1001/1002/1003,
 * 2026-10-05:
 *
 *     From: <sip:1002@10.31.0.14;ob>;tag=7a0e73bb…     (B's own address)
 *     leg remote: sip:1002@10.31.0.214:5060            (the server)
 *
 * Every acknowledgement failed to match, so no peer ever passed `meshes()`, the focus
 * relayed audio for all of them, and the spokes had no leg to carry video between them.
 */
class SipAddressMatchingTest {

    @Test
    fun `the same address written two ways is the same address`() {
        assertTrue(sameSipAddress("sip:1001@10.31.0.214", "<sip:1001@10.31.0.214;transport=udp>"))
    }

    @Test
    fun `a different user on the same host is a different person`() {
        assertFalse(sameSipAddress("sip:1001@10.31.0.214", "sip:1002@10.31.0.214"))
    }

    @Test
    fun `the B2BUA rewrite defeats an address match`() {
        // Not a bug in sameSipAddress - it is answering the question it was asked. This is
        // the reason a second, weaker question has to exist at all.
        assertFalse(
            sameSipAddress("sip:1002@10.31.0.214:5060", "<sip:1002@10.31.0.14;ob>"),
            "the hosts genuinely differ, so the strict match must not claim they are equal",
        )
    }

    @Test
    fun `the user part survives the rewrite`() {
        assertTrue(sameSipUser("sip:1002@10.31.0.214:5060", "<sip:1002@10.31.0.14;ob>;tag=7a0e73bb"))
    }

    @Test
    fun `a user match still distinguishes two different extensions`() {
        assertFalse(sameSipUser("sip:1002@10.31.0.214", "sip:1003@10.31.0.14"))
    }

    @Test
    fun `user extraction tolerates the shapes these headers arrive in`() {
        assertEquals("1002", sipUserOf("<sip:1002@10.31.0.14;ob>"))
        assertEquals("1002", sipUserOf("sip:1002@10.31.0.214:5060"))
        assertEquals("1002", sipUserOf("SIP:1002@HOST"))
        assertNull(sipUserOf(null))
        assertNull(sipUserOf("   "))
    }

    @Test
    fun `neither comparison matches when one side is missing`() {
        assertFalse(sameSipUser(null, "sip:1002@10.31.0.14"))
        assertFalse(sameSipUser("sip:1002@10.31.0.214", null))
        assertFalse(sameSipAddress(null, null))
    }
}
