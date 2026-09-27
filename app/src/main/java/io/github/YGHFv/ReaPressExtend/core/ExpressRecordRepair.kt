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

/**
 * 用**当前**解析逻辑重扫一遍记录里存下的原文，把旧解析器写错的字段改回来。
 *
 * ## 为什么光修 [ExpressParser] 不够
 *
 * 解析只在通知到达的那一刻跑一次，结果随即落盘。之后修好解析器，**已经躺在 XML 里的那些
 * 值一个字也不会变**，而且它们会继续造成两类后果：
 *
 * - 界面直接按存下来的值显示、分组（`驿站取尾号1234包裹` 就是一个首页分组）；
 * - [ExpressRecord.mergeEnrichment] 是「只填空不覆盖」，脏值占住了格子，宿主富化给的真名
 *   永远进不来 —— 解析层修好反而被历史数据挡住。
 *
 * 所以需要一条**读路径**上的修复：每次 [ExpressRecordStore.load] 时按原文重算一次。选读路径
 * 而不是一次性迁移脚本，是因为它不需要版本号、不需要「迁移有没有跑过」的状态，而且下一次
 * 任何写入都会把修复结果存回去（同 `ExpressRecordStore.load` 里清「无身份记录」的做法）。
 *
 * ## 真机证据（2026-09-27）
 *
 * 用户上报「短信里的取件码识别不到、只认到快递尾号、驿站也识别不对」，落库的正是这一条：
 *
 * | 字段 | 旧解析器写下的 | 当前解析器给出的 |
 * |---|---|---|
 * | `trackingNumber` | `8613800138000`（**发件手机号**） | `null` |
 * | `pickupCode` | `null` | `1-1-2001` |
 * | `station` | `驿站取尾号1234包裹` | `阳光花园菜鸟驿站` |
 * | `parcelTail` | `null` | `1234` |
 *
 * 原文：`+8613800138000\n【中通快递】凭1-1-2001到阳光花园菜鸟驿站取尾号1234包裹`。
 * 前两行是同一个根因（`\b` 在 Android 是 Unicode 词边界，见 [ExpressParser] 文件头），
 * 第三行是驿站名「只从关键词往后啃」。
 *
 * ## 规矩：只改「能证明原来那一步做错了」的字段
 *
 * 这是**读**路径，任何一次误改都会被用户看到，而且（因为会跟着下一次写入落盘）不可逆。
 * 所以不做「重新解析一遍然后整条替换」——那条路会把宿主富化来的字段（带楼栋号的驿站名、
 * 商品图、轨迹）一起冲掉。逐字段，每个字段都有独立的、能说出理由的准入条件：
 *
 * 1. **运单号**：只处理「这个号其实就是原文里那个带国家码的手机号」这一种情况
 *    （原文里存在 `+<号>`）。不能写成「重解析结果为空就清掉」——`19` / `20` 开头的
 *    14 位运单号会被 [ExpressParser.parseTrackingNumber] 当成日期挡掉，那不是错。
 * 2. **驿站名**：只处理「说不出来在哪」和「关键词 + 半句话」两种
 *    （[ExpressStationName.hasLocation] / [ExpressStationName.isNarrativeArtifact]）。
 *    宿主给的 `幸福小区54栋104店` 过不了这两条，一个字都不会动。
 * 3. **取件码 / 包裹尾号 / 手机尾号**：只填空，从不覆盖 —— 与富化合并同一条规矩。
 * 4. **快递公司**：只在认不出来（[Courier.UNKNOWN]）时按原文的品牌名猜一次。
 *    原来能认出来的不动，避免和运单号前缀那条路打架。
 * 5. **状态**：只**推进**，不认 [ExpressStatus.UNKNOWN] —— 与 `ExpressRecordStore.claimTail`
 *    同一条规则，否则富化来的「已签收」会被原文里的「取件码」两个字打回「待取件」。
 *
 * 幂等：修完的结果再修一次不会变（这也是它能安全地挂在每次 [ExpressRecordStore.load] 上的前提）。
 */
object ExpressRecordRepair {

