package com.example.touchengine

import android.graphics.Rect

enum class NavNodeOrigin { ACCESSIBILITY, VISION }

/**
 * 导航节点
 *
 * 字段说明：
 *  - bounds      原始屏幕坐标矩形，用于焦点框绘制和点击执行
 *  - centerX/Y   预计算的中心点，供 ContagionNavEngine 和 NavMeshBuilder 使用
 *  - description 节点文字描述，调试用
 *  - windowId    所属窗口 ID，用于 Z 轴层级隔离（弹窗/抽屉防穿透）
 *  - clickMethod 融合层计算出的点击执行策略
 *  - sourceSet   同一目标累计命中的 Accessibility/OCR/YOLO 等证据来源
 *  - stableId    白纸机制和增量 NavMesh 使用的稳定身份
 *  - neighbors   邻居节点列表，由 NavMeshBuilder.build() 填充
 */
data class NavNode(
    val bounds: Rect,
    val description: String?,
    val windowId: Int = 0,
    // 预计算中心点，避免每帧重复计算
    val centerX: Float = bounds.exactCenterX(),
    val centerY: Float = bounds.exactCenterY(),
    val origin: NavNodeOrigin = NavNodeOrigin.ACCESSIBILITY,
    val clickMethod: ClickMethod = if (origin == NavNodeOrigin.ACCESSIBILITY) {
        ClickMethod.ACCESSIBILITY_ACTION
    } else {
        ClickMethod.GESTURE_CENTER
    },
    val sourceSet: Set<TargetSource> = if (origin == NavNodeOrigin.ACCESSIBILITY) {
        setOf(TargetSource.ACCESSIBILITY)
    } else {
        emptySet()
    },
    val visualEvidence: VisualTargetEvidence? = null,
    /**
     * 白纸机制与增量 NavMesh 使用的稳定身份。
     *
     * Accessibility 节点在完整建图提交时补齐；视觉节点由 PersistentVisualCanvas
     * 在首次登记时分配，并在后续扫描、融合和局部更新中持续透传。
     */
    val stableId: String? = null
) {
    val neighbors = mutableListOf<NavNode>()
}
