package com.example.touchengine

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 将无障碍节点与视觉节点转换、过滤、合并并升级为最终导航目标。
 *
 * 本引擎不访问 AccessibilityService，不读取窗口，不修改缓存、焦点或 NavMesh。
 * 所有页面状态都由 [TargetFusionContext] 以一次快照的形式传入。
 */
internal class TargetFusionEngine {
    private val CONTAINED_LABEL_PARENT_ROLES = setOf(
        TargetRole.ACTION,
        TargetRole.ICON_ENTRY,
        TargetRole.LIST_ITEM,
        TargetRole.RIGHT_RAIL
    )
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

    fun fuse(
        rawNodes: List<NavNode>,
        context: TargetFusionContext
    ): TargetFusionResult {
        if (rawNodes.isEmpty()) {
            return TargetFusionResult(
                nodes = emptyList(),
                targets = emptyList(),
                stats = TargetFusionStats(
                    rawNodeCount = 0,
                    rawCandidateCount = 0,
                    candidateCount = 0,
                    mergedCandidateCount = 0,
                    finalCount = 0,
                    accessibilityCount = 0,
                    visionCount = 0,
                    pageKey = context.pageKey
                )
            )
        }

        val rawCandidates = rawNodes.mapNotNull(::navNodeToCandidateTarget)
        val candidates = rawCandidates.filter { candidate ->
            isCandidateAllowedForFinalTarget(candidate, rawCandidates, context)
        }
        val mergedCandidates = mergeCandidateTargets(candidates, context)
        val finalTargets = mergedCandidates.mapNotNull { candidate ->
            promoteCandidateToFinalTarget(candidate, context)
        }
        val selected = arbitrateFinalTargets(finalTargets)
            .sortedWith(
                compareByDescending<FinalTarget> { it.priority }
                    .thenByDescending { TargetSource.ACCESSIBILITY in it.sourceSet }
                    .thenByDescending { it.confidence }
                    .thenBy { it.node.bounds.areaLong() }
                    .thenBy { it.node.bounds.top }
                    .thenBy { it.node.bounds.left }
            )
            .take(finalTargetLimit(context.targetPackage))
            .sortedWith(compareBy({ it.node.bounds.top }, { it.node.bounds.left }))
        val finalNodes = selected.map(::finalTargetToNavNode)
        val accessibilityCount = selected.count { TargetSource.ACCESSIBILITY in it.sourceSet }
        val visionCount = selected.count { TargetSource.ACCESSIBILITY !in it.sourceSet }
        val yoloInputCount = rawCandidates.count { TargetSource.YOLO in it.sourceSet }
        val ocrInputCount = rawCandidates.count { TargetSource.OCR in it.sourceSet }
        val fusionInputCount = rawCandidates.count { TargetSource.VISUAL_FUSION in it.sourceSet }
        val listItemInputCount = rawCandidates.count { it.role == TargetRole.LIST_ITEM }
        val listItemFinalCount = selected.count { it.role == TargetRole.LIST_ITEM }
        val mergeRelations = selected.flatMap { target ->
            target.node.visualEvidence?.mergeRelations.orEmpty()
        }

        return TargetFusionResult(
            nodes = finalNodes,
            targets = selected,
            stats = TargetFusionStats(
                rawNodeCount = rawNodes.size,
                rawCandidateCount = rawCandidates.size,
                candidateCount = candidates.size,
                mergedCandidateCount = mergedCandidates.size,
                finalCount = finalNodes.size,
                accessibilityCount = accessibilityCount,
                visionCount = visionCount,
                pageKey = context.pageKey,
                yoloInputCount = yoloInputCount,
                ocrInputCount = ocrInputCount,
                fusionInputCount = fusionInputCount,
                listItemInputCount = listItemInputCount,
                listItemFinalCount = listItemFinalCount,
                containedLabelMergeCount = mergeRelations.count { it == FusionMergeRelation.CONTAINED_LABEL },
                iconLabelPairCount = mergeRelations.count { it == FusionMergeRelation.ICON_LABEL_PAIR },
                keptInnerActionCount = mergeRelations.count { it == FusionMergeRelation.KEEP_INNER_ACTION },
                overlapDuplicateCount = mergeRelations.count { it == FusionMergeRelation.OVERLAP_DUPLICATE }
            )
        )
    }


    /**
     * Phase 8B.3 final arbitration after all OCR/YOLO/accessibility evidence from one
     * scan has been fused. Competing targets in the same semantic family may not both
     * enter NavMesh. This makes the winner deterministic and prevents visible "frame
     * stealing" between slightly shifted card hypotheses.
     */
    private fun arbitrateFinalTargets(
        targets: List<FinalTarget>
    ): List<FinalTarget> {
        val ordered = targets.sortedWith(
            compareByDescending<FinalTarget> { it.priority }
                .thenByDescending { TargetSource.ACCESSIBILITY in it.sourceSet }
                .thenByDescending { it.confidence }
                .thenBy { it.node.bounds.areaLong() }
                .thenBy { it.node.bounds.top }
                .thenBy { it.node.bounds.left }
        )
        val kept = mutableListOf<FinalTarget>()
        ordered.forEach { candidate ->
            val conflictIndex = kept.indexOfFirst { existing ->
                finalTargetsCompete(existing, candidate)
            }
            if (conflictIndex < 0) {
                kept += candidate
            } else {
                kept[conflictIndex] = mergeFinalTargets(kept[conflictIndex], candidate)
            }
        }
        return kept
    }