    /** 旧版 PDD 扫描器在 `orderSn` 缺失时发出的裸诊断串。 */
    private const val PDD_V23_DIAGNOSTIC_RAW = "拼多多取快递缓存"

    /** 旧版 PDD 扫描器带订单号的诊断串（订单号形状 `260410-367735652661006`）。 */
    private val PDD_V23_DIAGNOSTIC_RAW_RE = Regex("""拼多多取快递缓存（订单 [0-9-]{8,45}）""")

    /** 修复一条记录；没有可修的返回原对象（调用方可以直接用 `===` 判断有没有变）。 */
    fun repair(record: ExpressRecord): ExpressRecord {
        val raw = record.rawText
        if (raw.isBlank()) return record
        var result = record

        // ⓪ 拼多多「发现」腿第一版（缓存解析器只有三样字段时）给每条记录发的诊断副标题。
        //    它不是任何通知的原文 —— 这个字符串只可能出自我们自己的旧扫描器
        //    （`PddCacheScanner` v23），卡片副行退回原文时显示的就是这句废话。
        //    精确匹配整串才清：普通通知/短信不可能长这样，误伤面为零。
        //    清空后卡片副行自动省略（UI 对 blank raw 的回退就是不显示）。
        if (raw == PDD_V23_DIAGNOSTIC_RAW || raw.matches(PDD_V23_DIAGNOSTIC_RAW_RE)) {
            return record.copy(rawText = "")
        }

        // ① 运单号其实是个手机号。判据是「原文里那串号带国家码」——不是「重解析结果为空」，
        //    见类注释第 1 条。
        val suspiciousTracking = result.trackingNumber
            ?.takeIf { it.isNotBlank() && isEchoedWithCountryCode(raw, it) }
        if (suspiciousTracking != null) {
            result = result.copy(trackingNumber = ExpressParser.parseTrackingNumber(raw))
        }

        // ② 驿站名：空的、或者「关键词 + 半句话」的产物。说不出地点就清空，
        //    留着只会污染首页分组并挡住富化的真名。
        val station = result.station
        if (station.isNullOrBlank() || ExpressStationName.isNarrativeArtifact(station)) {
            val fresh = ExpressParser.parseStation(raw)
            if (fresh != station) result = result.copy(station = fresh)
        }

        // ③ 只填空的三兄弟。为什么取件码要在这里补：旧解析器抽不到它，这条记录的强标识
        //    就只剩那个假运单号，而它刚好又会被 ① 清掉 —— 不补的话整条记录会失去身份被丢弃。
        if (result.pickupCode.isNullOrBlank()) {
            ExpressParser.parsePickupCode(raw)?.let { result = result.copy(pickupCode = it) }
        }
        if (result.parcelTail.isNullOrBlank()) {
            ExpressParser.parseParcelTail(raw)?.let { result = result.copy(parcelTail = it) }
        }
        if (result.phoneTail.isNullOrBlank()) {
            ExpressParser.parsePhoneTail(raw)?.let { result = result.copy(phoneTail = it) }
        }

        // ④ 认不出公司时才按原文猜。`【中通快递】` 这类品牌名就写在通知标题里，
        //    比留一个「快递」更能在卡片上说清是哪家。
        if (result.courier == Courier.UNKNOWN) {
            val byText = Courier.fromCompanyName(raw)
            if (byText != Courier.UNKNOWN) result = result.copy(courier = byText)
        }

        // ⑤ 状态只推进。
        val fresh = ExpressParser.parseStatus(raw)
        if (fresh.order > result.status.order) result = result.copy(status = fresh)

        return result
    }

    /**
     * 原文里这个「运单号」是不是带国家码写出来的（`+8613800138000`）。
     *
     * 只认 `+` 号 — 不带国家码的 11 位号码本来就不会被当成运单号（[ExpressParser] 刻意不收
     * 11 位纯数字），所以不必另判。`00` 开头的国际写法极少出现在国内短信里，不收。
     */
    private fun isEchoedWithCountryCode(raw: String, trackingNumber: String): Boolean =
        raw.contains("+$trackingNumber") || raw.contains("+86$trackingNumber")
}
