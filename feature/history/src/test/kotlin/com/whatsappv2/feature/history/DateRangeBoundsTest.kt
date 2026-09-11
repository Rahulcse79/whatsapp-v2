package com.whatsappv2.feature.history

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DateRangeBoundsTest {

    // Kolkata: +05:30, no DST. Far enough from UTC that a wrong conversion is visible.
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 9, 12)

    private fun utcMidnightOf(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `one day picked is that day, midnight to midnight, in the log's zone`() {
        val bounds = dateRangeBounds(utcMidnightOf(day), endUtcDayMillis = null, zone = zone)

        assertEquals(day.atStartOfDay(zone).toInstant().toEpochMilli(), bounds.fromEpochMillis)
        assertEquals(day.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli(), bounds.toEpochMillis)
    }

    @Test
    fun `a call at two in the morning is inside the day it was made on`() {
        // The defect this conversion prevents: the picker's UTC midnight is 05:30 local,
        // and a query built on it would file a 02:00 call under the previous day.
        val bounds = dateRangeBounds(utcMidnightOf(day), null, zone)
        val twoAm = day.atTime(2, 0).atZone(zone).toInstant().toEpochMilli()

        assertTrue(twoAm in bounds.fromEpochMillis..bounds.toEpochMillis)
    }

    @Test
    fun `a range runs from the first day's start to the last day's end`() {
        val last = day.plusDays(2)
        val bounds = dateRangeBounds(utcMidnightOf(day), utcMidnightOf(last), zone)

        assertEquals(day.atStartOfDay(zone).toInstant().toEpochMilli(), bounds.fromEpochMillis)
        assertEquals(last.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli(), bounds.toEpochMillis)
    }

    @Test
    fun `a range picked backwards is put the right way round`() {
        val forwards = dateRangeBounds(utcMidnightOf(day), utcMidnightOf(day.plusDays(2)), zone)
        val backwards = dateRangeBounds(utcMidnightOf(day.plusDays(2)), utcMidnightOf(day), zone)

        assertEquals(forwards, backwards)
    }

    @Test
    fun `the chip names one day once and a range with both ends`() {
        val one = dateRangeBounds(utcMidnightOf(day), null, zone)
        val two = dateRangeBounds(utcMidnightOf(day), utcMidnightOf(day.plusDays(2)), zone)

        // A pinned locale: en_GB on Java 17 abbreviates September as "Sept", and the
        // test is about the shape of the label, not the machine's dictionary.
        assertEquals("12 Sep", dateRangeLabel(one.fromEpochMillis, one.toEpochMillis, zone, Locale.US))
        assertEquals("12 Sep – 14 Sep", dateRangeLabel(two.fromEpochMillis, two.toEpochMillis, zone, Locale.US))
    }

    @Test
    fun `a bound goes back into the picker as the day it stands for`() {
        // Reopening the picker must show the applied range, not an empty calendar — and
        // must show the right day, not the one the UTC offset lands on.
        val bounds = dateRangeBounds(utcMidnightOf(day), utcMidnightOf(day.plusDays(1)), zone)

        assertEquals(utcMidnightOf(day), bounds.fromEpochMillis.toUtcDayMillis(zone))
        assertEquals(utcMidnightOf(day.plusDays(1)), bounds.toEpochMillis.toUtcDayMillis(zone))
    }
}
