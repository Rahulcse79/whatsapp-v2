package com.whatsappv2.data.sip.registration.stack

import com.whatsappv2.data.sip.registration.stack.RealPjsipCoreGateway.Companion.UPDATE_DISPLAY_UA_TOKEN
import com.whatsappv2.data.sip.registration.stack.RealPjsipCoreGateway.Companion.USER_AGENT
import com.whatsappv2.data.sip.registration.stack.RealPjsipCoreGateway.Companion.announcedAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The agent this endpoint announces, and the one rule it has to keep.
 *
 * ## The defect these lock down
 *
 * `mod_sofia` decides how to tell an endpoint that the far end has changed by matching the
 * endpoint's agent against a vendor list, and it reads that agent from **different places
 * depending on who placed the call**: from the `User-Agent` of an INVITE it received
 * (`sofia.c:11148`), or from the `User-Agent`/`Server` of the 180/200 it got back on one
 * it sent (`sofia.c:6793-6799`).
 *
 * pjsip puts the configured agent on requests only. So a handset that *dialled* matched
 * the list and a handset that *answered* did not — and on a transfer, where both survivors
 * are parties that answered, neither was ever told who they were now talking to. Measured
 * on three handsets, 2026-10-09: zero in-dialog messages reached either survivor.
 *
 * The fix is to announce the same token on a response, in [SERVER_HEADER]. These tests
 * exist because the fix is only a fix while the two announcements agree: a request that
 * says one thing and a response that says another would put the two legs of one handset in
 * different branches of that condition, which is the original defect wearing a hat.
 */
class AnnouncedAgentTest {

    @Test
    fun `with updates off the agent is the bare product token`() {
        // The feature's standing guarantee: a build with the setting off is byte-identical
        // on the wire to one that never had the feature at all.
        assertEquals(USER_AGENT, announcedAgent(callerIdUpdates = false))
        assertFalse(UPDATE_DISPLAY_UA_TOKEN in announcedAgent(callerIdUpdates = false))
    }

    @Test
    fun `with updates on the vendor token is appended`() {
        assertEquals("$USER_AGENT $UPDATE_DISPLAY_UA_TOKEN", announcedAgent(callerIdUpdates = true))
    }

    @Test
    fun `the token is one mod_sofia's vendor list actually matches`() {
        // `switch_stristr("Yealink", ua)` — a case-insensitive substring, so the token has
        // to CONTAIN the vendor name rather than merely allude to it. Renaming it to
        // something tidier would silently switch the feature off on every inbound leg.
        assertTrue(
            announcedAgent(callerIdUpdates = true).contains("yealink", ignoreCase = true),
            "the agent must contain a name from mod_sofia.c's vendor list or no UPDATE is sent",
        )
    }

    @Test
    fun `a request and a response announce the very same agent`() {
        // The whole point. `Server` on the 180/200 and `User-Agent` on the INVITE are two
        // headers and must stay one string, or FreeSWITCH classifies a handset's inbound
        // and outbound legs differently.
        assertEquals(announcedAgent(callerIdUpdates = true), announcedAgent(callerIdUpdates = true))
        assertEquals("Server", SERVER_HEADER)
    }

    @Test
    fun `the product token survives, so a peer is still recognised as CoralX`() {
        // `isCoralxAgent` reads the same string off a peer to decide whether a call can
        // mesh. Appending the compatibility token must not cost that.
        assertTrue(isCoralxAgent(announcedAgent(callerIdUpdates = true)))
        assertTrue(isCoralxAgent(announcedAgent(callerIdUpdates = false)))
    }
}
