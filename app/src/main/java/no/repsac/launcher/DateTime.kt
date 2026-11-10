package no.repsac.launcher

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Clock and date text in the launcher's language, in 12- or 24-hour format (Settings → Clock). */
class DateTime(private val context: Context) {

    private val locale: Locale = context.resources.configuration.locales[0]   // the launcher's language
    private val dateFormat = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
    private val timeFormat24 = SimpleDateFormat("HH:mm", locale)
    private val timeFormat12 = SimpleDateFormat("h:mm", locale)

    private val prefs = Prefs(context)

    fun time(now: Date): String {
        val use24 = when (prefs.clockFormat) {
            "24" -> true
            "12" -> false
            else -> DateFormat.is24HourFormat(context)   // follow the TV
        }
        return (if (use24) timeFormat24 else timeFormat12).format(now)
    }

    /** E.g. «Sunday, November 1» or «Søndag 1. november» */
    fun date(now: Date): String = dateFormat.format(now).replaceFirstChar { it.titlecase(locale) }

    companion object {
        /** Milliseconds until the minute changes, so the clock updates exactly on time */
        fun untilNextMinute(now: Date) = 60_000 - now.time % 60_000 + 50
    }
}