    /**
     * 最终仲裁仍可能遇到比候选合并阈值更宽松的冲突。此时几何与语义使用
     * 排名更高的目标，但来源必须取并集，点击策略也必须重新裁决，不能再次
     * 退化为“赢家替换”。
     */
    private fun mergeFinalTargets(first: FinalTarget, second: FinalTarget): FinalTarget {
        val winner = listOf(first, second).sortedWith(
            compareByDescending<FinalTarget> { it.priority }
                .thenByDescending { TargetSource.ACCESSIBILITY in it.sourceSet }
                .thenByDescending { it.confidence }
                .thenBy { it.node.bounds.areaLong() }
        ).first()
        val geometryWinner = when {
            containsMost(first.node.bounds, second.node.bounds, 0.78f) -> first
            containsMost(second.node.bounds, first.node.bounds, 0.78f) -> second
            else -> winner
        }
        val mergedSources = first.sourceSet + second.sourceSet
        val mergedMethod = resolveFinalClickMethod(first, second, geometryWinner, mergedSources)
        val survivorStableId = geometryWinner.node.stableId ?: winner.node.stableId
        val absorbedStableIds = buildSet {
            addAll(first.node.visualEvidence?.absorbedStableIds.orEmpty())
            addAll(second.node.visualEvidence?.absorbedStableIds.orEmpty())
            first.node.stableId?.let(::add)
            second.node.stableId?.let(::add)
            survivorStableId?.let(::remove)
        }
        val mergedRelations = first.node.visualEvidence?.mergeRelations.orEmpty() +
            second.node.visualEvidence?.mergeRelations.orEmpty() +
            FusionMergeRelation.OVERLAP_DUPLICATE
        val preferredEvidence = geometryWinner.node.visualEvidence
            ?: winner.node.visualEvidence
            ?: first.node.visualEvidence
            ?: second.node.visualEvidence
        return winner.copy(
            node = geometryWinner.node.copy(
                clickMethod = mergedMethod,
                sourceSet = mergedSources,
                visualEvidence = preferredEvidence?.copy(
                    sources = mergedSources,
                    clickMethod = mergedMethod,
                    mergeRelations = mergedRelations,
                    absorbedStableIds = absorbedStableIds
                ),
                stableId = survivorStableId,
                interactionBounds = geometryWinner.node.interactionBounds?.let(::Rect)
                    ?: Rect(geometryWinner.node.bounds)
            ),
            sourceSet = mergedSources,
            clickMethod = mergedMethod,
            confidence = maxOf(first.confidence, second.confidence),
            priority = maxOf(first.priority, second.priority),
            stable = first.stable || second.stable
        )
    }

    private fun resolveFinalClickMethod(
        first: FinalTarget,
        second: FinalTarget,
        winner: FinalTarget,
        mergedSources: Set<TargetSource>
    ): ClickMethod {
        if (winner.role == TargetRole.LIST_ITEM ||
            first.clickMethod == ClickMethod.GESTURE_SAFE_LEFT ||
            second.clickMethod == ClickMethod.GESTURE_SAFE_LEFT
        ) {
            return ClickMethod.GESTURE_SAFE_LEFT
        }
        if (TargetSource.ACCESSIBILITY in mergedSources) {
            return ClickMethod.ACCESSIBILITY_ACTION
        }
        if (first.clickMethod == ClickMethod.GESTURE_CENTER ||
            second.clickMethod == ClickMethod.GESTURE_CENTER
        ) {
            return ClickMethod.GESTURE_CENTER
        }
        return winner.clickMethod
    }

    private fun finalTargetsCompete(
        first: FinalTarget,
        second: FinalTarget
    ): Boolean {
        if (first.node.windowId != second.node.windowId) return false
        if (keptInnerActionPair(first.node, second.node)) return false
        if (!sameFinalCompetitionFamily(first.role, second.role)) return false
        val a = first.node.bounds
        val b = second.node.bounds
        val overlap = intersectionArea(a, b)
        if (overlap <= 0L) return false
        val overlapOverSmaller = overlap /
            minOf(a.areaLong(), b.areaLong()).toFloat().coerceAtLeast(1f)

        val cardFamily = first.role == TargetRole.CARD && second.role == TargetRole.CARD
        val listFamily = first.role == TargetRole.LIST_ITEM && second.role == TargetRole.LIST_ITEM
        if (cardFamily || listFamily) {
            if (rectIou(a, b) >= 0.14f || overlapOverSmaller >= 0.42f) return true
            if (containsMost(a, b, 0.58f) || containsMost(b, a, 0.58f)) return true
            val horizontalOverlap = intersectionWidth(a, b) /
                minOf(a.width(), b.width()).toFloat().coerceAtLeast(1f)
            val verticalOverlap = intersectionHeight(a, b) /
                minOf(a.height(), b.height()).toFloat().coerceAtLeast(1f)
            val centerDistance = abs(a.centerY() - b.centerY())
            return horizontalOverlap >= 0.58f &&
                (verticalOverlap >= 0.20f || centerDistance <= minOf(a.height(), b.height()) * 0.55f)
        }

        // Buttons/icons need a stricter overlap threshold so adjacent navigation items
        // remain independent.
        if (rectIou(a, b) >= 0.42f || overlapOverSmaller >= 0.68f) return true
        return containsMost(a, b, 0.78f) || containsMost(b, a, 0.78f)
    }

    private fun sameFinalCompetitionFamily(first: TargetRole, second: TargetRole): Boolean {
        if (first == TargetRole.CARD || second == TargetRole.CARD) {
            return first == TargetRole.CARD && second == TargetRole.CARD
        }
        if (first == TargetRole.LIST_ITEM || second == TargetRole.LIST_ITEM) {
            return first == TargetRole.LIST_ITEM && second == TargetRole.LIST_ITEM
        }
        val firstAction = first == TargetRole.ACTION ||
            first == TargetRole.ICON_ENTRY ||
            first == TargetRole.RIGHT_RAIL
        val secondAction = second == TargetRole.ACTION ||
            second == TargetRole.ICON_ENTRY ||
            second == TargetRole.RIGHT_RAIL
        return firstAction && secondAction
    }

    private fun finalTargetToNavNode(target: FinalTarget): NavNode {
        val node = target.node
        return NavNode(
            bounds = Rect(node.bounds),
            description = node.description,
            windowId = node.windowId,
            centerX = node.centerX,
            centerY = node.centerY,
            origin = node.origin,
            clickMethod = target.clickMethod,
            sourceSet = target.sourceSet,
            visualEvidence = node.visualEvidence?.copy(
                sources = target.sourceSet,
                clickMethod = target.clickMethod
            ),
            stableId = node.stableId,
            interactionBounds = node.interactionBounds?.let(::Rect)
        )
    }

    private fun intersectionArea(first: Rect, second: Rect): Long =
        intersectionWidth(first, second).toLong() * intersectionHeight(first, second).toLong()

    private fun overlapOverSmaller(first: Rect, second: Rect): Float {
        val smallerArea = minOf(first.areaLong(), second.areaLong()).coerceAtLeast(1L)
        return intersectionArea(first, second) / smallerArea.toFloat()
    }

