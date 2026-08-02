package com.example.touchengine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 无障碍树解析器。
 *
 * 职责：
 *  - collectNodesFromWindows：遍历所有窗口，调用 parseAccessibilityTree 汇总节点
 *  - parseAccessibilityTree：递归遍历单棵无障碍树，采集可点击节点
 *
 * 无状态设计：所有屏幕参数和 service 上下文通过 [Host] 接口注入，
 * parser 本身不持有 Service 引用，便于单独测试。
 *
 * 可变诊断状态（lastTaobaoParseDiagnostic / lastDouyinParseDiagnostic）由
 * host 维护并通过 Host.updateTaobaoDiagnostic / Host.updateDouyinDiagnostic 写回。
 */
class AccessibilityTreeParser(private val host: Host) {

    // ── Host 接口 ─────────────────────────────────────────
    interface Host {
        val screenWidth: Int
        val screenHeight: Int
        val screenArea: Int
        val servicePackageName: String       // Service 自身包名，用于过滤自身悬浮窗
        fun accessibilityWindowsSnapshot(): List<AccessibilityWindowInfo>?
        fun obtainPrimaryContentRoot(): AccessibilityNodeInfo?
        fun activeTargetPackage(): String?
        fun keyboardTop(): Int?
        fun lastTaobaoDiagnostic(): String?
        fun lastDouyinDiagnostic(): String?
        fun updateTaobaoDiagnostic(value: String)
        fun updateDouyinDiagnostic(value: String)
    }

    // ── 私有数据类 ────────────────────────────────────────
    private data class SubtreeProbeKey(val nodeHash: Int, val windowId: Int)

    private data class TaobaoSemanticCandidate(
        val node: NavNode,
        val band: EdgeInteractionRules.TaobaoContentBand,
        val insideScrollContent: Boolean
    )

    // ── 便捷属性 ──────────────────────────────────────────
    private val screenWidth  get() = host.screenWidth
    private val screenHeight get() = host.screenHeight
    private val screenArea   get() = host.screenArea

    // =====================================================
    // 公开入口
    // =====================================================

