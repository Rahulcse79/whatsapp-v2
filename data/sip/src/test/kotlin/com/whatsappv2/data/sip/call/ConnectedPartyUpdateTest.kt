package com.whatsappv2.data.sip.call

import com.whatsappv2.data.sip.registration.stack.withUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading the connected party out of FreeSWITCH's display-update INFO.
 *
 * The message below is the one captured on the deployment (4022 transferring a call to
 * 4021, 2026-10-08), trimmed to the headers that matter and kept with its CRLF endings
 * because that is what `SipRxData.wholeMsg` hands over — a parser that only works on `\n`
 * works in a test and on no handset.
 */
class ConnectedPartyUpdateTest {

    @Test
    fun `the display update names the new party`() {
        val update = ConnectedPartyUpdate.parse(UPDATE_DISPLAY_INFO)

        assertEquals(ConnectedPartyUpdate(displayName = "4021", number = "4021"), update)
    }

    @Test
    fun `a name and a number that differ are both kept`() {
        // The two headers are not required to agree, and the one a person reads is the
        // name. Collapsing them to one field would make an extension the only thing this
        // can ever show.
        val update = ConnectedPartyUpdate.parse(
            info(
                "Content-Type: ${ConnectedPartyUpdate.CONTENT_TYPE}",
                "X-FS-Display-Name: \"Sales desk\"",
                "X-FS-Display-Number: 4021",
            ),
        )

        assertEquals("Sales desk", update?.displayName)
        assertEquals("4021", update?.number)
    }

    @Test
    fun `a number with no name is still an update`() {
        val update = ConnectedPartyUpdate.parse(
            info("Content-Type: ${ConnectedPartyUpdate.CONTENT_TYPE}", "X-FS-Display-Number: 4021"),
        )

        assertEquals(ConnectedPartyUpdate(displayName = null, number = "4021"), update)
    }

    @Test
    fun `the compact Content-Type form is read too`() {
        val update = ConnectedPartyUpdate.parse(
            info("c: ${ConnectedPartyUpdate.CONTENT_TYPE}", "X-FS-Display-Number: 4021"),
        )

        assertEquals("4021", update?.number)
    }

    @Test
    fun `the field name is matched without regard to case`() {
        val update = ConnectedPartyUpdate.parse(
            info("CONTENT-TYPE: message/update_display", "x-fs-display-number: 4021"),
        )

        assertEquals("4021", update?.number)
    }

    @Test
    fun `an INFO of another type is not an update`() {
        // This parser is fed every message on every dialog. Claiming one it does not
        // understand would rename a call on the strength of a keyframe request.
        assertNull(
            ConnectedPartyUpdate.parse(
                info("Content-Type: application/dtmf-relay", "X-FS-Display-Number: 4021"),
            ),
        )
    }

    @Test
    fun `the right content type with neither header is not an update`() {
        assertNull(ConnectedPartyUpdate.parse(info("Content-Type: ${ConnectedPartyUpdate.CONTENT_TYPE}")))
    }

    @Test
    fun `a body that mentions the headers is not read as one`() {
        // The headers end at the first blank line, and this message's carry neither name.
        // A parser that scanned the whole string would take the body's and rename the call
        // after a line that is somebody's payload.
        val message = info("Content-Type: ${ConnectedPartyUpdate.CONTENT_TYPE}", "Content-Length: 26") +
            "\r\n" +
            "X-FS-Display-Number: 9999\r\n"

        assertNull(ConnectedPartyUpdate.parse(message))
    }

    @Test
    fun `an empty header value is as good as absent`() {
        assertNull(
            ConnectedPartyUpdate.parse(
                info(
                    "Content-Type: ${ConnectedPartyUpdate.CONTENT_TYPE}",
                    "X-FS-Display-Name:",
                    "X-FS-Display-Number:   ",
                ),
            ),
        )
    }

    // ------------------------------------------------- the address it is turned into

    @Test
    fun `the new party keeps the server the call is already on`() {
        // What the update cannot say for itself: FreeSWITCH sends `4021`, and an extension
        // on its own cannot be dialled back, logged, or matched to a contact.
        assertEquals("sip:4021@192.168.20.56", withUser("sip:4022@192.168.20.56", "4021"))
    }

    @Test
    fun `the port and transport survive the substitution`() {
        assertEquals(
            "sips:4021@192.168.20.56:5061;transport=tls",
            withUser("sips:4022@192.168.20.56:5061;transport=tls", "4021"),
        )
    }

    @Test
    fun `an address with no user part gains one`() {
        assertEquals("sip:4021@conference.example.com", withUser("sip:conference.example.com", "4021"))
    }

    @Test
    fun `angle brackets are not carried into the result`() {
        assertEquals("sip:4021@192.168.20.56", withUser("<sip:4022@192.168.20.56>", "4021"))
    }

    @Test
    fun `an address with no scheme produces nothing`() {
        // Rather than guessing `sip:`, which is how a sips account silently becomes an
        // unencrypted one. The caller keeps the address it already had.
        assertNull(withUser("4022@192.168.20.56", "4021"))
    }

    @Test
    fun `a blank new party produces nothing`() {
        assertNull(withUser("sip:4022@192.168.20.56", "   "))
    }

    private companion object {

        /** One INFO, with [headers] after the fixed ones and no body. */
        fun info(vararg headers: String): String = buildString {
            append("INFO sip:6001@192.168.30.202:5088;ob SIP/2.0\r\n")
            append("From: \"tccs4\" <sip:4022@192.168.20.56>;tag=H296pKUZ8mtjS\r\n")
            append("To: <sip:6001@192.168.30.202:5088;ob>;tag=bcab8201b798\r\n")
            append("CSeq: 121129397 INFO\r\n")
            headers.forEach { append(it).append("\r\n") }
        }

        val UPDATE_DISPLAY_INFO = info(
            "Content-Type: message/update_display",
            "Content-Length: 0",
            "X-FS-Display-Name: 4021",
            "X-FS-Display-Number: 4021",
            "X-FS-Lazy-Attended-Transfer: true",
        )
    }
}
