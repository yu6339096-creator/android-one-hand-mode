package com.example.touchengine

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * 悬浮球松手后的二次确认扫描差分策略。
 *
 * 目标：
 * 1. 不改现有旧节点清除规则；页面/窗口/滚动等强刷新仍由原逻辑处理。
 * 2. 只处理 Selection Resume 后的确认扫描结果。
 * 3. 如果确认扫描和当前稳定导航几乎一致，则不安装、不重建、不刷新 Overlay。
 * 4. 如果只有少量变化，则用旧视觉节点做基础，只局部 patch 变化目标。
 * 5. 如果变化很大，则交回原有完整安装流程。
 */
internal object SelectionConfirmDiffPolicy {

    sealed interface Decision {
        /** 没有有意义变化：保留当前 stable navigation，丢弃本次 pending install。 */
        data class NoMeaningfulChange(
            val matchedOldCount: Int,
            val matchedNewCount: Int,
            val oldCount: Int,
            val newCount: Int
        ) : Decision

        /** 小变化：保留旧视觉节点，只用新扫描结果 patch 命中的节点与新增节点。 */
        data class PatchChangedNodes(
            val patchedVisualNodes: List<NavNode>,
            val matchedOldCount: Int,
            val matchedNewCount: Int,
            val oldCount: Int,
            val newCount: Int
        ) : Decision

        /** 大变化：继续走原来的完整刷新/安装逻辑。 */
        data class MajorChange(
            val reason: String,
            val matchedOldCount: Int,
            val matchedNewCount: Int,
            val oldCount: Int,
            val newCount: Int
        ) : Decision
    }

    fun decide(
        currentStableNodes: List<NavNode>,
        confirmNodes: List<NavNode>
    ): Decision {
        val oldVisual = currentStableNodes.filter { it.origin == NavNodeOrigin.VISION }
        val newVisual = confirmNodes.filter { it.origin == NavNodeOrigin.VISION }

        if (newVisual.isEmpty()) {
            return Decision.NoMeaningfulChange(
                matchedOldCount = 0,
                matchedNewCount = 0,
                oldCount = oldVisual.size,
                newCount = 0
            )
        }
        if (oldVisual.isEmpty()) {
            return Decision.MajorChange(
                reason = "noOldVisualNodes",
                matchedOldCount = 0,
                matchedNewCount = 0,
                oldCount = 0,
                newCount = newVisual.size
            )
        }

        val matches = matchNodes(oldVisual, newVisual)
        val matchedOld = matches.map { it.oldIndex }.toSet()
        val matchedNew = matches.map { it.newIndex }.toSet()
        val oldCoverage = matchedOld.size.toFloat() / oldVisual.size.toFloat()
        val newCoverage = matchedNew.size.toFloat() / newVisual.size.toFloat()
        val countDelta = abs(oldVisual.size - newVisual.size)
        val noChangeCountDelta = max(2, (oldVisual.size * 0.10f).toInt())
        val smallChangeCountDelta = max(4, (oldVisual.size * 0.28f).toInt())

        val allMatchedNodesStable = matches.all { match ->
            isSameEnoughForNoUpdate(oldVisual[match.oldIndex], newVisual[match.newIndex])
        }

        if (oldCoverage >= 0.88f &&
            newCoverage >= 0.88f &&
            countDelta <= noChangeCountDelta &&
            allMatchedNodesStable
        ) {
            return Decision.NoMeaningfulChange(
                matchedOldCount = matchedOld.size,
                matchedNewCount = matchedNew.size,
                oldCount = oldVisual.size,
                newCount = newVisual.size
            )
        }

        if (oldCoverage >= 0.62f &&
            newCoverage >= 0.62f &&
            countDelta <= smallChangeCountDelta
        ) {
            return Decision.PatchChangedNodes(
                patchedVisualNodes = patchVisualNodes(oldVisual, newVisual, matches),
                matchedOldCount = matchedOld.size,
                matchedNewCount = matchedNew.size,
                oldCount = oldVisual.size,
                newCount = newVisual.size
            )
        }

        return Decision.MajorChange(
            reason = "coverageOrCountChanged oldCoverage=$oldCoverage newCoverage=$newCoverage countDelta=$countDelta",
            matchedOldCount = matchedOld.size,
            matchedNewCount = matchedNew.size,
            oldCount = oldVisual.size,
            newCount = newVisual.size
        )
    }

    private data class Match(
        val oldIndex: Int,
        val newIndex: Int,
        val score: Float
    )

