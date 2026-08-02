package com.example.touchengine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * ContagionNavEngine v3.1 — 方案B：短暂确认后跳
 *
 * 交互模型：
 *  - 快速点拨（按住 < PRE_DELAY_MS 后松手）→ 跳一格，精确微操
 *  - 按住超过 PRE_DELAY_MS               → 首跳触发，继续按住则无极加速连跳
 *  - 拖住悬浮球转向（偏转 > DIR_RESET_DEG）→ 立即响应新方向，加速进度重置
 *  - 松手                                → 所有状态归零
 *
 * 调参建议：
 *  - 感觉确认太慢    → 降低 PRE_DELAY_MS（最低约 50ms）
 *  - 点拨误触发连跳  → 降低 PRE_DELAY_MS 或缩短 FLICK_WINDOW_MS
 *  - 加速太猛        → 增大 INTERVAL_SLOW_MS 或增大 ACCEL_DURATION_MS
 *  - 加速太慢        → 缩小 INTERVAL_FAST_MS 或缩短 ACCEL_DURATION_MS
 *  - 转向太灵敏      → 增大 DIR_RESET_DEG
 *  - 转向太迟钝      → 减小 DIR_RESET_DEG
 */

// 导航引擎返回结果
data class DragResult(
    val node: NavNode,      // 本帧应高亮的节点
    val atEdge: Boolean,    // 该方向是否真的没有邻居（用于触发自动滚动）
    val intentAngle: Double // 当前意图角度（度），用于判断滚动方向
)

class ContagionNavEngine {
    // ── 确认延迟 ──────────────────────────────────────────
    var PRE_DELAY_MS: Long = 80L
    // ── 加速系统 ──────────────────────────────────────────
    var ACCEL_DURATION_MS: Long = 800L
    var INTERVAL_SLOW_MS: Long  = 500L
    var INTERVAL_FAST_MS: Long  = 100L
    // ── 点拨判定 ──────────────────────────────────────────
    var FLICK_WINDOW_MS: Long = 300L
    // ── 摇杆死区 ──────────────────────────────────────────
    var DEAD_ZONE_RATIO: Float = 0.28f
    // ── 扇区角度 ──────────────────────────────────────────
    var CONE_HALF_ANGLE: Double = 45.0
    // ── 转向重置 ──────────────────────────────────────────
    var DIR_RESET_DEG: Double = 35.0
    private var responseMode: FocusResponseMode = FocusResponseMode.ORIGINAL_PLAN_B
    // ── 内部状态 ──────────────────────────────────────────
    private var pressing       = false
    private var pressStartTime = 0L
    private var lockedAngle    = Double.NaN
    private var preConfirmed   = false
    private var firstJumped    = false
    private var accelStartTime = 0L
    private var lastJumpTime   = 0L

    // ─────────────────────────────────────────────────────

    /** 每次新的摇杆会话锁定模式，设置页中途切换不会改变正在进行的选择。 */
    fun beginGesture(mode: FocusResponseMode) {
        resetState()
        responseMode = mode
    }

