package com.whatsappv2.feature.calls

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The member list of a conference this device is mixing (ADR-009).
 *
 * The request behind it: "if conference call then show all devices, with extension or
 * display name". A conference of six that lists five, or lists them as one dotted line
 * that truncates, does not answer it. Pure, so every row is decidable without a screen.
 */
class LocalMixRosterTest {

    private val self = LocalParticipant(label = "Alice", extension = "1000")

    @Test
    fun `every mixed leg is a row, in the order the calls were made, after the user`() {
        val calls = listOf(
            call("a", "1001"),
            call("b", "1002", displayName = "Bob"),
            call("c", "1003"),
        )

        val roster = localMixRoster(calls, mixed = ids("a", "b", "c"), self = self)!!

        assertTrue(roster.rosterAvailable, "the mixer holds the whole membership; nothing is unknown")
        assertEquals(listOf("Alice", "1001", "Bob", "1003"), roster.participants.map { it.label })
        assertEquals(listOf(true, false, false, false), roster.participants.map { it.isSelf })
    }

    @Test
    fun `the extension sits under a name, and nowhere when the name is the extension`() {
        val calls = listOf(call("a", "1001"), call("b", "1002", displayName = "Bob"))

        val rows = localMixRoster(calls, mixed = ids("a", "b"), self = self)!!.participants

        assertEquals("1000", rows[0].detail, "the user's own extension, under their name")
        assertNull(rows[1].detail, "'1001' over '1001' is noise")
        assertEquals("1002", rows[2].detail, "Bob is reachable at 1002, and the row says so")
    }

    @Test
    fun `a member not carrying audio says so`() {
        // The one thing the list can add beyond names: a member who is held hears nothing
        // and is heard by nobody, and a conference that looks whole while one member is
        // deaf is the failure the list exists to surface.
        val calls = listOf(
            call("a", "1001"),
            call("b", "1002", state = CallState.Held(HoldParty.LOCAL)),
            call("c", "1003", state = CallState.Resuming()),
            call("d", "1004", state = CallState.Outgoing.Ringing),
        )

        val rows = localMixRoster(calls, mixed = ids("a", "b", "c", "d"), self = null)!!.participants

        assertEquals(
            listOf(null, ParticipantStatus.ON_HOLD, ParticipantStatus.REJOINING, ParticipantStatus.CONNECTING),
            rows.map { it.status },
        )
    }

    @Test
    fun `the user's row carries this device's own microphone`() {
        // The mute fan-out keeps every member's flag in step, so the members agree. When
        // they briefly do not, "muted" is the safe reading: a live microphone reported as
        // off is the error that matters.
        val muted = CallControls.DEFAULT.copy(isMuted = true)
        val calls = listOf(call("a", "1001", state = CallState.Connected(muted)), call("b", "1002"))

        val rows = localMixRoster(calls, mixed = ids("a", "b"), self = self)!!.participants

        assertTrue(rows[0].isMuted, "one microphone, one answer")
        assertTrue(rows.drop(1).none { it.isMuted }, "the members' rows are not this device's mute")
    }

    @Test
    fun `the address book names a member, and the extension moves under the name`() {
        // A leg this device dialled carries no display name from the wire — the 200 OK's
        // To header is whatever this device sent — so the address book is the only place
        // a name can come from, exactly as it is for the watched call's title.
        val calls = listOf(call("a", "1001"), call("b", "1002", displayName = "Asserted"))
        val contacts = mapOf(
            calls[0].remote to Contact(displayName = "Priya Nair", photoUri = "content://photo/1"),
        )

        val rows = localMixRoster(calls, mixed = ids("a", "b"), self = null, contacts = contacts)!!.participants

        assertEquals("Priya Nair", rows[0].label, "the address book's name wins")
        assertEquals("1001", rows[0].detail, "with the extension underneath")
        assertEquals("content://photo/1", rows[0].photoUri)
        assertEquals("Asserted", rows[1].label, "a name the far end asserted still counts when the book has none")
        assertEquals("1002", rows[1].detail)
    }

    @Test
    fun `no self row until the account is known`() {
        val calls = listOf(call("a", "1001"), call("b", "1002"))

        val rows = localMixRoster(calls, mixed = ids("a", "b"), self = null)!!.participants

        assertEquals(listOf("1001", "1002"), rows.map { it.label }, "a nameless row is worse than none")
    }

    @Test
    fun `fewer than two mixed calls is not a conference`() {
        val calls = listOf(call("a", "1001"), call("b", "1002"))

        assertNull(localMixRoster(calls, mixed = emptySet(), self = self))
        assertNull(localMixRoster(calls, mixed = ids("a"), self = self))
    }

    @Test
    fun `a call outside the mix is not listed`() {
        // A third call on hold beside a merged pair is a separate call, not a member.
        val calls = listOf(call("a", "1001"), call("b", "1002"), call("c", "1003"))

        val rows = localMixRoster(calls, mixed = ids("a", "b"), self = null)!!.participants

        assertEquals(listOf("1001", "1002"), rows.map { it.label })
    }

    private fun ids(vararg keys: String): Set<CallId> = keys.mapTo(mutableSetOf(), ::CallId)

    private fun call(
        key: String,
        extension: String,
        displayName: String? = null,
        state: CallState = CallState.Connected(),
    ) = CallSnapshot(
        callId = CallId(key),
        accountId = AccountId("acct-1"),
        remote = SipUri.parse("sip:$extension@sip.example.com").getOrNull()!!,
        remoteDisplayName = displayName,
        direction = CallDirection.OUTGOING,
        state = state,
        media = MediaProfile.AUDIO,
        startedAtEpochMillis = 0L,
        connectedAtEpochMillis = null,
    )
}
