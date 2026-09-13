package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.time.MutableClock
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.testing.FakeCallLogRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pruning by retention.
 *
 * Every entry here is placed relative to one fixed "now", because the cutoff is computed
 * from the clock the use case is given and nothing else — a test that used the wall clock
 * would pass or fail depending on when it ran.
 */
class PruneCallHistoryUseCaseTest {

    private val log = FakeCallLogRepository()
    private val clock = MutableClock(NOW)
    private val prune = PruneCallHistoryUseCase(log, clock, NoOpLogger)

    @Test
    fun `calls older than the retention go, newer ones stay`() = runTest {
        val old = log.record(entry(daysAgo = TWENTY_ONE))
        val recent = log.record(entry(daysAgo = NINETEEN))

        val removed = prune(CallHistoryRetention.DEFAULT)

        assertEquals(1, removed)
        assertEquals(listOf(recent.id), remainingIds())
        assertFalse(old.id in remainingIds())
    }

    @Test
    fun `audio and video are pruned by the same rule`() = runTest {
        // The requirement is "apply the setting consistently to both". The way to be sure
        // is a pair that differ in nothing but the camera, and a prune that cannot tell
        // them apart.
        log.record(entry(daysAgo = TWENTY_ONE, media = MediaProfile.AUDIO))
        log.record(entry(daysAgo = TWENTY_ONE, media = MediaProfile.AUDIO_VIDEO))
        val keptAudio = log.record(entry(daysAgo = NINETEEN, media = MediaProfile.AUDIO))
        val keptVideo = log.record(entry(daysAgo = NINETEEN, media = MediaProfile.AUDIO_VIDEO))

        assertEquals(2, prune(CallHistoryRetention.DEFAULT))
        assertEquals(setOf(keptAudio.id, keptVideo.id), remainingIds().toSet())
    }

    @Test
    fun `a call that started exactly at the cutoff is kept`() = runTest {
        // Strictly older than the retention. "Keep 20 days" that dropped a call from
        // exactly 20 days ago would be keeping 19 days and a bit.
        log.record(entry(daysAgo = TWENTY))

        assertEquals(0, prune(CallHistoryRetention.DEFAULT))
        assertEquals(1, remainingIds().size)
    }

    @Test
    fun `keeping everything removes nothing, however old`() = runTest {
        log.record(entry(daysAgo = TEN_YEARS_IN_DAYS))

        assertEquals(0, prune(CallHistoryRetention.KEEP_EVERYTHING))
        assertEquals(1, remainingIds().size)
    }

    @Test
    fun `a shorter retention takes effect on the next prune`() = runTest {
        // Changing the setting is not retroactive in either direction: a shorter one
        // removes on the next run, and nothing brings a removed call back.
        val kept = log.record(entry(daysAgo = NINETEEN))
        assertEquals(0, prune(CallHistoryRetention.DEFAULT))

        assertEquals(1, prune(CallHistoryRetention.ofDays(SEVEN)))
        assertFalse(kept.id in remainingIds())
    }

    private suspend fun remainingIds(): List<CallLogId> = log.observe().first().map { it.id }

    private fun entry(daysAgo: Int, media: MediaProfile = MediaProfile.AUDIO): CallLogEntry {
        val startedAt = NOW - daysAgo * CallHistoryRetention.MILLIS_PER_DAY
        return CallLogEntry(
            id = CallLogId.UNSAVED,
            accountId = AccountId("acct-1"),
            remote = REMOTE,
            remoteDisplayName = null,
            contactName = null,
            direction = CallDirection.OUTGOING,
            startedAtEpochMillis = startedAt,
            answeredAtEpochMillis = startedAt,
            endedAtEpochMillis = startedAt + ONE_MINUTE,
            reason = HangupReason.LOCAL_HANGUP,
            media = media,
        )
    }

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
        const val NOW = 1_700_000_000_000L
        const val ONE_MINUTE = 60_000L
        const val SEVEN = 7
        const val NINETEEN = 19
        const val TWENTY = 20
        const val TWENTY_ONE = 21
        const val TEN_YEARS_IN_DAYS = 3_650
    }
}
