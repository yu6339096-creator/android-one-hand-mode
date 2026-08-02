package com.example.touchengine

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

object EdgeInteractionRules {
    const val VISUAL_FINGERPRINT_DISTANCE_THRESHOLD = 12
    const val VISUAL_REFRESH_THROTTLE_MS = 3_000L
    enum class TaobaoContentBand { UPPER, MIDDLE, LOWER }
    enum class GenericContentBand { BODY_UPPER, BODY_LOWER }
    /**
     * 只有“整段 OCR 文本本身就是按钮/入口标签”时，才允许升级为 ACTION_TEXT。
     * 普通 ML Kit 文字仍会保留为 TEXT_EVIDENCE，不受这里的长度和关键词规则影响。
     */
    private const val MAX_ACTION_TEXT_LENGTH = 12

    private val iconOnlyActionTexts = setOf(
        ">", "›", "→", "»",
        "<", "‹", "←", "«"
    )

    private val actionDecorationChars = charArrayOf(
        '>', '›', '→', '»',
        '<', '‹', '←', '«',
        ':', '：', '·',
        '!', '！', '.', '。'
    )

    private val visualActionWords = setOf(
        "关闭", "跳过", "取消", "确定", "确认", "知道了", "允许", "拒绝", "同意",
        "回复", "发送", "下一步", "完成", "登录", "查看更多", "换一换",
        "立即购买", "立即抢", "购买", "加入购物车", "加购", "领券", "领取",
        "去领取", "去使用", "查看详情", "查看物流", "客服", "分享", "关注",
        "展开", "全屏观看", "搜索", "待付款", "待评价", "购物车", "视频", "我的",
        "会员中心", "设置", "首页", "播放", "发现", "通讯", "我", "表情",
        "支付", "状态", "名称", "朋友圈", "扫一扫", "返回", "小程序", "进入", "推荐", "精选",
        "经验", "同城", "商城", "朋友", "消息", "转发", "编辑", "日常", "收藏", "喜欢", "私密",
        "去发布", "获赞", "互关", "粉丝", "我的订单", "我的预约", "历史", "中心", "添加", "互动",
        "删除", "拉黑", "举报", "按住", "语音输入", "继续"
    )

    private val actionableVisionWords = setOf(
        "关闭", "跳过", "取消", "确定", "知道了", "允许", "拒绝", "同意", "回复", "发送",
        "确认", "下一步", "完成", "登录", "查看更多", "换一换", "搜索", "立即",
        "立即购买", "去购买", "购买", "加入购物车", "加购", "结算", "领券", "领取", "去领取",
        "去使用", "查看详情", "查看物流", "客服", "官方客服", "分享", "关注", "收藏", "设置",
        "地址", "全部", "消息", "购物车", "我的淘宝", "待付款", "待发货", "待收货", "待评价",
        "退款", "售后", "管理", "对比", "下单"
    )

    /** 允许少量常见前缀，但前缀后的内容仍必须是完整动作标签。 */
    private val actionPrefixes = setOf("立即", "马上", "点击", "去", "一键")

    private val prefixableActionWords = setOf(
        "购买", "领取", "使用", "查看", "查看详情", "查看物流", "进入", "下单", "结算",
        "登录", "打开", "关闭", "提交", "保存", "支付", "播放", "发送", "分享", "关注", "收藏", "加购"
    )

    private val actionableVisualLabels: Set<String> = visualActionWords + actionableVisionWords


    fun isTaobaoPackage(packageName: String?): Boolean {
        return packageName?.contains("taobao", ignoreCase = true) == true
    }

    fun isDouyinPackage(packageName: String?): Boolean {
        return packageName?.contains("aweme", ignoreCase = true) == true ||
            packageName?.contains("douyin", ignoreCase = true) == true
    }

    fun isHighFrequencyPackage(packageName: String?): Boolean {
        val packageText = packageName?.lowercase() ?: return false
        return packageText.contains("taobao") ||
            packageText.contains("aweme") ||
            packageText.contains("douyin") ||
            packageText.contains("kuaishou") ||
            packageText.contains("gifmaker") ||
            packageText.contains("smile")
    }

