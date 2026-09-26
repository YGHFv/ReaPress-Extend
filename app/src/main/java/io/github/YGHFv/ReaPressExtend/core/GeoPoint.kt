package io.github.YGHFv.ReaPressExtend.core

/**
 * 一个经纬度点（WGS84 度）。
 *
 * 只有 [GeoDistance] 与身份码弹窗用得到它 —— 「哪个驿站离我最近」这件事要一个能过
 * `equals` / 能当 `data class` 字段的类型，而不是散着的两个 `Double?`。
 */
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * 把宿主给的驿站坐标归一化成**真正的 WGS84 度**。
 *
 * ## 为什么需要它（2026-09-26 真机实证）
 *
 * 菜鸟 `packageStation.stationLat` / `stationLng` 下发的是**放大过的整数**，不是度：
 *
 * | 宿主原值 | 实际坐标 |
 * |---|---|
 * | `3177340` / `11726441` | 31.77340 / 117.26441（合肥南湖春城） |
 * | `3294167` / `11581004` | 32.94167 / 115.81004（阜阳颍滨花园） |
 *
 * 系数是 **1e5**（`(int)(度 * 100000)`）。直接把 3177340 当纬度喂给 Haversine，
 * `Math.toRadians` 会得到十万弧度的角度、`sin` 直接进振荡区 —— 算出来的「距离」是垃圾，
 * 表现却是**看起来像数据问题的「最近驿站选错了」**（用户报的正是这个），很难往回查到单位上。
 *
 * ## 为什么是幂等的
 *
 * 历史记录里存的已经是放大值，只改写入侧修不好它们；只改读取侧又挡不住新写进来的。
 * 所以这个函数**两边都过一遍**，前提是它对已归一的值必须原样返回：
 * 判据是「分量落在合法经纬度范围内就认为已经是对的」——
 * 31.77 合法 → 原样；3177340 越界 → 除 1e5 再看。两种输入得到同一个点。
 *
 * ## 认不出来时返回 null，不硬猜
 *
 * 除完仍越界（比如换了放大倍数）、缺一半、非有限值 —— 一律当「没有坐标」。
 * 一个**错的**坐标会让弹窗把用户指去几百公里外的驿站；没有坐标只是退化成按件数挑，
 * 两者代价差着数量级。
 *
 * ## 为什么把 (0, 0) 也当没有
 *
 * 几内亚湾上的一个点。宿主字段缺省时给 0 的概率，远大于真有用户站在那个坐标上。
 */
fun geoPointOf(lat: Double?, lng: Double?): GeoPoint? {
    if (lat == null || lng == null) return null
    if (!lat.isFinite() || !lng.isFinite()) return null
    if (lat == 0.0 && lng == 0.0) return null
    if (isValidLatitude(lat) && isValidLongitude(lng)) return GeoPoint(lat, lng)

    val scaled = GeoPoint(lat / HOST_COORDINATE_SCALE, lng / HOST_COORDINATE_SCALE)
    return scaled.takeIf { isValidLatitude(it.lat) && isValidLongitude(it.lng) }
}

/**
 * 菜鸟坐标的放大系数。
 *
 * 只认这一个值、**不做「反复除到合法为止」**：3177340 除两次 1e5 会得到 0.317734 —— 也在
 * 合法范围内，看起来「成功了」，实际是个格陵兰附近的点。宁可返回 null 让上层退化。
 */
private const val HOST_COORDINATE_SCALE = 100_000.0

private fun isValidLatitude(value: Double): Boolean = value in -90.0..90.0

private fun isValidLongitude(value: Double): Boolean = value in -180.0..180.0
