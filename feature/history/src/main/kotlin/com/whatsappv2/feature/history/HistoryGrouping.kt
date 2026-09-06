package com.whatsappv2.feature.history

import java.time.Instant
import java.time.ZoneId

/**
 * Which day a call belongs to, for the headings the list is grouped by (Task 48).
 *
 * ## The zone is a parameter
 *
 * "Which day" is a question only a time zone can answer, and the answer changes: a call at
 * half past midnight UTC happened yesterday in New York. Taking the zone rather than
 * reaching for the system default keeps this pure and lets a test pin a zone instead of
 * asserting whatever the machine running it happens to be set to.
 */
internal fun Long.toEpochDay(zone: ZoneId): Long =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate().toEpochDay()

/**
 * The heading that belongs between two rows, if any.
 *
 * A heading goes above the first call of each day, which is a fact about the pair either
 * side of it rather than about any one call — the last call of a day has no way to know it
 * was the last. Returning null for the end of the list is what stops a trailing heading
 * with nothing under it.
 */
internal fun dayHeaderBetween(
    before: HistoryRow.Call?,
    after: HistoryRow.Call?,
    zone: ZoneId,
): HistoryRow.DayHeader? {
    val next = after ?: return null
    val day = next.entry.startedAtEpochMillis.toEpochDay(zone)
    val sameDayAsPrevious = before?.entry?.startedAtEpochMillis?.toEpochDay(zone) == day
    return if (sameDayAsPrevious) null else HistoryRow.DayHeader(day)
}
