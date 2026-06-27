package dev.jaredhq.dashboardandroid.watch.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.DateTimeException

/**
 * JVM coverage for [WatchTime] — the offset→wall-clock math and date/sentinel helpers that timestamp
 * every point metric we ship (body energy, stress, HRV) plus activity/sleep dates. Focuses on the
 * two failure-prone spots called out in the docs: the seconds offset-unit caveat and the
 * past-midnight carry.
 */
class WatchTimeTest {

    @Test
    fun ymdZeroPads() {
        assertEquals("2026-06-27", WatchTime.ymd(2026, 6, 27))
        assertEquals("2026-01-05", WatchTime.ymd(2026, 1, 5))
        assertEquals("0099-12-31", WatchTime.ymd(99, 12, 31))
    }

    @Test
    fun ymdhmsZeroPads() {
        assertEquals("2026-06-27 21:07:16", WatchTime.ymdhms(2026, 6, 27, 21, 7, 16))
        assertEquals("2026-06-27 00:00:00", WatchTime.ymdhms(2026, 6, 27, 0, 0, 0))
        assertEquals("2026-06-27 09:05:03", WatchTime.ymdhms(2026, 6, 27, 9, 5, 3))
    }

    @Test
    fun localDateTimeTreatsOffsetAsSeconds() {
        // 0 seconds = midnight.
        assertEquals("2026-06-27 00:00:00", WatchTime.localDateTime(2026, 6, 27, 0))
        // The offset-unit caveat: 90 here means 90 *seconds* (00:01:30), NOT 90 minutes. Callers
        // that have a minute offset must pass minutes*60 — this is the regression guard for that.
        assertEquals("2026-06-27 00:01:30", WatchTime.localDateTime(2026, 6, 27, 90))
        // A 90-*minute* sample, correctly converted to seconds at the call site, lands at 01:30:00.
        assertEquals("2026-06-27 01:30:00", WatchTime.localDateTime(2026, 6, 27, 90 * 60))
        // Last second of the day stays on the same day.
        assertEquals("2026-06-27 23:59:59", WatchTime.localDateTime(2026, 6, 27, 86_399))
    }

    @Test
    fun localDateTimeCarriesPastMidnight() {
        // Exactly 24h rolls to the next day at midnight.
        assertEquals("2026-06-28 00:00:00", WatchTime.localDateTime(2026, 6, 27, 86_400))
        // An offset > a day carries the right number of days forward.
        assertEquals("2026-06-28 01:00:00", WatchTime.localDateTime(2026, 6, 27, 86_400 + 3_600))
        // Carry across a month boundary.
        assertEquals("2026-07-01 00:00:30", WatchTime.localDateTime(2026, 6, 30, 86_400 + 30))
        // Carry across a year boundary.
        assertEquals("2027-01-01 00:00:00", WatchTime.localDateTime(2026, 12, 31, 86_400))
    }

    @Test
    fun localDateTimeThrowsOnInvalidDate() {
        // Callers wrap this in runCatching so one bad record can't abort the sync.
        assertThrows(DateTimeException::class.java) {
            WatchTime.localDateTime(2026, 13, 1, 0)
        }
    }

    @Test
    fun nonZeroNullsOnlyZero() {
        assertNull(0.nonZero())
        assertEquals(42, 42.nonZero())
        // Negative is a real measurement, not the "not measured" sentinel.
        assertEquals(-5, (-5).nonZero())
    }
}
