package com.example.touchengine

import android.graphics.Rect

/** 视觉模型执行层与 OCR 证据解析层之间共享的不可变数据。 */
internal data class OcrExtractionContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val keyboardTop: Int?,
    val visualOcclusionBounds: Rect?,
    val targetPackage: String? = null,
    val douyinScene: DouyinScene = DouyinScene.GENERAL,
    val regionPolicy: VisualRegionPolicy = VisualRegionPolicy()
)

internal data class OcrRecognitionResult(
    val candidates: List<VisualOcrCandidate>,
    val elapsedMs: Long
)

internal data class ObjectRecognitionResult(
    val boxes: List<Rect>,
    val elapsedMs: Long
)

internal data class YoloPrewarmResult(
    val backendName: String?,
    val elapsedMs: Long
)
