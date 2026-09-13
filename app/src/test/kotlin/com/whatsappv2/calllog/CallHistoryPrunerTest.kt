package com.whatsappv2.calllog

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
import com.whatsappv2.domain.model.ThemeMode
import com.whatsappv2.domain.testing.FakeAppSettingsRepository
import com.whatsappv2.domain.testing.FakeCallLogRepository
import com.whatsappv2.domain.usecase.PruneCallHistoryUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The three moments the pruner has to act on: start, a changed setting, a recorded call.
 *
 * Each test seeds the log, starts the pruner, and then does one of the three; what it
 * asserts is which entries are left. The prune itself is `PruneCallHistoryUseCaseTest`'s
 * business — here it is enough that it ran when it should have.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallHistoryPrunerTest {

    private val log = FakeCallLogRepository()
    private val settings = FakeAppSettingsRepository()
    private val clock = MutableClock(NOW)

    private fun TestScope.pruner() = CallHistoryPruner(
        prune = PruneCallHistoryUseCase(log, clock, NoOpLogger),
        settings = settings,
        callLog = log,
        // The background scope, because the pruner never completes on its own and the
        // test must not wait for it to.
        scope = backgroundScope,
    )

    @Test
    fun `starting prunes what the log already holds`() = runTest(UnconfinedTestDispatcher()) {
        // The app-start moment: a phone that has been off for a month has a month of
        // entries past their date, and nobody has opened the history screen yet.
        log.record(entry(daysAgo = THIRTY))
        val kept = log.record(entry(daysAgo = ONE))

        pruner().start()

        assertEquals(listOf(kept.id), remainingIds())
    }

    @Test
    fun `shortening the retention prunes at once`() = runTest(UnconfinedTestDispatcher()) {
        val kept = log.record(entry(daysAgo = ONE))
        val tenDaysOld = log.record(entry(daysAgo = TEN))
        pruner().start()
        assertEquals(setOf(kept.id, tenDaysOld.id), remainingIds().toSet())

        settings.setCallHistoryRetention(CallHistoryRetention.ofDays(SEVEN))

        assertEquals(listOf(kept.id), remainingIds())
    }

    @Test
    fun `a recorded call re-checks the boundary on a device that stayed awake`() =
        runTest(UnconfinedTestDispatcher()) {
            // No setting changed and the app did not restart; time passed. The entry
            // that was inside the retention at start is outside it by the time the next
            // call is logged, and the log must not keep showing it.
            val ageing = log.record(entry(daysAgo = NINETEEN))
            pruner().start()
            assertEquals(listOf(ageing.id), remainingIds())

            clock.advanceBy(TWO * CallHistoryRetention.MILLIS_PER_DAY)
            val fresh = log.record(entry(daysAgo = 0))

            assertEquals(listOf(fresh.id), remainingIds())
        }

    @Test
    fun `a settings write that leaves the retention alone does not prune`() =
        runTest(UnconfinedTestDispatcher()) {
            // Not for correctness — an extra prune would delete nothing wrong — but so
            // the theme toggle is not paying for a database write it has nothing to do
            // with. Observable here because time moves between the start and the write.
            val ageing = log.record(entry(daysAgo = NINETEEN))
            pruner().start()
            clock.advanceBy(TWO * CallHistoryRetention.MILLIS_PER_DAY)

            settings.setThemeMode(ThemeMode.DARK)

            assertEquals(listOf(ageing.id), remainingIds())
        }

    @Test
    fun `keeping everything leaves the log alone`() = runTest(UnconfinedTestDispatcher()) {
        settings.setCallHistoryRetention(CallHistoryRetention.KEEP_EVERYTHING)
        val ancient = log.record(entry(daysAgo = TEN_YEARS_IN_DAYS))

        pruner().start()

        assertEquals(listOf(ancient.id), remainingIds())
    }

    private suspend fun remainingIds(): List<CallLogId> = log.observe().first().map { it.id }

    private fun entry(daysAgo: Int): CallLogEntry {
        val startedAt = clock.nowEpochMillis() - daysAgo * CallHistoryRetention.MILLIS_PER_DAY
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
            media = MediaProfile.AUDIO,
        )
    }

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
        const val NOW = 1_700_000_000_000L
        const val ONE_MINUTE = 60_000L
        const val ONE = 1
        const val TWO = 2
        const val SEVEN = 7
        const val TEN = 10
        const val NINETEEN = 19
        const val THIRTY = 30
        const val TEN_YEARS_IN_DAYS = 3_650
    }
}
