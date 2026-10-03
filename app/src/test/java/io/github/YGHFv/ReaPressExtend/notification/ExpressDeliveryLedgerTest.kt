/*
 * Copyright (C) 2026 YGHFv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.YGHFv.ReaPressExtend.notification

import io.github.YGHFv.ReaPressExtend.core.ExpressRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双链路去重账本的键语义。
 *
 * 这里钉住的是那条**最容易写反**的规则：去重只能压「同一条事件的重复投递」，
 * 不能压「同一个包裹的下一次更新」。写反的后果是状态推进被静默吞掉 ——
 * 界面上看不出任何异常，只是那件包裹永远停在旧句子上。
 */
class ExpressDeliveryLedgerTest {

    private val ledger = DeliveryLedger()

    private fun claim(record: ExpressRecord): Boolean =
        ledger.deliver(ExpressDeliveryLedger.keyOf(record)) { true } == DeliveryLedger.Result.POSTED

    private fun record(tracking: String?, code: String?, text: String) = ExpressRecord(
        sourcePackage = "com.cainiao.wireless",
        rawText = text,
        trackingNumber = tracking,
        pickupCode = code,
    )

    @Test
    fun `同一条通知第二次不再投递`() {
        val first = record("773000000000001", null, "您的包裹已到站，请凭取件码 1-1-2001 取件")
        val second = record("773000000000001", null, "您的包裹已到站，请凭取件码 1-1-2001 取件")
        assertTrue(claim(first))
        assertFalse(claim(second))
    }

    @Test
    fun `同一个包裹的新动态照常投递`() {
        val arrived = record("773000000000001", null, "您的包裹已到站")
        val picked = record("773000000000001", null, "您的包裹已签收")
        assertTrue(claim(arrived))
        assertTrue(claim(picked))
    }

    @Test
    fun `不同包裹互不影响`() {
        assertTrue(claim(record("773000000000001", null, "已到站")))
        assertTrue(claim(record("773000000000002", null, "已到站")))
    }

    @Test
    fun `键由主键与正文共同决定`() {
        val a = record("773000000000001", null, "已到站")
        assertEquals(ExpressDeliveryLedger.keyOf(a), ExpressDeliveryLedger.keyOf(a.copy()))
        // 正文不同 = 不同事件。
        assertNotEquals(ExpressDeliveryLedger.keyOf(a), ExpressDeliveryLedger.keyOf(a.copy(rawText = "已签收")))
        // 主键不同 = 不同包裹。
        assertNotEquals(
            ExpressDeliveryLedger.keyOf(a),
            ExpressDeliveryLedger.keyOf(a.copy(trackingNumber = "773000000000002")),
        )
    }
}
