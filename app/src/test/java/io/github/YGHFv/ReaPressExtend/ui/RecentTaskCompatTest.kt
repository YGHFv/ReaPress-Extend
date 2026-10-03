package io.github.YGHFv.ReaPressExtend.ui

import android.app.ActivityManager
import io.github.YGHFv.ReaPressExtend.testing.withAndroidSdk
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class RecentTaskCompatTest {
    @Test
    fun android26UsesLegacyTaskId() = withAndroidSdk(26) {
        val wrong = task(legacyId = 1, currentId = 42)
        val expected = task(legacyId = 42, currentId = 2)
        assertSame(expected, RecentTaskCompat.findTask(listOf(wrong, expected), 42))
    }

    @Test
    fun android28UsesLegacyTaskId() = withAndroidSdk(28) {
        val expected = task(legacyId = 42, currentId = 2)
        assertSame(expected, RecentTaskCompat.findTask(listOf(task(1, 42), expected), 42))
    }

    @Test
    fun android29UsesCurrentTaskId() = withAndroidSdk(29) {
        val expected = task(legacyId = 2, currentId = 42)
        assertSame(expected, RecentTaskCompat.findTask(listOf(task(42, 1), expected), 42))
    }

    @Test
    fun disappearedTaskDoesNotPreventFindingAnotherTask() = withAndroidSdk(35) {
        val disappeared = mock(ActivityManager.AppTask::class.java)
        `when`(disappeared.taskInfo).thenThrow(IllegalArgumentException("removed"))
        val expected = task(1, 42)
        assertSame(expected, RecentTaskCompat.findTask(listOf(disappeared, expected), 42))
    }

    @Test
    fun singleTaskWithMissingInfoIsSafeFallback() = withAndroidSdk(26) {
        val only = mock(ActivityManager.AppTask::class.java)
        assertSame(only, RecentTaskCompat.findTask(listOf(only), 42))
    }

    @Test
    fun ambiguousMultipleTasksAreNotHiddenArbitrarily() = withAndroidSdk(35) {
        assertNull(RecentTaskCompat.findTask(listOf(task(1, 1), task(2, 2)), 42))
    }

    @Test
    fun emptyTasksHaveNoFallback() {
        assertNull(RecentTaskCompat.findTask(emptyList(), 42))
    }

    @Suppress("DEPRECATION")
    private fun task(legacyId: Int, currentId: Int): ActivityManager.AppTask {
        val info = mock(ActivityManager.RecentTaskInfo::class.java)
        info.id = legacyId
        info.taskId = currentId
        return mock(ActivityManager.AppTask::class.java).also { `when`(it.taskInfo).thenReturn(info) }
    }
}
