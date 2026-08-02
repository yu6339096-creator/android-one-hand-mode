package com.example.touchengine

/** 摇杆边缘交互与提示层共享的数据模型。 */
enum class ScrollState { IDLE, HINT, PLAN_B }

enum class ScrollDir { UP, DOWN, LEFT, RIGHT }

/** NavMesh 扫描深度。AccessibilityTreeParser 与 Service 共同使用。 */
enum class MeshScanPass { FAST, ANCHOR_RESCUE, HOLE_CONFIRM, DOUYIN_VIDEO_DEEP }

enum class HintType { PAGE_FLIP, SCROLL }

data class ScrollHintData(
    val dir: ScrollDir,
    val progress: Float,
    val type: HintType = HintType.SCROLL
)