    private fun navNodeToCandidateTarget(node: NavNode): CandidateTarget? {
        val bounds = Rect(node.bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        val evidence = node.visualEvidence
        if (node.origin == NavNodeOrigin.VISION && evidence == null) return null
        val role = evidence?.role ?: TargetRole.UNKNOWN
        val text = if (node.origin == NavNodeOrigin.ACCESSIBILITY) {
            node.description?.trim()
        } else {
            evidence?.ocrText?.trim()
        }.takeIf { !it.isNullOrBlank() }
        val sourceSet = when {
            node.origin == NavNodeOrigin.ACCESSIBILITY -> setOf(TargetSource.ACCESSIBILITY) + node.sourceSet
            evidence != null -> {
                val explicitSources = node.sourceSet
                if (explicitSources.isEmpty()) evidence.sources else evidence.sources + explicitSources
            }
            else -> node.sourceSet
        }
        val primarySource = when {
            TargetSource.ACCESSIBILITY in sourceSet -> TargetSource.ACCESSIBILITY
            TargetSource.YOLO in sourceSet -> TargetSource.YOLO
            TargetSource.OCR in sourceSet -> TargetSource.OCR
            else -> TargetSource.VISUAL_FUSION
        }
        val normalizedEvidence = evidence?.copy(
            sources = sourceSet,
            clickMethod = node.clickMethod
        )
        return CandidateTarget(
            node = node.copy(sourceSet = sourceSet, visualEvidence = normalizedEvidence),
            source = primarySource,
            sourceSet = sourceSet,
            role = role,
            text = text,
            bounds = bounds,
            confidence = evidence?.yoloConfidence
                ?: if (TargetSource.ACCESSIBILITY in sourceSet) 1f else visualRoleConfidence(role),
            clickable = TargetSource.ACCESSIBILITY in sourceSet ||
                evidence?.directEligible == true ||
                (TargetSource.OCR in sourceSet && role == TargetRole.ACTION),
            scrollable = false,
            rawType = evidence?.yoloClass ?: node.origin.name,
            evidence = normalizedEvidence,
            clickMethod = node.clickMethod
        )
    }

    private fun visualRoleConfidence(role: TargetRole): Float {
        return when (role) {
            TargetRole.ACTION -> 0.82f
            TargetRole.ICON_ENTRY -> 0.78f
            TargetRole.RIGHT_RAIL -> 0.80f
            TargetRole.CARD -> 0.76f
            TargetRole.LIST_ITEM -> 0.90f
            TargetRole.TEXT_EVIDENCE -> 0.35f
            TargetRole.UNKNOWN -> 0.50f
        }
    }

    private fun isCandidateAllowedForFinalTarget(
        candidate: CandidateTarget,
        allCandidates: List<CandidateTarget>,
        context: TargetFusionContext
    ): Boolean {
        val bounds = candidate.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        if (bounds.centerX() !in 0..context.screenWidth || bounds.centerY() !in 0..context.screenHeight) return false
        if (isLikelyStatusBarCandidate(bounds, context) || isLikelySystemNavigationCandidate(bounds, context)) return false
        if (isLikelyKeyboardCandidate(bounds, context) || isWithinKeyboardRegion(bounds, context)) return false
        if (candidate.source != TargetSource.ACCESSIBILITY &&
            overlapsExpandedJoystick(bounds, context) &&
            !isOverlayTolerantWeChatRow(candidate, context)
        ) return false
        if (isLikelySelfOverlayText(candidate.text.orEmpty())) return false
        val text = normalizedVisualText(candidate.text.orEmpty())
        if (candidate.source == TargetSource.ACCESSIBILITY) {
            if (text.length > 48 && EdgeInteractionRules.isDouyinPackage(context.targetPackage)) return false
            return true
        }
        if (candidate.role == TargetRole.TEXT_EVIDENCE) return false

        if (!isSafeVisualPromotionGeometry(candidate, context)) return false

        val isPlainOcrOnly = TargetSource.OCR in candidate.sourceSet &&
            TargetSource.YOLO !in candidate.sourceSet &&
            TargetSource.VISUAL_FUSION !in candidate.sourceSet &&
            TargetSource.ACCESSIBILITY !in candidate.sourceSet
        val trustedDirectOcr = isPlainOcrOnly &&
            candidate.role in setOf(TargetRole.ACTION, TargetRole.ICON_ENTRY) &&
            candidate.evidence?.directEligible == true
        if (isPlainOcrOnly && !trustedDirectOcr) {
            if (hasGreenParentContainer(candidate, allCandidates)) return false
            if (!shouldPromoteOcrOnlyCandidate(candidate, context)) return false
        }
        if (candidate.evidence?.directEligible != true &&
            isBlankishVisualCandidate(candidate, context)
        ) return false
        if (isLikelyUnsupportedDouyinVisualText(candidate, context)) return false
        val areaRatio = bounds.areaLong() / context.screenArea.toFloat().coerceAtLeast(1f)
        if (areaRatio > 0.34f && candidate.role != TargetRole.LIST_ITEM && candidate.role != TargetRole.CARD) {
            return false
        }
        if (text.isBlank() &&
            candidate.evidence?.directEligible != true &&
            candidate.role != TargetRole.CARD &&
            candidate.role != TargetRole.ICON_ENTRY
        ) {
            return false
        }
        return true
    }

    private fun isSafeVisualPromotionGeometry(
        candidate: CandidateTarget,
        context: TargetFusionContext
    ): Boolean {
        val bounds = candidate.bounds
        val screenArea = context.screenArea.toFloat().coerceAtLeast(1f)
        val widthRatio = bounds.width() / context.screenWidth.toFloat().coerceAtLeast(1f)
        val heightRatio = bounds.height() / context.screenHeight.toFloat().coerceAtLeast(1f)
        val areaRatio = bounds.areaLong() / screenArea
        val verticalAspect = bounds.height() / bounds.width().toFloat().coerceAtLeast(1f)
        val horizontalAspect = bounds.width() / bounds.height().toFloat().coerceAtLeast(1f)

        return when (candidate.role) {
            TargetRole.CARD ->
                widthRatio >= 0.04f &&
                    heightRatio in 0.018f..0.42f &&
                    areaRatio in 0.002f..0.25f &&
                    verticalAspect <= 5.0f &&
                    horizontalAspect <= 8.0f

            TargetRole.LIST_ITEM ->
                widthRatio >= 0.42f && heightRatio in 0.030f..0.19f && areaRatio <= 0.20f

            TargetRole.ACTION ->
                heightRatio <= 0.24f && areaRatio <= 0.20f

            TargetRole.ICON_ENTRY,
            TargetRole.RIGHT_RAIL ->
                heightRatio <= 0.28f && areaRatio <= 0.20f

            TargetRole.TEXT_EVIDENCE ->
                heightRatio <= 0.11f && areaRatio <= 0.050f

            TargetRole.UNKNOWN ->
                heightRatio <= 0.24f && areaRatio <= 0.16f
        }
    }

    private fun isGenericYoloCardFallback(text: String): Boolean {
        val compact = normalizedVisualText(text).lowercase()
        return compact.isBlank() ||
            compact == "卡片" ||
            compact == "图片入口" ||
            compact == "图片" ||
            compact == "card" ||
            compact == "image" ||
            compact == "list"
    }

    private fun shouldPromoteOcrOnlyCandidate(
        candidate: CandidateTarget,
        context: TargetFusionContext
    ): Boolean {
        val text = normalizedVisualText(candidate.text.orEmpty())
        if (text.isBlank()) return false
        if (!isReasonableOcrActionBounds(candidate.bounds, context)) return false
        if (EdgeInteractionRules.isActionableVisualText(text) && isCommonActionArea(candidate.bounds, context)) return true
        if (isCompactNavigationText(text) && isCommonActionArea(candidate.bounds, context)) return true
        if (context.douyinScene == DouyinScene.SEARCH_TEXT) {
            return EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(
                candidate.bounds.width(),
                candidate.bounds.height(),
                candidate.bounds.exactCenterY(),
                context.screenHeight,
                context.keyboardTop
            ) && !isPriceLikeVisualText(text) && text.length <= 18
        }
        return false
    }

    private fun hasGreenParentContainer(
        candidate: CandidateTarget,
        allCandidates: List<CandidateTarget>
    ): Boolean {
        return allCandidates.any { parent ->
            parent !== candidate &&
                parent.role != TargetRole.TEXT_EVIDENCE &&
                parent.source != TargetSource.OCR &&
                parent.bounds.areaLong() > candidate.bounds.areaLong() * 2L &&
                containsMost(parent.bounds, candidate.bounds, 0.72f)
        }
    }

    private fun isReasonableOcrActionBounds(bounds: Rect, context: TargetFusionContext): Boolean {
        val width = bounds.width()
        val height = bounds.height()
        if (width < dpToPx(28f, context) || height < dpToPx(16f, context)) return false
        if (height > context.screenHeight * 0.105f || width > context.screenWidth * 0.58f) return false
        val areaRatio = bounds.areaLong() / context.screenArea.toFloat().coerceAtLeast(1f)
        return areaRatio in 0.00035f..0.030f
    }

    private fun isCommonActionArea(bounds: Rect, context: TargetFusionContext): Boolean {
        return isLikelyTopNavigationCandidate(bounds, context) ||
            isLikelyBottomNavigationCandidate(bounds, context) ||
            isLikelyDialogButtonCandidate(bounds, context) ||
            bounds.centerX() > context.screenWidth * 0.70f ||
            bounds.centerY() < context.screenHeight * 0.24f
    }

    private fun isLikelyDialogButtonCandidate(bounds: Rect, context: TargetFusionContext): Boolean {
        val cx = bounds.centerX() / context.screenWidth.toFloat().coerceAtLeast(1f)
        val cy = bounds.centerY() / context.screenHeight.toFloat().coerceAtLeast(1f)
        return cx in 0.18f..0.82f &&
            cy in 0.24f..0.82f &&
            bounds.height() in dpToPx(16f, context)..dpToPx(72f, context) &&
            bounds.width() <= context.screenWidth * 0.62f
    }

    private fun isBlankishVisualCandidate(candidate: CandidateTarget, context: TargetFusionContext): Boolean {
        val text = normalizedVisualText(candidate.text.orEmpty())
        val areaRatio = candidate.bounds.areaLong() / context.screenArea.toFloat().coerceAtLeast(1f)
        if (text.isBlank()) return true
        if (text.length <= 1 && candidate.role == TargetRole.TEXT_EVIDENCE) return true
        if (candidate.role == TargetRole.TEXT_EVIDENCE && areaRatio > 0.030f) return true
        return false
    }

    private fun isLikelyUnsupportedDouyinVisualText(
        candidate: CandidateTarget,
        context: TargetFusionContext
    ): Boolean {
        if (!EdgeInteractionRules.isDouyinPackage(context.targetPackage)) return false
        if (context.douyinScene == DouyinScene.SEARCH_TEXT) return false
        if (candidate.evidence?.directEligible == true) return false
        val text = normalizedVisualText(candidate.text.orEmpty())
        if (candidate.role == TargetRole.CARD || candidate.role == TargetRole.LIST_ITEM) return true
        if (text.contains("#") || text.length > 18) return true
        if (isPriceLikeVisualText(text)) return true
        if (text.any { it.isDigit() } && text.length <= 6 && candidate.bounds.centerX() > context.screenWidth * 0.68f) return true
        return candidate.role == TargetRole.TEXT_EVIDENCE && !EdgeInteractionRules.isActionableVisualText(text)
    }

    private fun mergeCandidateTargets(
        candidates: List<CandidateTarget>,
        context: TargetFusionContext
    ): List<CandidateTarget> {
        val containedLabelsMerged = absorbContainedOcrLabels(candidates, context)
        val iconLabelsMerged = mergeIconLabelPairs(containedLabelsMerged, context)
        val ordered = iconLabelsMerged.sortedWith(
            compareByDescending<CandidateTarget> { candidatePriorityHint(it, context) }
                .thenByDescending { it.confidence }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left }
        )
        val kept = mutableListOf<CandidateTarget>()
        ordered.forEach { candidate ->
            val overlappingIndex = kept.indexOfFirst { existing ->
                shouldMergeCandidateTargets(existing, candidate, context)
            }
            if (overlappingIndex < 0) {
                kept.add(candidate)
            } else {
                kept[overlappingIndex] = mergeCandidatePair(
                    kept[overlappingIndex],
                    candidate,
                    context,
                    FusionMergeRelation.OVERLAP_DUPLICATE
                )
            }
        }
        return suppressWeChatRowFragments(
            suppressOverlappingVisualCards(kept, context),
            context
        )
    }

