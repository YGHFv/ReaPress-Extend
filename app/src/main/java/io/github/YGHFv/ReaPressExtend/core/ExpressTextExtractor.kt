package io.github.YGHFv.ReaPressExtend.core

/**
 * 通知文本抽取。
 *
 * 刻意不接收 `android.app.Notification`：core 层要能在 JVM 单测里跑，而 `android.jar` 在单测里
 * 是桩（没有 Robolectric），碰 `Notification.extras` 会抛 "not mocked"。
 * 所以这里只吃一个 `Map<String, Any?>`，由 system_server 侧的适配层把 `extras` 拆进来。
 *
 * key 用 Android 平台常量的字面量，不 import `android.app.Notification` —— 常量值是 AOSP 稳定
 * 契约（`android.title` 等），比引一个桩类更可靠。
 */
object ExpressTextExtractor {

    const val EXTRA_TITLE = "android.title"
    const val EXTRA_TEXT = "android.text"
    const val EXTRA_BIG_TEXT = "android.bigText"
    const val EXTRA_SUB_TEXT = "android.subText"
    const val EXTRA_TICKER_TEXT = "android.tickerText"
    const val EXTRA_TEXT_LINES = "android.textLines"
    const val EXTRA_INFO_TEXT = "android.infoText"

    /**
     * 按信息量从多到少取正文。
     *
     * 优先级说明：
     * - `bigText` 是展开后的完整正文，信息最全，优先
     * - `textLines` 是多行展开内容（短信、聊天类常用），次之
     * - `text` 是折叠态正文，可能被截断
     * - `infoText` 是系统加的辅助行（如「3 条新消息」），信息量最低
     */
    fun extractBody(extras: Map<String, Any?>): String {
        val candidates = listOf(
            EXTRA_BIG_TEXT,
            EXTRA_TEXT_LINES,
            EXTRA_TEXT,
            EXTRA_INFO_TEXT,
            EXTRA_TICKER_TEXT,
        )
        for (key in candidates) {
            val value = flatten(extras[key])
            if (value.isNotBlank()) return value
        }
        return ""
    }

    fun extractTitle(extras: Map<String, Any?>): String = flatten(extras[EXTRA_TITLE]).trim()

    /**
     * 标题 + 副标题 + 正文拼成完整文本。
     *
     * 去重：很多 App 把标题原样塞进正文开头（「菜鸟\n菜鸟 您的包裹...」），
     * 不处理会让关键词重复命中、置信度虚高。
     */
    fun extractFullText(extras: Map<String, Any?>): String {
        val parts = listOf(
            extractTitle(extras),
            flatten(extras[EXTRA_SUB_TEXT]).trim(),
            extractBody(extras),
        ).filter { it.isNotBlank() }.distinct()

        return parts.joinToString("\n")
    }

    /**
     * extras 里的值可能是 String、CharSequence、CharSequence[]（textLines）或 SpannedString。
     * 统一拍平成 String。
     */
    private fun flatten(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is CharSequence -> value.toString()
        // textLines 是 CharSequence[]，逐行拼接
        is Array<*> -> value.joinToString("\n") { flatten(it) }
        is Iterable<*> -> value.joinToString("\n") { flatten(it) }
        else -> value.toString()
    }
}
