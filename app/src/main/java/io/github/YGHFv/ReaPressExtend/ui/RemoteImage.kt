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
 * 详情页商品图的最小网络加载器，不引第三方图片库（为一张 64dp 缩略图不值得带一整套图形栈）。
 * 必须自己守住：IO 线程下载解码、按 [TARGET_PX] 采样（原图 800x800 直接解码一张 2.5MB）、
 * 内存缓存 [cache]（进出详情页、列表复用都会重复请求）。磁盘缓存故意不做：商品图是一次性的，落盘会留下用户看过什么的痕迹。
 */
@Composable
internal fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
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
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier.background(MiuixTheme.colorScheme.secondaryContainer))
    }
}

/** 按张数而不是字节数计数：图都被采样到 [TARGET_PX] 上下，体积彼此差不了几倍，按张数封顶够用。 */
private val cache = object : LruCache<String, ImageBitmap>(MAX_ENTRIES) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = 1
}

private const val MAX_ENTRIES = 24

/** 显示尺寸 64dp，3x 密度算 192px，取 240 留余量保证 4x 屏不糊。 */
private const val TARGET_PX = 240

private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 5_000

/** 下载并解码，任何失败都返回 null，由调用方退化到占位块。 */
private suspend fun download(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return@withContext null

        // 先只读尺寸算出降采样倍数，再真正解码；反过来先解码再缩等于在内存里过一遍原图。
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

/** 满足长边不超过 [target] 的最大 2 的幂次（inSampleSize 只认 2 的幂次）；宽高都参与，只按宽算竖长图会漏。 */
private fun sampleSize(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / (sample * 2) >= target && height / (sample * 2) >= target) {
        sample *= 2
    }
    return sample
}
