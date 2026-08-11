package com.example.touchengine

import android.graphics.Rect

/**
 * 视觉候选与最终目标融合共同使用的纯规则。
 *
 * 本文件只包含文本归一化、常见视觉语义判断和矩形几何计算，
 * 不读取 Service 状态，也不修改缓存、焦点或 NavMesh。
 */
internal fun normalizedVisualText(text: String): String {
    return text.trim().replace("\\s+".toRegex(), "")
}

internal fun visualRegionPolicyFor(
    scene: DouyinScene,
    screenWidth: Int,
    screenHeight: Int
): VisualRegionPolicy {
    if (scene != DouyinScene.VIDEO_FEED_PORTRAIT || screenWidth <= 0 || screenHeight <= 0) {
        return VisualRegionPolicy()
    }
    return VisualRegionPolicy(
        allowedRegions = listOf(
            Rect(0, 0, screenWidth, (screenHeight * 0.18f).toInt()),
            Rect(
                (screenWidth * 0.78f).toInt(),
                (screenHeight * 0.18f).toInt(),
                screenWidth,
                (screenHeight * 0.82f).toInt()
            ),
            Rect(0, (screenHeight * 0.82f).toInt(), screenWidth, screenHeight)
        )
    )
}

internal fun isIconEntryText(text: String): Boolean {
    val compact = normalizedVisualText(text)
    return compact.length <= 6 && listOf(
        "\u5ba2\u670d", "\u8bbe\u7f6e", "\u5730\u5740", "\u6d88\u606f", "\u5206\u4eab",
        "\u5173\u6ce8", "\u6536\u85cf", "\u8d2d\u7269\u8f66", "\u5f85\u4ed8\u6b3e",
        "\u5f85\u53d1\u8d27", "\u5f85\u6536\u8d27", "\u5f85\u8bc4\u4ef7",
        "\u7ea2\u5305", "\u4f18\u60e0\u5238", "\u6dd8\u91d1\u5e01", "\u5929\u732b\u79ef\u5206",
        "\u5145\u503c", "\u5145\u503c\u91d1", "\u9886\u7ea2\u5305", "\u9886\u91d1\u5e01",
        "\u5feb\u9012", "\u8db3\u8ff9", "\u9000\u6b3e", "\u552e\u540e",
        "\u4f1a\u5458", "VIP", "88VIP", "\u56fd\u8865", "\u95ea\u8d2d",
        "\u5916\u5356", "\u98de\u732a", "\u7a7f\u642d", "\u6dd8\u5de5\u5382",
        "\u82ad\u82ad\u519c\u573a", "\u5929\u732b\u8d85\u5e02"
    ).any { compact.contains(it) }
}

internal fun isCompactNavigationText(text: String): Boolean {
    val compact = normalizedVisualText(text)
    if (compact.isBlank() || compact.length > 10) return false
    return compact == "\u5168\u90e8" ||
        compact == "\u66f4\u591a" ||
        compact == "\u5c55\u5f00" ||
        compact == "\u6536\u8d77" ||
        compact == "\u6362\u4e00\u6362" ||
        compact == "\u67e5\u770b" ||
        compact == "\u7ba1\u7406" ||
        compact.endsWith(">") ||
        compact.endsWith("\u300b") ||
        compact.endsWith("\u203a") ||
        compact.endsWith("\u2228") ||
        compact.endsWith("\u2304") ||
        compact.endsWith("\u25bc")
}

internal fun isPriceLikeVisualText(text: String): Boolean {
    val compact = normalizedVisualText(text)
    if (compact.contains("¥") || compact.contains("￥")) return true
    // "券"这个关键词被移除：像"5元鲜食加补券待领取"这类促销横条
    // 同时含数字和"券"字，但并不是真实价格标签，之前会误触发
    // CARD_TARGET价格聚类逻辑，把旁边不相关的图标一起吞进大框里
    if (!compact.matches(Regex(".*[0-9]+(\\.[0-9]+)?.*"))) return false
    return compact.contains("已售") || compact.contains("热销") ||
            compact.contains("补贴")
}

internal fun isPromotionalText(text: String): Boolean {
    return text.contains("618") || text.contains("\u8865\u8d34") ||
        text.contains("\u4f18\u60e0") || text.contains("\u7acb\u51cf") ||
        text.contains("\u79d2\u6740")
}

internal fun isLikelySelfOverlayText(text: String): Boolean {
    val compact = normalizedVisualText(text).lowercase()
    return compact.contains("vision") ||
        compact.contains("visual-hole") ||
        compact.contains("ocr") ||
        compact.contains("cache") ||
        compact.contains("selected") ||
        compact.contains("clicklocked")
}

internal fun containsMost(container: Rect, child: Rect, ratio: Float): Boolean {
    val childArea = child.areaLong().coerceAtLeast(1L)
    return intersectionArea(container, child) / childArea.toFloat() >= ratio
}

internal fun rectIou(a: Rect, b: Rect): Float {
    val intersection = intersectionArea(a, b)
    val union = a.areaLong() + b.areaLong() - intersection
    return if (union <= 0L) 0f else intersection / union.toFloat()
}

internal fun intersectionArea(a: Rect, b: Rect): Long {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    return maxOf(0, right - left).toLong() * maxOf(0, bottom - top).toLong()
}

internal fun Rect.areaLong(): Long {
    return width().coerceAtLeast(0).toLong() * height().coerceAtLeast(0).toLong()
}
