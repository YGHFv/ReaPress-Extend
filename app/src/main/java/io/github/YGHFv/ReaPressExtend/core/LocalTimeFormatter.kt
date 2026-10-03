package io.github.YGHFv.ReaPressExtend.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/** Immutable formatters are safe across log/UI threads; defaults are re-read after configuration changes. */
class LocalTimeFormatter(private val pattern: String) {
    private data class Cached(val locale: Locale, val formatter: DateTimeFormatter)
    @Volatile private var cached: Cached? = null

    fun format(at: Long): String {
        val locale = Locale.getDefault()
        val current = cached?.takeIf { it.locale == locale } ?: Cached(
            locale,
            DateTimeFormatter.ofPattern(pattern, locale).withDecimalStyle(DecimalStyle.of(locale)),
        ).also { cached = it }
        return current.formatter.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
    }
}
