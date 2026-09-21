package com.whatsappv2.data.sip.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ConferenceInfoParser] against the bytes FreeSWITCH actually sends.
 *
 * The document in [notify] is not invented: it is a `NOTIFY` captured from the running
 * reference server on 2026-09-20, after a `SUBSCRIBE` with `Event: conference` was
 * answered `202 Accepted`. Writing the test against a plausible-looking document instead
 * is how a parser passes its suite and returns nothing on a handset.
 */
class ConferenceInfoParserTest {

    @Test
    fun `reads the roster FreeSWITCH sends`() {
        val roster = assertNotNull(ConferenceInfoParser.parse(notify(), selfUri = null))

        assertEquals("sip:3000@192.168.2.194", roster.entity)
        assertEquals(2, roster.participants.size)

        val first = roster.participants.first()
        assertEquals("sip:1001@192.168.2.194", first.id)
        assertEquals("sip:1001@192.168.2.194", first.uri)
        assertEquals("1001", first.displayName)
        assertTrue(first.hasVideoStream, "audio and video both sendrecv")
        assertFalse(first.isMuted)
        assertNotNull(first.joinedAtEpochMillis)
    }

    @Test
    fun `a member sending no video takes no tile`() {
        val roster = assertNotNull(ConferenceInfoParser.parse(notify(), selfUri = null))
        val audioOnly = roster.participants.single { it.id.contains("1005") }

        assertFalse(audioOnly.hasVideoStream, "the 1005 leg has no video media at all")
    }

    @Test
    fun `a member whose audio is recvonly reads as muted`() {
        val roster = assertNotNull(ConferenceInfoParser.parse(notify(audioStatus = "recvonly"), null))

        assertTrue(roster.participants.first().isMuted)
    }

    @Test
    fun `this device recognises itself however its own address is spelled`() {
        val roster = assertNotNull(
            ConferenceInfoParser.parse(notify(), selfUri = "<sip:1001@192.168.2.194;transport=udp>"),
        )

        assertTrue(roster.participants.first { it.id.contains("1001") }.isSelf)
        assertFalse(roster.participants.first { it.id.contains("1005") }.isSelf)
    }

    @Test
    fun `a partial update is not mistaken for the whole room`() {
        // The contract is a full restatement; applying a delta to a roster this parser
        // does not keep is how somebody who left stays on screen.
        assertNull(ConferenceInfoParser.parse(notify(state = "partial"), selfUri = null))
    }

    @Test
    fun `a NOTIFY for another event package is left alone`() {
        val mwi = """
            NOTIFY sip:1001@192.168.2.191:46786;ob SIP/2.0
            Event: message-summary
            Content-Type: application/simple-message-summary

            Messages-Waiting: no
        """.trimIndent().replace("\n", "\r\n")

        assertNull(ConferenceInfoParser.parse(mwi, selfUri = null))
    }

    @Test
    fun `a member with no entity is dropped rather than given an invented id`() {
        val body = notify().replace(""" entity="sip:1005@192.168.2.194"""", "", ignoreCase = false)

        val roster = assertNotNull(ConferenceInfoParser.parse(body, selfUri = null))
        assertTrue(roster.participants.none { it.displayName == "1005" })
    }

    private fun notify(state: String = "full", audioStatus: String = "sendrecv"): String {
        val body = """
            <?xml version="1.0"?>
            <conference-info version="1" state="$state" xmlns="urn:ietf:params:xml:ns:conference-info" entity="sip:3000@192.168.2.194">
              <conference-description>
                <display-text>FreeSWITCH Conference</display-text>
              </conference-description>
              <conference-state>
                <user-count>2</user-count>
                <active>true</active>
              </conference-state>
              <users>
                <user state="full" entity="sip:1001@192.168.2.194">
                  <display-text>1001</display-text>
                  <endpoint entity="sip:1001@192.168.2.194">
                    <display-text>1001</display-text>
                    <status>connected</status>
                    <joining-info>
                      <when>2026-09-20T21:39:50+05:00</when>
                    </joining-info>
                    <media id="68a">
                      <type>audio</type>
                      <src-id>3084727750</src-id>
                      <status>$audioStatus</status>
                    </media>
                    <media id="68v">
                      <type>video</type>
                      <src-id>2189789191</src-id>
                      <status>sendrecv</status>
                    </media>
                  </endpoint>
                </user>
                <user state="full" entity="sip:1005@192.168.2.194">
                  <display-text>1005</display-text>
                  <endpoint entity="sip:1005@192.168.2.194">
                    <status>connected</status>
                    <media id="69a">
                      <type>audio</type>
                      <status>sendrecv</status>
                    </media>
                  </endpoint>
                </user>
              </users>
            </conference-info>
        """.trimIndent()

        return buildString {
            append("NOTIFY sip:1004@192.168.2.194:60710 SIP/2.0\r\n")
            append("Event: conference\r\n")
            append("Subscription-State: active;expires=3600\r\n")
            append("Content-Type: ${ConferenceInfoParser.CONTENT_TYPE}\r\n")
            append("Content-Length: ${body.length}\r\n")
            append("\r\n")
            append(body)
        }
    }
}
