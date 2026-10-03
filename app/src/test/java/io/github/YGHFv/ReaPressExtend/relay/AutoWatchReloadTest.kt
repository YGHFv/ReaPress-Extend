package io.github.YGHFv.ReaPressExtend.relay

import android.content.Intent
import io.github.YGHFv.ReaPressExtend.testing.ObjectStateScope
import io.github.YGHFv.ReaPressExtend.testing.withAndroidTestRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock

class AutoWatchReloadTest {
    @Test
    fun `连续恢复请求等待旧循环退出且取消中间重启避免并行轮查`() = withAndroidTestRuntime {
        val service = mock(AutoWatchService::class.java, CALLS_REAL_METHODS)
        doThrow(SecurityException("synthetic notification unavailable"))
            .`when`(service).getSystemService(android.content.Context.NOTIFICATION_SERVICE)
        val intent = mock(Intent::class.java)
        doReturn(AutoWatchService.ACTION_RELOAD).`when`(intent).action
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val mutex = Mutex()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val exited = AtomicBoolean(false)
            val original = scope.launch {
                mutex.withLock {
                    started.countDown()
                    try {
                        check(release.await(10, TimeUnit.SECONDS))
                    } finally {
                        exited.set(true)
                    }
                }
            }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS))
                ObjectStateScope().use { state ->
                    state.set(service, "scope", scope)
                    state.set(service, "loopMutex", mutex)
                    state.set(service, "loop", original)
                    service.onStartCommand(intent, 0, 1)
                    val first = loopOf(service)
                    service.onStartCommand(intent, 0, 2)
                    val second = loopOf(service)
                    assertTrue(original.isCancelled)
                    assertTrue(first.isCancelled)
                    assertTrue(second.isActive)
                    assertFalse(exited.get())
                    assertTrue(mutex.isLocked)
                    second.cancel()
                    release.countDown()
                    runBlocking {
                        withTimeout(5_000L) {
                            original.join()
                            first.join()
                            second.join()
                        }
                    }
                    assertTrue(exited.get())
                }
            } finally {
                release.countDown()
                scope.cancel()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun loopOf(service: AutoWatchService): kotlinx.coroutines.Job =
        AutoWatchService::class.java.getDeclaredField("loop").apply { isAccessible = true }
            .get(service) as kotlinx.coroutines.Job
}
