package com.example.touchengine

import android.graphics.Rect

/** 最终目标融合阶段使用的来源类型。 */
enum class TargetSource {
    ACCESSIBILITY,
    YOLO,
    OCR,
    VISUAL_FUSION
}

/** 最终目标融合阶段使用的语义角色。 */
enum class TargetRole {
    ACTION,
    ICON_ENTRY,
    RIGHT_RAIL,
    CARD,
    LIST_ITEM,
    TEXT_EVIDENCE,
    UNKNOWN
}

/** 融合层计算、NavNode 透传、TargetClickExecutor 消费的点击策略。 */
enum class ClickMethod {
    ACCESSIBILITY_ACTION,
    GESTURE_CENTER,
    GESTURE_SAFE_LEFT
}

/** Typed reason explaining why multiple observations became one navigation target. */
enum class FusionMergeRelation {
    OVERLAP_DUPLICATE,
    CONTAINED_LABEL,
    ICON_LABEL_PAIR,
    KEEP_INNER_ACTION
}

/**
 * Typed visual evidence. Fusion must consume these fields instead of parsing
 * display-only description strings.
 */
data class VisualTargetEvidence(
    val sources: Set<TargetSource>,
    val yoloClass: String? = null,
    val yoloConfidence: Float? = null,
    val ocrText: String? = null,
    val role: TargetRole,
    val score: Int,
    val clickMethod: ClickMethod,
    val directEligible: Boolean,
    val mergeRelations: Set<FusionMergeRelation> = emptySet(),
    /** Stable identities replaced by this fused observation. */
    val absorbedStableIds: Set<String> = emptySet()
)

internal data class CandidateTarget(
    val node: NavNode,
    /** 用于几何、评分与语义角色选择的主来源。 */
    val source: TargetSource,
    /** 同一目标累计命中的全部证据来源。 */
    val sourceSet: Set<TargetSource> = setOf(source),
    val role: TargetRole,
    val text: String?,
    val bounds: Rect,
    val confidence: Float,
    val clickable: Boolean,
    val scrollable: Boolean,
    val rawType: String,
    val evidence: VisualTargetEvidence? = node.visualEvidence,
    /** 当前候选建议的点击执行策略。 */
    val clickMethod: ClickMethod = node.clickMethod,
    val mergeRelations: Set<FusionMergeRelation> = evidence?.mergeRelations.orEmpty(),
    val absorbedStableIds: Set<String> = evidence?.absorbedStableIds.orEmpty()
)

internal data class FinalTarget(
    val node: NavNode,
    val sourceSet: Set<TargetSource>,
    val role: TargetRole,
    val clickMethod: ClickMethod,
    val confidence: Float,
    val priority: Int,
    val stable: Boolean,
    val pageKey: String
)

/** TargetFusionEngine 一次同步融合所需的不可变环境快照。 */
internal data class TargetFusionContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val keyboardTop: Int?,
    val visualOcclusionBounds: Rect?,
    val targetPackage: String?,
    val douyinScene: DouyinScene,
    val useDouyinVideoAccessibility: Boolean
) {
    val screenArea: Int
        get() = screenWidth * screenHeight

    val pageKey: String
        get() = "${targetPackage.orEmpty()}:${douyinScene.name}"
}

internal data class TargetFusionStats(
    val rawNodeCount: Int,
    val rawCandidateCount: Int,
    val candidateCount: Int,
    val mergedCandidateCount: Int,
    val finalCount: Int,
    val accessibilityCount: Int,
    val visionCount: Int,
    val pageKey: String,
    val yoloInputCount: Int = 0,
    val ocrInputCount: Int = 0,
    val fusionInputCount: Int = 0,
    val listItemInputCount: Int = 0,
    val listItemFinalCount: Int = 0,
    val containedLabelMergeCount: Int = 0,
    val iconLabelPairCount: Int = 0,
    val keptInnerActionCount: Int = 0,
    val overlapDuplicateCount: Int = 0
)

internal data class TargetFusionResult(
    val nodes: List<NavNode>,
    val targets: List<FinalTarget>,
    val stats: TargetFusionStats
)
