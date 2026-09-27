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

package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驿站名身份判定的单测。
 *
 * 样本照 2026-09-26 真机 `reapress_records` 里的两条记录改写：`阳光花园菜鸟驿站` /
 * `阳光23号楼109阳光花园菜鸟驿站` —— 用户当时看到的就是同一个驿站裂成两张卡片。
 *
 * 地名店名换成了假名（仓库公开），但**长度与后缀关系照原样保留**，`是后缀`这个前提
 * 没被动过，所以断言的效力不变。
 */
class ExpressStationNameTest {

    // ------------------------------------------------------------ 归一化

    @Test
    fun `品牌前缀和外层括号被剥掉`() {
        assertEquals("临河阳光花园店", ExpressStationName.normalize("菜鸟驿站(临河阳光花园店)"))
        assertEquals("临河阳光花园店", ExpressStationName.normalize("菜鸟驿站（临河阳光花园店）"))
        // 通知里常见「无括号」写法
        assertEquals("临河阳光花园店", ExpressStationName.normalize("菜鸟驿站临河阳光花园店"))
        assertEquals("临河阳光花园店", ExpressStationName.normalize("驿站(临河阳光花园店)"))
        // 本来就没包装的名字保持原样 —— 归一化必须是幂等的
        assertEquals("临河阳光花园店", ExpressStationName.normalize("临河阳光花园店"))
    }

    @Test
    fun `归一化是幂等的`() {
        for (raw in listOf("菜鸟驿站(临河阳光花园店)", "临河阳光花园店", "驿站(A店)")) {
            val once = ExpressStationName.normalize(raw)
            assertEquals(once, ExpressStationName.normalize(once))
        }
    }

    @Test
    fun `空白不参与驿站身份`() {
        assertEquals(
            ExpressStationName.normalize("菜鸟驿站(临河阳光花园店)"),
            ExpressStationName.normalize(" 菜鸟驿站 （ 临河阳光花园店 ） "),
        )
    }

    @Test
    fun `括号不在末尾时不剥`() {
        // `菜鸟驿站(A区)东门` 里的括号是名字的一部分。剥了会把它错认成 A 区 —— 另一个地点。
        assertEquals("(A区)东门", ExpressStationName.normalize("菜鸟驿站(A区)东门"))
    }

    @Test
    fun `整个名字就是品牌时退回原文`() {
        // 剥没了就退回原文：那至少还是用户看到的那串字，而且它确实没说是哪家店
        assertEquals("菜鸟驿站", ExpressStationName.normalize("菜鸟驿站"))
    }

    @Test
    fun `空名返回空串`() {
        // 空串由调用方兜底（分组那边归到「未知取件地点」），这里不替它决定
        assertEquals("", ExpressStationName.normalize(null))
        assertEquals("", ExpressStationName.normalize("   "))
    }

    @Test
    fun `只说了地点类型的词不算地址`() {
        // 淘宝的通知只写「您购买的宝贝已送达代收点」，抽出来就是这三个字。它没说在哪 ——
        // 拿它当驿站名会在首页凭空多出一个叫「代收点」的分组（2026-09-26 真机），
        // 还会按「只填空不覆盖」把宿主富化的真名挡在外面。所以一律当作「不知道在哪」。
        assertEquals("", ExpressStationName.normalize("代收点"))
        assertEquals("", ExpressStationName.normalize(" 代收点 "))
        assertEquals("", ExpressStationName.normalize("快递柜"))
        assertFalse(ExpressStationName.hasLocation("代收点"))

        // 裸的 `驿站` 同理 —— 真机里只以两种方式出现：句子里那句 `…已到驿站…`，
        // 以及旧解析器留下的 `驿站取尾号1234包裹` 截断后的残留。都不能当地名。
        assertEquals("", ExpressStationName.normalize("驿站"))
        assertFalse(ExpressStationName.hasLocation("驿站"))
        // 但 `菜鸟驿站` 不算：剥品牌前缀时它拒绝被剥成一个品牌词，所以原样留下
        assertEquals("菜鸟驿站", ExpressStationName.normalize("菜鸟驿站"))

        // 带了地名就是有效地点，不能误伤 —— `南门小区代收点` 是能找得到的地方
        assertEquals("南门小区代收点", ExpressStationName.normalize("南门小区代收点"))
        assertTrue(ExpressStationName.hasLocation("南门小区代收点"))
    }

