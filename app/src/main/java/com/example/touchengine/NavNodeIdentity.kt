package com.example.touchengine

import android.graphics.Rect

/**
 * 为完整建图与增量建图补齐稳定身份，并提供不包含 neighbors 的节点内容比较。
 *
 * 视觉节点优先沿用 PersistentVisualCanvas 分配的 stableId。Accessibility 节点没有
 * 上游 stableId 时，使用当前语义与几何生成可重复身份；完整页面重建仍可重新分配，
 * 但同一 StableMesh 内的视觉增量更新可以持续复用这些身份。
 */
internal object NavNodeIdentity {

    fun ensureStableIds(nodes: List<NavNode>): List<NavNode> {
        if (nodes.isEmpty()) return emptyList()
        val used = linkedSetOf<String>()
        val duplicateCounters = mutableMapOf<String, Int>()
        return nodes.map { node ->
            val requested = node.stableId?.takeIf { it.isNotBlank() } ?: deterministicBaseId(node)
            var resolved = requested
            if (!used.add(resolved)) {
                var suffix = (duplicateCounters[requested] ?: 1)
                do {
                    suffix += 1
                    resolved = "$requested#$suffix"
                } while (!used.add(resolved))
                duplicateCounters[requested] = suffix
            }
            if (node.stableId == resolved) node else node.copy(stableId = resolved)
        }
    }

    fun samePayload(first: NavNode, second: NavNode): Boolean {
        return first.windowId == second.windowId &&
            first.bounds == second.bounds &&
            first.description == second.description &&
            first.origin == second.origin &&
            first.clickMethod == second.clickMethod &&
            first.sourceSet == second.sourceSet &&
            first.visualEvidence == second.visualEvidence &&
            first.interactionBounds == second.interactionBounds
    }

    /** Metadata may converge in Canvas without publishing a new navigation snapshot. */
    fun sameNavigation(first: NavNode, second: NavNode): Boolean =
        sameGeometry(first, second) && first.clickMethod == second.clickMethod &&
            (first.interactionBounds ?: first.bounds) == (second.interactionBounds ?: second.bounds)

    fun sameGeometry(first: NavNode, second: NavNode): Boolean {
        return first.windowId == second.windowId && first.bounds == second.bounds
    }

    fun cloneWithoutNeighbors(node: NavNode): NavNode {
        return NavNode(
            bounds = Rect(node.bounds),
            description = node.description,
            windowId = node.windowId,
            centerX = node.bounds.exactCenterX(),
            centerY = node.bounds.exactCenterY(),
            origin = node.origin,
            clickMethod = node.clickMethod,
            sourceSet = node.sourceSet,
            visualEvidence = node.visualEvidence,
            stableId = node.stableId,
            interactionBounds = node.interactionBounds?.let(::Rect)
        )
    }

    private fun deterministicBaseId(node: NavNode): String {
        val rect = node.bounds
        val normalizedText = node.description.orEmpty()
            .replace("\\s+".toRegex(), "")
            .take(32)
        val sourceKey = node.sourceSet.map { it.name }.sorted().joinToString(",")
        val raw = buildString {
            append(node.origin.name)
            append('|').append(node.windowId)
            append('|').append(rect.left / 4)
            append('|').append(rect.top / 4)
            append('|').append(rect.right / 4)
            append('|').append(rect.bottom / 4)
            append('|').append(node.visualEvidence?.role?.name.orEmpty())
            append('|').append(sourceKey)
            append('|').append(normalizedText)
        }
        return "mesh:${raw.hashCode().toUInt().toString(16)}"
    }
}
