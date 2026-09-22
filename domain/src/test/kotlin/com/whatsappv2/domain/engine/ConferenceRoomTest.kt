package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge's address, and how a call log entry is recognised as a leg into it.
 *
 * [ConferenceRoom.matches] is what folds a bridged conference's rows into one history
 * entry and keeps the room itself off the roster (Task 60 follow-up), so the rules it
 * applies — user and host only, case-insensitive, never a bare host — are each pinned.
 */
class ConferenceRoomTest {

    private fun uri(text: String): SipUri = requireNotNull(SipUri.parse(text).getOrNull()) { text }

    @Test
    fun `the room resolves on a domain the way a dialled extension does`() {
        assertEquals(uri("sip:3000@sip.example.com"), ConferenceRoom.DEFAULT.uriOn("sip.example.com"))
        assertEquals(uri("sip:3000@pbx.local"), ConferenceRoom("sip:3000@pbx.local").uriOn("sip.example.com"))
        assertNull(ConferenceRoom.NONE.uriOn("sip.example.com"), "no bridge, no address")
    }

    @Test
    fun `a leg into the room matches on the account's domain`() {
        val room = ConferenceRoom.DEFAULT
        assertTrue(room.matches(uri("sip:3000@sip.example.com"), domain = "sip.example.com"))
        assertTrue(
            room.matches(uri("sip:3000@SIP.EXAMPLE.COM"), domain = "sip.example.com"),
            "hosts compare case-insensitively",
        )
        assertFalse(
            room.matches(uri("sip:3000@other.example.com"), domain = "sip.example.com"),
            "the same number elsewhere is somebody else",
        )
    }

    @Test
    fun `with no domain known the remote's own host is used`() {
        val room = ConferenceRoom.DEFAULT
        assertTrue(room.matches(uri("sip:3000@10.0.0.5")))
        assertTrue(room.matches(uri("sip:3000@10.0.0.5"), domain = ""), "blank is the same as unknown")
        assertFalse(room.matches(uri("sip:3001@10.0.0.5")), "a neighbouring extension is not the room")
    }

    @Test
    fun `the room's parameters do not take part in the comparison`() {
        // A bridge's Contact carries transport and `ob` parameters the configured room
        // never spells; comparing renderings would never match.
        val contact = uri("sip:3000@sip.example.com;transport=tcp;ob")
        assertTrue(ConferenceRoom.DEFAULT.matches(contact, "sip.example.com"))
    }

    @Test
    fun `a room that is a bare host matches nothing`() {
        // Otherwise two absent user parts would compare equal and every call to a bare
        // host would be a conference.
        val bareHost = ConferenceRoom("sip:bridge.example.com")
        assertFalse(bareHost.matches(uri("sip:bridge.example.com")))
        assertFalse(bareHost.matches(uri("sip:3000@bridge.example.com")))
    }

    @Test
    fun `no configured room matches nothing`() {
        assertFalse(ConferenceRoom.NONE.matches(uri("sip:3000@sip.example.com"), "sip.example.com"))
        assertFalse(ConferenceRoom.NONE.isConfigured)
        assertTrue(ConferenceRoom.DEFAULT.isConfigured)
    }
}
