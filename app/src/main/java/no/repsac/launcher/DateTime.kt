package no.repsac.launcher

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Clock and date text in the TV's language and 12/24-hour setting. */
class DateTime(private val context: Context) {

    private val locale: Locale = Locale.getDefault()
    private val dateFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    private val timeFormat24 = SimpleDateFormat("HH:mm", locale)
    private val timeFormat12 = SimpleDateFormat("h:mm", locale)

    fun time(now: Date): String =
        (if (DateFormat.is24HourFormat(context)) timeFormat24 else timeFormat12).format(now)

    /** E.g. «Sunday, November 1» or «Søndag 1. november» */
    fun date(now: Date): String = dateFormat.format(now).replaceFirstChar { it.titlecase(locale) }

    companion object {
        /** Milliseconds until the minute changes, so the clock updates exactly on time */
        fun untilNextMinute(now: Date) = 60_000 - now.time % 60_000 + 50
    }
}