    private fun absorbContainedOcrLabels(
        candidates: List<CandidateTarget>,
        context: TargetFusionContext
    ): List<CandidateTarget> {
        if (candidates.size < 2) return candidates
        val assignments = linkedMapOf<Int, MutableList<Int>>()
        val keptInnerActions = linkedSetOf<Int>()

        candidates.forEachIndexed { labelIndex, label ->
            if (!isPairableOcrLabel(label)) return@forEachIndexed
            val possibleParents = candidates.mapIndexedNotNull { parentIndex, parent ->
                if (parentIndex == labelIndex || !isContainedLabelParent(parent, label)) {
                    return@mapIndexedNotNull null
                }
                parentIndex to parent
            }.sortedWith(
                compareBy<Pair<Int, CandidateTarget>> { it.second.bounds.areaLong() }
                    .thenByDescending { candidatePriorityHint(it.second, context) }
            )
            val parentEntry = possibleParents.firstOrNull() ?: return@forEachIndexed
            if (shouldKeepIndependentInnerAction(parentEntry.second, label, context)) {
                keptInnerActions += labelIndex
            } else {
                assignments.getOrPut(parentEntry.first) { mutableListOf() } += labelIndex
            }
        }

        if (assignments.isEmpty() && keptInnerActions.isEmpty()) return candidates
        val absorbedLabelIndices = assignments.values.flatten().toSet()
        return candidates.mapIndexedNotNull { index, candidate ->
            when {
                index in absorbedLabelIndices -> null
                index in assignments -> {
                    val labels = assignments.getValue(index).map(candidates::get)
                    val bestText = labels.sortedWith(
                        compareByDescending<CandidateTarget> { semanticLabelScore(it.text.orEmpty()) }
                            .thenBy { it.bounds.top }
                            .thenBy { it.bounds.left }
                    ).firstNotNullOfOrNull { it.text?.takeIf(String::isNotBlank) }
                    labels.fold(candidate) { merged, label ->
                        mergeCandidatePair(
                            merged,
                            label,
                            context,
                            FusionMergeRelation.CONTAINED_LABEL,
                            geometryOwner = merged,
                            forcedText = bestText,
                            forcedRole = merged.role
                        )
                    }
                }
                index in keptInnerActions -> candidate.withMergeRelation(FusionMergeRelation.KEEP_INNER_ACTION)
                else -> candidate
            }
        }
    }

