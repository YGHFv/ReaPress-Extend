package io.github.YGHFv.ReaPressExtend.ui

import android.app.ActivityManager
import android.os.Build

internal object RecentTaskCompat {
    fun findTask(tasks: List<ActivityManager.AppTask>, taskId: Int): ActivityManager.AppTask? =
        tasks.firstOrNull { task ->
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@firstOrNull false
            val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else {
                @Suppress("DEPRECATION")
                info.id
            }
            id == taskId
        } ?: tasks.singleOrNull()
}
