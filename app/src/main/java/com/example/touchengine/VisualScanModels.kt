package com.example.touchengine

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 单次视觉扫描所需的只读运行环境。
 *
 * 这里不保存 Service、Handler、NavMeshBuilder 或 Compose 生命周期对象，避免扫描层反向依赖
 * AccessibilityService。列表均为构建快照时的引用快照；扫描完成前仍会通过 runtimeProvider
 * 再次检查包名、选择状态和滚动状态。
 */
internal data class VisualScanRuntime(
    val targetPackage: String?,
    val primaryWindowId: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val keyboardTop: Int?,
    /** 摇杆真实交互范围；只用于交互状态，不代表截图中一定存在遮挡。 */
    val joystickInteractionBounds: Rect?,
    val inputMethodVisible: Boolean,
    val activeNodes: List<NavNode>,
    val stableNodes: List<NavNode>,
    val isJoystickSelecting: Boolean,
    val scrollIdle: Boolean,
    val objectEvidenceEnabled: Boolean,
    val yoloInputSize: YoloInputSize
)

/** 当前帧实际采用的截图来源。 */
internal enum class VisualCaptureMode {
    /** Android 14+ 目标应用窗口截图，不包含本服务的无障碍悬浮层。 */
    WINDOW_CLEAN,

    /** 整个显示屏截图，可能包含本服务的悬浮摇杆。 */
    DISPLAY_FALLBACK
}

/** 截图层只依赖这些不可变信息，避免在异步回调中重新猜测目标窗口。 */
internal data class VisualCaptureRequest(
    val targetPackage: String?,
    val primaryWindowId: Int,
    val requiredBounds: Rect,
    val screenWidth: Int,
    val screenHeight: Int
)

/**
 * 截图及其屏幕坐标元数据。
 * [boundsInScreen] 描述 bitmap 左上角在屏幕坐标系中的位置。
 */
internal data class VisualScreenshotFrame(
    val bitmap: Bitmap,
    val captureMode: VisualCaptureMode,
    val windowId: Int,
    val boundsInScreen: Rect,
    val fallbackReason: String? = null
)



/**
 * 一次视觉扫描的不可变身份。
 *
 * pageEpoch 用于页面/窗口会话隔离，viewportRevision 用于滚动或翻页后的视口隔离，
 * scanId 用于区分同一视口内的不同扫描。任何一个字段过期，结果都不得写回缓存或 NavMesh。
 */
internal enum class VisualScanMode {
    /** 普通后台扫描；可按原有缓存规则安装。 */
    BASE_SCAN,

    /** 页面、窗口、滚动、翻页等强变化；继续走原有完整刷新/清旧节点规则。 */
    FULL_REFRESH,

    /** 悬浮球松手后的确认扫描；先 diff，没变化就不安装、不刷新 Overlay。 */
    SELECTION_CONFIRM_DIFF
}

/**
 * 视觉结果写入白纸的阶段。直入阶段不等待另一种模型；FUSED 仅负责随后校正同一批节点。
 */
internal enum class VisualUpdateStage {
    DIRECT_YOLO,
    DIRECT_OCR,
    FUSED
}

internal data class VisualScanToken(
    val pageEpoch: Long,
    val viewportRevision: Long,
    val scanId: Long,
    val targetPackage: String?,
    val primaryWindowId: Int,
    val scanMode: VisualScanMode = VisualScanMode.BASE_SCAN
)

/** Service 已经判定可以扫描后，交给 Coordinator 的完整请求。 */
internal data class VisualScanRequest(
    val runtime: VisualScanRuntime,
    val requestedBands: Set<EdgeInteractionRules.GenericContentBand>,
    val cropBounds: Rect,
    val bandBounds: Map<EdgeInteractionRules.GenericContentBand, Rect>,
    val surfaceKey: VisualSurfaceKey,
    val douyinScene: DouyinScene,
    val regionPolicy: VisualRegionPolicy = VisualRegionPolicy(),
    val fullScreenTestMode: Boolean
)

/**
 * Android 系统截图桥。
 *
 * 成功回调后 Bitmap 所有权移交给 VisualScanCoordinator，Coordinator 必须负责 recycle。
 */
internal interface VisualScreenshotProvider {
    fun capture(
        request: VisualCaptureRequest,
        onSuccess: (VisualScreenshotFrame) -> Unit,
        onFailure: (Int) -> Unit
    )
}

/** 缓存提交后返回给 Service 的焦点保持结果与显示数据。 */
internal data class VisualCacheCommitResult(
    val replacementFocus: NavNode?,
    val preservesFocus: Boolean,
    val display: VisualOcrDisplay
)