    private fun matchNodes(oldNodes: List<NavNode>, newNodes: List<NavNode>): List<Match> {
        val candidates = mutableListOf<Match>()
        for (oldIndex in oldNodes.indices) {
            for (newIndex in newNodes.indices) {
                val score = matchScore(oldNodes[oldIndex], newNodes[newIndex])
                if (score >= 0.58f) {
                    candidates += Match(oldIndex, newIndex, score)
                }
            }
        }
        val usedOld = mutableSetOf<Int>()
        val usedNew = mutableSetOf<Int>()
        val result = mutableListOf<Match>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (candidate.oldIndex in usedOld || candidate.newIndex in usedNew) continue
            usedOld += candidate.oldIndex
            usedNew += candidate.newIndex
            result += candidate
        }
        return result
    }

    private fun patchVisualNodes(
        oldNodes: List<NavNode>,
        newNodes: List<NavNode>,
        matches: List<Match>
    ): List<NavNode> {
        val byOld = matches.associateBy { it.oldIndex }
        val matchedNew = matches.map { it.newIndex }.toSet()
        val patched = mutableListOf<NavNode>()

        for (oldIndex in oldNodes.indices) {
            val match = byOld[oldIndex]
            patched += if (match != null) {
                mergeVisualNode(oldNodes[oldIndex], newNodes[match.newIndex])
            } else {
                oldNodes[oldIndex]
            }
        }

        for (newIndex in newNodes.indices) {
            if (newIndex !in matchedNew) patched += newNodes[newIndex]
        }

        return patched.distinctBy { node ->
            val r = node.bounds
            "${node.windowId}:${r.left / 6}:${r.top / 6}:${r.right / 6}:${r.bottom / 6}:${node.description.orEmpty()}"
        }
    }

    private fun mergeVisualNode(oldNode: NavNode, newNode: NavNode): NavNode {
        val mergedSources = oldNode.sourceSet + newNode.sourceSet
        val clickMethod = chooseClickMethod(oldNode, newNode)
        if (isSameEnoughForNoUpdate(oldNode, newNode)) {
            return oldNode.copy(
                sourceSet = mergedSources,
                clickMethod = clickMethod,
                visualEvidence = mergedVisualEvidence(oldNode, newNode, mergedSources, clickMethod)
            )
        }
        return newNode.copy(
            sourceSet = mergedSources,
            clickMethod = clickMethod,
            visualEvidence = mergedVisualEvidence(newNode, oldNode, mergedSources, clickMethod)
        )
    }

    private fun mergedVisualEvidence(
        preferred: NavNode,
        fallback: NavNode,
        sources: Set<TargetSource>,
        clickMethod: ClickMethod
    ): VisualTargetEvidence? {
        return (preferred.visualEvidence ?: fallback.visualEvidence)?.copy(
            sources = sources,
            clickMethod = clickMethod
        )
    }

    private fun chooseClickMethod(oldNode: NavNode, newNode: NavNode): ClickMethod {
        return when {
            oldNode.clickMethod == ClickMethod.ACCESSIBILITY_ACTION -> oldNode.clickMethod
            newNode.clickMethod == ClickMethod.ACCESSIBILITY_ACTION -> newNode.clickMethod
            oldNode.clickMethod == ClickMethod.GESTURE_SAFE_LEFT -> oldNode.clickMethod
            newNode.clickMethod == ClickMethod.GESTURE_SAFE_LEFT -> newNode.clickMethod
            else -> newNode.clickMethod
        }
    }

    private fun isSameEnoughForNoUpdate(oldNode: NavNode, newNode: NavNode): Boolean {
        if (oldNode.windowId != newNode.windowId) return false
        val oldText = normalizedDescription(oldNode)
        val newText = normalizedDescription(newNode)
        val sameText = oldText.isBlank() || newText.isBlank() || oldText == newText
        return sameText &&
            iou(oldNode.bounds, newNode.bounds) >= 0.72f &&
            edgeDelta(oldNode.bounds, newNode.bounds) <= 10
    }

    private fun matchScore(oldNode: NavNode, newNode: NavNode): Float {
        if (oldNode.windowId != newNode.windowId) return 0f
        val iouScore = iou(oldNode.bounds, newNode.bounds)
        val centerScore = centerCloseness(oldNode.bounds, newNode.bounds)
        val textBonus = if (normalizedDescription(oldNode) == normalizedDescription(newNode)) 0.08f else 0f
        return (iouScore * 0.78f + centerScore * 0.22f + textBonus).coerceIn(0f, 1f)
    }

    private fun normalizedDescription(node: NavNode): String {
        return node.description.orEmpty()
            .substringAfter(":", node.description.orEmpty())
            .replace("\\s+".toRegex(), "")
            .take(24)
    }

    private fun centerCloseness(first: Rect, second: Rect): Float {
        val dx = abs(first.exactCenterX() - second.exactCenterX())
        val dy = abs(first.exactCenterY() - second.exactCenterY())
        val tolerance = max(
            max(first.width(), second.width()),
            max(first.height(), second.height())
        ).coerceAtLeast(1).toFloat()
        return (1f - ((dx + dy) / (tolerance * 1.6f))).coerceIn(0f, 1f)
    }

    private fun edgeDelta(first: Rect, second: Rect): Int {
        return max(
            max(abs(first.left - second.left), abs(first.top - second.top)),
            max(abs(first.right - second.right), abs(first.bottom - second.bottom))
        )
    }

    private fun iou(first: Rect, second: Rect): Float {
        val intersection = Rect(first)
        if (!intersection.intersect(second)) return 0f
        val intersectionArea = intersection.width().toFloat() * intersection.height().toFloat()
        val unionArea = first.width().toFloat() * first.height().toFloat() +
            second.width().toFloat() * second.height().toFloat() - intersectionArea
        return if (unionArea <= 0f) 0f else intersectionArea / unionArea
    }
}