    private data class IconLabelMatch(
        val iconIndex: Int,
        val labelIndex: Int,
        val cost: Float
    )

    private fun mergeIconLabelPairs(
        candidates: List<CandidateTarget>,
        context: TargetFusionContext
    ): List<CandidateTarget> {
        if (candidates.size < 2) return candidates
        val iconIndices = candidates.indices.filter { isPairableIcon(candidates[it]) }
        val labelIndices = candidates.indices.filter { isPairableOcrLabel(candidates[it]) }
        if (iconIndices.isEmpty() || labelIndices.isEmpty()) return candidates

        val matches = iconIndices.flatMap { iconIndex ->
            labelIndices.mapNotNull { labelIndex ->
                iconLabelCost(candidates[iconIndex], candidates[labelIndex], context)?.let { cost ->
                    IconLabelMatch(iconIndex, labelIndex, cost)
                }
            }
        }
        if (matches.isEmpty()) return candidates
        val bestByIcon = matches.groupBy { it.iconIndex }.mapValues { (_, values) ->
            values.minWith(compareBy<IconLabelMatch> { it.cost }.thenBy { it.labelIndex })
        }
        val bestByLabel = matches.groupBy { it.labelIndex }.mapValues { (_, values) ->
            values.minWith(compareBy<IconLabelMatch> { it.cost }.thenBy { it.iconIndex })
        }
        val reciprocal = bestByIcon.values.filter { match ->
            bestByLabel[match.labelIndex]?.iconIndex == match.iconIndex
        }.associateBy { it.iconIndex }
        if (reciprocal.isEmpty()) return candidates
        val pairedLabels = reciprocal.values.map { it.labelIndex }.toSet()

        return candidates.mapIndexedNotNull { index, candidate ->
            when {
                index in pairedLabels -> null
                index in reciprocal -> {
                    val label = candidates[reciprocal.getValue(index).labelIndex]
                    val union = Rect(candidate.bounds).apply { union(label.bounds) }
                    mergeCandidatePair(
                        candidate,
                        label,
                        context,
                        FusionMergeRelation.ICON_LABEL_PAIR,
                        geometryOwner = candidate,
                        displayBounds = union,
                        interactionBounds = candidate.node.interactionBounds ?: candidate.bounds,
                        forcedText = label.text,
                        forcedRole = TargetRole.ICON_ENTRY
                    )
                }
                else -> candidate
            }
        }
    }

    private fun isContainedLabelParent(parent: CandidateTarget, label: CandidateTarget): Boolean {
        if (parent.node.windowId != label.node.windowId) return false
        val accessibilityStructure = TargetSource.ACCESSIBILITY in parent.sourceSet &&
            parent.role == TargetRole.UNKNOWN
        if (!parent.clickable ||
            (parent.role !in CONTAINED_LABEL_PARENT_ROLES && !accessibilityStructure)
        ) return false
        if (parent.bounds.areaLong() <= label.bounds.areaLong()) return false
        if (parent.sourceSet == setOf(TargetSource.OCR)) return false
        return overlapOverSmaller(parent.bounds, label.bounds) >= 0.78f
    }

    private fun shouldKeepIndependentInnerAction(
        parent: CandidateTarget,
        label: CandidateTarget,
        context: TargetFusionContext
    ): Boolean {
        val text = normalizedVisualText(label.text.orEmpty())
        if (!EdgeInteractionRules.isActionableVisualText(text)) return false
        if (parent.role == TargetRole.LIST_ITEM) return true
        val compactParent = parent.role in setOf(TargetRole.ACTION, TargetRole.ICON_ENTRY, TargetRole.RIGHT_RAIL) &&
            parent.bounds.areaLong() <= label.bounds.areaLong() * 6L &&
            parent.bounds.height() <= context.screenHeight * 0.12f
        return !compactParent
    }

    private fun isPairableOcrLabel(candidate: CandidateTarget): Boolean {
        if (candidate.sourceSet != setOf(TargetSource.OCR)) return false
        if (candidate.evidence?.directEligible != true) return false
        val text = normalizedVisualText(candidate.text.orEmpty())
        return text.isNotBlank() && text.length <= 12 && text.any(Char::isLetter)
    }

    private fun isPairableIcon(candidate: CandidateTarget): Boolean {
        if (candidate.role != TargetRole.ICON_ENTRY) return false
        if (TargetSource.YOLO in candidate.sourceSet || TargetSource.ACCESSIBILITY in candidate.sourceSet) return true
        val text = normalizedVisualText(candidate.text.orEmpty())
        return TargetSource.OCR in candidate.sourceSet && text.none(Char::isLetter)
    }

    private fun iconLabelCost(
        icon: CandidateTarget,
        label: CandidateTarget,
        context: TargetFusionContext
    ): Float? {
        if (icon.node.windowId != label.node.windowId) return null
        val minGap = -dpToPx(4f, context)
        val maxGap = maxOf(dpToPx(16f, context), (icon.bounds.height() * 0.45f).roundToInt())
        val verticalGap = label.bounds.top - icon.bounds.bottom
        if (verticalGap !in minGap..maxGap) return null
        val centerDelta = abs(icon.bounds.exactCenterX() - label.bounds.exactCenterX())
        val maxCenterDelta = maxOf(
            dpToPx(12f, context).toFloat(),
            maxOf(icon.bounds.width(), label.bounds.width()) * 0.35f
        )
        if (centerDelta > maxCenterDelta) return null
        val union = Rect(icon.bounds).apply { union(label.bounds) }
        val maxUnionWidth = maxOf(dpToPx(96f, context), (icon.bounds.width() * 2.5f).roundToInt())
        if (union.width() > maxUnionWidth || union.height() > context.screenHeight * 0.14f) return null
        return verticalGap.coerceAtLeast(0) / maxGap.toFloat().coerceAtLeast(1f) +
            centerDelta / maxCenterDelta.coerceAtLeast(1f)
    }

