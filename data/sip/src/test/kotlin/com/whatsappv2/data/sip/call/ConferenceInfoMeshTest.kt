package com.whatsappv2.data.sip.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The mesh marker, written and read back (`ConferenceMesh`).
 *
 * One format and one parser: the document a focus announces is the same one a server
 * bridge would send, plus an extension element that says every participant holds a leg to
 * every other. This is the round trip, because a marker that only one side understands is
 * a conference where half the devices wait for a picture that is never composed.
 */
class ConferenceInfoMeshTest {

    private fun message(body: String): String = buildString {
        append("MESSAGE sip:alice@example.com SIP/2.0\r\n")
        append("Content-Type: ${ConferenceInfoParser.CONTENT_TYPE}\r\n")
        append("Content-Length: ${body.length}\r\n")
        append("\r\n")
        append(body)
    }

    private val members = listOf(
        StackParticipant(
            id = "sip:4030@192.168.20.56",
            uri = "sip:4030@192.168.20.56",
            displayName = "Reception",
            isMuted = false,
            isSpeaking = false,
            isSelf = false,
            hasVideoStream = true,
            joinedAtEpochMillis = null,
        ),
        StackParticipant(
            id = "sip:4032@192.168.20.56",
            uri = "sip:4032@192.168.20.56",
            displayName = null,
            isMuted = true,
            isSpeaking = false,
            isSelf = false,
            hasVideoStream = true,
            joinedAtEpochMillis = null,
        ),
    )

    @Test
    fun `a mesh roster survives the round trip`() {
        val document = ConferenceInfoWriter.roster("sip:4030@192.168.20.56", members, mesh = true)

        val roster = assertNotNull(ConferenceInfoParser.parse(message(document), selfUri = null))

        assertTrue(roster.mesh, "the mesh marker did not survive: $document")
        assertEquals("sip:4030@192.168.20.56", roster.entity)
        assertEquals(listOf("sip:4030@192.168.20.56", "sip:4032@192.168.20.56"), roster.participants.map { it.uri })
    }

    @Test
    fun `a roster without the marker describes a star`() {
        // A server bridge's document, and one from a build that predates meshing. Both
        // leave this device a spoke receiving one composed picture; dialling the other
        // participants off one of them would open legs nobody is expecting.
        val document = ConferenceInfoWriter.roster("sip:3000@192.168.20.56", members)

        val roster = assertNotNull(ConferenceInfoParser.parse(message(document), selfUri = null))

        assertFalse(roster.mesh)
    }

    @Test
    fun `a member is still recognised as themselves in a mesh roster`() {
        // The parser decides `isSelf` by address, and the wire spells this device's own
        // with whatever parameters the transport added.
        val document = ConferenceInfoWriter.roster("sip:4030@192.168.20.56", members, mesh = true)

        val roster = assertNotNull(
            ConferenceInfoParser.parse(message(document), selfUri = "<sip:4032@192.168.20.56;transport=udp>"),
        )

        assertEquals(listOf(false, true), roster.participants.map { it.isSelf })
    }

    @Test
    fun `a relayed participant survives the round trip, and only that one`() {
        // The marker has to cross the wire intact, because it is an instruction and not a
        // decoration: a member that misses it dials somebody the focus is already
        // carrying, and that participant is then heard twice.
        val deskPhone = members[1].copy(uri = "sip:4099@192.168.20.56", isRelayed = true)
        val document = ConferenceInfoWriter.roster(
            "sip:4030@192.168.20.56",
            listOf(members[0], deskPhone),
            mesh = true,
        )

        val roster = assertNotNull(ConferenceInfoParser.parse(message(document), selfUri = null))

        assertTrue(roster.mesh, "the topology was lost")
        assertEquals(
            listOf("sip:4099@192.168.20.56"),
            roster.participants.filter { it.isRelayed }.map { it.uri },
        )
        assertEquals(2, roster.participants.size)
    }

    @Test
    fun `a roster with nobody relayed marks nobody`() {
        // Normal CoralX-to-CoralX conferencing: the element must not appear at all, so a
        // reader of an older build sees byte-for-byte what it always saw.
        val document = ConferenceInfoWriter.roster("sip:4030@192.168.20.56", members, mesh = true)

        assertFalse(document.contains(ConferenceInfoWriter.RELAYED), "an unrelayed roster carried the marker")

        val roster = assertNotNull(ConferenceInfoParser.parse(message(document), selfUri = null))
        assertTrue(roster.participants.none { it.isRelayed })
    }
}
