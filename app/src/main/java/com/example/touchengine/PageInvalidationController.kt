package com.example.touchengine

import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.max

/**
 * Phase 8D.1: coalesces noisy accessibility content events and separates
 * navigation-structure changes from text/image-only changes.
 *
 * The controller never rebuilds NavMesh or starts OCR/YOLO itself. It only emits a
 * decision after a short quiet window. This keeps the policy testable and prevents
 * each TYPE_WINDOW_CONTENT_CHANGED from directly invalidating the active page.
 */
internal class PageInvalidationController(
    private val handler: Handler,
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onDecision: (PageInvalidationDecision) -> Unit
) {

    private var baseline: PageChangeSnapshot? = null
    private var pendingBatch: PendingContentBatch? = null
    private var generation = 0
    private var baselineGeneration = 0
    private var lastSoftScanAtMs = 0L

    private val evaluateRunnable = Runnable { evaluatePendingBatch() }
    private val baselineRunnable = Runnable {
        val expectedGeneration = baselineGeneration
        val snapshot = captureSnapshot()
        if (expectedGeneration == baselineGeneration && snapshot != null) {
            baseline = snapshot
            Log.d(
                TAG,
                "baseline pkg=${snapshot.targetPackage} window=${snapshot.primaryWindowId} " +
                    "structure=${snapshot.structureKeys.size} content=${snapshot.contentTokens.size}"
            )
        }
    }

    fun submit(event: AccessibilityEvent) {
        val sample = ContentEventSample(
            packageName = event.packageName?.toString(),
            windowId = event.windowId,
            eventType = event.eventType,
            contentChangeTypes = event.contentChangeTypes,
            sourceBounds = eventSourceBounds(event),
            receivedAtMs = SystemClock.elapsedRealtime()
        )

        pendingBatch = pendingBatch?.merge(sample) ?: PendingContentBatch.from(sample)
        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, EVENT_COALESCE_MS)
    }

    fun reset(reason: String) {
        generation++
        baselineGeneration++
        pendingBatch = null
        baseline = null
        handler.removeCallbacks(evaluateRunnable)
        handler.removeCallbacks(baselineRunnable)
        Log.d(TAG, "reset reason=$reason")
    }

    fun refreshBaseline(reason: String, delayMs: Long = BASELINE_REFRESH_DELAY_MS) {
        baselineGeneration++
        handler.removeCallbacks(baselineRunnable)
        handler.postDelayed(baselineRunnable, delayMs.coerceAtLeast(0L))
        Log.d(TAG, "baselineRefresh reason=$reason delay=${delayMs}ms")
    }

    fun close() {
        generation++
        baselineGeneration++
        pendingBatch = null
        baseline = null
        handler.removeCallbacks(evaluateRunnable)
        handler.removeCallbacks(baselineRunnable)
    }

    private fun evaluatePendingBatch() {
        val batch = pendingBatch ?: return
        pendingBatch = null
        val evaluationGeneration = generation
        val current = captureSnapshot()
        if (evaluationGeneration != generation) return

        if (current == null) {
            onDecision(
                PageInvalidationDecision(
                    level = PageInvalidationLevel.DEFERRED_FULL_SCAN,
                    eventSummary = batch.summary("rootUnavailable"),
                    eventPackage = batch.packageName,
                    structuralDistance = 1f,
                    contentDistance = 1f,
                    changedAreaRatio = batch.changedAreaRatio(screenWidth, screenHeight)
                )
            )
            return
        }

        val previous = baseline
        if (previous == null ||
            previous.targetPackage != current.targetPackage ||
            !sameWindow(previous.primaryWindowId, current.primaryWindowId)
        ) {
            baseline = current
            onDecision(
                PageInvalidationDecision(
                    level = PageInvalidationLevel.FULL_SCAN,
                    eventSummary = batch.summary("baselineMissingOrSurfaceChanged"),
                    eventPackage = current.targetPackage ?: batch.packageName,
                    structuralDistance = 1f,
                    contentDistance = 1f,
                    changedAreaRatio = batch.changedAreaRatio(screenWidth, screenHeight)
                )
            )
            return
        }

        val structuralDistance = setDistance(previous.structureKeys, current.structureKeys)
        val contentDistance = setDistance(previous.contentTokens, current.contentTokens)
        val structuralCountDelta = abs(
            current.structureKeys.size - previous.structureKeys.size
        )
        val changedAreaRatio = batch.changedAreaRatio(screenWidth, screenHeight)
        val strongStructureHint = batch.hasStructuralHint()
        val textOnlyHint = batch.isTextOnlyHint()

        val countThreshold = max(
            STRUCTURE_NODE_DELTA_MIN,
            (max(previous.structureKeys.size, current.structureKeys.size) *
                STRUCTURE_NODE_DELTA_RATIO).toInt()
        )
        val structureChanged = structuralDistance >= STRUCTURE_DISTANCE_FULL ||
            structuralCountDelta >= countThreshold ||
            (strongStructureHint && structuralDistance >= STRUCTURE_DISTANCE_HINTED)

        val level = when {
            structureChanged -> PageInvalidationLevel.FULL_SCAN

            // A pure text/content-description event with a stable tree is exactly the
            // pattern produced by countdowns and running text. Keep the current graph.
            textOnlyHint && structuralDistance < STRUCTURE_DISTANCE_STABLE ->
                PageInvalidationLevel.NONE

            // Small, localized changes on an otherwise identical tree are treated as
            // content animation. Region quarantine is added in 8D.2; this cheap guard
            // already blocks most marquee/countdown refresh storms.
            changedAreaRatio in 0f..SMALL_CHANGED_AREA_RATIO &&
                structuralDistance < STRUCTURE_DISTANCE_STABLE ->
                PageInvalidationLevel.NONE

            // A large same-layout replacement (for example, same-window tab content)
            // still deserves a visual refresh, but must not rebuild the accessibility graph.
            contentDistance >= CONTENT_DISTANCE_SOFT_SCAN ||
                (current.contentTokens.isEmpty() &&
                    changedAreaRatio >= LARGE_CHANGED_AREA_RATIO &&
                    batch.hasUnknownOrSubtreeHint()) ->
                softScanLevel()

            else -> PageInvalidationLevel.NONE
        }

        baseline = current
        onDecision(
            PageInvalidationDecision(
                level = level,
                eventSummary = batch.summary(
                    "structure=$structuralDistance content=$contentDistance " +
                        "countDelta=$structuralCountDelta area=$changedAreaRatio"
                ),
                eventPackage = current.targetPackage ?: batch.packageName,
                structuralDistance = structuralDistance,
                contentDistance = contentDistance,
                changedAreaRatio = changedAreaRatio
            )
        )
    }

    private fun softScanLevel(): PageInvalidationLevel {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSoftScanAtMs < SOFT_SCAN_MIN_INTERVAL_MS) {
            return PageInvalidationLevel.NONE
        }
        lastSoftScanAtMs = now
        return PageInvalidationLevel.CONTENT_ONLY_SCAN
    }

    private fun captureSnapshot(): PageChangeSnapshot? {
        val root = try {
            rootProvider()
        } catch (error: Throwable) {
            Log.d(TAG, "snapshot root failed: ${error.message}")
            null
        } ?: return null

        return try {
            val structureKeys = LinkedHashSet<Long>(MAX_STRUCTURE_NODES)
            val contentTokens = LinkedHashSet<String>(MAX_CONTENT_TOKENS)
            var visited = 0

            fun traverse(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > MAX_DEPTH || visited >= MAX_STRUCTURE_NODES) return
                visited++

                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (node.isVisibleToUser && !rect.isEmpty && intersectsScreen(rect)) {
                    structureKeys += structureKey(node, rect, depth)
                    if (contentTokens.size < MAX_CONTENT_TOKENS) {
                        val raw = node.text?.toString()
                            ?: node.contentDescription?.toString()
                        normalizeContentToken(raw)?.let(contentTokens::add)
                    }
                }

                for (index in 0 until node.childCount) {
                    if (visited >= MAX_STRUCTURE_NODES) break
                    val child = node.getChild(index) ?: continue
                    try {
                        traverse(child, depth + 1)
                    } finally {
                        child.recycle()
                    }
                }
            }

            traverse(root, 0)
            PageChangeSnapshot(
                targetPackage = root.packageName?.toString(),
                primaryWindowId = root.windowId,
                structureKeys = structureKeys,
                contentTokens = contentTokens
            )
        } catch (error: Throwable) {
            Log.d(TAG, "snapshot failed: ${error.message}")
            null
        } finally {
            root.recycle()
        }
    }

    private fun structureKey(
        node: AccessibilityNodeInfo,
        rect: Rect,
        depth: Int
    ): Long {
        var flags = 0
        if (node.isClickable) flags = flags or 1
        if (node.isLongClickable) flags = flags or 2
        if (node.isScrollable) flags = flags or 4
        if (node.isFocusable) flags = flags or 8
        if (node.isEditable) flags = flags or 16
        if (node.isCheckable) flags = flags or 32
        if (node.isEnabled) flags = flags or 64

        val key = buildString(96) {
            append(node.windowId)
            append(':').append(depth.coerceAtMost(9))
            append(':').append(rect.left / BOUNDS_QUANTUM_PX)
            append(':').append(rect.top / BOUNDS_QUANTUM_PX)
            append(':').append(rect.right / BOUNDS_QUANTUM_PX)
            append(':').append(rect.bottom / BOUNDS_QUANTUM_PX)
            append(':').append(node.className?.toString().orEmpty())
            append(':').append(node.childCount.coerceAtMost(15))
            append(':').append(flags)
        }
        return key.hashCode().toLong() and 0xffffffffL
    }

    private fun normalizeContentToken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw
            .trim()
            .lowercase()
            .replace(WHITESPACE_REGEX, "")
            .replace(NUMBER_REGEX, "#")
            .replace(REPEATED_MARK_REGEX, "#")
            .take(MAX_TOKEN_LENGTH)
        return normalized.takeIf { it.length >= MIN_TOKEN_LENGTH }
    }

    private fun intersectsScreen(rect: Rect): Boolean {
        return rect.right > 0 && rect.bottom > 0 &&
            rect.left < screenWidth && rect.top < screenHeight
    }

    private fun eventSourceBounds(event: AccessibilityEvent): Rect? {
        val source = runCatching { event.source }.getOrNull() ?: return null
        return try {
            val rect = Rect()
            source.getBoundsInScreen(rect)
            rect.takeIf { !it.isEmpty && intersectsScreen(it) }
        } catch (_: Throwable) {
            null
        } finally {
            source.recycle()
        }
    }

    private fun sameWindow(left: Int, right: Int): Boolean {
        return left < 0 || right < 0 || left == right
    }

    private fun <T> setDistance(left: Set<T>, right: Set<T>): Float {
        if (left.isEmpty() && right.isEmpty()) return 0f
        if (left.isEmpty() || right.isEmpty()) return 1f
        val smaller = if (left.size <= right.size) left else right
        val larger = if (left.size <= right.size) right else left
        var intersection = 0
        smaller.forEach { if (it in larger) intersection++ }
        return 1f - intersection.toFloat() / max(left.size, right.size).toFloat()
    }

    private data class PageChangeSnapshot(
        val targetPackage: String?,
        val primaryWindowId: Int,
        val structureKeys: Set<Long>,
        val contentTokens: Set<String>
    )

    private data class ContentEventSample(
        val packageName: String?,
        val windowId: Int,
        val eventType: Int,
        val contentChangeTypes: Int,
        val sourceBounds: Rect?,
        val receivedAtMs: Long
    )

    private data class PendingContentBatch(
        val packageName: String?,
        val windowId: Int,
        val eventTypes: Set<Int>,
        val contentChangeMask: Int,
        val changedBounds: Rect?,
        val eventCount: Int,
        val firstAtMs: Long,
        val lastAtMs: Long
    ) {
        fun merge(sample: ContentEventSample): PendingContentBatch {
            val mergedBounds = when {
                changedBounds == null -> sample.sourceBounds?.let { Rect(it) }
                sample.sourceBounds == null -> Rect(changedBounds)
                else -> Rect(changedBounds).apply { union(sample.sourceBounds) }
            }
            return copy(
                packageName = sample.packageName ?: packageName,
                windowId = if (sample.windowId >= 0) sample.windowId else windowId,
                eventTypes = eventTypes + sample.eventType,
                contentChangeMask = contentChangeMask or sample.contentChangeTypes,
                changedBounds = mergedBounds,
                eventCount = eventCount + 1,
                lastAtMs = sample.receivedAtMs
            )
        }

        fun hasStructuralHint(): Boolean {
            return contentChangeMask and STRUCTURAL_CHANGE_MASK != 0
        }

        fun hasUnknownOrSubtreeHint(): Boolean {
            return contentChangeMask == 0 ||
                contentChangeMask and CHANGE_SUBTREE != 0
        }

        fun isTextOnlyHint(): Boolean {
            if (AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED in eventTypes) return true
            if (contentChangeMask == 0) return false
            return contentChangeMask and TEXT_CHANGE_MASK != 0 &&
                contentChangeMask and STRUCTURAL_CHANGE_MASK == 0
        }

        fun changedAreaRatio(screenWidth: Int, screenHeight: Int): Float {
            val rect = changedBounds ?: return 1f
            val width = rect.width().coerceAtLeast(0)
            val height = rect.height().coerceAtLeast(0)
            val screenArea = (screenWidth.toLong() * screenHeight.toLong()).coerceAtLeast(1L)
            return ((width.toLong() * height.toLong()).toDouble() / screenArea.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

        fun summary(extra: String): String {
            return "events=$eventCount types=$eventTypes changeMask=$contentChangeMask " +
                "pkg=$packageName window=$windowId duration=${lastAtMs - firstAtMs}ms $extra"
        }

        companion object {
            fun from(sample: ContentEventSample): PendingContentBatch {
                return PendingContentBatch(
                    packageName = sample.packageName,
                    windowId = sample.windowId,
                    eventTypes = setOf(sample.eventType),
                    contentChangeMask = sample.contentChangeTypes,
                    changedBounds = sample.sourceBounds?.let { Rect(it) },
                    eventCount = 1,
                    firstAtMs = sample.receivedAtMs,
                    lastAtMs = sample.receivedAtMs
                )
            }
        }
    }

    private companion object {
        const val TAG = "TouchEngineInvalidation"
        const val EVENT_COALESCE_MS = 220L
        const val BASELINE_REFRESH_DELAY_MS = 80L
        const val SOFT_SCAN_MIN_INTERVAL_MS = 2_400L

        const val MAX_DEPTH = 8
        const val MAX_STRUCTURE_NODES = 180
        const val MAX_CONTENT_TOKENS = 96
        const val MAX_TOKEN_LENGTH = 40
        const val MIN_TOKEN_LENGTH = 2
        const val BOUNDS_QUANTUM_PX = 24

        const val STRUCTURE_DISTANCE_FULL = 0.14f
        const val STRUCTURE_DISTANCE_HINTED = 0.055f
        const val STRUCTURE_DISTANCE_STABLE = 0.04f
        const val STRUCTURE_NODE_DELTA_MIN = 5
        const val STRUCTURE_NODE_DELTA_RATIO = 0.12f
        const val CONTENT_DISTANCE_SOFT_SCAN = 0.30f
        const val SMALL_CHANGED_AREA_RATIO = 0.12f
        const val LARGE_CHANGED_AREA_RATIO = 0.28f

        const val CHANGE_SUBTREE = 1
        const val CHANGE_TEXT = 2
        const val CHANGE_CONTENT_DESCRIPTION = 4
        const val CHANGE_PANE_TITLE = 8
        const val CHANGE_PANE_APPEARED = 16
        const val CHANGE_PANE_DISAPPEARED = 32
        const val CHANGE_STATE_DESCRIPTION = 64

        const val STRUCTURAL_CHANGE_MASK = CHANGE_SUBTREE or
            CHANGE_PANE_APPEARED or CHANGE_PANE_DISAPPEARED
        const val TEXT_CHANGE_MASK = CHANGE_TEXT or CHANGE_CONTENT_DESCRIPTION or
            CHANGE_PANE_TITLE or CHANGE_STATE_DESCRIPTION

        val WHITESPACE_REGEX = Regex("\\s+")
        val NUMBER_REGEX = Regex("[0-9０-９]+(?:[.:：/\\-][0-9０-９]+)*")
        val REPEATED_MARK_REGEX = Regex("#{2,}")
    }
}

internal enum class PageInvalidationLevel {
    NONE,
    CONTENT_ONLY_SCAN,
    FULL_SCAN,
    DEFERRED_FULL_SCAN
}

internal data class PageInvalidationDecision(
    val level: PageInvalidationLevel,
    val eventSummary: String,
    val eventPackage: String?,
    val structuralDistance: Float,
    val contentDistance: Float,
    val changedAreaRatio: Float
)
