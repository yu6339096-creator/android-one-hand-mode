package com.example.touchengine

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * 系统手势派发器。
 *
 * 只负责把方向与区域转换成 GestureDescription，不持有焦点、NavMesh 或滚动状态。
 */
internal class GestureDispatcher(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val configProvider: () -> ScrollConfig,
    private val dispatch: (GestureDescription) -> Boolean
) {

    fun dispatchHorizontalPage(dir: ScrollDir, targetBounds: Rect?): Boolean {
        if (!dir.isHorizontal()) return false
        val bounds = targetBounds?.takeUnless { it.isEmpty }
            ?: Rect(
                0,
                (screenHeight * 0.12f).roundToInt(),
                screenWidth,
                (screenHeight * 0.88f).roundToInt()
            )
        val left = bounds.left + bounds.width() * 0.22f
        val right = bounds.left + bounds.width() * 0.78f
        val y = bounds.exactCenterY()
        val (startX, endX) = when (dir) {
            ScrollDir.RIGHT -> Pair(right, left)
            ScrollDir.LEFT -> Pair(left, right)
            else -> return false
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply {
                        moveTo(startX, y)
                        lineTo(endX, y)
                    },
                    0L,
                    configProvider().scrollGestureDurationMs
                )
            )
            .build()
        return dispatch(gesture)
    }

    /** 执行一次 Plan B 手势。保持原有距离算法，横向仍按屏幕高度计算。 */
    fun dispatchContinuousScroll(dir: ScrollDir): Boolean {
        val config = configProvider()
        val sw = screenWidth.toFloat()
        val sh = screenHeight.toFloat()
        val dist = sh * config.scrollSensitivity
        val (startX, startY, endX, endY) = when (dir) {
            ScrollDir.DOWN -> arrayOf(sw / 2, sh * 0.65f, sw / 2, sh * 0.65f - dist)
            ScrollDir.UP -> arrayOf(sw / 2, sh * 0.35f, sw / 2, sh * 0.35f + dist)
            ScrollDir.RIGHT -> arrayOf(sw * 0.65f, sh / 2, sw * 0.65f - dist, sh / 2)
            ScrollDir.LEFT -> arrayOf(sw * 0.35f, sh / 2, sw * 0.35f + dist, sh / 2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply {
                        moveTo(startX, startY)
                        lineTo(endX, endY)
                    },
                    0L,
                    config.scrollGestureDurationMs
                )
            )
            .build()
        return dispatch(gesture)
    }
}
