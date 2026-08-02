package com.example.touchengine

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlin.math.roundToInt

/**
 * 视觉候选生成与筛选引擎。
 *
 * 只处理一次扫描快照：OCR / YOLO 证据、布局分类、卡片与列表推断、候选打分、
 * 去重和 NavNode 转换。它不持有 AccessibilityService，不发起截图，不修改缓存、
 * 焦点或 NavMesh，因此可以独立测试。
 */
internal data class VisualProcessingContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val keyboardTop: Int?,
    val joystickBounds: Rect?,
    val inputMethodVisible: Boolean,
    val accessibilityNodes: List<NavNode>
)

internal class VisualCandidateEngine(
    private val context: VisualProcessingContext
) {
    private val STRONG_YOLO_CLASSES = setOf(
        "bottomnavigation",
        "checkbox",
        "checkedtextview",
        "edittext",
        "multitab",
        "spinner",
        "switch",
        "textbutton"
    )
    private val ALL_YOLO_EVIDENCE_CLASSES = setOf(
        "bottomnavigation",
        "card",
        "checkbox",
        "checkedtextview",
        "drawer",
        "edittext",
        "icon",
        "image",
        "map",
        "modal",
        "multitab",
        "pageindicator",
        "remember",
        "spinner",
        "switch",
        "text",
        "textbutton",
        "toolbar",
        "uppertaskbar"
    )
    private val screenWidth: Int get() = context.screenWidth
    private val screenHeight: Int get() = context.screenHeight

    /**
     * Phase 8B.1: red color regions are only weak anchors. They must never become
     * selectable cards by themselves.
     */
    private data class CardShapeAnchor(
        val bounds: Rect,
        val anchorY: Int
    )

    /**
     * A card hypothesis backed by independent evidence. The score is used only to
     * choose one candidate when several heuristics describe the same product cell.
     */
    private data class CardHypothesis(
        val bounds: Rect,
        val label: String,
        val rowIndex: Int,
        val cellIndex: Int,
        val score: Int,
        val hasPrice: Boolean,
        val hasTitle: Boolean,
        val hasPromo: Boolean,
        val hasYolo: Boolean,
        val yoloScore: Float?,
        val hasShape: Boolean
    )

    private data class PriceRow(
        val centerY: Int,
        val evidence: List<VisualOcrCandidate>
    )

    private data class PriceCell(
        val rowIndex: Int,
        val cellIndex: Int,
        val left: Int,
        val right: Int,
        val rowTopLimit: Int,
        val rowBottomLimit: Int,
        val priceEvidence: List<VisualOcrCandidate>
    )

    /**
     * 微信聊天列表中的一条结构化消息行。
     *
     * Phase 9B.1：左侧头像/方形图标是稳定的行锚点，中部标题或摘要是必需证据；
     * 右侧时间只用于增强置信度，不能再作为生成聊天行的硬条件。
     */
    private data class WeChatMessageRow(
        val centerY: Int,
        val title: VisualOcrCandidate,
        val timestamp: VisualOcrCandidate?,
        val textEvidence: List<VisualOcrCandidate>,
        val leftAnchor: Rect,
        val anchorSources: Set<TargetSource>
    )

    private data class WeChatRowAnchor(
        val bounds: Rect,
        val sources: Set<TargetSource>
    )

    private fun keyboardTop(): Int? = context.keyboardTop

    private fun isLikelyKeyboardCandidate(bounds: Rect): Boolean {
        val top = context.keyboardTop ?: return false
        return bounds.centerY() >= top - 8 && bounds.bottom > top
    }

    private fun isLikelySelfOverlayCandidate(bounds: Rect): Boolean {
        val joystickBounds = context.joystickBounds ?: return false
        val candidateArea = bounds.areaLong().coerceAtLeast(1L)
        val joystickArea = joystickBounds.areaLong().coerceAtLeast(1L)
        val areaRatio = candidateArea / joystickArea.toFloat()
        val compactCandidate = areaRatio in 0.18f..2.8f &&
            bounds.width() < screenWidth * 0.28f &&
            bounds.height() < screenHeight * 0.20f
        if (!compactCandidate) return false
        return rectIou(bounds, joystickBounds) > 0.08f ||
            containsMost(joystickBounds, bounds, 0.35f) ||
            containsMost(bounds, joystickBounds, 0.70f)
    }
    private fun isUsableOcrEvidenceBounds(bounds: Rect): Boolean {
        val keyboardTop = context.keyboardTop
        val inKeyboardRegion = keyboardTop != null && bounds.bottom > keyboardTop - 8
        return bounds.width() >= 10 &&
            bounds.height() >= 10 &&
            bounds.height() < screenHeight * 0.13f &&
            bounds.width() < screenWidth * 0.88f &&
            !isLikelyStatusBarCandidate(bounds) &&
            !isLikelySystemNavigationCandidate(bounds) &&
            !inKeyboardRegion &&
            !isLikelySelfOverlayCandidate(bounds)
    }

    internal fun buildNavigationNodes(
        candidates: List<VisualOcrCandidate>,
        targetPackage: String?,
        cropped: Bitmap,
        cropBounds: Rect,
        allowTextStructures: Boolean,
        yoloResult: YoloUiResult? = null
    ): List<NavNode> {
        val visualCandidates = candidates.filterNot { isLikelyKeyboardCandidate(it.bounds) }
        val accessibilityNodes = context.accessibilityNodes
            .filterNot { it.origin == NavNodeOrigin.VISION }
        val primaryWindowId = accessibilityNodes.groupingBy { it.windowId }.eachCount()
            .maxByOrNull { it.value }?.key ?: 0
        val isWechatAdapted = targetPackage == "com.tencent.mm"
        val supportsGenericStructuredRows = allowTextStructures &&
            !EdgeInteractionRules.isDouyinPackage(targetPackage) &&
            (isWechatAdapted || EdgeInteractionRules.isHighFrequencyPackage(targetPackage))
        val isTextOnlyPackage = EdgeInteractionRules.isHighFrequencyPackage(targetPackage) && !isWechatAdapted
        val rightRailCandidates = emptyList<VisualOcrCandidate>()
        val rightEdgeCount = visualCandidates.count { it.bounds.centerX() > screenWidth * 0.78f }
        val actionOnly = EdgeInteractionRules.shouldPreferActionOnlyVisionCandidates(
            targetPackage,
            visualCandidates.size,
            rightEdgeCount
        )
        val allowStructuredTargets = allowTextStructures &&
            supportsGenericStructuredRows
        val actionCandidates = visualCandidates.asSequence()
            .filter { EdgeInteractionRules.isActionableVisualText(it.text) }
            .map {
                it.copy(
                    kind = if (isIconEntryText(it.text)) {
                        VisualCandidateKind.ICON_ENTRY
                    } else {
                        VisualCandidateKind.ACTION_TEXT
                    }
                )
            }
            .toList()
        val textOnlyCandidates = if (isTextOnlyPackage) {
            buildTextOnlyVisualCandidates(visualCandidates, actionCandidates)
        } else {
            emptyList()
        }
        // Build YOLO evidence before deciding whether a WeChat screen is really a
        // repeated chat list. Phase 9B.1 anchors rows by left avatars / square icons;
        // right-side timestamps are optional evidence only.
        val yoloCandidates = yoloResult?.let {
            buildYoloVisualCandidates(it, cropBounds, targetPackage)
        }.orEmpty()
        val weChatMessageRows = if (isWechatAdapted && allowStructuredTargets) {
            detectWeChatMessageRows(
                candidates = visualCandidates,
                yoloCandidates = yoloCandidates,
                accessibilityNodes = accessibilityNodes
            )
        } else {
            emptyList()
        }
        val allowMessageRowTargets = supportsGenericStructuredRows &&
            if (isWechatAdapted) {
                weChatMessageRows.size >= 2
            } else {
                isLikelyMessageListSurface(visualCandidates, targetPackage)
            }
        val layoutKind = if (allowStructuredTargets) {
            classifyVisualLayout(
                candidates = visualCandidates,
                actionCandidates = actionCandidates,
                accessibilityNodes = accessibilityNodes,
                targetPackage = targetPackage,
                confirmedMessageList = allowMessageRowTargets
            )
        } else {
            VisualLayoutKind.UNKNOWN
        }
        val shapeAnchors = if (allowStructuredTargets &&
            (layoutKind == VisualLayoutKind.TWO_COLUMN_GRID || layoutKind == VisualLayoutKind.MULTI_COLUMN_GRID)
        ) {
            buildShapeEvidenceAnchors(cropped, cropBounds, targetPackage)
        } else {
            emptyList()
        }
        val structureCandidates = when {
            !allowStructuredTargets -> emptyList()
            actionOnly -> emptyList()
            layoutKind == VisualLayoutKind.SINGLE_COLUMN_LIST && allowMessageRowTargets ->
                if (isWechatAdapted) {
                    buildWeChatListItemCandidates(weChatMessageRows)
                } else {
                    buildVisualListItemCandidates(visualCandidates, actionCandidates)
                }
            layoutKind == VisualLayoutKind.TWO_COLUMN_GRID ||
                layoutKind == VisualLayoutKind.MULTI_COLUMN_GRID ->
                buildVisualCardCandidates(
                    candidates = visualCandidates,
                    actionCandidates = actionCandidates,
                    targetPackage = targetPackage,
                    shapeAnchors = shapeAnchors,
                    yoloCandidates = yoloCandidates
                )
            else -> emptyList()
        }
        val fusedYoloCandidates = if (yoloCandidates.isNotEmpty()) {
            buildFusedYoloCandidates(yoloCandidates, visualCandidates, actionCandidates, targetPackage)
        } else {
            emptyList()
        }
        val structuredListThreshold = if (isWechatAdapted) 2 else 3
        val shouldPreferStructuredList =
            allowMessageRowTargets &&
            layoutKind == VisualLayoutKind.SINGLE_COLUMN_LIST &&
                structureCandidates.count { it.kind == VisualCandidateKind.LIST_ITEM_TARGET } >= structuredListThreshold
        if (isWechatAdapted) {
            Log.d(
                "TouchEngineWeChat",
                "messageList=$allowMessageRowTargets rows=${weChatMessageRows.size} " +
                    "listTargets=${structureCandidates.count { it.kind == VisualCandidateKind.LIST_ITEM_TARGET }} " +
                    "ocr=${visualCandidates.size} yolo=${yoloCandidates.size} layout=$layoutKind"
            )
        }
        if (fusedYoloCandidates.isNotEmpty() || shouldPreferStructuredList) {
            val primaryYoloFusion = if (shouldPreferStructuredList) emptyList() else fusedYoloCandidates
            val supplementalText = if (shouldPreferStructuredList) {
                emptyList()
            } else {
                buildSupplementalTextCandidatesForYoloFusion(
                    textEvidence = visualCandidates,
                    fusedYoloCandidates = primaryYoloFusion,
                    actionCandidates = actionCandidates,
                    targetPackage = targetPackage,
                    allowStructuredTargets = allowStructuredTargets
                )
            }
            val sparseTextFallback = if (!shouldPreferStructuredList &&
                isTextOnlyPackage &&
                fusedYoloCandidates.size < 8
            ) {
                textOnlyCandidates
            } else {
                emptyList()
            }
            val usable = finalizeVisualNavigationCandidates(
                candidates = primaryYoloFusion + structureCandidates + supplementalText + sparseTextFallback,
                targetPackage = targetPackage,
                shouldPreferStructuredList = shouldPreferStructuredList
            )
            Log.d(
                "TouchEngineVision",
                "yoloFusion backend=${yoloResult?.backendName} yolo=${yoloCandidates.size} " +
                    "fused=${fusedYoloCandidates.size} structures=${structureCandidates.size} " +
                    "supplemental=${supplementalText.size} sparseText=${sparseTextFallback.size} actions=${actionCandidates.size} " +
                    "usable=${usable.size} textEvidence=${visualCandidates.size} layout=$layoutKind pkg=$targetPackage"
            )
            return visualCandidatesToNavNodes(usable, primaryWindowId)
        }
        val cardCount = structureCandidates.count {
            it.kind == VisualCandidateKind.CARD_TARGET || it.kind == VisualCandidateKind.LIST_ITEM_TARGET
        }
        Log.d(
            "TouchEngineVision",
            "layout=$layoutKind textEvidence=${candidates.size} actions=${actionCandidates.size} " +
                "textOnly=${textOnlyCandidates.size} shapeAnchors=${shapeAnchors.size} " +
                "structures=$cardCount rail=${rightRailCandidates.size} pkg=$targetPackage"
        )
        val usable = finalizeVisualNavigationCandidates(
            candidates = rightRailCandidates + actionCandidates + textOnlyCandidates + structureCandidates,
            targetPackage = targetPackage,
            shouldPreferStructuredList = false,
            hardLimit = if (actionOnly) 18 else null
        )
        return visualCandidatesToNavNodes(usable, primaryWindowId)
    }

    /**
     * 快速路径：ACTION 级 YOLO 完成后即可生成可导航节点，不依赖 OCR 文本或融合结果。
     */
    internal fun buildDirectYoloNavigationNodes(
        result: YoloUiResult,
        cropBounds: Rect,
        targetPackage: String?
    ): List<NavNode> {
        val primaryWindowId = context.accessibilityNodes
            .filterNot { it.origin == NavNodeOrigin.VISION }
            .groupingBy { it.windowId }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 0
        val direct = buildYoloVisualCandidates(result, cropBounds, targetPackage)
            .asSequence()
            .filter { it.tier == YoloEvidenceTier.ACTION }
            .map { yolo ->
                VisualOcrCandidate(
                    bounds = Rect(yolo.bounds),
                    text = yoloFallbackLabel(yolo, targetPackage),
                    kind = yolo.kind,
                    sourceSet = setOf(TargetSource.YOLO),
                    yoloClass = normalizedYoloClass(yolo.label),
                    yoloConfidence = yolo.score,
                    evidenceScore = when (yolo.kind) {
                        VisualCandidateKind.ACTION_TEXT -> 72
                        VisualCandidateKind.ICON_ENTRY -> 70
                        VisualCandidateKind.CARD_TARGET -> 66
                        else -> 64
                    },
                    directEligible = true,
                    clickMethod = ClickMethod.GESTURE_CENTER
                )
            }
            .toList()
        return visualCandidatesToNavNodes(
            finalizeVisualNavigationCandidates(
                candidates = direct,
                targetPackage = targetPackage,
                shouldPreferStructuredList = false
            ),
            primaryWindowId
        )
    }

    /**
     * 快速路径：只让已有“可操作文本”规则认可的 OCR 结果直入，不扩大 OCR 命中范围。
     */
    internal fun buildDirectOcrNavigationNodes(
        candidates: List<VisualOcrCandidate>,
        targetPackage: String?
    ): List<NavNode> {
        val primaryWindowId = context.accessibilityNodes
            .filterNot { it.origin == NavNodeOrigin.VISION }
            .groupingBy { it.windowId }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 0
        val direct = candidates.asSequence()
            .filter { isUsableOcrEvidenceBounds(it.bounds) }
            .filter { EdgeInteractionRules.isActionableVisualText(it.text) }
            .map { candidate ->
                val compact = normalizedVisualText(candidate.text)
                candidate.copy(
                    text = compact,
                    kind = if (isIconEntryText(compact)) {
                        VisualCandidateKind.ICON_ENTRY
                    } else {
                        VisualCandidateKind.ACTION_TEXT
                    },
                    sourceSet = setOf(TargetSource.OCR),
                    evidenceScore = candidate.evidenceScore.coerceAtLeast(70),
                    directEligible = true,
                    clickMethod = ClickMethod.GESTURE_CENTER
                )
            }
            .toList()
        return visualCandidatesToNavNodes(
            finalizeVisualNavigationCandidates(
                candidates = direct,
                targetPackage = targetPackage,
                shouldPreferStructuredList = false
            ),
            primaryWindowId
        )
    }

    private fun finalizeVisualNavigationCandidates(
        candidates: List<VisualOcrCandidate>,
        targetPackage: String?,
        shouldPreferStructuredList: Boolean,
        hardLimit: Int? = null
    ): List<VisualOcrCandidate> {
        val highFrequencyPackage = EdgeInteractionRules.isHighFrequencyPackage(targetPackage) &&
            targetPackage != "com.tencent.mm"
        val minimumScore = 50
        val scored = candidates.asSequence()
            .filterNot {
                isLikelyStatusBarCandidate(it.bounds) ||
                    isLikelySystemNavigationCandidate(it.bounds) ||
                    isLikelyKeyboardCandidate(it.bounds) ||
                    isLikelySelfOverlayCandidate(it.bounds) ||
                    isLikelySelfOverlayText(it.text)
            }
            // Phase 8B: do not allow an over-merged structure candidate to become
            // a green navigation target.  Debug evidence is still available in the
            // diagnostic overlay, but abnormal geometry stops here.
            .filter(::isSafeFinalVisualGeometry)
            .distinctBy {
                "${it.bounds.left / 8}:${it.bounds.top / 8}:${it.bounds.right / 8}:${it.bounds.bottom / 8}"
            }
            .map { it to finalVisualTargetScore(it, targetPackage, shouldPreferStructuredList) }
            .filter { (_, score) -> score >= minimumScore }
            .toList()
        val structureBounds = scored
            .map { it.first }
            .filter {
                it.kind == VisualCandidateKind.CARD_TARGET ||
                    it.kind == VisualCandidateKind.LIST_ITEM_TARGET ||
                    (it.kind == VisualCandidateKind.ICON_ENTRY &&
                        it.bounds.width() > screenWidth * 0.18f &&
                        it.bounds.height() > screenHeight * 0.06f)
            }
            .map { it.bounds }
        val cleaned = scored.filterNot { (candidate, _) ->
            val compact = normalizedVisualText(candidate.text)
            val textLike = candidate.kind == VisualCandidateKind.ACTION_TEXT ||
                candidate.kind == VisualCandidateKind.TEXT_EVIDENCE
            val protectedText = EdgeInteractionRules.isActionableVisualText(compact) ||
                isCompactNavigationText(compact) ||
                isIconEntryText(compact) ||
                isLikelyBottomNavigationCandidate(candidate.bounds) ||
                isLikelyTopNavigationCandidate(candidate.bounds)
            textLike && !protectedText &&
                structureBounds.any { structure ->
                    structure != candidate.bounds &&
                        containsMost(structure, candidate.bounds, 0.78f) &&
                        structure.areaLong() > candidate.bounds.areaLong() * 3L
                }
        }
        val limit = hardLimit ?: 96
        val arbitrated = arbitrateFinalVisualCandidates(cleaned)
        return arbitrated
            .sortedWith(
                compareByDescending<Pair<VisualOcrCandidate, Int>> { it.second }
                    .thenBy { it.first.bounds.areaLong() }
                    .thenBy { it.first.bounds.top }
                    .thenBy { it.first.bounds.left }
            )
            .take(limit)
            .map { it.first }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /**
     * Phase 8B.3 final single-frame arbitration.
     *
     * The OCR structure pass and the YOLO corroboration pass may describe the same
     * region with slightly different rectangles. Only one candidate in the same
     * semantic family may win a region. Actions nested inside a card remain valid;
     * only competing cards/lists/actions are mutually exclusive.
     */
    private fun arbitrateFinalVisualCandidates(
        scored: List<Pair<VisualOcrCandidate, Int>>
    ): List<Pair<VisualOcrCandidate, Int>> {
        val ordered = scored.sortedWith(
            compareByDescending<Pair<VisualOcrCandidate, Int>> { it.second }
                .thenBy { it.first.bounds.areaLong() }
                .thenBy { it.first.bounds.top }
                .thenBy { it.first.bounds.left }
        )
        val kept = mutableListOf<Pair<VisualOcrCandidate, Int>>()
        ordered.forEach { candidate ->
            val conflict = kept.any { existing ->
                visualCandidatesCompete(existing.first, candidate.first)
            }
            if (!conflict) kept += candidate
        }
        return kept
    }

    private fun visualCandidatesCompete(
        first: VisualOcrCandidate,
        second: VisualOcrCandidate
    ): Boolean {
        if (!sameVisualCompetitionFamily(first.kind, second.kind)) return false
        val a = first.bounds
        val b = second.bounds
        val overlapOverSmaller = overlapArea(a, b) /
            minOf(a.areaLong(), b.areaLong()).toFloat().coerceAtLeast(1f)
        if (rectIou(a, b) >= 0.16f || overlapOverSmaller >= 0.46f) return true
        if (containsMost(a, b, 0.60f) || containsMost(b, a, 0.60f)) return true

        if (first.kind == VisualCandidateKind.CARD_TARGET &&
            second.kind == VisualCandidateKind.CARD_TARGET
        ) {
            val horizontalOverlap = intersectionWidth(a, b) /
                minOf(a.width(), b.width()).toFloat().coerceAtLeast(1f)
            val verticalOverlap = intersectionHeight(a, b) /
                minOf(a.height(), b.height()).toFloat().coerceAtLeast(1f)
            val centerDistance = kotlin.math.abs(a.centerY() - b.centerY())
            return horizontalOverlap >= 0.58f &&
                (verticalOverlap >= 0.22f || centerDistance <= minOf(a.height(), b.height()) * 0.58f)
        }
        return false
    }

    private fun sameVisualCompetitionFamily(
        first: VisualCandidateKind,
        second: VisualCandidateKind
    ): Boolean {
        if (first == VisualCandidateKind.CARD_TARGET || second == VisualCandidateKind.CARD_TARGET) {
            return first == VisualCandidateKind.CARD_TARGET && second == VisualCandidateKind.CARD_TARGET
        }
        if (first == VisualCandidateKind.LIST_ITEM_TARGET || second == VisualCandidateKind.LIST_ITEM_TARGET) {
            return first == VisualCandidateKind.LIST_ITEM_TARGET && second == VisualCandidateKind.LIST_ITEM_TARGET
        }
        val firstAction = first == VisualCandidateKind.ACTION_TEXT ||
            first == VisualCandidateKind.ICON_ENTRY ||
            first == VisualCandidateKind.RIGHT_RAIL_ICON
        val secondAction = second == VisualCandidateKind.ACTION_TEXT ||
            second == VisualCandidateKind.ICON_ENTRY ||
            second == VisualCandidateKind.RIGHT_RAIL_ICON
        return firstAction && secondAction
    }

    private fun overlapArea(first: Rect, second: Rect): Long =
        intersectionWidth(first, second).toLong() * intersectionHeight(first, second).toLong()

    /**
     * Final geometry guard for one scan batch.
     *
     * OCR grouping is heuristic. A chain of individually reasonable text boxes can
     * occasionally bridge two product rows. This guard prevents that chain from
     * being promoted to a selectable node while leaving the raw debug rectangles
     * visible for diagnosis.
     */
    private fun isSafeFinalVisualGeometry(candidate: VisualOcrCandidate): Boolean {
        val bounds = candidate.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return false
        }
        val widthRatio = bounds.width() / screenWidth.toFloat()
        val heightRatio = bounds.height() / screenHeight.toFloat()
        val areaRatio = bounds.areaLong() /
            (screenWidth.toLong().coerceAtLeast(1L) * screenHeight.toLong().coerceAtLeast(1L)).toFloat()
        val verticalAspect = bounds.height() / bounds.width().toFloat().coerceAtLeast(1f)
        val horizontalAspect = bounds.width() / bounds.height().toFloat().coerceAtLeast(1f)

        return when (candidate.kind) {
            VisualCandidateKind.LIST_ITEM_TARGET ->
                widthRatio >= 0.45f && heightRatio in 0.030f..0.18f && areaRatio <= 0.19f

            VisualCandidateKind.CARD_TARGET ->
                widthRatio >= 0.04f &&
                    heightRatio in 0.018f..0.42f &&
                    areaRatio in 0.002f..0.25f &&
                    verticalAspect <= 5.0f &&
                    horizontalAspect <= 8.0f

            VisualCandidateKind.ACTION_TEXT ->
                heightRatio <= 0.24f && areaRatio <= 0.20f

            VisualCandidateKind.ICON_ENTRY,
            VisualCandidateKind.RIGHT_RAIL_ICON ->
                heightRatio <= 0.28f && areaRatio <= 0.20f

            VisualCandidateKind.TEXT_EVIDENCE ->
                heightRatio <= 0.10f && areaRatio <= 0.045f
        }
    }

    private fun finalVisualTargetScore(
        candidate: VisualOcrCandidate,
        targetPackage: String?,
        shouldPreferStructuredList: Boolean
    ): Int {
        if (candidate.evidenceScore > 0) return candidate.evidenceScore
        val compact = normalizedVisualText(candidate.text)
        val highFrequencyPackage = EdgeInteractionRules.isHighFrequencyPackage(targetPackage) &&
            targetPackage != "com.tencent.mm"
        var score = when (candidate.kind) {
            VisualCandidateKind.ACTION_TEXT -> 52
            VisualCandidateKind.ICON_ENTRY -> 76
            VisualCandidateKind.RIGHT_RAIL_ICON -> 82
            VisualCandidateKind.CARD_TARGET -> 78
            VisualCandidateKind.LIST_ITEM_TARGET -> 100
            VisualCandidateKind.TEXT_EVIDENCE -> 32
        }
        if (EdgeInteractionRules.isActionableVisualText(compact)) score += 34
        if (isIconEntryText(compact)) score += 22
        if (isCompactNavigationText(compact)) score += 20
        if (isLikelyBottomNavigationCandidate(candidate.bounds)) score += 24
        if (isLikelyTopNavigationCandidate(candidate.bounds)) score += 16
        if (candidate.bounds.width() > screenWidth * 0.30f &&
            candidate.bounds.height() > screenHeight * 0.055f
        ) {
            score += 12
        }
        if (candidate.kind == VisualCandidateKind.CARD_TARGET &&
            candidate.bounds.width() > screenWidth * 0.22f &&
            candidate.bounds.height() > screenHeight * 0.08f
        ) {
            score += 18
        }
        if (shouldPreferStructuredList && candidate.kind != VisualCandidateKind.LIST_ITEM_TARGET) {
            score -= 16
        }
        if (candidate.kind == VisualCandidateKind.TEXT_EVIDENCE) {
            score -= if (highFrequencyPackage) 20 else 10
        }
        if (isPriceLikeVisualText(compact)) score -= 38
        if (isPromotionalText(compact) && !EdgeInteractionRules.isActionableVisualText(compact)) score -= 22
        if (highFrequencyPackage &&
            candidate.kind == VisualCandidateKind.ACTION_TEXT &&
            !EdgeInteractionRules.isActionableVisualText(compact) &&
            !isIconEntryText(compact) &&
            !isCompactNavigationText(compact) &&
            !isLikelyBottomNavigationCandidate(candidate.bounds) &&
            !isLikelyTopNavigationCandidate(candidate.bounds)
        ) {
            score -= 24
        }
        if (compact.length > 18 &&
            candidate.kind != VisualCandidateKind.LIST_ITEM_TARGET &&
            candidate.kind != VisualCandidateKind.CARD_TARGET
        ) {
            score -= 14
        }
        return score
    }

    private fun isLikelyBottomNavigationCandidate(bounds: Rect): Boolean {
        return bounds.centerY() > screenHeight * 0.895f &&
            bounds.height() < screenHeight * 0.095f &&
            bounds.width() < screenWidth * 0.34f
    }

    private fun isLikelyTopNavigationCandidate(bounds: Rect): Boolean {
        return bounds.centerY() in 0..(screenHeight * 0.16f).roundToInt() &&
            bounds.height() < screenHeight * 0.085f &&
            bounds.width() < screenWidth * 0.42f
    }

    private fun visualCandidatesToNavNodes(
        candidates: List<VisualOcrCandidate>,
        primaryWindowId: Int
    ): List<NavNode> {
        return candidates.map { candidate ->
            val kindLabel = when (candidate.kind) {
                VisualCandidateKind.ACTION_TEXT -> "ACTION"
                VisualCandidateKind.ICON_ENTRY -> "ICON"
                VisualCandidateKind.RIGHT_RAIL_ICON -> "RAIL"
                VisualCandidateKind.CARD_TARGET -> "CARD"
                VisualCandidateKind.LIST_ITEM_TARGET -> "ITEM"
                VisualCandidateKind.TEXT_EVIDENCE -> "TEXT"
            }
            NavNode(
                bounds = Rect(candidate.bounds),
                description = "VISION[$kindLabel]: ${candidate.text}",
                windowId = primaryWindowId,
                origin = NavNodeOrigin.VISION,
                clickMethod = candidate.clickMethod,
                sourceSet = candidate.sourceSet,
                visualEvidence = VisualTargetEvidence(
                    sources = candidate.sourceSet,
                    yoloClass = candidate.yoloClass,
                    yoloConfidence = candidate.yoloConfidence,
                    ocrText = candidate.text.takeIf { TargetSource.OCR in candidate.sourceSet },
                    role = targetRole(candidate.kind),
                    score = candidate.evidenceScore,
                    clickMethod = candidate.clickMethod,
                    directEligible = candidate.directEligible
                )
            )
        }
    }

    private fun targetRole(kind: VisualCandidateKind): TargetRole = when (kind) {
        VisualCandidateKind.ACTION_TEXT -> TargetRole.ACTION
        VisualCandidateKind.ICON_ENTRY -> TargetRole.ICON_ENTRY
        VisualCandidateKind.RIGHT_RAIL_ICON -> TargetRole.RIGHT_RAIL
        VisualCandidateKind.CARD_TARGET -> TargetRole.CARD
        VisualCandidateKind.LIST_ITEM_TARGET -> TargetRole.LIST_ITEM
        VisualCandidateKind.TEXT_EVIDENCE -> TargetRole.TEXT_EVIDENCE
    }

    internal fun buildDiagnostics(
        yoloResult: YoloUiResult?,
        cropBounds: Rect,
        ocrEvidence: List<VisualOcrCandidate>,
        finalCandidates: List<VisualOcrCandidate>,
        targetPackage: String?
    ): VisualDiagnostics {
        val finalBounds = finalCandidates.map { it.bounds }
        val keptYolo = yoloResult?.let { buildYoloVisualCandidates(it, cropBounds, targetPackage) }.orEmpty()
        val rawYoloDebug = yoloResult?.detections.orEmpty()
            .sortedByDescending { it.confidence }
            .mapNotNull { detection ->
                val absolute = yoloDetectionBounds(detection, cropBounds)
                val normalized = detection.label.trim().lowercase()
                if (absolute.isEmpty) return@mapNotNull null
                if (isLikelyKeyboardCandidate(absolute)) return@mapNotNull null
                val isText = normalized == "text" || normalized.contains("text ") || normalized.contains("label")
                val tier = yoloEvidenceTier(normalized, detection.confidence, absolute)
                val useful = !isText &&
                    tier != YoloEvidenceTier.DEBUG_ONLY &&
                    isUsefulYoloTarget(absolute, normalized, targetPackage)
                val kind = when {
                    isText -> VisualDebugKind.TEXT_EVIDENCE
                    useful -> VisualDebugKind.YOLO_STRUCTURE
                    else -> VisualDebugKind.FILTERED_LOW
                }
                VisualDebugCandidate(
                    bounds = absolute,
                    text = "${detection.label} ${"%.2f".format(detection.confidence)}",
                    kind = kind,
                    score = detection.confidence
                )
            }
        val yoloDebug = rawYoloDebug
            .filter { it.kind == VisualDebugKind.YOLO_STRUCTURE }
            .filterNot { debug ->
                targetPackage == "com.tencent.mm" &&
                    (
                        isCoveredByAnyVisualTarget(debug.bounds, finalBounds) ||
                            (
                                debug.bounds.width() > screenWidth * 0.55f &&
                                    debug.bounds.height() / screenHeight.toFloat().coerceAtLeast(1f) in 0.03f..0.17f
                                )
                        )
            }
            .take(42) +
            rawYoloDebug
                .filter { it.kind == VisualDebugKind.TEXT_EVIDENCE }
                .take(28) +
            rawYoloDebug
                .filter { it.kind == VisualDebugKind.FILTERED_LOW }
                .take(18)
        val ocrPromoted = ocrEvidence.asSequence()
            .filterNot { isLikelyKeyboardCandidate(it.bounds) }
            .filterNot { isCoveredByAnyVisualTarget(it.bounds, finalBounds) }
            .filter { isStructureBackedOcrFallback(it, targetPackage) }
            .sortedWith(
                compareByDescending<VisualOcrCandidate> {
                    EdgeInteractionRules.isActionableVisualText(normalizedVisualText(it.text))
                }.thenBy { it.bounds.top }
            )
            .take(18)
            .map {
                VisualDebugCandidate(
                    bounds = Rect(it.bounds),
                    text = normalizedVisualText(it.text),
                    kind = VisualDebugKind.OCR_PROMOTED
                )
            }
            .toList()
        val ocrTextEvidence = ocrEvidence.asSequence()
            .filterNot { isLikelyKeyboardCandidate(it.bounds) }
            .filterNot { isCoveredByAnyVisualTarget(it.bounds, finalBounds) }
            .filterNot { promoted -> ocrPromoted.any { rectIou(it.bounds, promoted.bounds) > 0.70f } }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(24)
            .map {
                VisualDebugCandidate(
                    bounds = Rect(it.bounds),
                    text = normalizedVisualText(it.text),
                    kind = VisualDebugKind.TEXT_EVIDENCE
                )
            }
            .toList()
        val fusedDebug = finalCandidates.asSequence()
            .filter {
                TargetSource.YOLO in it.sourceSet &&
                    TargetSource.OCR in it.sourceSet
            }
            .map {
                VisualDebugCandidate(
                    bounds = Rect(it.bounds),
                    text = it.text,
                    kind = VisualDebugKind.FUSED_CANDIDATE,
                    score = it.evidenceScore.toFloat()
                )
            }
            .toList()
        return VisualDiagnostics(
            candidates = (yoloDebug + ocrPromoted + ocrTextEvidence + fusedDebug)
                .distinctBy {
                    "${it.kind}:${it.bounds.left / 8}:${it.bounds.top / 8}:${it.bounds.right / 8}:${it.bounds.bottom / 8}"
                },
            rawYoloCount = yoloResult?.rawCandidateCount ?: 0,
            keptYoloCount = keptYolo.size,
            ocrPromotedCount = ocrPromoted.size,
            filteredYoloCount = rawYoloDebug.count { it.kind == VisualDebugKind.FILTERED_LOW }
        )
    }

    private fun yoloDetectionBounds(detection: YoloDetection, cropBounds: Rect): Rect {
        val local = detection.box
        return Rect(
            (cropBounds.left + local.left).roundToInt().coerceIn(0, screenWidth),
            (cropBounds.top + local.top).roundToInt().coerceIn(0, screenHeight),
            (cropBounds.left + local.right).roundToInt().coerceIn(0, screenWidth),
            (cropBounds.top + local.bottom).roundToInt().coerceIn(0, screenHeight)
        )
    }

    private fun isCoveredByAnyVisualTarget(bounds: Rect, targets: List<Rect>): Boolean {
        return targets.any { target ->
            belongsToVisualTarget(bounds, target) ||
                rectIou(bounds, target) > 0.45f ||
                containsMost(target, bounds, 0.72f)
        }
    }

    private fun isStructureBackedOcrFallback(
        candidate: VisualOcrCandidate,
        targetPackage: String?
    ): Boolean {
        if (!isPlainVisualTextCandidate(candidate)) return false
        val compact = normalizedVisualText(candidate.text)
        if (EdgeInteractionRules.isActionableVisualText(compact) ||
            isCompactNavigationText(compact) ||
            isIconEntryText(compact)
        ) {
            return true
        }
        if (EdgeInteractionRules.acceptsNavigationSemanticTarget(
                candidate.bounds.width(),
                candidate.bounds.height(),
                candidate.bounds.exactCenterY(),
                screenWidth,
                screenHeight
            )
        ) {
            return true
        }
        if (EdgeInteractionRules.isHighFrequencyPackage(targetPackage)) {
            val y = candidate.bounds.exactCenterY()
            val x = candidate.bounds.exactCenterX()
            val inEntryBand = y in (screenHeight * 0.20f)..(screenHeight * 0.82f)
            val shortEntryText = compact.length in 2..8 && candidate.bounds.width() < screenWidth * 0.42f
            val gridLike = x > screenWidth * 0.04f && x < screenWidth * 0.96f
            return inEntryBand && shortEntryText && gridLike && !isPriceLikeVisualText(compact)
        }
        return false
    }

    private fun buildYoloVisualCandidates(
        result: YoloUiResult,
        cropBounds: Rect,
        targetPackage: String?
    ): List<YoloVisualCandidate> {
        val candidates = result.detections.mapNotNull { detection ->
            val label = detection.label.trim()
            val normalized = normalizedYoloClass(label)
            val absolute = yoloDetectionBounds(detection, cropBounds)
            val tier = yoloEvidenceTier(normalized, detection.confidence, absolute)
            if (tier == YoloEvidenceTier.DEBUG_ONLY) return@mapNotNull null
            if (isLikelySelfOverlayCandidate(absolute)) return@mapNotNull null
            if (!isUsefulYoloTarget(absolute, normalized, targetPackage)) return@mapNotNull null
            val kind = when {
                normalized == "card" || normalized == "image" -> VisualCandidateKind.CARD_TARGET
                normalized == "icon" -> VisualCandidateKind.ICON_ENTRY
                normalized in STRONG_YOLO_CLASSES -> VisualCandidateKind.ACTION_TEXT
                else -> VisualCandidateKind.TEXT_EVIDENCE
            }
            YoloVisualCandidate(
                bounds = absolute,
                label = label,
                score = detection.confidence,
                kind = kind,
                tier = tier
            )
        }.sortedWith(
            compareByDescending<YoloVisualCandidate> { visualCandidatePriority(it.kind) }
                .thenByDescending { it.tier.ordinal }
                .thenByDescending { it.score }
        )
        val kept = mutableListOf<YoloVisualCandidate>()
        candidates.forEach { candidate ->
            val duplicate = kept.any { existing ->
                existing.kind == candidate.kind &&
                    (
                        rectIou(existing.bounds, candidate.bounds) > 0.72f ||
                            containsMost(existing.bounds, candidate.bounds, 0.92f)
                        )
            }
            if (!duplicate) kept += candidate
            if (kept.size >= 56) return kept
        }
        return kept
    }

    private fun buildFusedYoloCandidates(
        yoloCandidates: List<YoloVisualCandidate>,
        textEvidence: List<VisualOcrCandidate>,
        actionCandidates: List<VisualOcrCandidate>,
        targetPackage: String?
    ): List<VisualOcrCandidate> {
        val actionBounds = actionCandidates.map { it.bounds }
        return yoloCandidates.mapNotNull { yolo ->
            val attachedText = textEvidence.filter { evidence ->
                belongsToVisualTarget(evidence.bounds, yolo.bounds)
            }
            val meaningfulText = attachedText.filter(::isMeaningfulYoloFusionText)
            val hasActionableText = attachedText.any {
                EdgeInteractionRules.isActionableVisualText(normalizedVisualText(it.text))
            }
            val directEligible = yolo.tier == YoloEvidenceTier.ACTION
            if (!directEligible && meaningfulText.isEmpty() && !hasActionableText &&
                yolo.kind != VisualCandidateKind.TEXT_EVIDENCE
            ) {
                return@mapNotNull null
            }

            val label = mergeTextEvidenceWithinTarget(attachedText, yolo.bounds)
                ?: if (directEligible) {
                    yoloFallbackLabel(yolo, targetPackage)
                } else {
                    yolo.label
                }
            val kind = when {
                hasActionableText -> VisualCandidateKind.ACTION_TEXT
                yolo.kind == VisualCandidateKind.CARD_TARGET &&
                    actionBounds.any { belongsToVisualTarget(it, yolo.bounds) } -> VisualCandidateKind.ACTION_TEXT
                else -> yolo.kind
            }
            if (kind == VisualCandidateKind.CARD_TARGET &&
                attachedText.isEmpty() &&
                yolo.bounds.areaLong() > screenWidth.toLong() * screenHeight.toLong() / 5L
            ) {
                return@mapNotNull null
            }
            VisualOcrCandidate(
                bounds = Rect(yolo.bounds),
                text = label,
                kind = kind,
                sourceSet = buildSet {
                    add(TargetSource.YOLO)
                    if (attachedText.isNotEmpty()) add(TargetSource.OCR)
                },
                yoloClass = normalizedYoloClass(yolo.label),
                yoloConfidence = yolo.score,
                evidenceScore = v45VisualScore(
                    yolo = yolo,
                    kind = kind,
                    hasOcr = attachedText.isNotEmpty(),
                    hasActionableText = hasActionableText
                ),
                directEligible = directEligible,
                clickMethod = ClickMethod.GESTURE_CENTER
            )
        }
            .filterNot {
                isLikelyStatusBarCandidate(it.bounds) ||
                    isLikelySystemNavigationCandidate(it.bounds) ||
                    isLikelySelfOverlayCandidate(it.bounds) ||
                    isLikelySelfOverlayText(it.text)
            }
            .distinctBy { "${it.kind}:${it.bounds.left / 8}:${it.bounds.top / 8}:${it.bounds.right / 8}:${it.bounds.bottom / 8}" }
    }

    private fun buildSupplementalTextCandidatesForYoloFusion(
        textEvidence: List<VisualOcrCandidate>,
        fusedYoloCandidates: List<VisualOcrCandidate>,
        actionCandidates: List<VisualOcrCandidate>,
        targetPackage: String?,
        allowStructuredTargets: Boolean
    ): List<VisualOcrCandidate> {
        val fusedBounds = fusedYoloCandidates.map { it.bounds }
        fun isCoveredByYolo(bounds: Rect): Boolean {
            return fusedBounds.any { target ->
                belongsToVisualTarget(bounds, target) ||
                    rectIou(bounds, target) > 0.45f ||
                    containsMost(target, bounds, 0.72f)
            }
        }
        val uncoveredActions = actionCandidates
            .filterNot { isCoveredByYolo(it.bounds) }
            .filter { isUsableOcrEvidenceBounds(it.bounds) }
            .map {
                it.copy(
                    kind = if (isIconEntryText(it.text)) {
                        VisualCandidateKind.ICON_ENTRY
                    } else {
                        VisualCandidateKind.ACTION_TEXT
                    }
                )
            }
        val highFrequencyGridEntries = buildHighFrequencyGridEntryTextCandidates(textEvidence, targetPackage)
            .filterNot { isCoveredByYolo(it.bounds) }
        val uncoveredNavigationText = textEvidence.asSequence()
            .filterNot { isCoveredByYolo(it.bounds) }
            .filter { isUsableOcrEvidenceBounds(it.bounds) }
            .filterNot { isLikelySelfOverlayText(it.text) }
            .filter { evidence ->
                val compact = normalizedVisualText(evidence.text)
                EdgeInteractionRules.isActionableVisualText(compact) ||
                    isCompactNavigationText(compact) ||
                    isIconEntryText(compact) ||
                    EdgeInteractionRules.acceptsNavigationSemanticTarget(
                        evidence.bounds.width(),
                        evidence.bounds.height(),
                        evidence.bounds.exactCenterY(),
                        screenWidth,
                        screenHeight
                    )
            }
            .map { evidence ->
                val compact = normalizedVisualText(evidence.text)
                evidence.copy(
                    text = compact,
                    kind = if (isIconEntryText(compact)) {
                        VisualCandidateKind.ICON_ENTRY
                    } else {
                        VisualCandidateKind.ACTION_TEXT
                    }
                )
            }
            .toList()
        val uncoveredSearchText = buildDouyinSearchTextCandidates(textEvidence, targetPackage)
            .filterNot { isCoveredByYolo(it.bounds) }
        val listItems = if (targetPackage == "com.tencent.mm" && allowStructuredTargets) {
            buildVisualListItemCandidates(textEvidence, actionCandidates)
                .filterNot { isCoveredByYolo(it.bounds) }
                .filterNot {
                    isLikelyStatusBarCandidate(it.bounds) ||
                        isLikelySystemNavigationCandidate(it.bounds) ||
                        isLikelySelfOverlayCandidate(it.bounds)
                }
                .take(18)
        } else {
            emptyList()
        }
        return (highFrequencyGridEntries + uncoveredActions + uncoveredNavigationText + uncoveredSearchText + listItems)
            .distinctBy {
                "${it.kind}:${it.bounds.left / 8}:${it.bounds.top / 8}:${it.bounds.right / 8}:${it.bounds.bottom / 8}"
            }
            .sortedWith(
                compareByDescending<VisualOcrCandidate> { visualCandidatePriority(it) }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
            )
            .take(
                when {
                    targetPackage == "com.tencent.mm" -> 24
                    EdgeInteractionRules.isDouyinPackage(targetPackage) && keyboardTop() != null -> 24
                    EdgeInteractionRules.isHighFrequencyPackage(targetPackage) -> 24
                    else -> 16
                }
            )
    }

    private fun buildHighFrequencyGridEntryTextCandidates(
        textEvidence: List<VisualOcrCandidate>,
        targetPackage: String?
    ): List<VisualOcrCandidate> {
        if (!EdgeInteractionRules.isHighFrequencyPackage(targetPackage) || targetPackage == "com.tencent.mm") {
            return emptyList()
        }
        return textEvidence.asSequence()
            .filter { isPlainVisualTextCandidate(it) }
            .filterNot { isLikelySelfOverlayText(it.text) }
            .filter { evidence ->
                val compact = normalizedVisualText(evidence.text)
                val y = evidence.bounds.exactCenterY()
                val x = evidence.bounds.exactCenterX()
                compact.length in 2..8 &&
                    isIconEntryText(compact) &&
                    !isPriceLikeVisualText(compact) &&
                    y in (screenHeight * 0.12f)..(screenHeight * 0.68f) &&
                    x in (screenWidth * 0.02f)..(screenWidth * 0.98f) &&
                    evidence.bounds.width() < screenWidth * 0.42f
            }
            .map {
                it.copy(
                    text = normalizedVisualText(it.text),
                    kind = VisualCandidateKind.ICON_ENTRY
                )
            }
            .distinctBy {
                "${it.bounds.left / 8}:${it.bounds.top / 8}:${it.bounds.right / 8}:${it.bounds.bottom / 8}"
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(16)
            .toList()
    }

    private fun buildDouyinSearchTextCandidates(
        textEvidence: List<VisualOcrCandidate>,
        targetPackage: String?
    ): List<VisualOcrCandidate> {
        if (!EdgeInteractionRules.isDouyinPackage(targetPackage)) return emptyList()
        val imeTop = keyboardTop() ?: return emptyList()
        return textEvidence.asSequence()
            .filter { isPlainVisualTextCandidate(it) }
            .filterNot { isLikelySelfOverlayText(it.text) }
            .filter {
                EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(
                    it.bounds.width(),
                    it.bounds.height(),
                    it.bounds.exactCenterY(),
                    screenHeight,
                    imeTop
                )
            }
            .map {
                it.copy(
                    text = normalizedVisualText(it.text),
                    kind = VisualCandidateKind.TEXT_EVIDENCE
                )
            }
            .distinctBy {
                "${it.bounds.left / 8}:${it.bounds.top / 8}:${it.bounds.right / 8}:${it.bounds.bottom / 8}"
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(18)
            .toList()
    }

    private fun mergeTextEvidenceWithinTarget(
        evidence: List<VisualOcrCandidate>,
        targetBounds: Rect
    ): String? {
        if (evidence.isEmpty()) return null
        val useful = evidence
            .filter { it.bounds.width() >= 8 && it.bounds.height() >= 8 }
            .filterNot { normalizedVisualText(it.text).contains("visual-hole", ignoreCase = true) }
            .sortedWith(compareBy({ it.bounds.centerY() }, { it.bounds.left }))
        if (useful.isEmpty()) return null
        val rowThreshold = useful.map { it.bounds.height() }.average().takeIf { !it.isNaN() }
            ?.let { (it * 0.72f).roundToInt().coerceIn(10, 36) } ?: 18
        val rows = mutableListOf<MutableList<VisualOcrCandidate>>()
        useful.forEach { candidate ->
            val row = rows.firstOrNull { existing ->
                kotlin.math.abs(existing.map { it.bounds.centerY() }.average() - candidate.bounds.centerY()) <= rowThreshold
            }
            if (row == null) rows += mutableListOf(candidate) else row += candidate
        }
        val segments = rows.flatMap { row ->
            splitTextRowByGap(row.sortedBy { it.bounds.left }, targetBounds)
        }
        val best = segments
            .map { segment -> segment.joinToString("") { normalizedVisualText(it.text) }.trim() }
            .filter { it.isNotBlank() && it.length <= 32 }
            .sortedWith(
                compareByDescending<String> { EdgeInteractionRules.isActionableVisualText(it) }
                    .thenBy { if (isPriceLikeVisualText(it)) 1 else 0 }
                    .thenBy { it.length }
            )
            .firstOrNull()
        return best ?: useful.firstOrNull()?.text?.trim()
    }

    private fun splitTextRowByGap(
        row: List<VisualOcrCandidate>,
        targetBounds: Rect
    ): List<List<VisualOcrCandidate>> {
        if (row.isEmpty()) return emptyList()
        val hardGapPx = dpToPx(32f)
        val softMinPx = dpToPx(16f)
        val groups = mutableListOf<MutableList<VisualOcrCandidate>>()
        var current = mutableListOf(row.first())
        row.drop(1).forEach { next ->
            val previous = current.last()
            val gap = next.bounds.left - previous.bounds.right
            val averageHeight = (next.bounds.height() + previous.bounds.height()) / 2f
            val allowedGap = maxOf(softMinPx, (averageHeight * 1.2f).roundToInt())
            val sameTarget = targetBounds.contains(next.bounds.centerX(), next.bounds.centerY()) &&
                targetBounds.contains(previous.bounds.centerX(), previous.bounds.centerY())
            if (!sameTarget || gap > hardGapPx || gap > allowedGap) {
                groups += current
                current = mutableListOf(next)
            } else {
                current += next
            }
        }
        groups += current
        return groups
    }

    private fun belongsToVisualTarget(textBounds: Rect, targetBounds: Rect): Boolean {
        if (targetBounds.contains(textBounds.centerX(), textBounds.centerY())) return true
        if (containsMost(targetBounds, textBounds, 0.80f)) return true
        val overlap = intersectionArea(targetBounds, textBounds)
        val textArea = textBounds.areaLong().coerceAtLeast(1L)
        if (overlap / textArea.toFloat() >= 0.80f) return true
        return rectIou(targetBounds, textBounds) >= 0.18f
    }

    private fun isUsefulYoloTarget(bounds: Rect, label: String, targetPackage: String?): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        if (isLikelyStatusBarCandidate(bounds) || isLikelySystemNavigationCandidate(bounds)) return false
        if (isLikelyKeyboardCandidate(bounds)) return false
        if (isLikelySelfOverlayCandidate(bounds)) return false
        val minSize = when {
            label.contains("icon") || label.contains("button") ||
                label.contains("edit") || label.contains("tab") ||
                label.contains("navigation") -> 14
            label.contains("image") || label.contains("card") -> 18
            else -> 16
        }
        if (bounds.width() < minSize || bounds.height() < minSize) return false
        if (bounds.areaLong() < 220L) return false
        val screenArea = screenWidth.toLong().coerceAtLeast(1L) * screenHeight.toLong().coerceAtLeast(1L)
        if (label.contains("image") && bounds.areaLong() > screenArea * 42L / 100L) return false
        if (EdgeInteractionRules.isDouyinPackage(targetPackage) && bounds.centerY() > screenHeight * 0.91f &&
            !label.contains("bottom") && !label.contains("navigation")
        ) {
            return false
        }
        return true
    }

    private fun normalizedYoloClass(label: String): String =
        YoloUiDetector.canonicalYoloClass(label)

    private fun yoloEvidenceTier(
        label: String,
        score: Float,
        bounds: Rect
    ): YoloEvidenceTier {
        val normalized = normalizedYoloClass(label)
        if (normalized == "backgroundimage") return YoloEvidenceTier.DEBUG_ONLY
        if (!isAllowedYoloFusionLabel(normalized)) return YoloEvidenceTier.DEBUG_ONLY
        val screenArea = screenWidth.toLong().coerceAtLeast(1L) *
            screenHeight.toLong().coerceAtLeast(1L)
        val areaRatio = bounds.areaLong() / screenArea.toFloat()
        return when {
            normalized in STRONG_YOLO_CLASSES -> YoloEvidenceTier.ACTION
            normalized == "icon" && score >= 0.10f -> YoloEvidenceTier.ACTION
            (normalized == "card" || normalized == "image") &&
                score >= 0.15f && areaRatio in 0.002f..0.25f -> YoloEvidenceTier.ACTION
            else -> YoloEvidenceTier.FUSION
        }
    }

    private fun isAllowedYoloFusionLabel(label: String): Boolean {
        return normalizedYoloClass(label) in ALL_YOLO_EVIDENCE_CLASSES
    }

    private fun yoloFusionMinimumScore(label: String): Float {
        return when {
            label.contains("button") || label.contains("edit") || label.contains("input") ||
                label.contains("tab") || label.contains("navigation") || label.contains("bottom") -> 0.42f
            label.contains("switch") || label.contains("checkbox") || label.contains("radio") -> 0.46f
            label.contains("icon") || label.contains("avatar") || label.contains("menu") -> 0.48f
            label.contains("card") || label.contains("list") -> 0.52f
            label.contains("image") -> 0.58f
            else -> 0.60f
        }
    }

    private fun yoloActionMinimumScore(label: String): Float {
        return when {
            label.contains("button") || label.contains("edit") || label.contains("input") ||
                label.contains("tab") || label.contains("navigation") || label.contains("bottom") -> 0.58f
            label.contains("switch") || label.contains("checkbox") || label.contains("radio") -> 0.60f
            label.contains("icon") || label.contains("avatar") || label.contains("menu") -> 0.64f
            label.contains("card") || label.contains("list") -> 0.68f
            label.contains("image") -> 0.72f
            else -> 0.74f
        }
    }

    private fun v45VisualScore(
        yolo: YoloVisualCandidate,
        kind: VisualCandidateKind,
        hasOcr: Boolean,
        hasActionableText: Boolean
    ): Int {
        val normalized = normalizedYoloClass(yolo.label)
        var score = when {
            yolo.tier != YoloEvidenceTier.ACTION && hasActionableText -> 50
            normalized in STRONG_YOLO_CLASSES -> 65
            normalized == "icon" -> 55
            normalized == "card" || normalized == "image" -> 52
            else -> 0
        }
        if (hasOcr) score += 20
        if (hasActionableText) score += 15
        score += (yolo.score * 20f).roundToInt()
        if (isLikelyTopNavigationCandidate(yolo.bounds) ||
            isLikelyBottomNavigationCandidate(yolo.bounds)
        ) {
            score += 8
        }
        if (kind == VisualCandidateKind.TEXT_EVIDENCE) return score.coerceAtMost(49)
        return score
    }

    private fun isMeaningfulYoloFusionText(candidate: VisualOcrCandidate): Boolean {
        val compact = normalizedVisualText(candidate.text)
        if (compact.isBlank() || compact.length > 40) return false
        if (isPriceLikeVisualText(compact)) return false
        if (isPromotionalText(compact) && !EdgeInteractionRules.isActionableVisualText(compact)) return false
        return EdgeInteractionRules.isActionableVisualText(compact) ||
            isIconEntryText(compact) ||
            isCompactNavigationText(compact) ||
            isTitleLikeCardText(candidate)
    }

    private fun yoloFallbackLabel(candidate: YoloVisualCandidate, targetPackage: String?): String {
        val label = candidate.label.lowercase()
        return when {
            label.contains("edit") -> "\u8f93\u5165\u6846"
            label.contains("button") -> "\u6309\u94ae"
            label.contains("tab") -> "\u5206\u9875"
            label.contains("bottom") || label.contains("navigation") -> "\u5bfc\u822a"
            label.contains("card") -> "\u5361\u7247"
            label.contains("image") -> if (EdgeInteractionRules.isDouyinPackage(targetPackage)) "\u89c6\u9891\u5165\u53e3" else "\u56fe\u7247\u5165\u53e3"
            label.contains("icon") -> "\u56fe\u6807"
            else -> candidate.label
        }
    }

    private fun visualCandidatePriority(candidate: VisualOcrCandidate): Int = visualCandidatePriority(candidate.kind)

    private fun visualCandidatePriority(kind: VisualCandidateKind): Int {
        return when (kind) {
            VisualCandidateKind.ACTION_TEXT -> 90
            VisualCandidateKind.ICON_ENTRY -> 80
            VisualCandidateKind.RIGHT_RAIL_ICON -> 78
            VisualCandidateKind.CARD_TARGET -> 60
            VisualCandidateKind.LIST_ITEM_TARGET -> 58
            VisualCandidateKind.TEXT_EVIDENCE -> 20
        }
    }

    private fun dpToPx(value: Float): Int {
        return (value * context.density).roundToInt().coerceAtLeast(1)
    }

    private fun isLikelyStatusBarCandidate(bounds: Rect): Boolean {
        return bounds.centerY() < screenHeight * 0.045f || bounds.top < 2
    }

    private fun isLikelySystemNavigationCandidate(bounds: Rect): Boolean {
        return bounds.centerY() > screenHeight * 0.965f
    }
    private fun classifyVisualLayout(
        candidates: List<VisualOcrCandidate>,
        actionCandidates: List<VisualOcrCandidate>,
        accessibilityNodes: List<NavNode>,
        targetPackage: String?,
        confirmedMessageList: Boolean
    ): VisualLayoutKind {
        val evidence = candidates
            .filterNot { candidate -> actionCandidates.any { overlapMostly(candidate.bounds, it.bounds) } }
            .filter { isLikelyCardTextEvidence(it) }
        val bounds = evidence.map { it.bounds }
        val packageName = targetPackage.orEmpty().lowercase()
        val accessibilityCoverage = EdgeInteractionRules.contentCoverage(accessibilityNodes, screenHeight)
        val coveredBands = accessibilityCoverage.count { it.value >= 2 }
        if (packageName == "com.tencent.mm") {
            return if (confirmedMessageList) {
                VisualLayoutKind.SINGLE_COLUMN_LIST
            } else {
                VisualLayoutKind.UNKNOWN
            }
        }
        if (actionCandidates.size >= 3 &&
            actionCandidates.map { it.bounds.centerY() / (screenHeight / 8).coerceAtLeast(1) }.toSet().size <= 2
        ) {
            return VisualLayoutKind.ICON_GRID
        }
        if (coveredBands >= 2 && !EdgeInteractionRules.isHighFrequencyPackage(targetPackage)) {
            return VisualLayoutKind.SINGLE_COLUMN_LIST
        }
        if (evidence.count { isPromotionalText(it.text) } >= 3 && bounds.size < 8) {
            return VisualLayoutKind.BANNER_OR_PROMO
        }
        return when {
            EdgeInteractionRules.isTwoLaneVisualGrid(bounds, screenWidth, screenHeight) ->
                VisualLayoutKind.TWO_COLUMN_GRID
            isMultiLaneVisualGrid(bounds) ->
                VisualLayoutKind.MULTI_COLUMN_GRID
            EdgeInteractionRules.isVisualSingleColumnList(candidates.map { it.bounds }, screenWidth, screenHeight) ->
                VisualLayoutKind.SINGLE_COLUMN_LIST
            else ->
                VisualLayoutKind.UNKNOWN
        }
    }

    private fun isMultiLaneVisualGrid(bounds: List<Rect>): Boolean {
        if (bounds.size < 4 || screenWidth <= 0 || screenHeight <= 0) return false
        if (EdgeInteractionRules.isVisualSingleColumnList(bounds, screenWidth, screenHeight)) return false
        val useful = bounds.filter {
            it.centerY() in (screenHeight * 0.18f).toInt()..(screenHeight * 0.88f).toInt() &&
                it.width() < screenWidth * 0.72f &&
                it.height() < screenHeight * 0.13f
        }
        if (useful.size < 4) return false
        val lanes = inferVisualLaneCount(useful)
        val laneCounts = useful.groupingBy { laneIndexFor(it.centerX(), lanes) }.eachCount()
        val occupied = laneCounts.count { it.value >= 1 }
        return occupied >= 2 && useful.size >= occupied * 2
    }

    private fun buildTextOnlyVisualCandidates(
        candidates: List<VisualOcrCandidate>,
        actionCandidates: List<VisualOcrCandidate>
    ): List<VisualOcrCandidate> {
        val actionBounds = actionCandidates.map { it.bounds }
        return candidates.asSequence()
            .filter { candidate -> actionBounds.none { overlapMostly(candidate.bounds, it) } }
            .filter { isPlainVisualTextCandidate(it) }
            .map { it.copy(kind = VisualCandidateKind.TEXT_EVIDENCE) }
            .distinctBy {
                "${it.bounds.left / 8}:${it.bounds.top / 8}:${it.bounds.right / 8}:${it.bounds.bottom / 8}"
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(24)
            .toList()
    }

    private fun isPlainVisualTextCandidate(candidate: VisualOcrCandidate): Boolean {
        val compact = normalizedVisualText(candidate.text)
        if (compact.length !in 1..28) return false
        if (compact.contains("visual-hole", ignoreCase = true)) return false
        if (compact.contains("OCR", ignoreCase = true)) return false
        if (candidate.bounds.height() < 14 || candidate.bounds.height() > screenHeight * 0.08f) return false
        if (candidate.bounds.width() < 18 || candidate.bounds.width() > screenWidth * 0.78f) return false
        return compact.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
    }

    private fun isInputMethodWindowVisible(): Boolean = context.inputMethodVisible

    private fun buildVisualCardCandidates(
        candidates: List<VisualOcrCandidate>,
        actionCandidates: List<VisualOcrCandidate>,
        targetPackage: String?,
        shapeAnchors: List<CardShapeAnchor>,
        yoloCandidates: List<YoloVisualCandidate>
    ): List<VisualOcrCandidate> {
        val actionBounds = actionCandidates.map { it.bounds }
        val commerceEvidence = candidates.filter { candidate ->
            actionBounds.none { overlapMostly(candidate.bounds, it) } &&
                isLikelyCommerceCardEvidence(candidate)
        }
        val priceEvidence = commerceEvidence.filter { isPriceLikeVisualText(it.text) }
        if (priceEvidence.size < 2) return emptyList()

        val supportingText = candidates.filter { candidate ->
            actionBounds.none { overlapMostly(candidate.bounds, it) } &&
                isLikelyCardTextEvidence(candidate)
        }
        val yoloCardEvidence = yoloCandidates.filter(::isReliableCardYoloEvidence)
        val priceRows = clusterPriceRows(priceEvidence)
        if (priceRows.isEmpty()) return emptyList()

        val hypotheses = priceRows.flatMapIndexed { rowIndex, row ->
            buildPriceCells(rowIndex, row, priceRows).mapNotNull { cell ->
                buildCardHypothesisForPriceCell(
                    cell = cell,
                    commerceEvidence = commerceEvidence,
                    allTextEvidence = supportingText,
                    shapeAnchors = shapeAnchors,
                    yoloCardEvidence = yoloCardEvidence
                )
            }
        }

        return selectStableCardHypotheses(hypotheses)
            .map { hypothesis ->
                VisualOcrCandidate(
                    bounds = Rect(hypothesis.bounds),
                    text = hypothesis.label,
                    kind = VisualCandidateKind.CARD_TARGET,
                    sourceSet = buildSet {
                        add(TargetSource.OCR)
                        add(TargetSource.VISUAL_FUSION)
                        if (hypothesis.hasYolo) add(TargetSource.YOLO)
                    },
                    yoloClass = if (hypothesis.hasYolo) "card" else null,
                    yoloConfidence = hypothesis.yoloScore,
                    clickMethod = ClickMethod.GESTURE_CENTER
                )
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(if (EdgeInteractionRules.isDouyinPackage(targetPackage)) 24 else 18)
    }

    /**
     * Detect weak red anchors only. They are not navigation candidates and cannot
     * decide the number of columns. A valid card still needs price + title/YOLO/promo.
     */
    private fun buildShapeEvidenceAnchors(
        bitmap: Bitmap,
        cropBounds: Rect,
        targetPackage: String?
    ): List<CardShapeAnchor> {
        val packageName = targetPackage.orEmpty().lowercase()
        if (!packageName.contains("taobao")) return emptyList()

        val redCells = detectRedShapeCells(bitmap, cropBounds)
            .filter { it.centerY() > screenHeight * 0.22f && it.centerY() < screenHeight * 0.86f }
        if (redCells.isEmpty()) return emptyList()

        // Red video frames and promo banners are common. Group only nearby cells and
        // preserve their small geometry; never expand them into lane-wide rectangles.
        val rowBucket = (screenHeight * 0.055f).roundToInt().coerceIn(70, 132)
        val xBucket = (screenWidth * 0.12f).roundToInt().coerceAtLeast(72)
        return redCells
            .groupBy { rect -> "${rect.centerX() / xBucket}:${rect.centerY() / rowBucket}" }
            .mapNotNull { (_, cells) ->
                val union = Rect(cells.first())
                cells.drop(1).forEach { union.union(it) }
                if (union.width() <= 0 || union.height() <= 0) return@mapNotNull null
                if (union.width() > screenWidth * 0.32f || union.height() > screenHeight * 0.085f) {
                    return@mapNotNull null
                }
                CardShapeAnchor(Rect(union), union.centerY())
            }
            .distinctBy { "${it.bounds.left / 24}:${it.bounds.top / 24}:${it.bounds.right / 24}:${it.bounds.bottom / 24}" }
            .take(40)
    }

    private fun clusterPriceRows(priceEvidence: List<VisualOcrCandidate>): List<PriceRow> {
        val tolerance = (screenHeight * 0.033f).roundToInt().coerceIn(34, 76)
        val rows = mutableListOf<MutableList<VisualOcrCandidate>>()
        val centers = mutableListOf<Int>()
        priceEvidence.sortedBy { it.bounds.centerY() }.forEach { evidence ->
            val nearest = centers.indices.minByOrNull { kotlin.math.abs(centers[it] - evidence.bounds.centerY()) }
                ?.takeIf { kotlin.math.abs(centers[it] - evidence.bounds.centerY()) <= tolerance }
            if (nearest == null) {
                rows += mutableListOf(evidence)
                centers += evidence.bounds.centerY()
            } else {
                rows[nearest] += evidence
                centers[nearest] = rows[nearest].map { it.bounds.centerY() }.average().roundToInt()
            }
        }
        return rows.mapIndexed { index, evidence ->
            PriceRow(centers[index], evidence)
        }.filter { row ->
            row.centerY in (screenHeight * 0.20f).roundToInt()..(screenHeight * 0.91f).roundToInt()
        }
    }

    private fun buildPriceCells(
        rowIndex: Int,
        row: PriceRow,
        allRows: List<PriceRow>
    ): List<PriceCell> {
        val mergedAnchors = mutableListOf<MutableList<VisualOcrCandidate>>()
        val xMergeTolerance = (screenWidth * 0.10f).roundToInt().coerceAtLeast(72)
        row.evidence.sortedBy { it.bounds.centerX() }.forEach { evidence ->
            val existing = mergedAnchors.lastOrNull()
            val shouldMerge = existing != null && existing.any { previous ->
                kotlin.math.abs(previous.bounds.centerX() - evidence.bounds.centerX()) <= xMergeTolerance ||
                    horizontalOverlapRatio(previous.bounds, evidence.bounds) >= 0.24f
            }
            if (shouldMerge) {
                existing!!.add(evidence)
            } else {
                mergedAnchors += mutableListOf(evidence)
            }
        }
        val anchors = mergedAnchors
            .map { group -> group to group.map { it.bounds.centerX() }.average().roundToInt() }
            .sortedBy { it.second }
            .take(4)
        if (anchors.isEmpty()) return emptyList()

        // Phase 8B.3: row boundaries are derived from neighboring price rows. A card may
        // extend upward to its image/title, but it may never cross the previous row's
        // price line or the next row's price line. This prevents a single OCR/YOLO
        // hypothesis from claiming two vertically adjacent products.
        val previousRowBottom = allRows.getOrNull(rowIndex - 1)
            ?.evidence
            ?.maxOfOrNull { it.bounds.bottom }
        val nextRowTop = allRows.getOrNull(rowIndex + 1)
            ?.evidence
            ?.minOfOrNull { it.bounds.top }
        val rowTopLimit = if (previousRowBottom != null) {
            (previousRowBottom + (screenHeight * 0.018f).roundToInt().coerceIn(18, 42))
                .coerceAtMost(row.centerY)
        } else {
            (screenHeight * 0.18f).roundToInt()
        }
        val rowBottomLimit = if (nextRowTop != null) {
            (nextRowTop - (screenHeight * 0.012f).roundToInt().coerceIn(14, 32))
                .coerceAtLeast(row.centerY)
        } else {
            (screenHeight * 0.92f).roundToInt()
        }

        return anchors.mapIndexedNotNull { index, (group, centerX) ->
            val left = when {
                anchors.size == 1 -> if (centerX < screenWidth / 2) 8 else screenWidth / 2 + 4
                index == 0 -> 8
                else -> (anchors[index - 1].second + centerX) / 2 + 4
            }.coerceAtLeast(0)
            val right = when {
                anchors.size == 1 -> if (centerX < screenWidth / 2) screenWidth / 2 - 4 else screenWidth - 8
                index == anchors.lastIndex -> screenWidth - 8
                else -> (centerX + anchors[index + 1].second) / 2 - 4
            }.coerceAtMost(screenWidth)
            val width = right - left
            if (width < screenWidth * 0.16f || width > screenWidth * 0.62f) return@mapIndexedNotNull null
            PriceCell(
                rowIndex = rowIndex,
                cellIndex = index,
                left = left,
                right = right,
                rowTopLimit = rowTopLimit,
                rowBottomLimit = rowBottomLimit,
                priceEvidence = group
            )
        }
    }

    private fun buildCardHypothesisForPriceCell(
        cell: PriceCell,
        commerceEvidence: List<VisualOcrCandidate>,
        allTextEvidence: List<VisualOcrCandidate>,
        shapeAnchors: List<CardShapeAnchor>,
        yoloCardEvidence: List<YoloVisualCandidate>
    ): CardHypothesis? {
        if (cell.priceEvidence.isEmpty() || hasMultiplePriceRows(cell.priceEvidence)) return null
        val priceBounds = unionBounds(cell.priceEvidence)
        val cellRect = Rect(cell.left, 0, cell.right, screenHeight)
        val supportBandTop = maxOf(
            cell.rowTopLimit,
            (priceBounds.top - screenHeight * 0.085f).roundToInt().coerceAtLeast(0)
        )
        val supportBandBottom = minOf(
            cell.rowBottomLimit,
            (priceBounds.bottom + screenHeight * 0.030f).roundToInt().coerceAtMost(screenHeight)
        )

        val nearbyText = allTextEvidence.filter { evidence ->
            evidence.bounds.centerX() in cell.left..cell.right &&
                evidence.bounds.centerY() in supportBandTop..supportBandBottom &&
                horizontalOverlapRatio(evidence.bounds, cellRect) >= 0.20f
        }
        val titleEvidence = nearbyText.filter(::isTitleLikeCardText)
        val promoEvidence = commerceEvidence.filter { evidence ->
            evidence !in cell.priceEvidence &&
                evidence.bounds.centerX() in cell.left..cell.right &&
                evidence.bounds.centerY() in supportBandTop..supportBandBottom &&
                !isPriceLikeVisualText(evidence.text)
        }
        val yoloSupport = findBestYoloCardEvidence(
            cellLeft = cell.left,
            cellRight = cell.right,
            anchorBounds = priceBounds,
            yoloCardEvidence = yoloCardEvidence
        )
        val shapeSupport = shapeAnchors.any { shape ->
            shape.bounds.centerX() in cell.left..cell.right &&
                kotlin.math.abs(shape.anchorY - priceBounds.centerY()) <=
                (screenHeight * 0.060f).roundToInt().coerceIn(54, 128)
        }

        // Phase 8B.3: OCR owns the product geometry. YOLO may corroborate a visible
        // card, but it may never manufacture a card from an invisible region. Requiring
        // a title also prevents price/promo rows and hidden detections from becoming a
        // large green target.
        if (titleEvidence.isEmpty()) return null

        val bounds = buildConservativeCardBounds(
            cellLeft = cell.left,
            cellRight = cell.right,
            rowTopLimit = cell.rowTopLimit,
            rowBottomLimit = cell.rowBottomLimit,
            textBounds = Rect(priceBounds).apply {
                titleEvidence.forEach { union(it.bounds) }
                promoEvidence.forEach { union(it.bounds) }
            },
            titleEvidence = titleEvidence,
            yoloSupport = yoloSupport
        ) ?: return null
        val labelEvidence = (titleEvidence + promoEvidence + cell.priceEvidence)
            .distinctBy { "${it.bounds.left / 8}:${it.bounds.top / 8}:${it.text}" }
        return CardHypothesis(
            bounds = bounds,
            label = bestCardLabel(labelEvidence),
            rowIndex = cell.rowIndex,
            cellIndex = cell.cellIndex,
            score = 6 +
                (if (titleEvidence.isNotEmpty()) 4 else 0) +
                (if (yoloSupport != null) 5 else 0) +
                minOf(2, promoEvidence.size) +
                (if (shapeSupport) 1 else 0),
            hasPrice = true,
            hasTitle = titleEvidence.isNotEmpty(),
            hasPromo = promoEvidence.isNotEmpty(),
            hasYolo = yoloSupport != null,
            yoloScore = yoloSupport?.score,
            hasShape = shapeSupport
        )
    }

    private fun selectStableCardHypotheses(
        hypotheses: List<CardHypothesis>
    ): List<CardHypothesis> {
        if (hypotheses.isEmpty()) return emptyList()
        val safe = hypotheses.filter { hypothesis ->
            val bounds = hypothesis.bounds
            bounds.width() >= screenWidth * 0.16f &&
                bounds.height() in (screenHeight * 0.050f).roundToInt()..(screenHeight * 0.26f).roundToInt() &&
                bounds.height() <= bounds.width() * 1.95f &&
                hypothesis.hasPrice &&
                hypothesis.hasTitle
        }
        val onePerCell = safe.groupBy { it.rowIndex to it.cellIndex }
            .values
            .mapNotNull { sameCell ->
                sameCell.maxWithOrNull(
                    compareBy<CardHypothesis> { it.score }
                        .thenBy { -it.bounds.areaLong() }
                )
            }
        val kept = mutableListOf<CardHypothesis>()
        onePerCell.sortedWith(
            compareByDescending<CardHypothesis> { it.score }
                .thenBy { it.bounds.areaLong() }
                .thenBy { it.bounds.top }
        ).forEach { candidate ->
            val conflict = kept.any { existing -> cardsConflict(existing.bounds, candidate.bounds) }
            if (!conflict) kept += candidate
        }
        return kept.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun cardsConflict(first: Rect, second: Rect): Boolean {
        val horizontalOverlap = intersectionWidth(first, second) /
            minOf(first.width(), second.width()).toFloat().coerceAtLeast(1f)
        if (horizontalOverlap < 0.55f) return false
        val verticalOverlap = intersectionHeight(first, second) /
            minOf(first.height(), second.height()).toFloat().coerceAtLeast(1f)
        return verticalOverlap > 0.45f ||
            rectIou(first, second) > 0.18f ||
            containsMost(first, second, 0.78f) ||
            containsMost(second, first, 0.78f)
    }

    private fun buildConservativeCardBounds(
        cellLeft: Int,
        cellRight: Int,
        rowTopLimit: Int,
        rowBottomLimit: Int,
        textBounds: Rect,
        titleEvidence: List<VisualOcrCandidate>,
        yoloSupport: YoloVisualCandidate?
    ): Rect? {
        val cellWidth = (cellRight - cellLeft).coerceAtLeast(1)
        val fallbackPadding = minOf(
            (screenHeight * 0.055f).roundToInt(),
            (cellWidth * 0.38f).roundToInt()
        )
        val titleTop = titleEvidence.minOfOrNull { it.bounds.top } ?: return null
        val titlePadding = (screenHeight * 0.010f).roundToInt().coerceIn(10, 24)
        val ocrAnchoredTop = (titleTop - titlePadding)
            .coerceAtLeast(rowTopLimit)
        val maxYoloLift = (screenHeight * 0.028f).roundToInt().coerceIn(24, 64)
        val yoloTop = yoloSupport?.bounds?.top
            ?.takeIf { it < ocrAnchoredTop }
            ?.coerceAtLeast(ocrAnchoredTop - maxYoloLift)
        val fallbackTop = (textBounds.top - fallbackPadding)
            .coerceAtLeast(rowTopLimit)

        // OCR title is the anchor. YOLO can only lift the top edge slightly and can
        // never override the row boundary or drag the card into the previous product.
        val top = minOf(ocrAnchoredTop, yoloTop ?: ocrAnchoredTop)
            .coerceAtLeast(fallbackTop)
            .coerceAtLeast(rowTopLimit)
        val bottom = (textBounds.bottom + (screenHeight * 0.010f).roundToInt().coerceIn(14, 26))
            .coerceAtMost(rowBottomLimit)
            .coerceAtMost(screenHeight)
        val bounds = Rect(cellLeft, top, cellRight, bottom)
        if (bounds.width() < screenWidth * 0.16f) return null
        if (bounds.height() !in (screenHeight * 0.050f).roundToInt()..(screenHeight * 0.26f).roundToInt()) {
            return null
        }
        if (bounds.height() > bounds.width() * 1.95f) return null
        return bounds
    }

    private fun findBestYoloCardEvidence(
        cellLeft: Int,
        cellRight: Int,
        anchorBounds: Rect,
        yoloCardEvidence: List<YoloVisualCandidate>
    ): YoloVisualCandidate? {
        val cellRect = Rect(cellLeft, 0, cellRight, screenHeight)
        val cellWidth = (cellRight - cellLeft).coerceAtLeast(1)
        val horizontalSlack = (cellWidth * 0.08f).roundToInt().coerceAtLeast(8)
        return yoloCardEvidence.asSequence()
            .filter { it.tier == YoloEvidenceTier.ACTION }
            .filter { it.bounds.centerX() in cellLeft..cellRight }
            .filter { it.bounds.left >= cellLeft - horizontalSlack }
            .filter { it.bounds.right <= cellRight + horizontalSlack }
            .filter { it.bounds.width() in (cellWidth * 0.34f).roundToInt()..(cellWidth * 1.16f).roundToInt() }
            .filter { it.bounds.top < anchorBounds.bottom }
            .filter { it.bounds.bottom <= anchorBounds.bottom + screenHeight * 0.025f }
            .filter { anchorBounds.bottom - it.bounds.top <= screenHeight * 0.28f }
            .filter { horizontalOverlapRatio(it.bounds, cellRect) >= 0.62f }
            .maxWithOrNull(
                compareBy<YoloVisualCandidate> { it.score }
                    .thenBy { horizontalOverlapRatio(it.bounds, cellRect) }
            )
    }

    private fun isReliableCardYoloEvidence(candidate: YoloVisualCandidate): Boolean {
        if (candidate.kind != VisualCandidateKind.CARD_TARGET) return false
        if (candidate.tier != YoloEvidenceTier.ACTION) return false
        val label = candidate.label.lowercase()
        if (!(label.contains("card") || label.contains("list") || label.contains("image"))) return false
        if (candidate.score < yoloActionMinimumScore(label)) return false
        val bounds = candidate.bounds
        val heightRatio = bounds.height() / screenHeight.toFloat().coerceAtLeast(1f)
        val widthRatio = bounds.width() / screenWidth.toFloat().coerceAtLeast(1f)
        return widthRatio in 0.14f..0.62f &&
            heightRatio in 0.045f..0.28f &&
            bounds.height() <= bounds.width() * 2.05f
    }

    private fun isTitleLikeCardText(candidate: VisualOcrCandidate): Boolean {
        val compact = normalizedVisualText(candidate.text)
        if (compact.length !in 3..34) return false
        if (isPriceLikeVisualText(compact) || isPromotionalText(compact)) return false
        if (EdgeInteractionRules.isActionableVisualText(compact) || isCompactNavigationText(compact)) return false
        if (listOf("已售", "券后", "补贴", "包邮", "立减", "抢购", "热销", "官方").any {
                compact.contains(it, ignoreCase = true)
            }
        ) {
            return false
        }
        return compact.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
    }

    private fun hasMultiplePriceRows(priceEvidence: List<VisualOcrCandidate>): Boolean {
        if (priceEvidence.size < 2) return false
        val tolerance = (screenHeight * 0.030f).roundToInt().coerceAtLeast(28)
        val minY = priceEvidence.minOf { it.bounds.centerY() }
        val maxY = priceEvidence.maxOf { it.bounds.centerY() }
        return maxY - minY > tolerance
    }

    private fun unionBounds(candidates: List<VisualOcrCandidate>): Rect {
        val union = Rect(candidates.first().bounds)
        candidates.drop(1).forEach { union.union(it.bounds) }
        return union
    }

    private fun horizontalOverlapRatio(first: Rect, second: Rect): Float {
        return intersectionWidth(first, second) /
            minOf(first.width(), second.width()).toFloat().coerceAtLeast(1f)
    }

    private fun intersectionWidth(first: Rect, second: Rect): Int =
        (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)

    private fun intersectionHeight(first: Rect, second: Rect): Int =
        (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)

    private fun detectRedShapeCells(bitmap: Bitmap, cropBounds: Rect): List<Rect> {
        val step = 6
        val grid = 72
        val buckets = mutableMapOf<Pair<Int, Int>, Int>()
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val red = android.graphics.Color.red(color)
                val green = android.graphics.Color.green(color)
                val blue = android.graphics.Color.blue(color)
                val isRedShape = red > 165 && red > green * 1.28f && red > blue * 1.20f
                if (isRedShape) {
                    val key = (x / grid) to (y / grid)
                    buckets[key] = (buckets[key] ?: 0) + 1
                }
                x += step
            }
            y += step
        }
        return buckets
            .filter { it.value >= 6 }
            .map { (key, _) ->
                val left = cropBounds.left + key.first * grid
                val top = cropBounds.top + key.second * grid
                Rect(
                    left,
                    top,
                    (left + grid).coerceAtMost(cropBounds.right),
                    (top + grid).coerceAtMost(cropBounds.bottom)
                )
            }
            .filter { it.width() > 0 && it.height() > 0 }
    }

    private fun detectWeChatMessageRows(
        candidates: List<VisualOcrCandidate>,
        yoloCandidates: List<YoloVisualCandidate>,
        accessibilityNodes: List<NavNode>
    ): List<WeChatMessageRow> {
        if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
        val bodyTop = (screenHeight * 0.14f).roundToInt()
        val bodyBottom = (screenHeight * 0.91f).roundToInt()
        val anchors = collectWeChatLeftAnchors(
            yoloCandidates = yoloCandidates,
            accessibilityNodes = accessibilityNodes,
            bodyTop = bodyTop,
            bodyBottom = bodyBottom
        )
        if (anchors.size < 2) return emptyList()

        val rows = mutableListOf<WeChatMessageRow>()
        for (anchor in anchors) {
            val anchorBounds = anchor.bounds
            val centerY = anchorBounds.centerY()
            val rowTolerance = maxOf(
                (anchorBounds.height() * 0.72f).roundToInt(),
                (screenHeight * 0.026f).roundToInt()
            ).coerceIn(36, 96)

            val middleText = candidates.filter { candidate ->
                kotlin.math.abs(candidate.bounds.centerY() - centerY) <= rowTolerance &&
                    isWeChatMiddleTextEvidence(candidate)
            }.sortedWith(
                compareBy<VisualOcrCandidate> { it.bounds.top }
                    .thenBy { it.bounds.left }
                    .thenBy { kotlin.math.abs(it.bounds.centerY() - centerY) }
                    .thenByDescending { normalizedVisualText(it.text).length }
            )
            val title = middleText.firstOrNull { candidate ->
                val compact = normalizedVisualText(candidate.text)
                candidate.bounds.centerX() < screenWidth * 0.72f &&
                    candidate.bounds.left > screenWidth * 0.10f &&
                    compact.length in 1..32
            } ?: continue

            val timestamp = candidates.asSequence()
                .filter { candidate ->
                    candidate.bounds.centerX() > screenWidth * 0.68f &&
                        kotlin.math.abs(candidate.bounds.centerY() - centerY) <= rowTolerance &&
                        isMessageTimestampText(candidate.text)
                }
                .minByOrNull { kotlin.math.abs(it.bounds.centerY() - centerY) }

            val rowEvidence = candidates.filter { candidate ->
                candidate.bounds.centerY() in bodyTop..bodyBottom &&
                    kotlin.math.abs(candidate.bounds.centerY() - centerY) <= rowTolerance &&
                    normalizedVisualText(candidate.text).isNotBlank()
            }
            rows += WeChatMessageRow(
                centerY = centerY,
                title = title,
                timestamp = timestamp,
                textEvidence = rowEvidence,
                leftAnchor = Rect(anchorBounds),
                anchorSources = anchor.sources
            )
        }

        val deduplicated = mutableListOf<WeChatMessageRow>()
        val duplicateTolerance = (screenHeight * 0.018f).roundToInt().coerceIn(24, 54)
        for (row in rows.sortedBy { it.centerY }) {
            val existingIndex = deduplicated.indexOfFirst {
                kotlin.math.abs(it.centerY - row.centerY) <= duplicateTolerance
            }
            if (existingIndex < 0) {
                deduplicated += row
            } else {
                val existing = deduplicated[existingIndex]
                val existingScore = weChatRowEvidenceScore(existing)
                val newScore = weChatRowEvidenceScore(row)
                if (newScore > existingScore) deduplicated[existingIndex] = row
            }
        }
        if (deduplicated.size < 2) return emptyList()

        val minGap = (screenHeight * 0.035f).roundToInt().coerceAtLeast(42)
        val maxGap = (screenHeight * 0.15f).roundToInt().coerceAtLeast(minGap + 1)
        val repeatedRows = deduplicated.filterIndexed { index, row ->
            val previousGap = if (index > 0) row.centerY - deduplicated[index - 1].centerY else Int.MAX_VALUE
            val nextGap = if (index < deduplicated.lastIndex) deduplicated[index + 1].centerY - row.centerY else Int.MAX_VALUE
            previousGap in minGap..maxGap || nextGap in minGap..maxGap
        }
        if (repeatedRows.size < 2) return emptyList()

        val headerConfirmed = candidates.any { candidate ->
            candidate.bounds.centerY() < screenHeight * 0.16f &&
                isWeChatHeaderText(candidate.text)
        }
        val bottomNavigationCount = candidates.asSequence()
            .filter { it.bounds.centerY() > screenHeight * 0.84f }
            .map { normalizedVisualText(it.text) }
            .filter { isWeChatBottomNavigationText(it) }
            .map { it.lowercase() }
            .distinct()
            .count()
        val timestampBackedRows = repeatedRows.count { it.timestamp != null }
        val pageConfirmed =
            (repeatedRows.size >= 2 && headerConfirmed) ||
                (
                    repeatedRows.size >= 3 &&
                        bottomNavigationCount >= 2 &&
                        timestampBackedRows >= 1
                    )

        Log.d(
            "TouchEngineWeChat",
            "anchors=${anchors.size} candidateRows=${deduplicated.size} " +
                "repeatedRows=${repeatedRows.size} header=$headerConfirmed " +
                "bottomNav=$bottomNavigationCount timestampRows=$timestampBackedRows " +
                "confirmed=$pageConfirmed"
        )
        return if (pageConfirmed) repeatedRows else emptyList()
    }

    private fun collectWeChatLeftAnchors(
        yoloCandidates: List<YoloVisualCandidate>,
        accessibilityNodes: List<NavNode>,
        bodyTop: Int,
        bodyBottom: Int
    ): List<WeChatRowAnchor> {
        val minSize = dpToPx(28f).coerceAtLeast(34)
        val maxWidth = (screenWidth * 0.22f).roundToInt()
        val maxHeight = (screenHeight * 0.12f).roundToInt()
        val maxCenterX = (screenWidth * 0.25f).roundToInt()
        val rawAnchors = mutableListOf<WeChatRowAnchor>()

        fun accepts(bounds: Rect): Boolean {
            if (bounds.isEmpty || bounds.centerY() !in bodyTop..bodyBottom) return false
            if (bounds.centerX() > maxCenterX) return false
            if (bounds.width() !in minSize..maxWidth || bounds.height() !in minSize..maxHeight) return false
            val aspect = bounds.width() / bounds.height().toFloat().coerceAtLeast(1f)
            return aspect in 0.62f..1.62f
        }

        yoloCandidates.forEach { candidate ->
            if (accepts(candidate.bounds)) {
                rawAnchors += WeChatRowAnchor(
                    bounds = Rect(candidate.bounds),
                    sources = setOf(TargetSource.YOLO)
                )
            }
        }
        accessibilityNodes.forEach { node ->
            if (accepts(node.bounds)) {
                rawAnchors += WeChatRowAnchor(
                    bounds = Rect(node.bounds),
                    sources = setOf(TargetSource.ACCESSIBILITY)
                )
            }
        }
        if (rawAnchors.isEmpty()) return emptyList()

        val mergeTolerance = (screenHeight * 0.020f).roundToInt().coerceIn(28, 60)
        val deduplicated = mutableListOf<WeChatRowAnchor>()
        for (anchor in rawAnchors.sortedBy { it.bounds.centerY() }) {
            val existingIndex = deduplicated.indexOfFirst {
                kotlin.math.abs(it.bounds.centerY() - anchor.bounds.centerY()) <= mergeTolerance
            }
            if (existingIndex < 0) {
                deduplicated += anchor
            } else {
                val existing = deduplicated[existingIndex]
                val merged = if (weChatAnchorQuality(anchor.bounds) > weChatAnchorQuality(existing.bounds)) {
                    anchor
                } else {
                    existing
                }
                deduplicated[existingIndex] = merged.copy(
                    sources = existing.sources + anchor.sources
                )
            }
        }
        return deduplicated.sortedBy { it.bounds.centerY() }
    }

    private fun weChatAnchorQuality(bounds: Rect): Float {
        val aspect = bounds.width() / bounds.height().toFloat().coerceAtLeast(1f)
        val squareScore = 1f - kotlin.math.abs(1f - aspect).coerceAtMost(1f)
        val sizeScore = (minOf(bounds.width(), bounds.height()) /
            dpToPx(64f).toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
        return squareScore * 2f + sizeScore
    }

    private fun weChatRowEvidenceScore(row: WeChatMessageRow): Int {
        return 4 +
            (if (row.timestamp != null) 2 else 0) +
            row.textEvidence.size.coerceAtMost(3)
    }

    private fun isWeChatHeaderText(text: String): Boolean {
        val compact = normalizedVisualText(text)
            .replace("（", "")
            .replace("）", "")
            .replace("(", "")
            .replace(")", "")
        return compact.equals("微信", ignoreCase = true) ||
            compact.startsWith("微信") ||
            compact.equals("wechat", ignoreCase = true) ||
            compact.startsWith("wechat", ignoreCase = true)
    }

    private fun isWeChatBottomNavigationText(text: String): Boolean {
        val compact = normalizedVisualText(text)
        return compact.equals("微信", ignoreCase = true) ||
            compact.equals("wechat", ignoreCase = true) ||
            compact == "通讯录" ||
            compact == "发现" ||
            compact == "我"
    }

    private fun isWeChatMiddleTextEvidence(candidate: VisualOcrCandidate): Boolean {
        val compact = normalizedVisualText(candidate.text)
        if (compact.isBlank() || compact.length !in 1..40) return false
        val minX = (screenWidth * 0.12f).roundToInt()
        val maxX = (screenWidth * 0.80f).roundToInt()
        if (candidate.bounds.centerX() !in minX..maxX) return false
        if (isMessageTimestampText(compact)) return false
        if (isPriceLikeVisualText(compact) || isPromotionalText(compact)) return false
        if (isActionNoiseText(compact)) return false
        return compact.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
    }

    private fun buildWeChatListItemCandidates(
        rows: List<WeChatMessageRow>
    ): List<VisualOcrCandidate> {
        if (rows.size < 2) return emptyList()
        val sortedRows = rows.sortedBy { it.centerY }
        val horizontalInset = dpToPx(8f).coerceAtLeast(12)
        val bodyTop = (screenHeight * 0.11f).roundToInt()
        val bodyBottom = (screenHeight * 0.92f).roundToInt()
        val validGaps = sortedRows.zipWithNext { first, second ->
            second.centerY - first.centerY
        }.filter {
            it in (screenHeight * 0.03f).roundToInt()..(screenHeight * 0.16f).roundToInt()
        }.sorted()
        val medianGap = when {
            validGaps.isNotEmpty() -> validGaps[validGaps.size / 2]
            else -> (screenHeight * 0.072f).roundToInt()
        }.coerceIn(dpToPx(56f), (screenHeight * 0.15f).roundToInt())

        val result = mutableListOf<VisualOcrCandidate>()
        for (index in sortedRows.indices) {
            val row = sortedRows[index]
            val previousCenter = sortedRows.getOrNull(index - 1)?.centerY
            val nextCenter = sortedRows.getOrNull(index + 1)?.centerY
            val rawTop = if (previousCenter != null) {
                (previousCenter + row.centerY) / 2
            } else {
                row.centerY - ((nextCenter?.minus(row.centerY) ?: medianGap) / 2)
            }
            val rawBottom = if (nextCenter != null) {
                (row.centerY + nextCenter) / 2
            } else {
                row.centerY + ((row.centerY - (previousCenter ?: row.centerY - medianGap)) / 2)
            }
            var top = rawTop.coerceAtLeast(bodyTop)
            var bottom = rawBottom.coerceAtMost(bodyBottom)

            val minHeight = dpToPx(54f).coerceAtLeast(54)
            if (bottom - top < minHeight) {
                val half = minHeight / 2
                top = (row.centerY - half).coerceAtLeast(bodyTop)
                bottom = (top + minHeight).coerceAtMost(bodyBottom)
                if (bottom - top < minHeight) {
                    top = (bottom - minHeight).coerceAtLeast(bodyTop)
                }
            }
            val heightRatio = (bottom - top) / screenHeight.toFloat().coerceAtLeast(1f)
            if (heightRatio !in 0.028f..0.16f) continue

            result += VisualOcrCandidate(
                bounds = Rect(horizontalInset, top, screenWidth - horizontalInset, bottom),
                text = bestWeChatListItemLabel(row),
                kind = VisualCandidateKind.LIST_ITEM_TARGET,
                sourceSet = buildSet {
                    add(TargetSource.OCR)
                    add(TargetSource.VISUAL_FUSION)
                    if (TargetSource.YOLO in row.anchorSources) add(TargetSource.YOLO)
                },
                clickMethod = ClickMethod.GESTURE_SAFE_LEFT
            )
        }
        return result
            .distinctBy { it.bounds.centerY() / dpToPx(12f).coerceAtLeast(1) }
            .sortedBy { it.bounds.top }
            .take(18)
    }

    private fun bestWeChatListItemLabel(row: WeChatMessageRow): String {
        return normalizedVisualText(row.title.text)
            .takeIf { it.isNotBlank() }
            ?: "微信聊天项"
    }

    private fun buildVisualListItemCandidates(
        candidates: List<VisualOcrCandidate>,
        actionCandidates: List<VisualOcrCandidate>
    ): List<VisualOcrCandidate> {
        val actionBounds = actionCandidates.map { it.bounds }
        val evidence = candidates
            .filter { candidate -> actionBounds.none { overlapMostly(candidate.bounds, it) } }
            .filter { isLikelyListTextEvidence(it) }
            .sortedBy { it.bounds.centerY() }
        if (evidence.size < 3) return emptyList()
        val rowGap = (screenHeight * 0.035f).roundToInt().coerceIn(34, 88)
        val rows = mutableListOf<MutableList<VisualOcrCandidate>>()
        var current = mutableListOf(evidence.first())
        var currentCenter = evidence.first().bounds.centerY()
        evidence.drop(1).forEach { next ->
            if (next.bounds.centerY() - currentCenter <= rowGap) {
                current.add(next)
                currentCenter = current.map { it.bounds.centerY() }.average().roundToInt()
            } else {
                rows.add(current)
                current = mutableListOf(next)
                currentCenter = next.bounds.centerY()
            }
        }
        rows.add(current)
        return rows.mapNotNull { row ->
            val union = Rect(row.first().bounds)
            row.drop(1).forEach { union.union(it.bounds) }
            if (union.height() > screenHeight * 0.11f) return@mapNotNull null
            val top = (union.top - 36).coerceAtLeast(0)
            val bottom = (union.bottom + 36).coerceAtMost(screenHeight)
            if (bottom - top < 56) return@mapNotNull null
            VisualOcrCandidate(
                bounds = Rect(24, top, screenWidth - 24, bottom),
                text = bestListItemLabel(row),
                kind = VisualCandidateKind.LIST_ITEM_TARGET,
                sourceSet = setOf(TargetSource.OCR, TargetSource.VISUAL_FUSION),
                clickMethod = ClickMethod.GESTURE_SAFE_LEFT
            )
        }
            .distinctBy { it.bounds.centerY() / 24 }
            .sortedBy { it.bounds.top }
            .take(18)
    }

    private fun isLikelyCardTextEvidence(candidate: VisualOcrCandidate): Boolean {
        val compact = normalizedVisualText(candidate.text)
        if (compact.length !in 2..40) return false
        if (compact.contains("visual-hole", ignoreCase = true)) return false
        if (candidate.bounds.centerY() < screenHeight * 0.18f ||
            candidate.bounds.centerY() > screenHeight * 0.88f
        ) {
            return false
        }
        if (isActionNoiseText(compact)) return false
        return true
    }

    private fun isLikelyCommerceCardEvidence(candidate: VisualOcrCandidate): Boolean {
        if (!isLikelyCardTextEvidence(candidate)) return false
        if (candidate.bounds.centerY() < screenHeight * 0.24f) return false
        val compact = normalizedVisualText(candidate.text)
        if (isPriceLikeVisualText(compact) || isPromotionalText(compact)) return true
        return listOf(
            "\u5df2\u552e", "\u5238", "\u4f4e\u4ef7", "\u5305\u90ae",
            "\u62a2", "\u8865\u8d34", "\u79d2\u6740", "\u76f4\u64ad",
            "\u70ed\u9500", "TOP", "\u5b98\u65b9", "\u7acb\u51cf"
        ).any { compact.contains(it, ignoreCase = true) }
    }

    private fun isLikelyListTextEvidence(candidate: VisualOcrCandidate): Boolean {
        val compact = normalizedVisualText(candidate.text)
        if (compact.length !in 2..36) return false
        if (compact.contains("visual-hole", ignoreCase = true)) return false
        if (candidate.bounds.centerY() < screenHeight * 0.16f ||
            candidate.bounds.centerY() > screenHeight * 0.88f
        ) {
            return false
        }
        if (isActionNoiseText(compact)) return false
        return true
    }

    private fun isLikelyMessageListSurface(
        candidates: List<VisualOcrCandidate>,
        targetPackage: String?
    ): Boolean {
        // 微信必须走 detectWeChatMessageRows() 的严格结构验证；这里不再因为包名直接放行。
        if (targetPackage == "com.tencent.mm") return false
        if (!EdgeInteractionRules.isHighFrequencyPackage(targetPackage) ||
            EdgeInteractionRules.isDouyinPackage(targetPackage)
        ) {
            return false
        }
        val timestampEvidence = candidates.filter { candidate ->
            val compact = normalizedVisualText(candidate.text)
            candidate.bounds.centerX() > screenWidth * 0.70f &&
                candidate.bounds.centerY() in (screenHeight * 0.12f).toInt()..(screenHeight * 0.92f).toInt() &&
                isMessageTimestampText(compact)
        }
        if (timestampEvidence.size < 2) return false
        val rowTolerance = (screenHeight * 0.035f).roundToInt().coerceIn(34, 78)
        val matchingRows = timestampEvidence.count { timeCandidate ->
            candidates.any { textCandidate ->
                if (textCandidate === timeCandidate) return@any false
                val compact = normalizedVisualText(textCandidate.text)
                val centerX = textCandidate.bounds.centerX()
                val sameRow = kotlin.math.abs(textCandidate.bounds.centerY() - timeCandidate.bounds.centerY()) <= rowTolerance
                sameRow &&
                    centerX in (screenWidth * 0.12f).toInt()..(screenWidth * 0.78f).toInt() &&
                    compact.length in 2..34 &&
                    !isPriceLikeVisualText(compact) &&
                    !isPromotionalText(compact) &&
                    !isCompactNavigationText(compact) &&
                    compact.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
            }
        }
        return matchingRows >= 2
    }

    private fun isMessageTimestampText(text: String): Boolean {
        val compact = normalizedVisualText(text)
        if (compact.isBlank() || compact.length > 8) return false
        return compact == "\u6628\u5929" ||
            compact == "\u4eca\u5929" ||
            compact == "\u521a\u521a" ||
            compact.startsWith("\u661f\u671f") ||
            compact.startsWith("\u5468") ||
            compact.matches(Regex("\\d{1,2}:\\d{2}")) ||
            compact.matches(Regex("\\d{1,2}/\\d{1,2}")) ||
            compact.matches(Regex("\\d{1,2}-\\d{1,2}"))
    }

    private fun bestListItemLabel(row: List<VisualOcrCandidate>): String {
        return row.asSequence()
            .map { normalizedVisualText(it.text) }
            .filter { it.length in 2..18 }
            .filterNot { it.matches(Regex("^[0-9:：./-]+$")) }
            .maxByOrNull { it.length }
            ?: row.first().text.trim()
    }

    private fun inferVisualLaneCountForCandidates(candidates: List<VisualOcrCandidate>): Int {
        return inferVisualLaneCount(candidates.map { it.bounds })
    }

    private fun inferVisualLaneCount(bounds: List<Rect>): Int {
        if (bounds.isEmpty() || screenWidth <= 0) return 2
        val useful = bounds.filter { it.width() < screenWidth * 0.70f }
        return (4 downTo 2).firstOrNull { lanes ->
            val occupied = useful.groupingBy { laneIndexFor(it.centerX(), lanes) }
                .eachCount()
                .count { it.value >= 1 }
            occupied >= 2 && useful.size >= occupied * 2
        } ?: 2
    }

    private fun laneIndexFor(centerX: Int, laneCount: Int): Int {
        val laneWidth = (screenWidth / laneCount).coerceAtLeast(1)
        return (centerX / laneWidth).coerceIn(0, laneCount - 1)
    }

    private fun bestCardLabel(group: List<VisualOcrCandidate>): String {
        val label = group.asSequence()
            .map { normalizedVisualText(it.text) }
            .filter { it.length in 2..18 }
            .filterNot { isPriceLikeVisualText(it) }
            .filterNot { EdgeInteractionRules.isActionableVisualText(it) }
            .minByOrNull { text ->
                val promoPenalty = if (isPromotionalText(text)) 12 else 0
                text.length + promoPenalty
            }
        return label ?: group.firstOrNull { isPriceLikeVisualText(it.text) }?.text?.trim()
            ?: "\u5361\u7247"
    }
    private fun isActionNoiseText(text: String): Boolean {
        return text.matches(Regex("^[0-9:：.]+$")) ||
            text.contains("KB/s", ignoreCase = true) ||
            text.contains("MB/s", ignoreCase = true)
    }
    private fun overlapMostly(first: Rect, second: Rect): Boolean {
        val intersection = Rect(first)
        if (!intersection.intersect(second)) return false
        val intersectionArea = intersection.width() * intersection.height()
        val firstArea = first.width() * first.height()
        return firstArea > 0 && intersectionArea.toFloat() / firstArea.toFloat() > 0.6f
    }
}
