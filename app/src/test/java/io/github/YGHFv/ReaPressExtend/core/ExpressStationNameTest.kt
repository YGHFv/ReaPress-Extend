package io.github.YGHFv.ReaPressExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驿站名身份判定的单测。
 *
 * 样本照 2026-09-26 真机 `reapress_records` 里的两条记录改写：`阳光花园菜拼多多驿站` /
 * `阳光23号楼109阳光花园菜拼多多驿站` —— 用户当时看到的就是同一个驿站裂成两张卡片。
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

    // ------------------------------------------------------------ 聚类

    @Test
    fun `真机样本：地址详略不同的两种写法算同一处`() {
        val short = "阳光花园菜拼多多驿站"
        val long = "阳光23号楼109阳光花园菜拼多多驿站"
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
        val b = "合肥南湖新城店"
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
        val names = listOf("阳光花园菜拼多多驿站", "阳光23号楼109阳光花园菜拼多多驿站", "南湖新城店")
        val forward = ExpressStationName.representatives(names)
        val backward = ExpressStationName.representatives(names.reversed())

        assertEquals(forward, backward)
    }

    // ------------------------------------------------------------ 用户规则

    @Test
    fun `改名只影响这一个驿站`() {
        val rules = ExpressStationRules(mapOf("阳光花园菜拼多多驿站" to "家门口"))

        assertEquals("家门口", rules.apply("阳光花园菜拼多多驿站"))
        assertEquals("别的驿站", rules.apply("别的驿站"))
        assertTrue(rules.hasRule("阳光花园菜拼多多驿站"))
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
