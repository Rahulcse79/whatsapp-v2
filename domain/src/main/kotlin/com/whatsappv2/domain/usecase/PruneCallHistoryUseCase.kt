package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.logging.Logger
import com.whatsappv2.core.common.time.Clock
import com.whatsappv2.domain.model.CallHistoryRetention
import com.whatsappv2.domain.repository.CallLogRepository
import javax.inject.Inject

/**
 * Removes call-log entries older than the retention the user chose.
 *
 * ## Why this is pull, not a scheduled job
 *
 * A `WorkManager` job would be the obvious shape and the wrong one. The cost of pruning is
 * one `DELETE … WHERE started_at < ?` against an indexed column; the cost of a job is a
 * scheduler entry, a wake-up the user did not ask for, and a second place where the
 * retention setting is read. Running it when something already woke the process — the app
 * starting, a call being logged, the setting changing — deletes the same rows at a moment
 * the user is already paying for.
 *
 * The consequence is honest and worth stating: a phone that never opens this app keeps its
 * old history until it is next opened. Nothing is shown to the user in that window, because
 * nothing is running to show it.
 *
 * ## One rule for audio and video
 *
 * The cutoff is a time and the query matches on start time alone. There is no branch on
 * `hasVideo` here or in the SQL, which is what makes "apply it consistently to both" a
 * property of the design rather than a thing to remember.
 */
class PruneCallHistoryUseCase @Inject constructor(
    private val callLog: CallLogRepository,
    private val clock: Clock,
    private val logger: Logger,
) {

    /**
     * Prunes to [retention] and returns how many entries went.
     *
     * Zero is the ordinary answer and is not logged: this runs on every app start and on
     * every setting change, and a line saying nothing happened, every time, is a line that
     * teaches people to stop reading the log.
     */
    suspend operator fun invoke(retention: CallHistoryRetention): Int {
        val cutoff = retention.cutoffEpochMillis(clock.nowEpochMillis()) ?: return 0

        val removed = callLog.deleteStartedBefore(cutoff)
        if (removed > 0) {
            logger.info(TAG, "Removed $removed call(s) older than ${retention.days} day(s)")
        }
        return removed
    }

    private companion object {
        const val TAG = "CallLog"
    }
}
