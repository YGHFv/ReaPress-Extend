package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驿站规则表里那张**现场指纹**表（定位 + 附近 WiFi）。
 *
 * 值得单独钉住的原因：它和别的表共用同一套「跟着改名链走」的查询
 * （[ExpressStationRules.lookup]），漏掉这一步的后果很隐蔽 —— 用户把两处驿站合并到一行之后，
 * 是在**那一行**上点的「获取」，指纹只挂在链尾那一个键上；链头那些记录查出来就成了「没记过」。
 * 界面上表现为「明明采过了，这一站还显示未记录」，看起来像没保存成功。
 */
class ExpressStationRulesTest {

    private val fingerprint = StationFingerprint(
        position = GeoPoint(31.76936, 117.26814),
        accuracyMeters = 25f,
        wifi = listOf("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"),
        capturedAt = 1_790_000_000_000L,
    )

    /** 合并是链式的：A → B，指纹记在 B 上，A 的记录也该查得到。 */
    @Test
    fun `指纹跟着改名链走`() {
        val rules = ExpressStationRules(
            renames = mapOf("A驿站" to "B驿站"),
            fingerprints = mapOf("B驿站" to fingerprint),
        )
        assertEquals(fingerprint, rules.fingerprintFor("A驿站"))
        assertEquals(fingerprint, rules.fingerprintFor("B驿站"))
    }

    /** 环是用户的输入错误，走到头就该停 —— 但挂在环上的那个键必须还查得到自己的指纹。 */
    @Test
    fun `环里也能查到自己的指纹`() {
        val rules = ExpressStationRules(
            renames = mapOf("A" to "B", "B" to "A"),
            fingerprints = mapOf("B" to fingerprint),
        )
        assertEquals(fingerprint, rules.fingerprintFor("A"))
    }

    @Test
    fun `没记过指纹时返回 null`() {
        val rules = ExpressStationRules(renames = mapOf("A" to "B"))
        assertNull(rules.fingerprintFor("A"))
        assertNull(rules.fingerprintFor("B"))
    }

    /**
     * 只记了指纹、没改名，同样算「动过这一行」。
     *
     * 界面靠 [ExpressStationRules.hasRule] 决定要不要显示「恢复默认」—— 漏了这一项，
     * 用户就清不掉自己在某个驿站采下的坐标（表里有数据，而界面上没有任何入口能删掉它）。
     */
    @Test
    fun `hasRule 认指纹`() {
        val rules = ExpressStationRules(fingerprints = mapOf("A" to fingerprint))
        assertTrue(rules.hasRule("A"))
        assertFalse(rules.hasRule("B"))
    }

    /** 只有指纹时规则集不算空 —— 否则设置页那一行会显示成「还没设置过」。 */
    @Test
    fun `isEmpty 认指纹`() {
        assertFalse(ExpressStationRules(fingerprints = mapOf("A" to fingerprint)).isEmpty)
        assertTrue(ExpressStationRules.EMPTY.isEmpty)
    }

    /**
     * 「空指纹」的判据 = 既没位置也没 WiFi。
     *
     * 写入侧靠它拒绝落库（`ExpressStationRuleStore.setFingerprint`）：一条什么都没采到的记录
     * 写进去，界面上会显示成「已记录」，而实际上什么都判不了 —— 那比留着「未记录」更误导。
     */
    @Test
    fun `空指纹的判据`() {
        assertTrue(StationFingerprint().isEmpty)
        // 只有采集时间不算采到东西。
        assertTrue(StationFingerprint(capturedAt = 1L).isEmpty)
        // 只有精度（没有坐标）也没有意义 —— 精度是坐标的属性。
        assertTrue(StationFingerprint(accuracyMeters = 10f).isEmpty)
        assertFalse(StationFingerprint(position = GeoPoint(1.0, 2.0)).isEmpty)
        // 只有 WiFi、没有定位（室内很常见）：**不是空** —— WiFi 本身就是证据。
        assertFalse(StationFingerprint(wifi = listOf("aa:bb:cc:dd:ee:01")).isEmpty)
    }
}
