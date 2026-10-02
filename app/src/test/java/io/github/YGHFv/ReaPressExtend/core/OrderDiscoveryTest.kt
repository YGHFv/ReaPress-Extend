package io.github.YGHFv.ReaPressExtend.core

import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDiscoveryTest {
    @Test
    fun `超过五个订单时未处理订单留到下一批`() {
        val fixture = Fixture()
        val orders = (1..6).map { order("order-$it") }
        val first = fixture.run(orders)

        assertEquals(OrderDiscovery.Outcome(5, 5, 5, false), first)
        assertEquals(orders.take(5).map { it.orderId }, fixture.queried)
        assertEquals(FetchRequestLedger.Snapshot(0, 5, 0), fixture.ledger.snapshot())
        assertTrue(fixture.ledger.isEligible("order-6", fixture.now))

        val second = fixture.run(orders)
        assertEquals(OrderDiscovery.Outcome(1, 1, 1, false), second)
        assertEquals(orders.map { it.orderId }, fixture.queried)
        assertEquals(FetchRequestLedger.Snapshot(0, 6, 0), fixture.ledger.snapshot())
    }

    @Test
    fun `失败订单冷却结束也不能挤掉尚未查询的订单`() {
        val fixture = Fixture()
        val orders = (1..6).map { order("order-$it") }
        fixture.fetchParcel = { null }
        fixture.run(orders)
        fixture.now += 1_000L
        fixture.queried.clear()
        fixture.fetchParcel = ::parcel

        val result = fixture.run(orders)

        assertEquals(5, result.attempted)
        assertEquals("order-6", fixture.queried.first())
        assertEquals(listOf("order-6", "order-1", "order-2", "order-3", "order-4"), fixture.queried)
        assertTrue(fixture.ledger.isEligible("order-5", fixture.now))
    }

    @Test
    fun `重试优先处理最久未尝试的失败订单`() {
        val fixture = Fixture()
        val orders = (1..6).map { order("order-$it") }
        orders.forEachIndexed { index, candidate ->
            fixture.ledger.begin(candidate.orderId, fixture.now)
            fixture.ledger.finish(candidate.orderId, fixture.now + index, success = false)
        }
        fixture.now += 2_000L

        fixture.run(orders.reversed())

        assertEquals(orders.take(5).map { it.orderId }, fixture.queried)
        assertTrue(fixture.ledger.isEligible("order-6", fixture.now))
    }

    @Test
    fun `结束订单和重复订单不消耗查询名额`() {
        val fixture = Fixture()
        val result = fixture.run(
            listOf(
                TaobaoOrder("settled", statusText = "交易成功"),
                order("first"),
                order("first"),
                order("second"),
            ),
        )

        assertEquals(OrderDiscovery.Outcome(2, 2, 2, false), result)
        assertEquals(listOf("first", "second"), fixture.queried)
        assertEquals(1, fixture.paced)
    }

    @Test
    fun `空列表不查询不等待且不改变状态`() {
        val fixture = Fixture()

        assertEquals(OrderDiscovery.Outcome(0, 0, 0, false), fixture.run(emptyList()))
        assertTrue(fixture.queried.isEmpty())
        assertEquals(0, fixture.paced)
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 0), fixture.ledger.snapshot())
    }

    @Test
    fun `空物流页不会永远标记已查且失败订单按冷却重试`() {
        val fixture = Fixture()
        var available = false
        fixture.fetchParcel = { candidate ->
            if (candidate.orderId == "first" && !available) null else parcel(candidate)
        }
        val orders = listOf(order("first"), order("second"))
        assertEquals(OrderDiscovery.Outcome(2, 2, 1, false), fixture.run(orders))
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 1), fixture.ledger.snapshot())

        available = true
        fixture.now += 999L
        assertEquals(0, fixture.run(orders).attempted)
        fixture.now++
        assertEquals(1, fixture.run(orders).attempted)
        assertEquals(listOf("first", "second", "first"), fixture.queried)
        assertEquals(FetchRequestLedger.Snapshot(0, 2, 0), fixture.ledger.snapshot())
    }

    @Test
    fun `单个物流查询异常不丢后续订单也不留下占位`() {
        val fixture = Fixture()
        fixture.fetchParcel = { candidate ->
            if (candidate.orderId == "first") error("synthetic SSR failure")
            parcel(candidate)
        }

        val result = fixture.run(listOf(order("first"), order("second")))
        assertEquals(OrderDiscovery.Outcome(2, 2, 1, false), result)
        assertEquals(1, fixture.errors.size)
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 1), fixture.ledger.snapshot())
    }

    @Test
    fun `轨迹提交异常释放订单并等待冷却`() {
        val fixture = Fixture()
        fixture.onParcel = { _, _, _ -> error("synthetic handoff failure") }
        fixture.run(listOf(order("first")))

        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), fixture.ledger.snapshot())
        assertEquals(1, fixture.errors.size)
        fixture.now += 1_000L
        assertTrue(fixture.ledger.isEligible("first", fixture.now))
    }

    @Test
    fun `订单只在异步轨迹处理成功后完成且失败可重试`() {
        val fixture = Fixture()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        fixture.onParcel = { _, _, complete -> callbacks.add(complete) }
        val orders = listOf(order("first"))
        fixture.run(orders)

        assertEquals(FetchRequestLedger.Snapshot(1, 0, 0), fixture.ledger.snapshot())
        assertEquals(0, fixture.run(orders).attempted)
        callbacks.single()(false)
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), fixture.ledger.snapshot())
        fixture.now += 1_000L
        assertEquals(1, fixture.run(orders).attempted)
        callbacks.last()(true)
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), fixture.ledger.snapshot())
        assertEquals(0, fixture.run(orders).attempted)
    }

    @Test
    fun `批次开始前风控不占用任何订单`() {
        val fixture = Fixture()
        fixture.blocked = true
        val orders = listOf(order("first"), order("second"))

        assertEquals(OrderDiscovery.Outcome(2, 0, 0, true), fixture.run(orders))
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 0), fixture.ledger.snapshot())
        fixture.blocked = false
        assertEquals(2, fixture.run(orders).attempted)
    }

    @Test
    fun `风控中断后未执行订单仍能在下一批处理`() {
        val fixture = Fixture()
        val orders = listOf(order("first"), order("second"), order("third"))
        fixture.onParcel = { _, _, complete ->
            complete(true)
            fixture.blocked = true
        }

        assertEquals(OrderDiscovery.Outcome(3, 1, 1, true), fixture.run(orders))
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), fixture.ledger.snapshot())
        assertTrue(fixture.ledger.isEligible("second", fixture.now))
        assertTrue(fixture.ledger.isEligible("third", fixture.now))

        fixture.blocked = false
        fixture.onParcel = { _, _, complete -> complete(true) }
        assertEquals(OrderDiscovery.Outcome(2, 2, 2, false), fixture.run(orders))
        assertEquals(listOf("first", "second", "third"), fixture.queried)
    }

    @Test
    fun `间隔期间触发风控不会提前占用下一单`() {
        val fixture = Fixture()
        fixture.onPace = { fixture.blocked = true }

        val result = fixture.run(listOf(order("first"), order("second")))
        assertEquals(OrderDiscovery.Outcome(2, 1, 1, true), result)
        assertEquals(listOf("first"), fixture.queried)
        assertTrue(fixture.ledger.isEligible("second", fixture.now))
    }

    @Test
    fun `中断批次不会继续访问网络或占用剩余订单`() {
        val fixture = Fixture()
        fixture.onPace = { throw InterruptedException("synthetic interruption") }
        try {
            assertThrows(InterruptedException::class.java) {
                fixture.run(listOf(order("first"), order("second")))
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(listOf("first"), fixture.queried)
            assertTrue(fixture.ledger.isEligible("second", fixture.now))
            assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), fixture.ledger.snapshot())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `查询中取消会释放当前订单而不占用后续订单`() {
        val fixture = Fixture()
        fixture.fetchParcel = { throw CancellationException("synthetic cancellation") }

        assertThrows(CancellationException::class.java) {
            fixture.run(listOf(order("first"), order("second")))
        }
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), fixture.ledger.snapshot())
        assertTrue(fixture.ledger.isEligible("second", fixture.now))
    }

    @Test
    fun `实际请求队列的空结果回执让订单可以重新发现`() {
        val fixture = Fixture()
        val traceLedger = FetchRequestLedger(cooldownMillis = 1_000L)
        val pending = ArrayDeque<Runnable>()
        val queue = FetchRequestQueue<String>(
            executor = Executor { pending.addLast(it) },
            ledger = traceLedger,
            clock = { fixture.now },
            pace = {},
            isBlocked = { fixture.blocked },
            onError = { fixture.errors.add(it) },
        )
        var response: String? = null
        fixture.onParcel = { _, discovered, complete ->
            queue.request(
                key = discovered.mailNo,
                fetch = { response },
                deliver = {},
                onComplete = complete,
            )
        }
        val orders = listOf(order("first"))
        fixture.run(orders)
        pending.removeFirst().run()

        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), fixture.ledger.snapshot())
        assertEquals(FetchRequestLedger.Snapshot(0, 0, 1), traceLedger.snapshot())
        fixture.now += 1_000L
        response = "trace"
        assertEquals(1, fixture.run(orders).attempted)
        pending.removeFirst().run()
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), fixture.ledger.snapshot())
        assertEquals(0, fixture.run(orders).attempted)
        assertEquals(listOf("first", "first"), fixture.queried)
    }

    @Test
    fun `轨迹已有成功结果时订单完成而不重复查询轨迹`() {
        val fixture = Fixture()
        val traceLedger = FetchRequestLedger(cooldownMillis = 1_000L)
        traceLedger.begin("shared-tracking", fixture.now)
        traceLedger.finish("shared-tracking", fixture.now, success = true)
        val queue = FetchRequestQueue<String>(
            executor = Executor { error("must not enqueue an already successful trace") },
            ledger = traceLedger,
            clock = { fixture.now },
            pace = {},
            isBlocked = { false },
            onError = { fixture.errors.add(it) },
        )
        fixture.fetchParcel = { SsrParcel("shared-tracking") }
        fixture.onParcel = { _, discovered, complete ->
            assertFalse(
                queue.request(
                    key = discovered.mailNo,
                    fetch = { error("must not fetch an already successful trace") },
                    deliver = {},
                    onComplete = complete,
                ),
            )
        }
        fixture.run(listOf(order("first")))

        assertTrue(fixture.errors.isEmpty())
        assertEquals(FetchRequestLedger.Snapshot(0, 1, 0), fixture.ledger.snapshot())
    }

    private class Fixture {
        var now = 10_000L
        var blocked = false
        var paced = 0
        var onPace: () -> Unit = {}
        var fetchParcel: (TaobaoOrder) -> SsrParcel? = ::parcel
        var onParcel: (TaobaoOrder, SsrParcel, (Boolean) -> Unit) -> Unit = { _, _, complete -> complete(true) }
        val queried = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val ledger = FetchRequestLedger(cooldownMillis = 1_000L)
        private val discovery = OrderDiscovery(
            ledger = ledger,
            clock = { now },
            pace = { paced++; onPace() },
            isBlocked = { blocked },
            onError = { errors.add(it) },
        )

        fun run(orders: List<TaobaoOrder>): OrderDiscovery.Outcome = discovery.discover(
            orders = orders,
            limit = 5,
            fetchParcel = { queried.add(it.orderId); fetchParcel(it) },
            onParcel = onParcel,
        )
    }

    private companion object {
        fun order(orderId: String): TaobaoOrder =
            TaobaoOrder(orderId, tradeStatus = "WAIT_BUYER_CONFIRM_GOODS")

        fun parcel(order: TaobaoOrder): SsrParcel = SsrParcel("tracking-${order.orderId}")
    }
}
