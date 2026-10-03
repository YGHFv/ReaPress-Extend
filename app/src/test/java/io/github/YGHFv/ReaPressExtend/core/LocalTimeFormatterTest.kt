package io.github.YGHFv.ReaPressExtend.core

import io.github.YGHFv.ReaPressExtend.logging.ModuleLogBuffer
import io.github.YGHFv.ReaPressExtend.notification.ExpressNotificationLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalTimeFormatterTest {
    private val timestamp = 1_790_975_123_456L

    @Test
    fun localeChangeUsesCurrentLanguageAndDigits() = withDefaults {
        val formatter = LocalTimeFormatter("MMM dd HH:mm:ss.SSS")
        for (locale in listOf(Locale.US, Locale.FRANCE, Locale.forLanguageTag("ar-EG"))) {
            Locale.setDefault(locale)
            assertEquals(SimpleDateFormat("MMM dd HH:mm:ss.SSS", locale).format(Date(timestamp)), formatter.format(timestamp))
        }
    }

    @Test
    fun timezoneChangeDoesNotKeepOldOffset() = withDefaults {
        val formatter = LocalTimeFormatter("MM-dd HH:mm")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val utc = formatter.format(timestamp)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        assertNotEquals(utc, formatter.format(timestamp))
        assertEquals(SimpleDateFormat("MM-dd HH:mm").format(Date(timestamp)), formatter.format(timestamp))
    }

    @Test
    fun sharedFormatterIsSafeAcrossThreads() = withDefaults {
        val formatter = LocalTimeFormatter("MM-dd HH:mm:ss.SSS")
        val times = (0 until 300).map { timestamp + it * 987_654L }
        val expected = times.associateWith { SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date(it)) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            pool.invokeAll(times.map { at -> Callable {
                repeat(20) { assertEquals(expected.getValue(at), formatter.format(at)) }
            } }).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun realLogAndAuditFormattingFollowUpdatedDefaults() = withDefaults {
        for (locale in listOf(Locale.US, Locale.forLanguageTag("ar-EG"))) {
            Locale.setDefault(locale)
            assertEquals(SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date(timestamp)), ModuleLogBuffer.formatTime(timestamp))
            assertEquals(SimpleDateFormat("MM-dd HH:mm:ss").format(Date(timestamp)), ExpressNotificationLog.formatTime(timestamp))
        }
    }

    private fun withDefaults(block: () -> Unit) {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }
}
