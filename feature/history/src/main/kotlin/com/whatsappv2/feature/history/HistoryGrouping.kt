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

/**
 * Folds the legs of each conference in [rows] into one row (ADR-009).
 *
 * [rows] is a page of the log, newest first. Every leg that shares a conference key is
 * gathered into the row of the conference's **first** leg — the one furthest down the
 * page, because it started earliest — and the other legs disappear from the list into
 * that row's members. A leg with no key, or the only leg of its key on this page, is
 * left exactly as it was, so a log written before keys existed reads as it always did.
 *
 * Grouping happens within a page rather than in the query, and that is a deliberate
 * trade: a conference whose legs straddle a page boundary shows as two groups, one on
 * each page, and stops doing so when the pages are loaded together — whereas grouping in
 * SQL would mean every list query aggregating every row. Legs of one conference start
 * within minutes of each other and pages are a hundred rows long, so the boundary case
 * is rare and the row it produces is still true.
 */
internal fun groupConferences(rows: List<HistoryRow.Call>): List<HistoryRow.Call> {
    val byKey = rows.filter { it.entry.conferenceKey != null }.groupBy { it.entry.conferenceKey!! }
    if (byKey.values.none { it.size > 1 }) return rows

    return rows.mapNotNull { row ->
        val key = row.entry.conferenceKey ?: return@mapNotNull row
        val legs = byKey.getValue(key)
        if (legs.size < 2) return@mapNotNull row
        // Only the earliest-started leg carries the group; the rest fold into it.
        val first = legs.minBy { it.entry.startedAtEpochMillis }
        if (row !== first) return@mapNotNull null
        HistoryRow.Call(
            entry = first.entry,
            title = legs.sortedBy { it.entry.startedAtEpochMillis }.joinToString { it.title },
            legs = legs.sortedBy { it.entry.startedAtEpochMillis }.map { HistoryRow.Member(it.entry, it.title) },
        )
    }
}
