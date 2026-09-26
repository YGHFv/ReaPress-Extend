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

package io.github.YGHFv.ReaPressExtend.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络图片的**最小**加载器 —— 目前只服务详情页那一张商品图。
 *
 * ## 为什么不引第三方图片库
 *
 * Coil / Glide 各自带一套图形栈（磁盘缓存、请求调度、生命周期、解码池），完整但重：
 * 这个模块要的是「在详情页里显示一张 64dp 的缩略图」，为它引入一整套来说不过去 ——
 * 项目里已经有一条「不引入第三套图形栈」的取舍（见 `build.gradle.kts` 里 miuix-blur 的注释）。
 *
 * 代价是要自己守住三件事，这里都显式做了：
 * 1. **离开主线程**：[LaunchedEffect] 里切到 IO 下载 + 解码，绝不阻塞合成。
 * 2. **按显示尺寸采样**（[TARGET_PX]）：商品图原图常有 800×800，直接解码成 Bitmap
 *    一张就是 2.5MB；按 2 的幂次降采样到够 64dp 显示即可。
 * 3. **内存缓存**（[cache]）：详情页来回进出、同一条记录上下滚动都会重复请求，
 *    没有缓存就是每次都重新下载。
 *
 * 磁盘缓存**故意不做**：商品图是一次性的东西（包裹取走后再不会看），
 * 落一份到磁盘反而留下用户看过什么商品的痕迹。
 */
@Composable
internal fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    // 用 URL 当 key：同一个位置换了别的图（列表复用时）必须重新起一次加载，
    // 而 `remember(url)` 正好把「换图」这件事翻译成「重新初始化状态」。
    var bitmap by remember(url) { mutableStateOf(cache.get(url)) }

    if (bitmap == null) {
        LaunchedEffect(url) {
            val loaded = download(url) ?: return@LaunchedEffect
            cache.put(url, loaded)
            bitmap = loaded
        }
    }

    val shown = bitmap
    if (shown != null) {
        Image(
            bitmap = shown,
            contentDescription = contentDescription,
            modifier = modifier,
            // Crop 而不是 Fit：卡片里的方框是定死的，Fit 会把非正方形的图缩成一条，
            // 四周留白比裁掉一部分更难看。
            contentScale = ContentScale.Crop,
        )
    } else {
        // 加载中 / 加载失败都画同一块占位：尺寸由调用方给定，两种情况都不会让布局跳动。
        // 不做转圈动画 —— 这张图从本地缓存命中的概率很高，闪一下转圈反而更花。
        Box(modifier = modifier.background(MiuixTheme.colorScheme.secondaryContainer))
    }
}

/**
 * 已经解码好的商品图。按**张数**而不是字节数计数：这些图都被采样到 [TARGET_PX] 上下，
 * 单张的体积彼此差不了几倍，按张数封顶既够用又不必去精确算字节。
 */
private val cache = object : LruCache<String, ImageBitmap>(MAX_ENTRIES) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = 1
}

private const val MAX_ENTRIES = 24

/**
 * 目标边长（px）。
 *
 * 显示尺寸是 64dp，按 3x 密度算是 192px —— 取 240 留一点余量，同时保证在 4x 屏上也不糊。
 * 再大只是白占内存。
 */
private const val TARGET_PX = 240

private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 5_000

/**
 * 下载并解码。**任何失败都返回 null**（网络不通、地址失效、不是图片），
 * 由调用方退化到占位块 —— 详情页的主体是物流轨迹，一张商品图不该让它整页崩掉。
 */
private suspend fun download(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return@withContext null

        // 先只读尺寸（inJustDecodeBounds）拿到原图大小，算出降采样倍数，再真正解码。
        // 反过来先解码再缩是在内存里过一遍原图 —— 正是要避免的那件事。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (e: Throwable) {
        null
    } finally {
        connection?.disconnect()
    }
}

/**
 * 满足「长边不超过 [target]」的最大 2 的幂次倍数。
 *
 * 用 2 的幂次是因为 [BitmapFactory.Options.inSampleSize] 只有 2 的幂次才被真正当作降采样，
 * 其它值会被就近取整。宽高都参与判断：只按宽算，竖长图会漏。
 */
private fun sampleSize(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= target && height / (sample * 2) >= target) {
        sample *= 2
    }
    return sample
}
