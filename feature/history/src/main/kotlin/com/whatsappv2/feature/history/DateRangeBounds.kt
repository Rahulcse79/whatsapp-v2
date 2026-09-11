package com.whatsappv2.feature.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * From the calendar days a date picker hands back to the bounds the store filters on
 * (item 5.3).
 *
 * ## Two clocks, one conversion
 *
 * Material's date picker reports a chosen day as **UTC midnight** of that calendar date —
 * it is a calendar, not a clock, and it uses UTC so a day is the same number everywhere.
 * The call log records when a call started in real epoch millis, and "calls on the 12th"
 * means the 12th where the phone was. Handing the picker's UTC millis straight to the
 * query would shift the day by the zone offset: in Kolkata the range would start at 05:30
 * and a 02:00 call on the chosen day would be filed under the day before.
 *
 * So the picker's number is read back as a [LocalDate] and the bounds are that date's
 * midnight and the following midnight, less one millisecond, in the log's zone. A single
 * day picked twice, or a range with no end yet, is that one day.
 */
internal data class DateRangeBounds(val fromEpochMillis: Long, val toEpochMillis: Long)

internal fun dateRangeBounds(
    startUtcDayMillis: Long,
    endUtcDayMillis: Long?,
    zone: ZoneId,
): DateRangeBounds {
    val start = startUtcDayMillis.toUtcLocalDate()
    val end = endUtcDayMillis?.toUtcLocalDate() ?: start
    val (first, last) = if (end.isBefore(start)) end to start else start to end
    return DateRangeBounds(
        fromEpochMillis = first.atStartOfDay(zone).toInstant().toEpochMilli(),
        toEpochMillis = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
    )
}

/**
 * The chip's label for an active range: "12 Sep" for one day, "12 Sep – 14 Sep" for more.
 *
 * Read back from the bounds rather than remembered from the picker, so what the chip says
 * is derived from what the query actually filters on and the two cannot disagree after a
 * rotation or a restore.
 */
internal fun dateRangeLabel(
    fromEpochMillis: Long,
    toEpochMillis: Long,
    zone: ZoneId,
    locale: Locale = Locale.getDefault(),
): String {
    val format = DateTimeFormatter.ofPattern("d MMM", locale)
    val first = Instant.ofEpochMilli(fromEpochMillis).atZone(zone).toLocalDate()
    val last = Instant.ofEpochMilli(toEpochMillis).atZone(zone).toLocalDate()
    return if (first == last) {
        first.format(format)
    } else {
        "${first.format(format)} – ${last.format(format)}"
    }
}

/**
 * The picker's own coordinates for a bound already in the query, so reopening the picker
 * shows the range that is applied rather than an empty calendar.
 */
internal fun Long.toUtcDayMillis(zone: ZoneId): Long =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate().toEpochDay() * MILLIS_PER_DAY

private fun Long.toUtcLocalDate(): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(this, MILLIS_PER_DAY))

private const val MILLIS_PER_DAY = 86_400_000L
