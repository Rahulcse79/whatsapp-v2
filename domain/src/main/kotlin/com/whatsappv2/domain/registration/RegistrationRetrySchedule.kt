package com.whatsappv2.domain.registration

import com.whatsappv2.domain.model.AccountId
import kotlinx.coroutines.flow.StateFlow

/**
 * When the app will next retry a registration it could not complete.
 *
 * ## Why this is not part of [com.whatsappv2.domain.model.RegistrationState]
 *
 * That type answers "what happened", and it is produced by the engine, which deliberately
 * knows nothing about retries — `LinphoneSipEngine` sets `retryScheduled = false` and says
 * in a comment that scheduling belongs to whoever owns the backoff. Folding a time into it
 * would put a field on every state that only one state can populate, and would make the
 * engine responsible for a decision it does not make.
 *
 * So this is a second, smaller stream, published by the thing that actually schedules:
 * the recovery coordinator.
 *
 * ## Why the UI needs it at all
 *
 * `Failed(reason, retryScheduled = true)` tells someone the app has not given up. It does
 * not tell them whether to wait or to go and fix something, and "Reconnecting..." forever
 * is indistinguishable from a hang. A time answers that, and it is the one thing the
 * backoff knows and the screen cannot work out — the delay is sampled from a random
 * window (see [RegistrationBackoff]), so a UI that recomputed it would draw a countdown to
 * a moment nothing is going to happen.
 *
 * Absent means no retry is pending: either nothing has failed, or the failure is one no
 * retry can fix, or there is no network to retry over.
 */
interface RegistrationRetrySchedule {

    /**
     * Epoch milliseconds of the next scheduled attempt, per account.
     *
     * Epoch millis rather than a date-time type, matching [com.whatsappv2.core.common.time.Clock]:
     * the value crosses a module that must stay dependency-free, and formatting — "in 42
     * seconds", or a clock time — is the screen's decision, not this one's.
     */
    val nextRetryAt: StateFlow<Map<AccountId, Long>>
}