    fun collectNodesFromWindows(
        scanPass: MeshScanPass = MeshScanPass.FAST,
        probeBands: Set<EdgeInteractionRules.GenericContentBand> = emptySet()
    ): List<NavNode> {
        val allNodes = mutableListOf<NavNode>()
        val wins = host.accessibilityWindowsSnapshot()
        if (wins.isNullOrEmpty()) {
            // 兜底：windows 拿不到时退回单窗口模式
            val root = host.obtainPrimaryContentRoot() ?: return emptyList()
            try { allNodes.addAll(parseAccessibilityTree(root, scanPass, probeBands)) }
            finally { root.recycle() }
        } else {
            val targetPackage = host.activeTargetPackage()
            val sortedWins = wins.sortedByDescending { it.layer }
            for (window in sortedWins) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                // 跳过 Service 自身的悬浮球和高亮框窗口，避免自干扰
                if (root.packageName?.toString() == host.servicePackageName) {
                    root.recycle()
                    continue
                }
                if (targetPackage != null && root.packageName?.toString() != targetPackage) {
                    root.recycle()
                    continue
                }
                try {
                    allNodes.addAll(parseAccessibilityTree(root, scanPass, probeBands))
                } finally {
                    root.recycle()
                }
            }
        }
        return allNodes.distinctBy {
            "${it.windowId}:${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}"
        }
    }

    // =====================================================
    // 核心：递归遍历无障碍树
    // =====================================================

    /**
     * 递归遍历无障碍树，采集可点击节点。
     *
     * [FIX 2] child.recycle() 改为放在 finally 块中，
     *          保证遍历过程中即使发生异常也不会泄漏 ANI 对象。
     */
    fun parseAccessibilityTree(
        root: AccessibilityNodeInfo?,
        scanPass: MeshScanPass = MeshScanPass.FAST,
        probeBands: Set<EdgeInteractionRules.GenericContentBand> = emptySet()
    ): List<NavNode> {
        if (root == null) return emptyList()
        val isTaobao = isTaobaoPackage(root.packageName)
        val isDouyin = isDouyinPackage(root.packageName)
        val isComplexContent = EdgeInteractionRules.isHighFrequencyPackage(root.packageName?.toString())
        val isAnchorRescue = scanPass == MeshScanPass.ANCHOR_RESCUE
        val isHoleConfirm = scanPass == MeshScanPass.HOLE_CONFIRM
        val isDouyinVideoDeep = scanPass == MeshScanPass.DOUYIN_VIDEO_DEEP
        val keyboardBoundary = if (isDouyin) host.keyboardTop() else null
        val traversalBudget = when {
            isDouyinVideoDeep -> 2_400
            isAnchorRescue -> 1_600
            isHoleConfirm && isComplexContent -> 1_800
            isHoleConfirm -> 2_600
            else -> EdgeInteractionRules.traversalBudget(isComplexContent)
        }
        val nodeLimit = when {
            isDouyinVideoDeep -> 80
            isAnchorRescue -> 32
            isHoleConfirm -> 48
            else -> EdgeInteractionRules.nodeLimit(isComplexContent)
        }
        val maxTraversalDepth = when {
            isDouyinVideoDeep -> 14
            isAnchorRescue || isHoleConfirm -> 12
            else -> EdgeInteractionRules.maxTraversalDepth(isComplexContent)
        }
        val deadlineNs = when {
            isDouyinVideoDeep -> System.nanoTime() + 26_000_000L
            isAnchorRescue -> System.nanoTime() + 12_000_000L
            isHoleConfirm -> System.nanoTime() + 18_000_000L
            isComplexContent -> System.nanoTime() + 12_000_000L
            else -> Long.MAX_VALUE
        }
        val result = mutableListOf<NavNode>()
        val subtreeProbeCache = mutableMapOf<SubtreeProbeKey, Int>()
        val taobaoCandidates = mutableListOf<TaobaoSemanticCandidate>()
        val douyinCandidates = mutableListOf<NavNode>()
        val navigationCandidates = mutableListOf<NavNode>()
        val acceptedBounds = mutableSetOf<String>()
        var visitedNodes = 0
        var budgetExceeded = false

        fun addResult(node: NavNode) {
            val acceptedArea = when (scanPass) {
                MeshScanPass.FAST -> true
                MeshScanPass.ANCHOR_RESCUE ->
                    EdgeInteractionRules.isNavigationAnchor(node.centerY, screenHeight)
                MeshScanPass.HOLE_CONFIRM ->
                    EdgeInteractionRules.genericContentBand(node.centerY, screenHeight) in probeBands
                MeshScanPass.DOUYIN_VIDEO_DEEP ->
                    isDouyinVideoDeepTarget(node)
            }
            if (!acceptedArea) return
            val isNavigationAnchor = EdgeInteractionRules.isNavigationAnchor(node.centerY, screenHeight)
            if (result.size >= nodeLimit && !isNavigationAnchor) return
            val key = "${node.windowId}:${node.bounds.left}:${node.bounds.top}:${node.bounds.right}:${node.bounds.bottom}"
            if (acceptedBounds.add(key)) result.add(node)
        }

        fun traverse(
            node: AccessibilityNodeInfo,
            isParentScrollContainer: Boolean,
            insideScrollContent: Boolean,
            depth: Int
        ) {
            if (depth > maxTraversalDepth || ++visitedNodes > traversalBudget ||
                System.nanoTime() > deadlineNs
            ) {
                budgetExceeded = true
                return
            }
            val className = node.className?.toString() ?: ""
            val isScrollContainer = className.contains("RecyclerView")
                    || className.contains("ListView")
                    || className.contains("ScrollView")
                    || className.contains("GridView")
                    || className.contains("ViewPager")
                    || node.isScrollable
                    || (node.collectionInfo != null)

            val isNativeClickTarget = node.isClickable ||
                node.isFocusable ||
                AccessibilityNodeInfo.ACTION_CLICK in node.actionList.map { it.id }
            val isClickableTarget = if (isDouyinVideoDeep) {
                isNativeClickTarget
            } else {
                node.isClickable
                    || (isParentScrollContainer && !isScrollContainer)
                    || (node.collectionItemInfo != null)
                    || isWeChatListItem(node)
            }

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val area = rect.width() * rect.height()
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()
            val label = node.contentDescription?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            if (isAnchorRescue && node.isVisibleToUser && label != null &&
                EdgeInteractionRules.acceptsNavigationSemanticTarget(
                    rect.width(), rect.height(), cy, screenWidth, screenHeight
                )
            ) {
                navigationCandidates.add(NavNode(rect, label, node.windowId))
            }
            if (isTaobao && node.isVisibleToUser && isEligibleTaobaoSemanticBounds(rect)) {
                val band = EdgeInteractionRules.taobaoContentBand(cy, screenHeight)
                if (label != null && band != null) {
                    taobaoCandidates.add(
                        TaobaoSemanticCandidate(
                            NavNode(rect, label, node.windowId),
                            band,
                            insideScrollContent
                        )
                    )
                }
            }
            if (isDouyin && node.isVisibleToUser && label != null &&
                EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(
                    rect.width(), rect.height(), cy, screenHeight, keyboardBoundary
                )
            ) {
                douyinCandidates.add(NavNode(rect, label, node.windowId))
            }
            if (!isScrollContainer
                && node.isVisibleToUser
                && isClickableTarget
                && area > 400
                && area < screenArea * 0.75f
                && rect.width() > 0 && rect.height() > 0
                && cy > 0f && cy < screenHeight.toFloat()
                && cx > 0f && cx < screenWidth.toFloat()
            ) {
                val visitLimit = if (isAnchorRescue || isHoleConfirm) 64 else if (!isComplexContent) 160 else 24
                if (hasMultipleClickableChildren(node, subtreeProbeCache, visitLimit)) {
                    // 容器下有 >= 2 个独立可点击子节点，放行继续递归
                } else {
                    addResult(
                        NavNode(
                            bounds      = rect,
                            description = node.contentDescription?.toString()
                                ?: node.text?.toString(),
                            windowId    = node.windowId
                        )
                    )
                    return
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    traverse(child, isScrollContainer, insideScrollContent || isScrollContainer, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }

        traverse(root, false, false, 0)

        if (isAnchorRescue) {
            val maxPerEdge = 12
            for (isTop in listOf(true, false)) {
                var added = 0
                val ordered = navigationCandidates
                    .filter { (it.centerY < screenHeight * 0.50f) == isTop }
                    .sortedWith(compareBy({ it.centerY }, { it.centerX }))
                for (candidate in ordered) {
                    if (added >= maxPerEdge) break
                    val covered = result.any {
                        it.bounds.contains(candidate.centerX.toInt(), candidate.centerY.toInt())
                    }
                    if (!covered) { addResult(candidate); added++ }
                }
            }
        }
        if (isTaobao) {
            val (fallback, diagnostic) = selectTaobaoSemanticFallbackNodes(taobaoCandidates, result)
            fallback.forEach(::addResult)
            val report = "pass=$scanPass,$diagnostic,total=${result.size},fallback=${fallback.size},visited=$visitedNodes,limited=$budgetExceeded"
            if (report != host.lastTaobaoDiagnostic()) {
                Log.d("TouchEngineTaobao", "bands $report")
                host.updateTaobaoDiagnostic(report)
            }
        }
        if (isDouyin && keyboardBoundary != null) {
            val fallback = selectDouyinSearchFallbackNodes(douyinCandidates, result)
            fallback.forEach(::addResult)
            val report = "pass=$scanPass,imeTop=$keyboardBoundary,regular=${result.size - fallback.size},candidates=${douyinCandidates.size},fallback=${fallback.size},visited=$visitedNodes,limited=$budgetExceeded"
            if (report != host.lastDouyinDiagnostic()) {
                Log.d("TouchEngineDouyin", "search $report")
                host.updateDouyinDiagnostic(report)
            }
        }
        return result
    }

    // =====================================================
    // 私有 Helper 函数
    // =====================================================

    /**
     * 探测一个节点下是否包含至少 2 个独立可见的可点击子节点。
     */
    private fun hasMultipleClickableChildren(
        node: AccessibilityNodeInfo,
        cache: MutableMap<SubtreeProbeKey, Int>,
        visitLimit: Int = Int.MAX_VALUE
    ): Boolean {
        var remainingVisits = visitLimit
        fun countCapped(n: AccessibilityNodeInfo): Int {
            val key = SubtreeProbeKey(n.hashCode(), n.windowId)
            cache[key]?.let { return it }
            var count = 0
            for (i in 0 until n.childCount) {
                if (remainingVisits-- <= 0) return 2
                val child = n.getChild(i) ?: continue
                try {
                    if (child.isVisibleToUser && child.isClickable) {
                        count++
                        if (count >= 2) { cache[key] = 2; return 2 }
                    }
                    count += countCapped(child)
                    if (count >= 2) { cache[key] = 2; return 2 }
                } finally {
                    child.recycle()
                }
            }
            cache[key] = count
            return count
        }
        return countCapped(node) >= 2
    }

    /**
     * 判断一个节点是否是真正的微信聊天列表项。
     *
     * Phase 9B：不再只凭“微信包名 + 宽容器 + 一个文字”放行。无障碍侧必须同时看到：
     *  - 左侧近似方形头像/图标；
     *  - 中部有效标题或摘要；
     *  - 右侧时间/日期状态。
     * 这样通讯录、设置、聊天内容和普通菜单不会被误认为聊天长条。
     */
    private fun isWeChatListItem(node: AccessibilityNodeInfo): Boolean {
        if (node.packageName?.toString() != "com.tencent.mm" || !node.isVisibleToUser) return false
        val className = node.className?.toString().orEmpty()
        if (className.contains("TextView") ||
            className.contains("ImageView") ||
            className.contains("Button") ||
            className.contains("EditText") ||
            className.contains("ListView") ||
            className.contains("RecyclerView") ||
            className.contains("ScrollView") ||
            node.isScrollable
        ) {
            return false
        }

        val rowBounds = Rect()
        node.getBoundsInScreen(rowBounds)
        val widthRatio = rowBounds.width() / screenWidth.toFloat().coerceAtLeast(1f)
        val heightRatio = rowBounds.height() / screenHeight.toFloat().coerceAtLeast(1f)
        if (widthRatio < 0.72f || heightRatio !in 0.035f..0.16f) return false
        if (rowBounds.centerY() < screenHeight * 0.12f ||
            rowBounds.centerY() > screenHeight * 0.92f
        ) {
            return false
        }

        var visited = 0
        var textViewCount = 0
        var hasLeftImage = false
        var hasMiddleText = false
        var hasRightTimestamp = false

        fun inspectChildren(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > 4 || visited >= 48) return
            for (i in 0 until current.childCount) {
                if (visited++ >= 48) return
                val child = current.getChild(i) ?: continue
                try {
                    if (!child.isVisibleToUser) continue
                    val childBounds = Rect()
                    child.getBoundsInScreen(childBounds)
                    if (childBounds.isEmpty) continue
                    val childClass = child.className?.toString().orEmpty()
                    val compactText = (child.contentDescription?.toString()
                        ?: child.text?.toString())
                        ?.replace("\\s+".toRegex(), "")
                        .orEmpty()

                    val aspect = childBounds.width() /
                        childBounds.height().toFloat().coerceAtLeast(1f)
                    val imageLikeChild = childClass.contains("ImageView") ||
                        (compactText.isBlank() && child.childCount == 0)
                    if (imageLikeChild) {
                        val leftImage = childBounds.centerX() < screenWidth * 0.25f &&
                            childBounds.width() in 36..(screenWidth * 0.24f).roundToInt() &&
                            childBounds.height() in 36..(screenHeight * 0.12f).roundToInt() &&
                            aspect in 0.60f..1.65f
                        if (leftImage) hasLeftImage = true
                    }

                    if (compactText.isNotBlank()) {
                        textViewCount++
                        val centerX = childBounds.centerX()
                        if (centerX > screenWidth * 0.68f && isWeChatTimestampText(compactText)) {
                            hasRightTimestamp = true
                        } else if (centerX in
                            (screenWidth * 0.12f).roundToInt()..(screenWidth * 0.80f).roundToInt() &&
                            compactText.length in 1..40 &&
                            !isWeChatTimestampText(compactText)
                        ) {
                            hasMiddleText = true
                        }
                    }

                    if (child.childCount > 0 &&
                        !childClass.contains("ListView") &&
                        !childClass.contains("RecyclerView") &&
                        !childClass.contains("ScrollView")
                    ) {
                        inspectChildren(child, depth + 1)
                    }
                } finally {
                    child.recycle()
                }
            }
        }

        inspectChildren(node, 0)
        return hasLeftImage && hasMiddleText && hasRightTimestamp && textViewCount in 2..8
    }

    private fun isWeChatTimestampText(text: String): Boolean {
        val compact = text.replace("\\s+".toRegex(), "")
        if (compact.isBlank() || compact.length > 10) return false
        return compact == "昨天" ||
            compact == "今天" ||
            compact == "刚刚" ||
            compact.startsWith("星期") ||
            compact.startsWith("周") ||
            compact.matches(Regex("\\d{1,2}:\\d{2}")) ||
            compact.matches(Regex("\\d{1,2}[/-]\\d{1,2}"))
    }

    /**
     * 抖音搜索场景：从候选中补充未被普通节点覆盖的搜索结果。
     */
    private fun selectDouyinSearchFallbackNodes(
        candidates: List<NavNode>,
        existingNodes: List<NavNode>
    ): List<NavNode> {
        val fallback = mutableListOf<NavNode>()
        for (candidate in candidates.sortedWith(compareBy({ it.centerY }, { it.centerX }))) {
            if (fallback.size >= 18) break
            val cx = candidate.centerX.roundToInt()
            val cy = candidate.centerY.roundToInt()
            val covered = existingNodes.any { it.bounds.contains(cx, cy) } ||
                fallback.any {
                    hypot(
                        (it.centerX - candidate.centerX).toDouble(),
                        (it.centerY - candidate.centerY).toDouble()
                    ) < 28.0
                }
            if (!covered) fallback.add(candidate)
        }
        return fallback
    }

    private fun isEligibleTaobaoSemanticBounds(rect: Rect): Boolean {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        return rect.width() >= 44 &&
            rect.height() >= 20 &&
            rect.width() * rect.height() < screenArea * 0.18f &&
            cx > 0f && cx < screenWidth.toFloat() &&
            EdgeInteractionRules.taobaoContentBand(cy, screenHeight) != null
    }

    /**
     * 淘宝语义节点补全：仅为空洞内容带补入少量语义节点。
     */
    private fun selectTaobaoSemanticFallbackNodes(
        candidates: List<TaobaoSemanticCandidate>,
        existingNodes: List<NavNode>
    ): Pair<List<NavNode>, String> {
        val fallback = mutableListOf<NavNode>()
        val fineExisting = existingNodes.filter {
            it.bounds.width() * it.bounds.height() < screenArea * 0.18f
        }
        val maxPerBand = 10
        val reports = mutableListOf<String>()
        for (band in EdgeInteractionRules.TaobaoContentBand.entries) {
            val regularCount = fineExisting.count {
                EdgeInteractionRules.taobaoContentBand(it.centerY, screenHeight) == band
            }
            val bandCandidates = candidates
                .filter { it.band == band }
                .sortedWith(
                    compareByDescending<TaobaoSemanticCandidate> { it.insideScrollContent }
                        .thenBy { it.node.centerY }
                        .thenBy { it.node.centerX }
                )
            var added = 0
            if (EdgeInteractionRules.shouldBackfillTaobaoBand(regularCount, bandCandidates.size)) {
                for (candidate in bandCandidates) {
                    if (added >= maxPerBand) break
                    val cx = candidate.node.centerX.roundToInt()
                    val cy = candidate.node.centerY.roundToInt()
                    val covered = fineExisting.any { it.bounds.contains(cx, cy) } ||
                        fallback.any {
                            hypot(
                                (it.centerX - candidate.node.centerX).toDouble(),
                                (it.centerY - candidate.node.centerY).toDouble()
                            ) < 28.0
                        }
                    if (!covered) { fallback.add(candidate.node); added++ }
                }
            }
            reports.add("${band.name.lowercase()}=$regularCount/${bandCandidates.size}/$added")
        }
        return Pair(fallback, reports.joinToString(","))
    }

    /**
     * 判断一个 NavNode 是否是抖音视频流中需要深度采集的目标。
     */
    private fun isDouyinVideoDeepTarget(node: NavNode): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        if (screenHeight < screenWidth) return false
        val bounds = node.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val xRatio = node.centerX / screenWidth.toFloat()
        val yRatio = node.centerY / screenHeight.toFloat()
        val areaRatio = bounds.areaLong() / screenArea.toFloat().coerceAtLeast(1f)
        if (areaRatio > 0.20f) return false
        val topNav = yRatio in 0.035f..0.18f &&
            bounds.height() <= screenHeight * 0.14f &&
            bounds.width() <= screenWidth * 0.36f
        val rightRail = xRatio >= 0.70f &&
            yRatio in 0.28f..0.94f &&
            bounds.height() <= screenHeight * 0.18f &&
            bounds.width() <= screenWidth * 0.36f
        val bottomTab = yRatio >= 0.86f &&
            bounds.height() <= screenHeight * 0.16f &&
            bounds.width() <= screenWidth * 0.36f
        val floatingAction = xRatio in 0.35f..0.78f &&
            yRatio in 0.76f..0.92f &&
            bounds.height() <= screenHeight * 0.12f &&
            bounds.width() <= screenWidth * 0.52f
        if (!(topNav || rightRail || bottomTab || floatingAction)) return false
        val label = normalizedVisualText(node.description.orEmpty())
        if (label.length > 18 && !(rightRail || floatingAction)) return false
        if (label.contains("#") && !rightRail) return false
        return true
    }

    private fun isTaobaoPackage(packageName: CharSequence?): Boolean =
        EdgeInteractionRules.isTaobaoPackage(packageName?.toString())

    private fun isDouyinPackage(packageName: CharSequence?): Boolean =
        EdgeInteractionRules.isDouyinPackage(packageName?.toString())

    private fun normalizedVisualText(text: String): String =
        text.trim().replace("\\s+".toRegex(), "")

    private fun Rect.areaLong(): Long = width().toLong() * height().toLong()
}
