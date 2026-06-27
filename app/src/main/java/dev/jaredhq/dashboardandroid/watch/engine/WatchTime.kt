package dev.jaredhq.dashboardandroid.watch.engine

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Pure date/time + sentinel helpers shared by the watch mappers in [IdoSdkWatchEngine].
 *
 * Extracted from the engine companion so the offset→wall-clock math (which timestamps every point
 * metric we ship — body energy, stress, HRV, plus activity/sleep dates) is unit-testable without a
 * device or the SDK. No Android or `com.ido.*` dependencies — JVM-pure on purpose.
 */
object WatchTime {

    private val LOCAL_DT_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** A "YYYY-MM-DD" date string, zero-padded. */
    fun ymd(year: Int, month: Int, day: Int): String =
        "%04d-%02d-%02d".format(year, month, day)

    /** A "YYYY-MM-DD HH:MM:SS" wall-clock string from explicit fields, zero-padded. */
    fun ymdhms(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String =
        "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)

    /**
     * Resolve a "YYYY-MM-DD HH:MM:SS" wall-clock string from a day + an offset **in seconds** from
     * that day's local midnight. Goes through [LocalDate] so a sample that rolls past midnight
     * carries to the next day correctly.
     *
     * Offset-unit caveat: this expects **seconds**. The SDK reports per-metric offsets in *minutes*
     * for most intraday metrics (callers multiply by 60) and in a metric-specific unit for at least
     * one (body energy: `item.offset * unitSeconds`). Passing a raw minute offset here silently
     * yields a timestamp 60× too close to midnight — always convert to seconds at the call site.
     *
     * Throws [java.time.DateTimeException] on an invalid date — callers wrap in runCatching so one
     * bad record can't abort the sync.
     */
    fun localDateTime(year: Int, month: Int, day: Int, secondsFromMidnight: Int): String =
        LocalDate.of(year, month, day)
            .atStartOfDay()
            .plusSeconds(secondsFromMidnight.toLong())
            .format(LOCAL_DT_FMT)
}

/** Watch ints use 0 as "not measured" for several fields; surface those as null. */
internal fun Int.nonZero(): Int? = if (this != 0) this else null