    fun isHeldInDirection(
        expectedAngle: Double,
        dragX: Float,
        dragY: Float,
        maxRadius: Float,
        deadZoneRatio: Float = 0.28f,
        toleranceDegrees: Double = 45.0
    ): Boolean {
        if (hypot(dragX.toDouble(), dragY.toDouble()) / maxRadius < deadZoneRatio) return false
        val angle = Math.toDegrees(atan2(dragY.toDouble(), dragX.toDouble()))
        var difference = abs(angle - expectedAngle)
        if (difference > 180.0) difference = 360.0 - difference
        return difference <= toleranceDegrees
    }

    fun acceptsGenericHorizontalTarget(
        width: Int,
        height: Int,
        screenHeight: Int,
        isKnownVerticalContainer: Boolean,
        isDefaultHome: Boolean
    ): Boolean {
        if (width <= 0 || height <= 0 || isKnownVerticalContainer) return false
        if (isDefaultHome) return true
        return width > height * 1.2f && height <= screenHeight * 0.55f
    }

    fun taobaoContentBand(centerY: Float, screenHeight: Int): TaobaoContentBand? {
        if (screenHeight <= 0) return null
        return when (centerY / screenHeight.toFloat()) {
            in 0.12f..<0.36f -> TaobaoContentBand.UPPER
            in 0.36f..<0.62f -> TaobaoContentBand.MIDDLE
            in 0.62f..<0.88f -> TaobaoContentBand.LOWER
            else -> null
        }
    }

    fun shouldBackfillTaobaoBand(
        regularNodeCount: Int,
        semanticCandidateCount: Int,
        minimumCoverage: Int = 2
    ): Boolean {
        return semanticCandidateCount > 0 && regularNodeCount < minimumCoverage
    }

    fun acceptsDouyinSearchSemanticTarget(
        width: Int,
        height: Int,
        centerY: Float,
        screenHeight: Int,
        keyboardTop: Int?
    ): Boolean {
        if (keyboardTop == null || width < 44 || height < 20 || screenHeight <= 0) return false
        val maxBottom = keyboardTop - 12
        return centerY > screenHeight * 0.10f &&
            centerY < maxBottom &&
            width * height < screenHeight * screenHeight * 0.12f
    }

    fun traversalBudget(isComplexContent: Boolean): Int {
        return if (isComplexContent) 900 else 3600
    }

    fun nodeLimit(isComplexContent: Boolean): Int {
        return if (isComplexContent) 140 else 180
    }

    fun maxTraversalDepth(isComplexContent: Boolean): Int {
        return if (isComplexContent) 8 else Int.MAX_VALUE
    }

    fun genericContentBand(centerY: Float, screenHeight: Int): GenericContentBand? {
        if (screenHeight <= 0) return null
        return when (centerY / screenHeight.toFloat()) {
            in 0.20f..<0.51f -> GenericContentBand.BODY_UPPER
            in 0.51f..<0.83f -> GenericContentBand.BODY_LOWER
            else -> null
        }
    }

    fun contentCoverage(nodes: List<NavNode>, screenHeight: Int): Map<GenericContentBand, Int> {
        return GenericContentBand.entries.associateWith { band ->
            nodes.count { genericContentBand(it.centerY, screenHeight) == band }
        }
    }

    fun missingContentBands(
        nodes: List<NavNode>,
        screenHeight: Int,
        minimumCoverage: Int = 1
    ): Set<GenericContentBand> {
        val coverage = contentCoverage(nodes, screenHeight)
        return GenericContentBand.entries.filterTo(linkedSetOf()) { band ->
            (coverage[band] ?: 0) < minimumCoverage
        }
    }

    fun needsExpandedContentScan(nodes: List<NavNode>, screenHeight: Int): Boolean {
        return missingContentBands(nodes, screenHeight).isNotEmpty()
    }

    fun isNavigationAnchor(centerY: Float, screenHeight: Int): Boolean {
        if (screenHeight <= 0) return false
        val ratio = centerY / screenHeight.toFloat()
        return ratio in 0.05f..<0.20f || ratio in 0.83f..<0.98f
    }

