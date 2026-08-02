package com.example.touchengine

import java.util.ArrayDeque

/** 翻页后待比较的页面信息。 */
internal data class PendingPageFlip(
    val beforeFingerprint: String,
    val direction: ScrollDir?
)

internal sealed interface PageFlipEvaluation {
    object Normal : PageFlipEvaluation

    data class Circular(
        val originalFingerprint: String,
        val reverseDirection: ScrollDir
    ) : PageFlipEvaluation
}

/**
 * 翻页方向锁与循环页面历史。
 *
 * 保持原有 1.5 秒反向锁、6 页历史和循环死胡同判断，不读取 Android 或 Service 状态。
 */
internal class PageFlipCycleTracker(
    private val lockDurationMs: Long = 1500L,
    private val maxHistorySize: Int = 6
) {
    private var lastFlipDir: ScrollDir? = null
    private var lastFlipTimeMs: Long = 0L
    private val pageHistory = ArrayDeque<String>()
    private val circularDeadEnds = HashMap<String, MutableSet<ScrollDir>>()
    private var currentPageFingerprint = ""
    private var preFlipFingerprint = ""

    fun isBlocked(dir: ScrollDir, nowMs: Long): Boolean {
        val lockedDir = lastFlipDir?.opposite()
        if (lockedDir == dir && nowMs - lastFlipTimeMs < lockDurationMs) return true
        return circularDeadEnds[currentPageFingerprint]?.contains(dir) == true
    }

    fun prepareAttempt(beforeFingerprint: String) {
        preFlipFingerprint = beforeFingerprint
    }

    fun recordSuccess(dir: ScrollDir, nowMs: Long) {
        lastFlipDir = dir
        lastFlipTimeMs = nowMs
    }

    fun consumePending(): PendingPageFlip {
        val pending = PendingPageFlip(preFlipFingerprint, lastFlipDir)
        preFlipFingerprint = ""
        return pending
    }

    fun evaluate(pending: PendingPageFlip, afterFingerprint: String): PageFlipEvaluation {
        val direction = pending.direction
        val beforeFingerprint = pending.beforeFingerprint
        if (direction == null || beforeFingerprint.isBlank()) return PageFlipEvaluation.Normal

        if (afterFingerprint.isNotBlank() && pageHistory.contains(afterFingerprint)) {
            circularDeadEnds.getOrPut(beforeFingerprint) { mutableSetOf() }.add(direction)
            clearDirectionLock()
            return PageFlipEvaluation.Circular(
                originalFingerprint = beforeFingerprint,
                reverseDirection = direction.opposite()
            )
        }

        if (!pageHistory.contains(beforeFingerprint)) {
            pageHistory.addFirst(beforeFingerprint)
            if (pageHistory.size > maxHistorySize) pageHistory.removeLast()
        }
        currentPageFingerprint = afterFingerprint
        return PageFlipEvaluation.Normal
    }

    fun restorePageFingerprint(fingerprint: String) {
        currentPageFingerprint = fingerprint
    }

    private fun clearDirectionLock() {
        lastFlipDir = null
        lastFlipTimeMs = 0L
    }
}

internal fun ScrollDir.opposite(): ScrollDir {
    return when (this) {
        ScrollDir.LEFT -> ScrollDir.RIGHT
        ScrollDir.RIGHT -> ScrollDir.LEFT
        ScrollDir.UP -> ScrollDir.DOWN
        ScrollDir.DOWN -> ScrollDir.UP
    }
}
