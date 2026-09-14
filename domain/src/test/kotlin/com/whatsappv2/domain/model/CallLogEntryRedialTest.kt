package com.whatsappv2.domain.model

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a call back dials ([CallLogEntry.redialTarget]).
 *
 * The case that matters is the first one: the server the account is on moved to another
 * address after the call was logged, and the row must still reach the same extension.
 * Found 2026-09-14 on a handset whose PBX runs on a laptop — every row in its history
 * carried the previous Wi-Fi network's address, and every call back sent its INVITE there.
 */
class CallLogEntryRedialTest {

    @Test
    fun `a far end on the account's own server is redialled by extension`() {
        // The domain the account has *now* is not this one; that is the point. The
        // extension is what survives the move, and PlaceCallUseCase completes it.
        val entry = entry(remote = "sip:1003@192.168.2.196", accountDomain = "192.168.2.196")

        assertEquals("1003", entry.redialTarget())
    }

    @Test
    fun `a far end on another domain keeps its full address`() {
        // Dialled as an address on purpose; completing it against this account's domain
        // would be the silent rewrite DialledTarget refuses.
        val entry = entry(remote = "sip:carol@other.example.com", accountDomain = "sip.example.com")

        assertEquals("sip:carol@other.example.com", entry.redialTarget())
    }

    @Test
    fun `a row from before the domain was recorded is treated as on the account's server`() {
        // Every row written by schema 1 was an extension on the account's server, and
        // treating them as foreign would leave exactly those rows dead after a move.
        val entry = entry(remote = "sip:1003@192.168.2.196", accountDomain = null)

        assertEquals("1003", entry.redialTarget())
    }

    @Test
    fun `host names are compared without regard to case`() {
        // RFC 3261 §19.1.4: the host part is case-insensitive, and the account editor
        // does not fold what the user typed.
        val entry = entry(remote = "sip:1003@PBX.Example.com", accountDomain = "pbx.example.com")

        assertEquals("1003", entry.redialTarget())
    }

    @Test
    fun `an address with no user part is redialled as written`() {
        // There is no extension to complete, so the address is the only thing to dial.
        val entry = entry(remote = "sip:conference.example.com", accountDomain = "conference.example.com")

        assertEquals("sip:conference.example.com", entry.redialTarget())
    }

    @Test
    fun `the remote's port and parameters do not make it foreign`() {
        // A row logged from an INVITE whose Contact carried a port and transport is still
        // an extension on the account's server.
        val entry = entry(remote = "sip:1003@pbx.example.com:5061;transport=tcp", accountDomain = "pbx.example.com")

        assertEquals("1003", entry.redialTarget())
    }

    private fun entry(remote: String, accountDomain: String?) = CallLogEntry(
        id = CallLogId.UNSAVED,
        accountId = AccountId("acct-1"),
        remote = checkNotNull(SipUri.parse(remote).getOrNull()) { "bad fixture: $remote" },
        accountDomain = accountDomain,
        remoteDisplayName = null,
        contactName = null,
        direction = CallDirection.OUTGOING,
        startedAtEpochMillis = STARTED_AT,
        answeredAtEpochMillis = null,
        endedAtEpochMillis = STARTED_AT,
        reason = HangupReason.LOCAL_HANGUP,
        media = MediaProfile.AUDIO,
    )

    private companion object {
        const val STARTED_AT = 1_700_000_000_000L
    }
}