    private fun semanticLabelScore(text: String): Int {
        val compact = normalizedVisualText(text)
        if (compact.isBlank()) return Int.MIN_VALUE
        var score = 0
        if (EdgeInteractionRules.isActionableVisualText(compact)) score += 40
        if (compact.length in 2..8) score += 20
        if (compact.any(Char::isLetter)) score += 20
        if (compact.any(Char::isDigit)) score -= 25
        if (isPriceLikeVisualText(compact)) score -= 60
        return score
    }

    private fun CandidateTarget.withMergeRelation(relation: FusionMergeRelation): CandidateTarget {
        val relations = mergeRelations + relation
        val updatedEvidence = evidence?.copy(mergeRelations = relations)
        return copy(
            node = node.copy(visualEvidence = updatedEvidence),
            evidence = updatedEvidence,
            mergeRelations = relations
        )
    }

    /**
     * 同一目标的多个候选不再互相覆盖：几何、文本与主来源沿用评分更高的赢家，
     * Accessibility/OCR/YOLO 等来源取并集，点击策略基于合并后的完整证据重新裁决。
     */
    private fun mergeCandidatePair(
        first: CandidateTarget,
        second: CandidateTarget,
        context: TargetFusionContext,
        relation: FusionMergeRelation = FusionMergeRelation.OVERLAP_DUPLICATE,
        geometryOwner: CandidateTarget? = null,
        displayBounds: Rect? = null,
        interactionBounds: Rect? = null,
        forcedText: String? = null,
        forcedRole: TargetRole? = null
    ): CandidateTarget {
        val winner = chooseCandidateWinner(first, second, context)
        val geometry = geometryOwner ?: chooseGeometryOwner(first, second, winner, relation)
        val mergedBounds = Rect(displayBounds ?: geometry.bounds)
        val mergedSources = first.sourceSet + second.sourceSet
        val mergedMethod = resolveMergedClickMethod(first, second, winner, mergedSources, context)
        val mergedRelations = first.mergeRelations + second.mergeRelations + relation
        val survivorStableId = geometry.node.stableId ?: winner.node.stableId
        val absorbedStableIds = buildSet {
            addAll(first.absorbedStableIds)
            addAll(second.absorbedStableIds)
            first.node.stableId?.let(::add)
            second.node.stableId?.let(::add)
            survivorStableId?.let(::remove)
        }
        val mergedText = forcedText ?: chooseMergedText(first, second)
        val mergedRole = forcedRole ?: geometry.role
        val yoloEvidence = listOfNotNull(first.evidence, second.evidence)
            .filter { !it.yoloClass.isNullOrBlank() }
            .maxByOrNull { it.yoloConfidence ?: 0f }
        val visualEvidence = listOfNotNull(first.evidence, second.evidence)
            .maxByOrNull { it.score }
            ?.copy(
                sources = mergedSources,
                yoloClass = yoloEvidence?.yoloClass,
                yoloConfidence = yoloEvidence?.yoloConfidence,
                ocrText = mergedText,
                role = mergedRole,
                clickMethod = mergedMethod,
                score = maxOf(
                    candidatePriorityHint(first, context),
                    candidatePriorityHint(second, context)
                ),
                directEligible = first.evidence?.directEligible == true || second.evidence?.directEligible == true,
                mergeRelations = mergedRelations,
                absorbedStableIds = absorbedStableIds
            )
        val resolvedInteractionBounds = Rect(
            interactionBounds ?: geometry.node.interactionBounds ?: geometry.bounds
        )
        val mergedNode = geometry.node.copy(
            bounds = mergedBounds,
            centerX = mergedBounds.exactCenterX(),
            centerY = mergedBounds.exactCenterY(),
            description = mergedText ?: geometry.node.description,
            clickMethod = mergedMethod,
            sourceSet = mergedSources,
            visualEvidence = visualEvidence,
            stableId = survivorStableId,
            interactionBounds = resolvedInteractionBounds
        )
        return geometry.copy(
            node = mergedNode,
            sourceSet = mergedSources,
            role = mergedRole,
            text = mergedText,
            bounds = mergedBounds,
            confidence = maxOf(first.confidence, second.confidence),
            clickable = first.clickable || second.clickable,
            scrollable = first.scrollable || second.scrollable,
            evidence = visualEvidence,
            clickMethod = mergedMethod,
            mergeRelations = mergedRelations,
            absorbedStableIds = absorbedStableIds
        )
    }

    private fun chooseGeometryOwner(
        first: CandidateTarget,
        second: CandidateTarget,
        winner: CandidateTarget,
        relation: FusionMergeRelation
    ): CandidateTarget {
        if (relation == FusionMergeRelation.CONTAINED_LABEL) {
            return if (first.bounds.areaLong() >= second.bounds.areaLong()) first else second
        }
        val firstStructural = TargetSource.ACCESSIBILITY in first.sourceSet || TargetSource.YOLO in first.sourceSet
        val secondStructural = TargetSource.ACCESSIBILITY in second.sourceSet || TargetSource.YOLO in second.sourceSet
        return when {
            firstStructural && !secondStructural -> first
            secondStructural && !firstStructural -> second
            containsMost(first.bounds, second.bounds, 0.78f) -> first
            containsMost(second.bounds, first.bounds, 0.78f) -> second
            else -> winner
        }
    }

    private fun chooseMergedText(first: CandidateTarget, second: CandidateTarget): String? {
        val texts = listOfNotNull(
            first.evidence?.ocrText,
            second.evidence?.ocrText,
            first.text,
            second.text
        ).map(::normalizedVisualText).filter(String::isNotBlank).distinct()
        return texts.maxWithOrNull(
            compareBy<String> { semanticLabelScore(it) }
                .thenByDescending { it.length }
        )
    }

    private fun chooseCandidateWinner(
        first: CandidateTarget,
        second: CandidateTarget,
        context: TargetFusionContext
    ): CandidateTarget {
        val firstPriority = candidatePriorityHint(first, context)
        val secondPriority = candidatePriorityHint(second, context)
        return when {
            secondPriority > firstPriority -> second
            firstPriority > secondPriority -> first
            second.confidence > first.confidence -> second
            first.confidence > second.confidence -> first
            second.bounds.areaLong() < first.bounds.areaLong() -> second
            else -> first
        }
    }

