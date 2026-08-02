package com.example.touchengine

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal enum class TargetClickPath {
    NATIVE_ACTION,
    NATIVE_PARENT_ACTION,
    GESTURE_CENTER,
    GESTURE_SAFE_LEFT,
    GESTURE_FALLBACK,
    NONE
}

internal data class TargetClickResult(
    val clicked: Boolean,
    val requestedMethod: ClickMethod,
    val path: TargetClickPath,
    val x: Float?,
    val y: Float?
)

internal class TargetClickExecutor(
    private val host: Host
) {
    interface Host {
        val screenWidth: Int
        val screenHeight: Int
        val density: Float
        fun activeTargetPackage(): String?
        fun obtainPrimaryContentRoot(): AccessibilityNodeInfo?
        fun dispatchGesture(gesture: GestureDescription): Boolean
    }

    fun performClick(target: NavNode): TargetClickResult {
        return when (target.clickMethod) {
            ClickMethod.ACCESSIBILITY_ACTION -> performAccessibilityClick(target)
            ClickMethod.GESTURE_CENTER,
            ClickMethod.GESTURE_SAFE_LEFT -> performGestureClick(target, target.clickMethod)
        }
    }

    private fun performAccessibilityClick(target: NavNode): TargetClickResult {
        val root = host.obtainPrimaryContentRoot()
        val native = root?.let { findNativeNodeByBounds(it, target.bounds) }
        try {
            if (native != null && native.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return TargetClickResult(
                    clicked = true,
                    requestedMethod = target.clickMethod,
                    path = TargetClickPath.NATIVE_ACTION,
                    x = null,
                    y = null
                )
            }

            var parent = native?.parent
            while (parent != null) {
                try {
                    if (parent.isClickable &&
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        parent.recycle()
                        return TargetClickResult(
                            clicked = true,
                            requestedMethod = target.clickMethod,
                            path = TargetClickPath.NATIVE_PARENT_ACTION,
                            x = null,
                            y = null
                        )
                    }
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                } catch (error: Throwable) {
                    parent.recycle()
                    parent = null
                }
            }
        } finally {
            native?.recycle()
            root?.recycle()
        }

        return performGestureClick(target, ClickMethod.GESTURE_CENTER, TargetClickPath.GESTURE_FALLBACK)
    }

    private fun performGestureClick(
        target: NavNode,
        method: ClickMethod,
        forcedPath: TargetClickPath? = null
    ): TargetClickResult {
        val (x, y) = clickPointForTarget(target, method)
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x, y) },
                    0L,
                    50L
                )
            )
            .build()
        val clicked = host.dispatchGesture(gesture)
        val path = forcedPath ?: when (method) {
            ClickMethod.GESTURE_SAFE_LEFT -> TargetClickPath.GESTURE_SAFE_LEFT
            else -> TargetClickPath.GESTURE_CENTER
        }
        return TargetClickResult(
            clicked = clicked,
            requestedMethod = target.clickMethod,
            path = path,
            x = x,
            y = y
        )
    }

    private fun clickPointForTarget(target: NavNode, method: ClickMethod): Pair<Float, Float> {
        val bounds = target.bounds
        val rawX = when (method) {
            ClickMethod.GESTURE_SAFE_LEFT -> bounds.left + bounds.width() * 0.38f
            else -> bounds.exactCenterX()
        }
        val rawY = bounds.exactCenterY()
        val minX = bounds.left.toFloat() + 1f
        val maxX = (bounds.right.toFloat() - 1f).coerceAtLeast(minX)
        val minY = bounds.top.toFloat() + 1f
        val maxY = (bounds.bottom.toFloat() - 1f).coerceAtLeast(minY)
        return rawX.coerceIn(minX, maxX) to rawY.coerceIn(minY, maxY)
    }

    private fun findNativeNodeByBounds(
        root: AccessibilityNodeInfo,
        targetBounds: Rect
    ): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        fun traverse(node: AccessibilityNodeInfo) {
            if (result != null) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect == targetBounds && (node.isClickable || node.isFocusable)) {
                result = AccessibilityNodeInfo.obtain(node)
                return
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                traverse(child)
                child.recycle()
                if (result != null) return
            }
        }
        traverse(root)
        return result
    }

}
