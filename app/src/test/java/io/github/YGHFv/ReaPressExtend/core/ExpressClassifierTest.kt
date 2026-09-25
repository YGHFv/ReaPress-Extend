package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressClassifierTest {

    private val rule = ExpressRule()

    private fun classify(text: String, pkg: String = "com.cainiao.wireless") =
        ExpressClassifier.classify(pkg, text, rule)

    @Test
    fun `菜鸟到站通知判定为快递`() {
        val verdict = classify("您的包裹已到菜鸟驿站(杭州文一西路店)，取件码 8-2-3021，请及时取件")
        assertTrue(verdict.isExpress)
        assertTrue(verdict.confidence >= ExpressRule.DEFAULT_THRESHOLD)
        assertTrue(verdict.matchedKeywords.contains("取件码"))
    }

    @Test
    fun `顺丰派送通知判定为快递`() {
        val verdict = classify("您的顺丰快递 SF1234567890123 正在派送中")
        assertTrue(verdict.isExpress)
    }

    @Test
    fun `纯营销推送不判定为快递`() {
        // 没有任何快递专属关键词
        val verdict = classify("双十一大促开始啦，全场五折起")
        assertFalse(verdict.isExpress)
        assertEquals(0, verdict.confidence)
    }

    @Test
    fun `只有电商通用词时不判定`() {
        // 「订单」「发货」这类词刻意不在关键词表里 —— 否则营销推送会被误拦
        val verdict = classify("您的订单已发货，点击查看详情")
        assertFalse(verdict.isExpress)
    }

    @Test
    fun `排除词优先于关键词`() {
        val custom = ExpressRule(excludeKeywords = setOf("广告"))
        val verdict = ExpressClassifier.classify(
            "com.cainiao.wireless",
            "【广告】您的快递已到站，取件码 1234",
            custom,
        )
        assertFalse(verdict.isExpress)
        assertEquals("广告", verdict.excludedBy)
    }

    @Test
    fun `自定义关键词参与判定`() {
        val custom = ExpressRule(extraKeywords = setOf("闪送"))
        val verdict = ExpressClassifier.classify("com.cainiao.wireless", "您的闪送已到达", custom)
        assertTrue(verdict.isExpress)
    }

    @Test
    fun `结构化字段提高置信度`() {
        // 同样命中「包裹」，带取件码的应当分更高
        val plain = classify("您的包裹已发出")
        val rich = classify("您的包裹已到驿站，取件码 8-2-3021")
        assertTrue(rich.confidence > plain.confidence)
    }

    @Test
    fun `阈值可调高以收紧判定`() {
        val strict = ExpressRule(confidenceThreshold = 95)
        val verdict = ExpressClassifier.classify(
            "com.cainiao.wireless",
            "您的包裹已发出",
            strict,
        )
        assertFalse(verdict.isExpress)
    }

    @Test
    fun `空文本不判定`() {
        val verdict = classify("")
        assertFalse(verdict.isExpress)
        assertEquals(0, verdict.confidence)
    }

    @Test
    fun `置信度上限 100`() {
        val verdict = classify("顺丰快递 包裹 运单 SF1234567890123 取件码 A1 菜鸟驿站 已到站 派送")
        assertTrue(verdict.confidence <= 100)
    }

    // ---- 来源过滤 ----

    @Test
    fun `白名单内的包名被受理`() {
        assertTrue(ExpressClassifier.isSourceAllowed("com.cainiao.wireless", rule))
        assertTrue(ExpressClassifier.isSourceAllowed("com.xunmeng.pinduoduo", rule))
        assertTrue(ExpressClassifier.isSourceAllowed("com.taobao.taobao", rule))
    }

    @Test
    fun `白名单外的包名不受理`() {
        assertFalse(ExpressClassifier.isSourceAllowed("com.tencent.mm", rule))
        assertFalse(ExpressClassifier.isSourceAllowed("io.github.YGHFv.ReaPressExtend", rule))
    }

    @Test
    fun `短信来源受独立开关控制`() {
        assertTrue(ExpressClassifier.isSourceAllowed(ExpressClassifier.SMS_PACKAGE, rule))
        assertFalse(
            ExpressClassifier.isSourceAllowed(
                ExpressClassifier.SMS_PACKAGE,
                ExpressRule(handleSms = false),
            ),
        )
    }

    @Test
    fun `关闭短信不影响 App 来源`() {
        val noSms = ExpressRule(handleSms = false)
        assertFalse(ExpressClassifier.isSourceAllowed(ExpressClassifier.SMS_PACKAGE, noSms))
        assertTrue(ExpressClassifier.isSourceAllowed("com.cainiao.wireless", noSms))
    }

    @Test
    fun `额外放行来源优先于所有开关`() {
        // 用途：debug 构建放行 com.android.shell 以便用 adb 造通知做真机验证。
        // 它必须无视白名单与短信开关，否则 shell 通知（pkg=com.android.shell）
        // 会因为不在白名单里而被早退挡掉，验证链就跑不通。
        val rule = ExpressRule(
            sourcePackages = emptySet(),
            handleSms = false,
            extraAllowedSources = setOf("com.android.shell"),
        )
        assertTrue(ExpressClassifier.isSourceAllowed("com.android.shell", rule))
        assertFalse(ExpressClassifier.isSourceAllowed("com.tencent.mm", rule))
    }

    @Test
    fun `未配置额外来源时行为不变`() {
        val rule = ExpressRule()
        assertTrue(rule.extraAllowedSources.isEmpty())
        assertFalse(ExpressClassifier.isSourceAllowed("com.android.shell", rule))
    }

    @Test
    fun `命中关键词按长度倒序`() {
        // 长词优先，界面上展示「菜鸟驿站」比展示「驿站」更有解释力
        val verdict = classify("您的包裹已到菜鸟驿站，取件码 8-2-3021")
        val keywords = verdict.matchedKeywords
        assertNotNull(keywords)
        assertTrue(keywords.isNotEmpty())
        assertEquals(keywords.sortedByDescending { it.length }, keywords)
    }
}