    @Test
    fun `关键词加半句话的抓取产物认得出来`() {
        // 读路径的自愈（`ExpressRecordRepair`）靠这个判据决定「要不要用当前解析逻辑重扫一遍」。
        // 判据必须是「句子的尾巴被切下来了」，不能是「归一化后变了」—— 后者把剥品牌前缀、
        // 剥括号这些**正常包装**也算进去，那会让每条记录每读一次就重扫一次。
        assertTrue(ExpressStationName.isNarrativeArtifact("驿站取尾号1234包裹"))
        assertTrue(ExpressStationName.isNarrativeArtifact("代收点存放已超过24小时【取件码-8-2-3021】"))
        assertTrue(ExpressStationName.isNarrativeArtifact("A小区驿站（存放点）"))
        // 半句话在开头 —— 整串都是句子的其余部分，一点地名信息都没有
        assertTrue(ExpressStationName.isNarrativeArtifact("存放已超过24小时"))

        assertFalse(ExpressStationName.isNarrativeArtifact("阳光花园菜鸟驿站"))
        assertFalse(ExpressStationName.isNarrativeArtifact("菜鸟驿站(杭州文一西路店)"))
        assertFalse(ExpressStationName.isNarrativeArtifact("幸福小区54栋104店"))
        assertFalse(ExpressStationName.isNarrativeArtifact(""))
        assertFalse(ExpressStationName.isNarrativeArtifact(null))

        // `取尾号1234包裹` 里的「取尾号」必须被切断：它是凭据那半句的开头，
        // 不是店名的一部分（旧解析器就是从关键词往后啃、把它一起吃了）
        assertEquals("", ExpressStationName.normalize("驿站取尾号1234包裹"))
    }

    @Test
    fun `关键词后面接的句子被截掉`() {
        // 2026-09-27 真机存下来的脏名字（淘宝「超时未取」提醒）。解析层现在不会再抽出它，
        // 但**历史记录里已经存下的改不掉** —— 分组和显示名都过 normalize，所以这里兜一层。
        assertEquals("", ExpressStationName.normalize("代收点存放已超过24小时\u00a0【取件码-8-2-3021】"))
        assertFalse(ExpressStationName.hasLocation("代收点存放已超过24小时【取件码-8-2-3021】"))
        assertEquals("", ExpressStationName.normalize("存放已超过24小时"))

        // 截断而不是整条作废：前缀里的地名对「去哪取」有用，不该跟着句子一起丢
        assertEquals("临河阳光花园代收点", ExpressStationName.normalize("临河阳光花园代收点存放已超过24小时"))
        // 截断露出来的半个括号要刮掉，否则名字尾巴挂着一个孤零零的「（」
        assertEquals("A小区驿站", ExpressStationName.normalize("A小区驿站（存放点）"))

        // 「24小时便利店代收点」是真会出现的店名 —— 所以「小时」刻意不在判据里
        assertEquals("24小时便利店代收点", ExpressStationName.normalize("24小时便利店代收点"))
        // 反过来：宿主偶尔给的「网点名（服务热线…）」是**有效地点**，不许被这套判据误伤
        // （真机里同一处会同时存在这种写法和菜鸟驿站那种写法，2026-09-27）
        assertEquals(
            "临河阳光花园54栋驿站（服务热线12345678）",
            ExpressStationName.placeName("临河阳光花园54栋驿站（服务热线12345678）"),
        )
    }

    @Test
    fun `显示用的名字保留原始写法但丢掉句子`() {
        // 显示层（替换通知正文、驿站管理的「原始写法」）要认得出是哪家店 ——
        // 品牌前缀不能像 normalize 那样剥掉
        assertEquals("菜鸟驿站(临河阳光花园店)", ExpressStationName.placeName("菜鸟驿站(临河阳光花园店)"))
        // 句子那半截必须丢掉
        assertEquals("临河阳光花园代收点", ExpressStationName.placeName("临河阳光花园代收点存放已超过24小时"))

        // 说不出「哪一处」的串不算地点：通知正文里不再印一个零信息的「代收点」
        assertNull(ExpressStationName.placeName("代收点"))
        assertNull(ExpressStationName.placeName("代收点存放已超过24小时\u00a0【取件码-8-2-3021】"))
        assertNull(ExpressStationName.placeName("存放已超过24小时"))
        assertNull(ExpressStationName.placeName(""))
        assertNull(ExpressStationName.placeName(null))
    }

    // ------------------------------------------------------------ 聚类

