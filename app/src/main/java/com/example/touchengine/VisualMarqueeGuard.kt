package com.example.touchengine

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * 视觉跑马灯门卫。
 *
 * 观察 YOLO-only / OCR-only 且 directEligible 的视觉节点。它不截图、不调度模型，
 * 仅根据已经成功提交的视觉 Mesh 记录同一 surface 内的局部重建风暴；
 * 因此正常视觉目标不会增加首帧延迟。
 */
internal class VisualMarqueeGuard {

    data class Observation(
        internal val surface: SurfaceIdentity,
        internal val regions: List<Rect>
    )

    data class Decision(
        val nodes: List<NavNode>,
        val observation: Observation?,
        /** 当前扫描过滤了移动的跑马灯节点，需允许一次受控安装以移除旧节点。 */
        val requiresInstall: Boolean,
        val status: String
    )

    internal data class SurfaceIdentity(
        val pageEpoch: Long,
        val viewportRevision: Long,
        val primaryWindowId: Int,
        val surfaceKey: VisualSurfaceKey
    )

    private data class TrackedRegion(
        var bounds: Rect,
        val commitTimesMs: ArrayDeque<Long> = ArrayDeque(),
        var quarantined: Boolean = false
    )

    private var activeSurface: SurfaceIdentity? = null
    private val regions = mutableListOf<TrackedRegion>()