    private fun resolveMergedClickMethod(
        first: CandidateTarget,
        second: CandidateTarget,
        winner: CandidateTarget,
        mergedSources: Set<TargetSource>,
        context: TargetFusionContext
    ): ClickMethod {
        val mergedProbe = winner.copy(
            sourceSet = mergedSources,
            node = winner.node.copy(sourceSet = mergedSources)
        )
        if (context.targetPackage == "com.tencent.mm" &&
            isWeChatWholeRowCandidate(mergedProbe, context)
        ) {
            return ClickMethod.GESTURE_SAFE_LEFT
        }
        if (winner.role == TargetRole.LIST_ITEM ||
            first.clickMethod == ClickMethod.GESTURE_SAFE_LEFT ||
            second.clickMethod == ClickMethod.GESTURE_SAFE_LEFT
        ) {
            return ClickMethod.GESTURE_SAFE_LEFT
        }
        if (TargetSource.ACCESSIBILITY in mergedSources) {
            return ClickMethod.ACCESSIBILITY_ACTION
        }
        if (first.clickMethod == ClickMethod.GESTURE_CENTER ||
            second.clickMethod == ClickMethod.GESTURE_CENTER
        ) {
            return ClickMethod.GESTURE_CENTER
        }
        return winner.clickMethod
    }

    private fun shouldMergeCandidateTargets(
        a: CandidateTarget,
        b: CandidateTarget,
        context: TargetFusionContext
    ): Boolean {
        if (a.node.windowId != b.node.windowId) return false
        if (keptInnerActionPair(a.node, b.node)) return false
        val card = when {
            a.role == TargetRole.CARD -> a
            b.role == TargetRole.CARD -> b
            else -> null
        }
        val child = when (card) {
            a -> b
            b -> a
            else -> null
        }
        if (card != null && child != null &&
            child.role in setOf(TargetRole.ACTION, TargetRole.ICON_ENTRY, TargetRole.RIGHT_RAIL) &&
            child.bounds.areaLong() < card.bounds.areaLong() * 0.40f &&
            containsMost(card.bounds, child.bounds, 0.72f)
        ) {
            return false
        }
        if (rectIou(a.bounds, b.bounds) > 0.56f) return true
        if (containsMost(a.bounds, b.bounds, 0.82f) || containsMost(b.bounds, a.bounds, 0.82f)) return true
        if (context.targetPackage == "com.tencent.mm" &&
            (isWeChatWholeRowCandidate(a, context) || isWeChatWholeRowCandidate(b, context)) &&
            abs(a.bounds.centerY() - b.bounds.centerY()) < context.screenHeight * 0.040f
        ) {
            return true
        }
        return false
    }

    private fun keptInnerActionPair(first: NavNode, second: NavNode): Boolean {
        val pairs = listOf(first to second, second to first)
        return pairs.any { (child, parent) ->
            FusionMergeRelation.KEEP_INNER_ACTION in child.visualEvidence?.mergeRelations.orEmpty() &&
                parent.bounds.areaLong() > child.bounds.areaLong() * 6L &&
                containsMost(parent.bounds, child.bounds, 0.78f)
        }
    }

    /**
     * Phase 8B.1 second-line guard. Structured OCR cards and fused YOLO cards may
     * describe the same product cell with slightly different rectangles. Keep only
     * one visual card per overlapping lane/row instead of exposing stacked green boxes.
     */
    private fun suppressOverlappingVisualCards(
        candidates: List<CandidateTarget>,
        context: TargetFusionContext
    ): List<CandidateTarget> {
        val cards = candidates.filter {
            it.role == TargetRole.CARD && it.source != TargetSource.ACCESSIBILITY
        }.sortedWith(
            compareByDescending<CandidateTarget> { candidatePriorityHint(it, context) }
                .thenByDescending { it.confidence }
                .thenBy { it.bounds.areaLong() }
        )
        if (cards.size < 2) return candidates

        val keptCards = mutableListOf<CandidateTarget>()
        cards.forEach { candidate ->
            val conflictIndex = keptCards.indexOfFirst { existing ->
                if (existing.node.windowId != candidate.node.windowId) return@indexOfFirst false
                val horizontalOverlap = intersectionWidth(existing.bounds, candidate.bounds) /
                    minOf(existing.bounds.width(), candidate.bounds.width()).toFloat().coerceAtLeast(1f)
                val verticalOverlap = intersectionHeight(existing.bounds, candidate.bounds) /
                    minOf(existing.bounds.height(), candidate.bounds.height()).toFloat().coerceAtLeast(1f)
                horizontalOverlap >= 0.55f &&
                    (verticalOverlap > 0.45f ||
                        rectIou(existing.bounds, candidate.bounds) > 0.18f ||
                        containsMost(existing.bounds, candidate.bounds, 0.78f) ||
                        containsMost(candidate.bounds, existing.bounds, 0.78f))
            }
            if (conflictIndex < 0) {
                keptCards += candidate
            } else {
                keptCards[conflictIndex] = mergeCandidatePair(
                    keptCards[conflictIndex],
                    candidate,
                    context
                )
            }
        }
        val nonVisualCards = candidates.filter { candidate ->
            candidate.role != TargetRole.CARD || candidate.source == TargetSource.ACCESSIBILITY
        }
        return nonVisualCards + keptCards
    }

    private fun intersectionWidth(first: Rect, second: Rect): Int =
        (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)

    private fun intersectionHeight(first: Rect, second: Rect): Int =
        (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)

    private fun suppressWeChatRowFragments(
        candidates: List<CandidateTarget>,
        context: TargetFusionContext
    ): List<CandidateTarget> {
        if (context.targetPackage != "com.tencent.mm") return candidates
        val rows = candidates.filter { isWeChatWholeRowCandidate(it, context) }
        if (rows.size < 2) return candidates
        return candidates.filter { candidate ->
            if (candidate in rows || isProtectedChromeCandidate(candidate.bounds, context)) return@filter true
            rows.none { row ->
                candidate.bounds.centerY() in row.bounds.top..row.bounds.bottom &&
                    containsMost(row.bounds, candidate.bounds, 0.35f)
            }
        }
    }

    private fun isWeChatWholeRowCandidate(
        candidate: CandidateTarget,
        context: TargetFusionContext
    ): Boolean {
        // Phase 9B: 微信整行目标必须由上游结构检测明确标记为 LIST_ITEM。
        // 不再凭“位于微信 + 看起来又宽又矮”临时升级普通 OCR/YOLO 候选。
        return context.targetPackage == "com.tencent.mm" &&
            candidate.role == TargetRole.LIST_ITEM
    }

    private fun isProtectedChromeCandidate(bounds: Rect, context: TargetFusionContext): Boolean {
        return isLikelyTopNavigationCandidate(bounds, context) ||
            isLikelyBottomNavigationCandidate(bounds, context)
    }