    fun onDrag(
        currentNode: NavNode,
        dragX: Float,
        dragY: Float,
        maxRadius: Float
    ): DragResult {
        val ratio = hypot(dragX.toDouble(), dragY.toDouble()).toFloat() / maxRadius

        if (ratio < DEAD_ZONE_RATIO) {
            resetState()
            return DragResult(currentNode, atEdge = false, intentAngle = 0.0)
        }

        val now   = System.currentTimeMillis()
        val angle = Math.toDegrees(atan2(dragY.toDouble(), dragX.toDouble()))

        // 方向突变检测
        if (!lockedAngle.isNaN()) {
            var diff = abs(angle - lockedAngle)
            if (diff > 180) diff = 360 - diff
            if (diff > DIR_RESET_DEG) {
                pressStartTime = now
                preConfirmed   = false
                firstJumped    = false
                accelStartTime = 0L
                lockedAngle    = angle
            }
        }

        if (!pressing) {
            pressing       = true
            pressStartTime = now
            lockedAngle    = angle
            preConfirmed   = false
            firstJumped    = false
            accelStartTime = 0L
        }

        val heldMs = now - pressStartTime

        val preDelayMs = if (responseMode == FocusResponseMode.INSTANT_RESPONSE) 0L else PRE_DELAY_MS
        if (!preConfirmed) {
            if (heldMs < preDelayMs) return DragResult(currentNode, atEdge = false, intentAngle = angle)
            preConfirmed = true
        }

        if (!firstJumped) {
            firstJumped    = true
            accelStartTime = now
            lastJumpTime   = now
            val next = findBestNeighbor(currentNode, lockedAngle)
            return DragResult(
                node        = next ?: currentNode,
                atEdge      = next == null,
                intentAngle = lockedAngle
            )
        }

        val accelDuration = if (responseMode == FocusResponseMode.INSTANT_RESPONSE) 600L else ACCEL_DURATION_MS
        val slowInterval = if (responseMode == FocusResponseMode.INSTANT_RESPONSE) 260L else INTERVAL_SLOW_MS
        val fastInterval = if (responseMode == FocusResponseMode.INSTANT_RESPONSE) 90L else INTERVAL_FAST_MS
        val speedProgress = min(
            (now - accelStartTime).toDouble() / accelDuration, 1.0
        )
        val currentInterval = (slowInterval *
                (fastInterval.toDouble() / slowInterval).pow(speedProgress)
                ).toLong()

        return if (now - lastJumpTime >= currentInterval) {
            lastJumpTime = now
            val next = findBestNeighbor(currentNode, lockedAngle)
            DragResult(
                node        = next ?: currentNode,
                atEdge      = next == null,
                intentAngle = lockedAngle
            )
        } else {
            // 冷却中：检查该方向是否真的没有邻居
            val stillAtEdge = findBestNeighbor(currentNode, lockedAngle) == null
            DragResult(currentNode, atEdge = stillAtEdge, intentAngle = lockedAngle)
        }
    }

    fun onRelease(
        currentNode: NavNode,
        dragX: Float,
        dragY: Float,
        maxRadius: Float
    ): NavNode {
        val pressDuration = System.currentTimeMillis() - pressStartTime
        val ratio = hypot(dragX.toDouble(), dragY.toDouble()).toFloat() / maxRadius

        val isFlick = !preConfirmed
                && pressDuration <= FLICK_WINDOW_MS
                && ratio >= DEAD_ZONE_RATIO

        resetState()

        return if (isFlick) {
            val angle = Math.toDegrees(atan2(dragY.toDouble(), dragX.toDouble()))
            findBestNeighbor(currentNode, angle) ?: currentNode
        } else {
            currentNode
        }
    }

    fun reset() = resetState()

    private fun findBestNeighbor(currentNode: NavNode, intentAngle: Double): NavNode? {
        var bestNode: NavNode? = null
        var minAngleDiff = Double.MAX_VALUE

        for (neighbor in currentNode.neighbors) {
            val dx = neighbor.centerX - currentNode.centerX
            val dy = neighbor.centerY - currentNode.centerY
            val neighborAngle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))

            var angleDiff = abs(intentAngle - neighborAngle)
            if (angleDiff > 180) angleDiff = 360 - angleDiff

            if (angleDiff <= CONE_HALF_ANGLE && angleDiff < minAngleDiff) {
                minAngleDiff = angleDiff
                bestNode = neighbor
            }
        }

        return bestNode
    }

    private fun resetState() {
        pressing       = false
        pressStartTime = 0L
        lockedAngle    = Double.NaN
        preConfirmed   = false
        firstJumped    = false
        accelStartTime = 0L
        lastJumpTime   = 0L
    }
}
