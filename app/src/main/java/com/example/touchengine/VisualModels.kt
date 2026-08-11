package com.example.touchengine

import android.graphics.Rect

/** 视觉识别、候选生成、调试叠加层共享的数据模型。 */

internal data class VisualRegionPolicy(
    val allowedRegions: List<Rect> = emptyList(),
    val blockedRegions: List<Rect> = emptyList()
)

internal enum class VisualCandidateKind {
    TEXT_EVIDENCE,
    ACTION_TEXT,
    ICON_ENTRY,
    RIGHT_RAIL_ICON,
    CARD_TARGET,
    LIST_ITEM_TARGET
}

internal enum class VisualLayoutKind {
    TWO_COLUMN_GRID,
    MULTI_COLUMN_GRID,
    SINGLE_COLUMN_LIST,
    ICON_GRID,
    BANNER_OR_PROMO,
    UNKNOWN
}

internal enum class VisualDebugKind {
    YOLO_STRUCTURE,
    OCR_PROMOTED,
    TEXT_EVIDENCE,
    FUSED_CANDIDATE,
    FILTERED_LOW
}

internal enum class DouyinScene {
    VIDEO_FEED_PORTRAIT,
    SEARCH_TEXT,
    GENERAL
}

internal data class VisualOcrCandidate(
    val bounds: Rect,
    val text: String,
    val kind: VisualCandidateKind = VisualCandidateKind.TEXT_EVIDENCE,
    val sourceSet: Set<TargetSource> = setOf(TargetSource.OCR),
    val yoloClass: String? = null,
    val yoloConfidence: Float? = null,
    val evidenceScore: Int = 0,
    val directEligible: Boolean = false,
    val clickMethod: ClickMethod = ClickMethod.GESTURE_CENTER
)

internal enum class YoloEvidenceTier {
    /** Kept only for the blue/gray debug overlay. Never enters navigation fusion. */
    DEBUG_ONLY,

    /** May participate only when corroborated by meaningful OCR/action evidence. */
    FUSION,

    /** Strong enough to stand alone, subject to geometry and package rules. */
    ACTION
}

internal data class YoloVisualCandidate(
    val bounds: Rect,
    val label: String,
    val score: Float,
    val kind: VisualCandidateKind,
    val tier: YoloEvidenceTier
)

internal data class VisualDebugCandidate(
    val bounds: Rect,
    val text: String,
    val kind: VisualDebugKind,
    val score: Float = 0f
)

internal data class VisualDiagnostics(
    val candidates: List<VisualDebugCandidate>,
    val rawYoloCount: Int,
    val keptYoloCount: Int,
    val ocrPromotedCount: Int,
    val filteredYoloCount: Int
)

internal data class VisualEvidenceBox(
    val bounds: Rect,
    val source: String
)

internal data class VisualOcrDisplay(
    val candidates: List<VisualOcrCandidate> = emptyList(),
    val diagnostics: List<VisualDebugCandidate> = emptyList(),
    val elapsedMs: Long? = null,
    val status: String? = null
)

internal data class ObjectEvidenceDisplay(
    val boxes: List<VisualEvidenceBox> = emptyList(),
    val elapsedMs: Long? = null,
    val status: String? = null
)

internal data class PendingVisualCache(
    val token: VisualScanToken,
    val stage: VisualUpdateStage = VisualUpdateStage.FUSED,
    val captureMode: VisualCaptureMode = VisualCaptureMode.DISPLAY_FALLBACK,
    val nodes: List<NavNode>,
    val candidates: List<VisualOcrCandidate>,
    val targetPackage: String?,
    val bands: Set<EdgeInteractionRules.GenericContentBand>,
    val surfaceKey: VisualSurfaceKey,
    val fingerprints: Map<EdgeInteractionRules.GenericContentBand, Long>,
    val actionableSignature: Int,
    val textEvidenceCount: Int,
    val rejectedEvidenceCount: Int,
    val elapsedMs: Long,
    val reason: VisualCacheInvalidationReason,
    val diagnostics: List<VisualDebugCandidate> = emptyList(),
    val diagnosticSummary: String? = null,
    /** 仅供视觉跑马灯门卫在最终 Mesh 成功提交后回传观察结果。 */
    val marqueeObservation: VisualMarqueeGuard.Observation? = null
)

/** 白纸机制一次准备结果中的节点级增量。 */
internal data class VisualCanvasPatch(
    val addedStableIds: Set<String> = emptySet(),
    val updatedStableIds: Set<String> = emptySet(),
    val removedStableIds: Set<String> = emptySet(),
    val geometryChangedStableIds: Set<String> = emptySet(),
    val retainedMissingStableIds: Set<String> = emptySet()
) {
    val changedStableIds: Set<String>
        get() = addedStableIds + updatedStableIds + removedStableIds

    val isEmpty: Boolean
        get() = changedStableIds.isEmpty()
}

/**
 * PersistentVisualCanvas 对 PendingVisualCache 的稳定化结果。
 *
 * [nodes] 是本次提交后白纸应持有的完整视觉节点集合，但其内部 stableId 已经与旧白纸
 * 对齐；[patch] 描述真正发生变化的节点，供 NavMeshCoordinator 选择增量更新范围。
 */
internal data class VisualCanvasUpdate(
    val pending: PendingVisualCache,
    val nodes: List<NavNode>,
    val patch: VisualCanvasPatch,
    val nextMissingCounts: Map<String, Int>,
    /** 未完成融合前，分别保留两条快速路径的观察，避免互相覆盖。 */
    val nextDirectObservations: Map<VisualUpdateStage, List<NavNode>>,
    /** Old direct stableId -> surviving fused stableId. */
    val stableIdAliases: Map<String, String> = emptyMap()
)