    fun navigationAnchorCoverage(nodes: List<NavNode>, screenHeight: Int): Pair<Int, Int> {
        val top = nodes.count {
            val ratio = it.centerY / screenHeight.toFloat()
            ratio in 0.05f..<0.20f
        }
        val bottom = nodes.count {
            val ratio = it.centerY / screenHeight.toFloat()
            ratio in 0.83f..<0.98f
        }
        return top to bottom
    }

    fun needsExpandedScan(nodes: List<NavNode>, screenHeight: Int): Boolean {
        val (top, bottom) = navigationAnchorCoverage(nodes, screenHeight)
        return needsExpandedContentScan(nodes, screenHeight) || top == 0 || bottom == 0
    }

    fun missingNavigationAnchors(nodes: List<NavNode>, screenHeight: Int): Set<Boolean> {
        val (top, bottom) = navigationAnchorCoverage(nodes, screenHeight)
        return buildSet {
            if (top == 0) add(true)
            if (bottom == 0) add(false)
        }
    }

    fun unresolvedBandsAfterProbe(
        requestedBands: Set<GenericContentBand>,
        recoveredNodes: List<NavNode>,
        screenHeight: Int
    ): Set<GenericContentBand> {
        val recoveredBands = recoveredNodes.flatMapTo(mutableSetOf()) {
            genericContentBandsForNode(it.bounds, screenHeight)
        }
        return requestedBands - recoveredBands
    }

    private fun genericContentBandsForNode(bounds: Rect, screenHeight: Int): Set<GenericContentBand> {
        if (screenHeight <= 0 || bounds.isEmpty) return emptySet()
        val top = bounds.top / screenHeight.toFloat()
        val bottom = bounds.bottom / screenHeight.toFloat()
        return GenericContentBand.entries.filterTo(linkedSetOf()) { band ->
            val (bandTop, bandBottom) = when (band) {
                GenericContentBand.BODY_UPPER -> 0.18f to 0.54f
                GenericContentBand.BODY_LOWER -> 0.48f to 0.85f
            }
            bottom >= bandTop && top <= bandBottom
        }
    }

    fun acceptsVisualCandidateBatch(
        centers: List<Pair<Int, Int>>,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (centers.size < 4 || screenWidth <= 0 || screenHeight <= 0) return false
        val rows = centers.map { it.second / (screenHeight / 10).coerceAtLeast(1) }.toSet().size
        val columns = centers.map { it.first / (screenWidth / 3).coerceAtLeast(1) }.toSet().size
        return rows >= 3 || (rows >= 2 && columns >= 2)
    }

    private fun compactVisualText(text: String): String {
        return text.replace("\\s+".toRegex(), "")
    }

    private fun normalizeActionText(text: String): String {
        return compactVisualText(text)
            .trim { character -> character in actionDecorationChars }
    }

    private fun matchesActionLabel(text: String): Boolean {
        val compact = compactVisualText(text)
        if (compact.isBlank()) return false

        // 箭头等装饰图标即使被 OCR 单独识别，也不能独立升级为点击目标。
        if (compact in iconOnlyActionTexts) return false

        val normalized = normalizeActionText(compact)
        if (normalized.isBlank()) return false
        if (normalized.equals("x", ignoreCase = true) || normalized == "×") return true

        // 长句即使包含“购买、关注、设置”等词，也只保留为普通文字证据。
        if (normalized.length > MAX_ACTION_TEXT_LENGTH) return false

        if (normalized in actionableVisualLabels) return true
        val withoutCloseGlyph = normalized
            .replace(Regex("[xX\u00d7]$"), "")
            .trim()
        if (withoutCloseGlyph in actionableVisualLabels) return true
        val compoundParts = normalized
            .split('/', '\uff0f')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (compoundParts.size > 1 &&
            compoundParts.all { it in actionableVisualLabels }
        ) {
            return true
        }

        // 支持“立即领取、点击查看详情”等完整短标签，但不再进行任意 contains 匹配。
        return actionPrefixes.any { prefix ->
            normalized.startsWith(prefix) &&
                normalized.removePrefix(prefix) in prefixableActionWords
        }
    }