    private fun promoteCandidateToFinalTarget(
        candidate: CandidateTarget,
        context: TargetFusionContext
    ): FinalTarget? {
        val priority = candidatePriorityHint(candidate, context)
        if (priority < 50) return null
        val clickMethod = clickMethodForCandidate(candidate, context)
        return FinalTarget(
            node = candidate.node.copy(
                clickMethod = clickMethod,
                sourceSet = candidate.sourceSet,
                visualEvidence = candidate.node.visualEvidence?.copy(
                    sources = candidate.sourceSet,
                    clickMethod = clickMethod
                )
            ),
            sourceSet = candidate.sourceSet,
            role = candidate.role,
            clickMethod = clickMethod,
            confidence = candidate.confidence,
            priority = priority,
            stable = TargetSource.ACCESSIBILITY in candidate.sourceSet || candidate.role == TargetRole.LIST_ITEM,
            pageKey = context.pageKey
        )
    }

    private fun candidatePriorityHint(
        candidate: CandidateTarget,
        context: TargetFusionContext
    ): Int {
        val text = normalizedVisualText(candidate.text.orEmpty())
        val sources = candidate.sourceSet
        val yoloClass = candidate.evidence?.yoloClass.orEmpty()
        var score = when {
            TargetSource.ACCESSIBILITY in sources -> 100
            TargetSource.YOLO in sources && yoloClass in STRONG_YOLO_CLASSES -> 65
            TargetSource.YOLO in sources && yoloClass == "icon" -> 55
            TargetSource.YOLO in sources && yoloClass in setOf("card", "image") -> 52
            TargetSource.OCR in sources &&
                candidate.role == TargetRole.ACTION &&
                EdgeInteractionRules.isActionableVisualText(text) -> 50
            candidate.role == TargetRole.LIST_ITEM -> 65
            candidate.role == TargetRole.CARD -> 52
            else -> candidate.evidence?.score ?: 0
        }
        if (TargetSource.YOLO in sources && TargetSource.OCR in sources) score += 20
        if (EdgeInteractionRules.isActionableVisualText(text)) score += 15
        if (TargetSource.ACCESSIBILITY in sources && sources.size > 1) score += 30
        if (TargetSource.YOLO in sources) {
            score += ((candidate.evidence?.yoloConfidence ?: candidate.confidence) * 20f).roundToInt()
        }
        if (isLikelyTopNavigationCandidate(candidate.bounds, context) ||
            isLikelyBottomNavigationCandidate(candidate.bounds, context)
        ) {
            score += 8
        }
        if (candidate.source != TargetSource.ACCESSIBILITY &&
            overlapsExpandedJoystick(candidate.bounds, context) &&
            !isOverlayTolerantWeChatRow(candidate, context)
        ) score -= 80
        return score
    }

    private fun clickMethodForCandidate(
        candidate: CandidateTarget,
        context: TargetFusionContext
    ): ClickMethod {
        return when {
            candidate.role == TargetRole.LIST_ITEM -> ClickMethod.GESTURE_SAFE_LEFT
            TargetSource.ACCESSIBILITY in candidate.sourceSet -> ClickMethod.ACCESSIBILITY_ACTION
            candidate.clickMethod == ClickMethod.GESTURE_SAFE_LEFT -> ClickMethod.GESTURE_SAFE_LEFT
            else -> ClickMethod.GESTURE_CENTER
        }
    }

    private fun finalTargetLimit(targetPackage: String?): Int {
        return 96
    }

    private fun overlapsExpandedJoystick(bounds: Rect, context: TargetFusionContext): Boolean {
        val joystickBounds = context.visualOcclusionBounds ?: return false
        val expanded = Rect(joystickBounds).apply {
            inset(-dpToPx(12f, context), -dpToPx(12f, context))
        }
        if (expanded.contains(bounds.centerX(), bounds.centerY())) return true
        val minArea = minOf(bounds.areaLong(), expanded.areaLong()).coerceAtLeast(1L)
        val overlap = maxOf(0, minOf(bounds.right, expanded.right) - maxOf(bounds.left, expanded.left)).toLong() *
            maxOf(0, minOf(bounds.bottom, expanded.bottom) - maxOf(bounds.top, expanded.top)).toLong()
        return overlap / minArea.toFloat() > 0.18f
    }

    /**
     * 微信整行目标由重复头像锚点、OCR 标题与列表结构共同确认。即使整屏回退截图中
     * 摇杆遮住了行的中部，左侧安全点击区仍然可见且可操作，不应删除整行。小型视觉
     * 候选和其他应用继续使用原有遮挡过滤，避免把摇杆本身识别成目标。
     */
    private fun isOverlayTolerantWeChatRow(
        candidate: CandidateTarget,
        context: TargetFusionContext
    ): Boolean {
        return context.targetPackage == "com.tencent.mm" &&
            candidate.role == TargetRole.LIST_ITEM &&
            TargetSource.OCR in candidate.sourceSet &&
            TargetSource.VISUAL_FUSION in candidate.sourceSet &&
            candidate.clickMethod == ClickMethod.GESTURE_SAFE_LEFT &&
            candidate.bounds.width() >= context.screenWidth * 0.72f &&
            candidate.bounds.height() <= context.screenHeight * 0.19f
    }

    private fun isLikelyKeyboardCandidate(bounds: Rect, context: TargetFusionContext): Boolean {
        val top = context.keyboardTop ?: return false
        return bounds.centerY() >= top - 8 && bounds.bottom > top
    }

    private fun isWithinKeyboardRegion(bounds: Rect, context: TargetFusionContext): Boolean {
        val top = context.keyboardTop ?: return false
        return bounds.bottom > top - 8
    }

    private fun isLikelyBottomNavigationCandidate(bounds: Rect, context: TargetFusionContext): Boolean {
        return bounds.centerY() > context.screenHeight * 0.895f &&
            bounds.height() < context.screenHeight * 0.095f &&
            bounds.width() < context.screenWidth * 0.34f
    }

    private fun isLikelyTopNavigationCandidate(bounds: Rect, context: TargetFusionContext): Boolean {
        return bounds.centerY() in 0..(context.screenHeight * 0.16f).roundToInt() &&
            bounds.height() < context.screenHeight * 0.085f &&
            bounds.width() < context.screenWidth * 0.42f
    }

    private fun isLikelyStatusBarCandidate(bounds: Rect, context: TargetFusionContext): Boolean {
        return bounds.centerY() < context.screenHeight * 0.045f || bounds.top < 2
    }

    private fun isLikelySystemNavigationCandidate(bounds: Rect, context: TargetFusionContext): Boolean {
        return bounds.centerY() > context.screenHeight * 0.965f
    }

    private fun dpToPx(value: Float, context: TargetFusionContext): Int {
        return (value * context.density).roundToInt().coerceAtLeast(1)
    }
}
