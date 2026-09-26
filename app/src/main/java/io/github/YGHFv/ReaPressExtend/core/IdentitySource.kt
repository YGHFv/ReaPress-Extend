package io.github.YGHFv.ReaPressExtend.core

/**
 * 取件时要出示的**身份码来自哪个平台**。
 *
 * ## 为什么归属跟着驿站走
 *
 * 「身份码」不是全网统一的东西 —— 它是**各平台自己发的取件凭证**，彼此不通用：
 * 菜鸟驿站要菜鸟的（菜鸟里叫「出库码」，接口 `mtop.cainiao.nbpickup.identitycode.get.cn`），
 * 拼多多驿站要拼多多的。同一个人在这两处各有一份，在 A 家出示 B 家的码，那台机器根本认不出来，
 * 用户就白跑一趟。所以这一项是**驿站属性**（在「驿站管理」里为每个驿站选一次），不是全局一个。
 *
 * ## 为什么不设成「全局一个平台」
 *
 * 用户同时跑菜鸟驿站和拼多多驿站是常态（截图里就有两处）。若只存全局值，去哪一个都得先
 * 手动切一次，而用户站在柜台前那一刻是没有心思切开关的。
 *
 * ## 为什么 PDD 先标「支持不了」
 *
 * 菜鸟这条路能走通，是因为它自己就带着淘宝登录态（模块只是**复用宿主已有的凭据**，
 * 见 `HostCredentialSource`）。拼多多的 `anti_content` 在 **native 层**算出、不可复刻
 * （依据见 `express-source-research.md`），所以取码这一步先不做。
 *
 * 但**选择项现在就摆出来**：驿站要能标记「我这儿是拼多多的」。真到了取码时，弹窗会
 * [supported] 为 false 而**如实说明暂未支持**，绝不悄悄回退到菜鸟的码 ——
 * 那比取不到更糟，用户会拿着别的平台的码站在柜台前。
 */
enum class IdentitySource(val displayName: String) {

    /** 菜鸟驿站 / 菜鸟裹裹。唯一已实现取码的平台。 */
    CAINIAO("菜鸟"),

    /** 拼多多驿站。能选、能标记，取码待接（签名在 native 层）。 */
    PDD("拼多多"),
    ;

    /**
     * 现在能不能真取到码。
     *
     * false **不是**「这一项没用」—— 它表达的是「先把归属记下来，取码以后接」。
     * 弹窗据此决定是发请求还是直接说明情况。
     */
    val supported: Boolean get() = this == CAINIAO

    companion object {
        /**
         * 解析存下来的值（枚举名）。**不认识的一律返回 null**，当「没设过」处理。
         *
         * 刻意不猜成 [CAINIAO]：猜错的代价是用户拿着别的平台的码去出库，
         * 而返回 null 的代价只是回到默认行为 —— 两者不对等。
         */
        fun parse(raw: String?): IdentitySource? {
            if (raw.isNullOrBlank()) return null
            return values().firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
    }
}
