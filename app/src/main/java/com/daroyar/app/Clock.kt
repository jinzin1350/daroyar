package com.daroyar.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Date/time helpers. Dates are "yyyy-MM-dd", times are zero-padded "HH:mm". */
object Clock {
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private fun fmt(pattern: String, tz: TimeZone): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }

    fun now(): Long = System.currentTimeMillis()

    fun dateOf(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        fmt("yyyy-MM-dd", tz).format(Date(ms))

    fun today(tz: TimeZone = TimeZone.getDefault()): String = dateOf(now(), tz)

    fun hhmm(ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        fmt("HH:mm", tz).format(Date(ms))

    /** Epoch millis of date+time in the given zone. */
    fun epoch(date: String, time: String, tz: TimeZone = TimeZone.getDefault()): Long =
        fmt("yyyy-MM-dd HH:mm", tz).parse("$date $time")!!.time

    fun addDays(date: String, n: Int): String {
        val f = fmt("yyyy-MM-dd", UTC)
        val cal = Calendar.getInstance(UTC, Locale.US)
        cal.time = f.parse(date)!!
        cal.add(Calendar.DAY_OF_MONTH, n)
        return f.format(cal.time)
    }

    fun time(h: Int, m: Int): String = String.format(Locale.US, "%02d:%02d", h, m)

    /** A fixed-offset zone, e.g. +210 minutes -> GMT+03:30 (Tehran). */
    fun zoneOfOffset(minutes: Int): TimeZone {
        val sign = if (minutes < 0) "-" else "+"
        val a = abs(minutes)
        return TimeZone.getTimeZone(String.format(Locale.US, "GMT%s%02d:%02d", sign, a / 60, a % 60))
    }

    fun offsetMinutesNow(): Int = TimeZone.getDefault().getOffset(now()) / 60000
}

/** Persian presentation helpers. */
object Fa {
    fun digits(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch in '0'..'9') sb.append('۰' + (ch - '0')) else sb.append(ch)
        }
        return sb.toString()
    }

    /** "08:05" -> "۸:۰۵" */
    fun time(t: String): String {
        val parts = t.split(":")
        if (parts.size != 2) return digits(t)
        val h = parts[0].toIntOrNull() ?: return digits(t)
        return digits("$h:${parts[1]}")
    }

    fun clock(ms: Long, tz: TimeZone = TimeZone.getDefault()): String = time(Clock.hhmm(ms, tz))

    /** Persian (Jalali) date such as «شنبه ۲۶ مهر». */
    fun date(ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        return try {
            val f = android.icu.text.SimpleDateFormat(
                "EEEE d MMMM",
                android.icu.util.ULocale("fa_IR@calendar=persian")
            )
            f.timeZone = android.icu.util.TimeZone.getTimeZone(tz.id)
            f.format(Date(ms))
        } catch (t: Throwable) {
            digits(Clock.dateOf(ms, tz))
        }
    }

    /** Jalali date for a "yyyy-MM-dd" day string. */
    fun day(date: String, tz: TimeZone = TimeZone.getDefault()): String =
        date(Clock.epoch(date, "12:00", tz), tz)

    fun ago(ms: Long): String {
        if (ms <= 0L) return "هرگز"
        val diff = Clock.now() - ms
        val min = diff / 60000
        return when {
            min < 1 -> "همین الان"
            min < 60 -> digits("$min") + " دقیقه پیش"
            min < 60 * 24 -> digits("${min / 60}") + " ساعت پیش"
            else -> digits("${min / (60 * 24)}") + " روز پیش"
        }
    }
}
