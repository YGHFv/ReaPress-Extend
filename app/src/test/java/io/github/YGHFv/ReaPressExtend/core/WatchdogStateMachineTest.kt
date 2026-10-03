package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogStateMachineTest {
    @Test
    fun `首次安装登记受监控的尝试`() {
        val transition = WatchdogStateMachine.beforeInstall(WatchdogStateMachine.State(), null)
        assertEquals(WatchdogStateMachine.Decision.Install(1), transition.decision)
        assertEquals(WatchdogStateMachine.State(attempt = 1), transition.state)
    }

    @Test
    fun `连续两次没有存活即熔断且重复启动不继续增长`() {
        val first = WatchdogStateMachine.beforeInstall(WatchdogStateMachine.State(), null)
        val second = WatchdogStateMachine.beforeInstall(first.state, null)
        assertEquals(1, second.state.failures)
        assertTrue(second.decision is WatchdogStateMachine.Decision.Install)
        val third = WatchdogStateMachine.beforeInstall(second.state, null)
        assertEquals(2, third.state.failures)
        assertTrue(third.decision is WatchdogStateMachine.Decision.Refuse)
        assertEquals(third, WatchdogStateMachine.beforeInstall(third.state, null))
    }

    @Test
    fun `存活标记打断连续失败并保留已消费复位ID`() {
        val state = WatchdogStateMachine.State(attempt = 8, ok = 6, failures = 1, consumedResetId = "reset-1")
        val survived = WatchdogStateMachine.survived(state)
        assertEquals(8, survived.ok)
        assertEquals(0, survived.failures)
        assertEquals("reset-1", survived.consumedResetId)
        assertEquals(0, WatchdogStateMachine.beforeInstall(survived, "reset-1").state.failures)
    }

    @Test
    fun `人工复位清除旧失败并开启新受监控尝试`() {
        val tripped = WatchdogStateMachine.State(attempt = 5, ok = 2, failures = 3, disabled = true, reason = "old")
        val reset = WatchdogStateMachine.beforeInstall(tripped, "reset-1")
        assertEquals(WatchdogStateMachine.Decision.Install(1), reset.decision)
        assertEquals(WatchdogStateMachine.State(attempt = 1, consumedResetId = "reset-1"), reset.state)
    }

    @Test
    fun `同一个远端复位ID跨重启只生效一次之后仍会熔断`() {
        val reset = WatchdogStateMachine.beforeInstall(WatchdogStateMachine.State(disabled = true), "reset-1")
        val retry = WatchdogStateMachine.beforeInstall(reset.state, "reset-1")
        val refused = WatchdogStateMachine.beforeInstall(retry.state, "reset-1")
        assertTrue(refused.decision is WatchdogStateMachine.Decision.Refuse)
        assertTrue(refused.state.disabled)
        assertEquals("reset-1", refused.state.consumedResetId)
    }

    @Test
    fun `再次人工点击的新ID可以在新熔断之后再尝试`() {
        val state = WatchdogStateMachine.State(attempt = 2, failures = 2, disabled = true, consumedResetId = "reset-1")
        val reset = WatchdogStateMachine.beforeInstall(state, "reset-2")
        assertFalse(reset.state.disabled)
        assertEquals("reset-2", reset.state.consumedResetId)
        assertEquals(WatchdogStateMachine.Decision.Install(1), reset.decision)
    }

    @Test
    fun `没有复位或无效ID不能解除熔断`() {
        val state = WatchdogStateMachine.State(disabled = true, reason = "tripped")
        for (request in listOf(null, "", " ", "a".repeat(129))) {
            assertEquals(state, WatchdogStateMachine.beforeInstall(state, request).state)
        }
    }

    @Test
    fun `已熔断状态不能被迟到存活标记解除`() {
        val state = WatchdogStateMachine.State(attempt = 2, failures = 2, disabled = true, reason = "tripped")
        assertEquals(state, WatchdogStateMachine.survived(state))
    }

    @Test
    fun `计数溢出仍留下可识别的新失败尝试`() {
        val state = WatchdogStateMachine.State(attempt = Int.MAX_VALUE, ok = Int.MAX_VALUE)
        val started = WatchdogStateMachine.beforeInstall(state, null)
        assertEquals(1, started.state.attempt)
        assertEquals(0, started.state.ok)
        assertEquals(1, WatchdogStateMachine.beforeInstall(started.state, null).state.failures)
    }
}