    fun isHighIntentVisualText(text: String): Boolean {
        return matchesActionLabel(text)
    }

    fun isActionableVisualText(text: String): Boolean {
        return matchesActionLabel(text)
    }

    fun shouldPreferActionOnlyVisionCandidates(
        packageName: String?,
        candidateCount: Int,
        rightEdgeCandidateCount: Int
    ): Boolean {
        return isDouyinPackage(packageName) && candidateCount <= 12 && rightEdgeCandidateCount >= 2
    }

    fun permitsVisualTextCandidate(text: String, actionOnly: Boolean): Boolean {
        val compact = text.trim()
        if (compact.isBlank()) return false
        if (isActionableVisualText(compact)) return true
        return !actionOnly && compact.length in 2..32
    }

    fun acceptsNavigationSemanticTarget(
        width: Int,
        height: Int,
        centerY: Float,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (width < 24 || height < 18 || screenWidth <= 0 || screenHeight <= 0) return false
        if (!isNavigationAnchor(centerY, screenHeight)) return false
        val area = width * height
        val screenArea = screenWidth * screenHeight
        return width < screenWidth * 0.62f &&
            height < screenHeight * 0.11f &&
            area < screenArea * 0.08f
    }

    fun isMeaningfulVerticalVisualScroll(
        horizontalDelta: Int,
        verticalDelta: Int,
        screenHeight: Int
    ): Boolean {
        if (screenHeight <= 0) return false
        val vertical = abs(verticalDelta)
        val horizontal = abs(horizontalDelta)
        val minimumVerticalMovement = (screenHeight * 0.025f).toInt().coerceAtLeast(1)
        return vertical >= minimumVerticalMovement && vertical >= horizontal
    }

    fun visualSurfaceAnchorMarker(
        nodes: List<NavNode>,
        screenWidth: Int,
        screenHeight: Int,
        divisions: Int = 24
    ): Int {
        if (screenWidth <= 0 || screenHeight <= 0) return 0
        val quantumX = (screenWidth / divisions).coerceAtLeast(1)
        val quantumY = (screenHeight / divisions).coerceAtLeast(1)
        return nodes.asSequence()
            .filter { it.origin == NavNodeOrigin.ACCESSIBILITY }
            .filter { isNavigationAnchor(it.centerY, screenHeight) }
            .map {
                "${it.bounds.centerX() / quantumX}:${it.bounds.centerY() / quantumY}:" +
                    "${it.bounds.width() / quantumX}:${it.bounds.height() / quantumY}"
            }
            .sorted()
            .joinToString("|")
            .hashCode()
    }

    fun visualFingerprintDistance(previous: Long, current: Long): Int {
        return java.lang.Long.bitCount(previous xor current)
    }

    fun hasMeaningfulVisualChange(
        previous: Long?,
        current: Long,
        threshold: Int = VISUAL_FINGERPRINT_DISTANCE_THRESHOLD
    ): Boolean {
        return previous != null && visualFingerprintDistance(previous, current) > threshold
    }

    fun mayRefreshDynamicVisualCache(
        lastCommitAtMs: Long,
        nowMs: Long,
        minimumIntervalMs: Long = VISUAL_REFRESH_THROTTLE_MS
    ): Boolean {
        return lastCommitAtMs <= 0L || nowMs - lastCommitAtMs >= minimumIntervalMs
    }

    fun hasEquivalentVisualGeometry(
        previous: List<NavNode>,
        replacement: List<NavNode>,
        minimumCoverage: Float = 0.8f,
        minimumIou: Float = 0.7f,
        maximumCountDelta: Int = 2
    ): Boolean {
        if (previous.isEmpty() || replacement.isEmpty()) return false
        if (abs(previous.size - replacement.size) > maximumCountDelta) return false
        val matched = previous.count { old ->
            replacement.any { next -> intersectionOverUnion(old.bounds, next.bounds) >= minimumIou }
        }
        return matched.toFloat() / previous.size.toFloat() >= minimumCoverage
    }

