package com.whatsappv2.feature.history

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where the day headings go (Task 48).
 *
 * Pure, and tested with a pinned zone rather than the machine's: "which day" is a question
 * only a zone can answer, and a test that used the default would pass in London and fail
 * in Auckland.
 */
class HistoryGroupingTest {

    @Test
    fun `the first call of a day gets a heading`() {
        val header = dayHeaderBetween(before = null, after = call(NOON), zone = LONDON)

        assertEquals(LOCAL_DAY, header?.epochDay)
    }

    @Test
    fun `a later call the same day gets none`() {
        val header = dayHeaderBetween(
            before = call(NOON),
            after = call(NOON.plusHours(1)),
            zone = LONDON,
        )

        assertNull(header)
    }

    @Test
    fun `crossing midnight starts a new day`() {
        val header = dayHeaderBetween(
            before = call(NOON),
            after = call(NOON.plusDays(1)),
            zone = LONDON,
        )

        assertEquals(LOCAL_DAY + 1, header?.epochDay)
    }

    @Test
    fun `the end of the list gets no trailing heading`() {
        // Nothing would sit under it. The list is newest first, so `after` being null is
        // the oldest call, not the newest.
        assertNull(dayHeaderBetween(before = call(NOON), after = null, zone = LONDON))
    }

    @Test
    fun `the zone decides the day, not the instant`() {
        // Half past midnight in Auckland is still the previous afternoon in London.
        val instant = ZonedDateTime.of(2026, 3, 2, 0, 30, 0, 0, AUCKLAND)

        val auckland = dayHeaderBetween(null, call(instant), AUCKLAND)?.epochDay
        val london = dayHeaderBetween(null, call(instant), LONDON)?.epochDay

        assertEquals(1L, auckland!! - london!!)
    }

    private fun call(at: ZonedDateTime) = HistoryRow.Call(
        CallLogEntry(
            id = CallLogId(at.toEpochSecond()),
            accountId = AccountId("acct-1"),
            remote = REMOTE,
            remoteDisplayName = null,
            contactName = null,
            direction = CallDirection.OUTGOING,
            startedAtEpochMillis = at.toInstant().toEpochMilli(),
            answeredAtEpochMillis = null,
            endedAtEpochMillis = at.toInstant().toEpochMilli(),
            reason = HangupReason.LOCAL_HANGUP,
            media = MediaProfile.AUDIO,
        ),
    )

    private companion object {
        val LONDON: ZoneId = ZoneId.of("Europe/London")
        val AUCKLAND: ZoneId = ZoneId.of("Pacific/Auckland")
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
        val NOON: ZonedDateTime = ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, LONDON)
        val LOCAL_DAY: Long = NOON.toLocalDate().toEpochDay()
    }
}
