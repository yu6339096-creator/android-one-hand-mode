package com.example.touchengine

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 无障碍滚动容器查询器。
 *
 * 只负责读取无障碍窗口、判断方向能力和选择最合适的横向翻页节点。
 * 返回给调用方的 AccessibilityNodeInfo 由调用方负责 recycle()；遍历过程中
 * 临时取得的节点由本类回收。
 */
internal class AccessibilityScrollResolver(
    private val windowsProvider: () -> List<AccessibilityWindowInfo>?,
    private val primaryRootProvider: () -> AccessibilityNodeInfo?,
    private val servicePackageProvider: () -> String,
    private val homePackageProvider: () -> String?,
    private val screenWidth: Int,
    private val screenHeight: Int
) {

    fun isHomePackage(packageName: CharSequence?): Boolean {
        val targetPackage = packageName?.toString() ?: return false
        return targetPackage == homePackageProvider()
    }

    /** 判断可滚动容器是否支持在给定方向上滚动。 */
    fun supportsScrollDirection(node: AccessibilityNodeInfo, dir: ScrollDir): Boolean {
        val actions = node.actionList.map { it.id }
        val hasScrollUp = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id in actions
        val hasScrollDown = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id in actions
        val hasScrollLeft = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in actions
        val hasScrollRight = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in actions
        val hasVerticalActions = hasScrollUp || hasScrollDown
        val hasHorizontalActions = hasScrollLeft || hasScrollRight

        if (hasVerticalActions || hasHorizontalActions) {
            return when (dir) {
                ScrollDir.UP -> hasScrollUp
                ScrollDir.DOWN -> hasScrollDown
                ScrollDir.LEFT -> hasScrollLeft
                ScrollDir.RIGHT -> hasScrollRight
            }
        }

        val hasForward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions
        val hasBackward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
        if (!hasForward && !hasBackward) return false

        // 保持原有判断顺序。HorizontalScrollView 同时命中 ScrollView 的老问题
        // 记录在后续问题清单，本阶段不改变行为。
        val className = node.className?.toString() ?: ""
        val isHorizontalClass = className.contains("HorizontalScrollView", ignoreCase = true) ||
            className.contains("ViewPager", ignoreCase = true) ||
            className.contains("Workspace", ignoreCase = true)
        val isVerticalClass = className.contains("ScrollView", ignoreCase = true) ||
            className.contains("ListView", ignoreCase = true) ||
            className.contains("GridView", ignoreCase = true) ||
            className.contains("WebView", ignoreCase = true)

        return when (dir) {
            ScrollDir.UP -> hasBackward && !isHorizontalClass
            ScrollDir.DOWN -> hasForward && !isHorizontalClass
            ScrollDir.LEFT -> hasBackward && !isVerticalClass
            ScrollDir.RIGHT -> hasForward && !isVerticalClass
        }
    }

    /** 获取应该在该可滚动容器上执行的具体 Action ID。 */
    fun scrollActionId(node: AccessibilityNodeInfo, dir: ScrollDir): Int? {
        val actions = node.actionList.map { it.id }
        val specificAction = when (dir) {
            ScrollDir.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            ScrollDir.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            ScrollDir.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            ScrollDir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (specificAction.id in actions) return specificAction.id

        val fallbackActionId = when (dir) {
            ScrollDir.DOWN, ScrollDir.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDir.UP, ScrollDir.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return fallbackActionId.takeIf { it in actions }
    }

    fun boundsOf(node: AccessibilityNodeInfo): Rect {
        return Rect().also { node.getBoundsInScreen(it) }
    }

    fun findLocalHorizontalPageNode(focus: NavNode, dir: ScrollDir): AccessibilityNodeInfo? {
        return findBestAccessibilityNode(
            accept = { node ->
                isHorizontalPageNode(node, dir, focus) &&
                    (isHomePackage(node.packageName) || isLocallyRelatedToFocus(node, focus))
            },
            score = { node -> nodeScoreToFocus(node, focus) }
        )
    }

    fun findAnyHorizontalPageNode(dir: ScrollDir): AccessibilityNodeInfo? {
        return findBestAccessibilityNode(
            accept = { node -> isHorizontalPageNode(node, dir, null) },
            score = { node -> boundsOf(node).top.toFloat() }
        )
    }

    private fun isHorizontalPageNode(
        node: AccessibilityNodeInfo,
        dir: ScrollDir,
        focus: NavNode?
    ): Boolean {
        if (!dir.isHorizontal() || !supportsScrollDirection(node, dir)) return false
        val className = node.className?.toString() ?: ""
        val rect = boundsOf(node)
        val wideEnough = rect.width() > rect.height() * 1.2f
        val explicitPageClass =
            className.contains("ViewPager", ignoreCase = true) ||
                className.contains("HorizontalScrollView", ignoreCase = true) ||
                className.contains("Workspace", ignoreCase = true)

        if (explicitPageClass || (hasSpecificHorizontalAction(node, dir) && wideEnough)) return true
        if (!hasGenericScrollAction(node, dir)) return false

        val isKnownVerticalClass =
            className.contains("ScrollView", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true) ||
                className.contains("GridView", ignoreCase = true) ||
                className.contains("WebView", ignoreCase = true)
        val isHome = isHomePackage(node.packageName)
        if (!EdgeInteractionRules.acceptsGenericHorizontalTarget(
                rect.width(),
                rect.height(),
                screenHeight,
                isKnownVerticalClass,
                isHome
            )
        ) {
            return false
        }
        return isHome || (focus != null && isLocallyRelatedToFocus(node, focus))
    }

    private fun isLocallyRelatedToFocus(
        candidate: AccessibilityNodeInfo,
        focus: NavNode
    ): Boolean {
        if (candidate.windowId != focus.windowId) return false
        val rect = boundsOf(candidate)
        if (rect.isEmpty) return false

        val focusRect = focus.bounds
        val focusCx = focus.centerX.toInt()
        val focusCy = focus.centerY.toInt()
        val pad = (screenWidth * 0.04f).roundToInt().coerceAtLeast(32)
        val expanded = Rect(rect).apply { inset(-pad, -pad) }
        if (expanded.contains(focusCx, focusCy)) return true

        val yOverlap = overlap(rect.top, rect.bottom, focusRect.top, focusRect.bottom)
        val minHeight = minOf(rect.height(), focusRect.height()).coerceAtLeast(1)
        val sameRow = yOverlap >= minHeight * 0.45f
        val centerGap = kotlin.math.abs(rect.exactCenterX() - focus.centerX)
        return sameRow && centerGap <= screenWidth * 0.45f
    }

    private fun nodeScoreToFocus(node: AccessibilityNodeInfo, focus: NavNode): Float {
        val rect = boundsOf(node)
        val dx = rect.exactCenterX() - focus.centerX
        val dy = rect.exactCenterY() - focus.centerY
        val containsBonus = if (rect.contains(focus.centerX.toInt(), focus.centerY.toInt())) {
            -screenWidth.toFloat()
        } else {
            0f
        }
        return hypot(dx.toDouble(), dy.toDouble()).toFloat() + containsBonus
    }

    private fun findBestAccessibilityNode(
        accept: (AccessibilityNodeInfo) -> Boolean,
        score: (AccessibilityNodeInfo) -> Float
    ): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val sortedWindows = windowsProvider()?.sortedByDescending { it.layer }.orEmpty()
            if (sortedWindows.isNotEmpty()) {
                for (window in sortedWindows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val root = window.root ?: continue
                    if (root.packageName?.toString() == servicePackageProvider()) {
                        root.recycle()
                        continue
                    }
                    roots.add(root)
                }
            } else {
                val root = primaryRootProvider() ?: return null
                roots.add(root)
            }
        } catch (error: Exception) {
            Log.e("TouchEngine", "collect roots error: ${error.message}")
            roots.forEach { runCatching { it.recycle() } }
            return null
        }

        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Float.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addAll(roots)
        val deadlineNs = System.nanoTime() + 8_000_000L
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < 320 && System.nanoTime() < deadlineNs) {
                val node = queue.removeFirst()
                try {
                    visited++
                    if (accept(node)) {
                        val candidateScore = score(node)
                        if (candidateScore < bestScore) {
                            bestNode?.recycle()
                            bestNode = AccessibilityNodeInfo.obtain(node)
                            bestScore = candidateScore
                        }
                    }
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let(queue::add)
                    }
                } finally {
                    node.recycle()
                }
            }
        } catch (error: Exception) {
            bestNode?.recycle()
            Log.e("TouchEngine", "findBestAccessibilityNode error: ${error.message}")
            return null
        } finally {
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().recycle() }
            }
        }
        return bestNode
    }

    private fun hasSpecificHorizontalAction(
        node: AccessibilityNodeInfo,
        dir: ScrollDir
    ): Boolean {
        val actions = node.actionList.map { it.id }
        return when (dir) {
            ScrollDir.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in actions
            ScrollDir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in actions
            else -> false
        }
    }

    private fun hasGenericScrollAction(node: AccessibilityNodeInfo, dir: ScrollDir): Boolean {
        val actions = node.actionList.map { it.id }
        return when (dir) {
            ScrollDir.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in actions
            ScrollDir.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions
            else -> false
        }
    }

    private fun overlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int {
        return (minOf(aEnd, bEnd) - maxOf(aStart, bStart)).coerceAtLeast(0)
    }
}

internal fun ScrollDir.isHorizontal(): Boolean {
    return this == ScrollDir.LEFT || this == ScrollDir.RIGHT
}

internal fun ScrollDir.isVertical(): Boolean {
    return this == ScrollDir.UP || this == ScrollDir.DOWN
}