    fun closestEquivalentNode(target: NavNode, replacements: List<NavNode>, minimumIou: Float = 0.7f): NavNode? {
        return replacements
            .map { it to intersectionOverUnion(target.bounds, it.bounds) }
            .filter { it.second >= minimumIou }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun intersectionOverUnion(first: Rect, second: Rect): Float {
        val intersection = Rect(first)
        if (!intersection.intersect(second)) return 0f
        val intersectionArea = intersection.width().toFloat() * intersection.height().toFloat()
        val unionArea = first.width().toFloat() * first.height().toFloat() +
            second.width().toFloat() * second.height().toFloat() - intersectionArea
        return if (unionArea <= 0f) 0f else intersectionArea / unionArea
    }

    fun meshSignature(nodes: List<NavNode>, quantum: Int = 12): Int {
        if (nodes.isEmpty()) return 0
        return nodes.map { node ->
            val rect = node.bounds
            "${node.windowId}:${rect.left / quantum}:${rect.top / quantum}:" +
                "${rect.right / quantum}:${rect.bottom / quantum}:${node.clickMethod.name}"
        }.sorted().joinToString("|").hashCode()
    }

    fun actionableVisualSignature(nodes: List<NavNode>, quantum: Int = 48): Int {
        if (nodes.isEmpty()) return 0
        return nodes.map { node ->
            val rect = node.bounds
            val description = node.description.orEmpty()
            val kind = description.substringAfter("VISION[", "VISION")
                .substringBefore("]", "VISION")
            val label = if (kind == "ACTION" || kind == "ICON") {
                description.substringAfter("]:", "")
                    .replace("\\s+".toRegex(), "")
                    .take(8)
            } else {
                ""
            }
            "$kind:$label:${rect.left / quantum}:${rect.top / quantum}:" +
                "${rect.right / quantum}:${rect.bottom / quantum}"
        }.sorted().joinToString("|").hashCode()
    }

    fun isVisualSingleColumnList(
        bounds: List<Rect>,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (bounds.size < 8 || screenWidth <= 0 || screenHeight <= 0) return false
        val rowQuantum = (screenHeight / 18).coerceAtLeast(72)
        val rows = bounds.map { it.centerY() / rowQuantum }.toSet().size
        if (rows < 5) return false
        val leftAlignedText = bounds.count { rect ->
            rect.left in (screenWidth * 0.10f).toInt()..(screenWidth * 0.40f).toInt() &&
                rect.right > screenWidth * 0.44f &&
                rect.width() < screenWidth * 0.78f
        }
        val rightMeta = bounds.count { rect ->
            rect.left > screenWidth * 0.70f && rect.width() < screenWidth * 0.28f
        }
        val leadingColumn = bounds.count { rect ->
            rect.right < screenWidth * 0.24f &&
                rect.width() < screenWidth * 0.20f &&
                rect.height() > screenHeight * 0.018f
        }
        return leftAlignedText >= 4 && (rightMeta >= 2 || leadingColumn >= 3)
    }

    fun isTwoLaneVisualGrid(
        bounds: List<Rect>,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (bounds.size < 4 || screenWidth <= 0 || screenHeight <= 0) return false
        if (isVisualSingleColumnList(bounds, screenWidth, screenHeight)) return false
        val useful = bounds.filter { rect ->
            rect.centerY() in (screenHeight * 0.20f).toInt()..(screenHeight * 0.90f).toInt() &&
                rect.width() < screenWidth * 0.58f &&
                rect.height() < screenHeight * 0.12f
        }
        if (useful.size < 4) return false
        val left = useful.count { it.centerX() < screenWidth * 0.47f }
        val right = useful.count { it.centerX() > screenWidth * 0.53f }
        val middle = useful.count { it.centerX() in (screenWidth * 0.38f).toInt()..(screenWidth * 0.62f).toInt() }
        val crossing = useful.count { it.left < screenWidth * 0.44f && it.right > screenWidth * 0.56f }
        if (left < 2 || right < 2 || crossing > 1) return false
        val rowQuantum = (screenHeight / 14).coerceAtLeast(96)
        val rows = useful.map { it.centerY() / rowQuantum }.toSet().size
        if (rows < 2) return false
        return middle <= maxOf(1, useful.size / 3)
    }
}