    @Test
    fun `真机样本：地址详略不同的两种写法算同一处`() {
        val short = "阳光花园菜鸟驿站"
        val long = "阳光23号楼109阳光花园菜鸟驿站"
        val reps = ExpressStationName.representatives(setOf(short, long))

        assertEquals(long, reps[short])
        assertEquals(long, reps[long])
    }

    @Test
    fun `代表名取最长的那个`() {
        // 长名通常是宿主那份（带楼栋号），对「去哪取」更有用
        val short = "阳光花园店"
        val long = "阳光23号楼109阳光花园店"
        val reps = ExpressStationName.representatives(setOf(short, long))

        assertEquals(long, reps[short])
        assertEquals(long, reps[long])
    }

    @Test
    fun `不同驿站不会被合到一起`() {
        val a = "临河阳光花园店"
        val b = "幸福小区店"
        val reps = ExpressStationName.representatives(setOf(a, b))

        assertEquals(a, reps[a])
        assertEquals(b, reps[b])
        assertNotEquals(reps[a], reps[b])
    }

    @Test
    fun `太短的公共后缀不参与合并`() {
        // 「花园店」3 个字，谁都能以它结尾 —— 拿它当身份会把无关的驿站串起来
        val reps = ExpressStationName.representatives(setOf("花园店", "阳光花园店"))

        assertEquals("花园店", reps["花园店"])
        assertEquals("阳光花园店", reps["阳光花园店"])
    }

    @Test
    fun `地址加在后面的不算同一处`() {
        // 后缀规则只看结尾。`阳光花园店东门` 结尾不是 `阳光花园店`，两处不能合 ——
        // 合了用户会跑到没开的那道门去。
        val a = "阳光花园店"
        val b = "阳光花园店东门"
        val reps = ExpressStationName.representatives(setOf(a, b))

        assertEquals(a, reps[a])
        assertEquals(b, reps[b])
    }

    @Test
    fun `聚类结果与输入顺序无关`() {
        val names = listOf("阳光花园菜鸟驿站", "阳光23号楼109阳光花园菜鸟驿站", "幸福小区店")
        val forward = ExpressStationName.representatives(names)
        val backward = ExpressStationName.representatives(names.reversed())

        assertEquals(forward, backward)
    }

    // ------------------------------------------------------------ 用户规则

    @Test
    fun `改名只影响这一个驿站`() {
        val rules = ExpressStationRules(mapOf("阳光花园菜鸟驿站" to "家门口"))

        assertEquals("家门口", rules.apply("阳光花园菜鸟驿站"))
        assertEquals("别的驿站", rules.apply("别的驿站"))
        assertTrue(rules.hasRule("阳光花园菜鸟驿站"))
        assertFalse(rules.hasRule("别的驿站"))
    }

    @Test
    fun `合并就是把名字设成对方的`() {
        // 没有单独的「合并」概念：A 的名字被设成 B，两者就算出同一个名字
        val rules = ExpressStationRules(mapOf("南门驿站" to "南门小区代收点"))

        assertEquals(rules.apply("南门小区代收点"), rules.apply("南门驿站"))
    }

    @Test
    fun `规则可以叠着用`() {
        // A 并到 B，之后又把 B 改名 —— A 的包裹也该跟着显示新名字，否则同一张卡上会冒出两个名字
        val rules = ExpressStationRules(mapOf("A店" to "B店", "B店" to "家门口"))

        assertEquals("家门口", rules.apply("A店"))
    }

    @Test
    fun `互相指向时不会死循环`() {
        // 用户完全可能先把 A 并到 B、再反过来把 B 并到 A。走到上限就停下即可，不能卡住。
        val rules = ExpressStationRules(mapOf("A店" to "B店", "B店" to "A店"))

        assertTrue(rules.apply("A店") == "A店" || rules.apply("A店") == "B店")
    }

    @Test
    fun `空白规则等于没有规则`() {
        // 用户清空输入框应该是「回到原样」，而不是把驿站名变成空串（那会掉进「未知取件地点」）
        val rules = ExpressStationRules(mapOf("A店" to "   "))

        assertEquals("A店", rules.apply("A店"))
        assertFalse(rules.hasRule("A店"))
    }

    @Test
    fun `空规则表什么都不改`() {
        assertEquals("A店", ExpressStationRules.EMPTY.apply("A店"))
        assertTrue(ExpressStationRules.EMPTY.isEmpty)
    }
}