    fun evaluate(
        token: VisualScanToken,
        surfaceKey: VisualSurfaceKey,
        visualNodes: List<NavNode>,
        committedNodes: List<NavNode>,
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): Decision {
        val surface = SurfaceIdentity(
            pageEpoch = token.pageEpoch,
            viewportRevision = token.viewportRevision,
            primaryWindowId = token.primaryWindowId,
            surfaceKey = surfaceKey
        )
        if (activeSurface != surface) {
            reset("surfaceChanged")
            activeSurface = surface
        }
        prune(nowMs)

        val candidates = visualNodes.filter(::isTrackedDirectVisual)
        if (candidates.isEmpty()) {
            return Decision(visualNodes, observation = null, requiresInstall = false, status = "PASS noTrackedDirect")
        }

        val oldDirectNodes = committedNodes.filter(::isTrackedDirectVisual)
        // 只有本轮相对已提交视觉节点发生几何变化的 YOLO 槽位才可计入重建风暴。
        // 否则 OCR 或其它视觉节点的更新会错误地给不变的 YOLO 节点累计次数。
        val changedCandidates = candidates.filter { current ->
            oldDirectNodes.none { old -> isEquivalentObservation(current, old) }
        }

        val candidateRegions = clusterRegions(
            candidates = candidates,
            paddingPx = (24f * density).toInt().coerceAtLeast(1),
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        if (candidateRegions.isEmpty()) {
            return Decision(visualNodes, observation = null, requiresInstall = false, status = "PASS regionTooLarge")
        }
        val changedRegions = clusterRegions(
            candidates = changedCandidates,
            paddingPx = (24f * density).toInt().coerceAtLeast(1),
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )

        val quarantined = candidateRegions.filter { candidate ->
            regions.any { tracked -> tracked.quarantined && regionsMatch(tracked.bounds, candidate) }
        }
        if (quarantined.isEmpty()) {
            return Decision(
                nodes = visualNodes,
                observation = changedRegions.takeIf { it.isNotEmpty() }?.let {
                    Observation(surface, it.map(::Rect))
                },
                requiresInstall = false,
                status = "OBSERVE regions=${changedRegions.size}"
            )
        }

        var output = visualNodes
        var requiresInstall = false
        var retainedSlots = 0
        var removedSlots = 0
        quarantined.forEach { region ->
            val currentInRegion = output.filter { isTrackedDirectVisual(it) && intersects(region, it.bounds) }
            val oldInRegion = oldDirectNodes.filter { intersects(region, it.bounds) }
            if (hasStableSlotObservations(currentInRegion, oldInRegion)) {
                output = output.filterNot { isTrackedDirectVisual(it) && intersects(region, it.bounds) } + oldInRegion
                retainedSlots += oldInRegion.size
            } else {
                val hadCurrent = output.any { isTrackedDirectVisual(it) && intersects(region, it.bounds) }
                val hadOld = oldInRegion.isNotEmpty()
                output = output.filterNot { isTrackedDirectVisual(it) && intersects(region, it.bounds) }
                requiresInstall = requiresInstall || hadCurrent || hadOld
                removedSlots += oldInRegion.size.coerceAtLeast(if (hadCurrent) 1 else 0)
            }
        }

        val deduplicated = output.distinctBy { node ->
            val r = node.bounds
            "${node.windowId}:${r.left / 6}:${r.top / 6}:${r.right / 6}:${r.bottom / 6}:${node.sourceSet}"
        }
        Log.d(
            TAG,
            "quarantineApply regions=$quarantined retained=$retainedSlots removed=$removedSlots " +
                "requiresInstall=$requiresInstall surface=$surfaceKey"
        )
        val otherChangedRegions = changedRegions.filterNot { changed ->
            quarantined.any { quarantinedRegion -> regionsMatch(quarantinedRegion, changed) }
        }
        return Decision(
            nodes = deduplicated,
            observation = otherChangedRegions.takeIf { it.isNotEmpty() }?.let {
                Observation(surface, it.map(::Rect))
            },
            requiresInstall = requiresInstall,
            status = "QUARANTINE regions=${quarantined.size} retain=$retainedSlots remove=$removedSlots"
        )
    }

    /** 只接受视觉安装后的成功 Mesh 提交；被丢弃或签名未变的安装不会进入这里。 */
    fun recordBuildOutcome(observation: Observation?, committed: Boolean, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (!committed || observation == null || activeSurface != observation.surface) return
        prune(nowMs)
        observation.regions.forEach { observed ->
            val tracked = regions.firstOrNull { regionsMatch(it.bounds, observed) }
                ?: TrackedRegion(bounds = Rect(observed)).also(regions::add)
            tracked.bounds = Rect(observed)
            tracked.commitTimesMs.addLast(nowMs)
            prune(tracked, nowMs)
            if (!tracked.quarantined && tracked.commitTimesMs.size >= COMMIT_THRESHOLD) {
                tracked.quarantined = true
                Log.d(
                    TAG,
                    "quarantine commits=${tracked.commitTimesMs.size} region=${tracked.bounds} surface=${observation.surface.surfaceKey}"
                )
            } else {
                Log.d(
                    TAG,
                    "observe commits=${tracked.commitTimesMs.size} region=${tracked.bounds} surface=${observation.surface.surfaceKey}"
                )
            }
        }
    }

    fun reset(reason: String) {
        if (activeSurface != null || regions.isNotEmpty()) {
            Log.d(TAG, "reset reason=$reason regions=${regions.size} surface=$activeSurface")
        }
        activeSurface = null
        regions.clear()
    }

    private fun clusterRegions(
        candidates: List<NavNode>,
        paddingPx: Int,
        screenWidth: Int,
        screenHeight: Int
    ): List<Rect> {
        val clusters = mutableListOf<Rect>()
        candidates.forEach { node ->
            val expanded = Rect(node.bounds).apply {
                inset(-paddingPx, -paddingPx)
                intersect(0, 0, screenWidth, screenHeight)
            }
            val existing = clusters.firstOrNull { intersects(it, expanded) }
            if (existing == null) {
                clusters += expanded
            } else {
                existing.union(expanded)
            }
        }
        return clusters.filter { region ->
            val area = region.width().toLong().coerceAtLeast(0L) * region.height().toLong().coerceAtLeast(0L)
            val screenArea = screenWidth.toLong().coerceAtLeast(1L) * screenHeight.toLong().coerceAtLeast(1L)
            area.toFloat() / screenArea.toFloat() <= MAX_REGION_AREA_RATIO
        }
    }

    private fun hasStableSlotObservations(current: List<NavNode>, old: List<NavNode>): Boolean {
        if (current.isEmpty() || old.isEmpty() || current.size != old.size) return false
        val unmatched = old.toMutableList()
        return current.all { currentNode ->
            val match = unmatched.maxByOrNull { oldNode -> geometryScore(currentNode.bounds, oldNode.bounds) }
                ?: return@all false
            if (!isEquivalentObservation(currentNode, match)) return@all false
            unmatched.remove(match)
            true
        }
    }

    private fun geometryScore(first: Rect, second: Rect): Float {
        return iou(first, second) - edgeDelta(first, second) / 10_000f
    }

    private fun isTrackedDirectVisual(node: NavNode): Boolean {
        val evidence = node.visualEvidence ?: return false
        return evidence.directEligible &&
            (evidence.sources == setOf(TargetSource.YOLO) || evidence.sources == setOf(TargetSource.OCR))
    }

    private fun isEquivalentObservation(first: NavNode, second: NavNode): Boolean {
        if (!isEquivalentGeometry(first.bounds, second.bounds)) return false
        val firstSources = first.visualEvidence?.sources.orEmpty()
        val secondSources = second.visualEvidence?.sources.orEmpty()
        if (firstSources != secondSources) return false
        if (firstSources == setOf(TargetSource.OCR)) {
            return normalizedVisualText(first.visualEvidence?.ocrText ?: first.description.orEmpty()) ==
                normalizedVisualText(second.visualEvidence?.ocrText ?: second.description.orEmpty())
        }
        return true
    }

    private fun regionsMatch(first: Rect, second: Rect): Boolean {
        return iou(first, second) >= REGION_MATCH_IOU ||
            (abs(first.centerX() - second.centerX()) <= REGION_CENTER_TOLERANCE_PX &&
                abs(first.centerY() - second.centerY()) <= REGION_CENTER_TOLERANCE_PX)
    }

    private fun intersects(first: Rect, second: Rect): Boolean = Rect(first).intersect(second)

    private fun iou(first: Rect, second: Rect): Float {
        val intersection = Rect(first)
        if (!intersection.intersect(second)) return 0f
        val intersectionArea = intersection.width().toFloat() * intersection.height().toFloat()
        val unionArea = first.width().toFloat() * first.height().toFloat() +
            second.width().toFloat() * second.height().toFloat() - intersectionArea
        return if (unionArea <= 0f) 0f else intersectionArea / unionArea
    }

    private fun edgeDelta(first: Rect, second: Rect): Int {
        return max(
            max(abs(first.left - second.left), abs(first.top - second.top)),
            max(abs(first.right - second.right), abs(first.bottom - second.bottom))
        )
    }

    private fun isEquivalentGeometry(first: Rect, second: Rect): Boolean {
        return iou(first, second) >= STABLE_SLOT_IOU && edgeDelta(first, second) <= STABLE_SLOT_EDGE_DELTA_PX
    }

    private fun prune(nowMs: Long) {
        regions.forEach { prune(it, nowMs) }
        regions.removeAll { !it.quarantined && it.commitTimesMs.isEmpty() }
    }

    private fun prune(region: TrackedRegion, nowMs: Long) {
        while (region.commitTimesMs.isNotEmpty() && nowMs - region.commitTimesMs.first() > COMMIT_WINDOW_MS) {
            region.commitTimesMs.removeFirst()
        }
    }

    private companion object {
        const val TAG = "TouchEngineMarquee"
        const val COMMIT_THRESHOLD = 3
        const val COMMIT_WINDOW_MS = 5_000L
        const val MAX_REGION_AREA_RATIO = 0.25f
        const val STABLE_SLOT_IOU = 0.72f
        const val STABLE_SLOT_EDGE_DELTA_PX = 10
        const val REGION_MATCH_IOU = 0.35f
        const val REGION_CENTER_TOLERANCE_PX = 96
    }
}
