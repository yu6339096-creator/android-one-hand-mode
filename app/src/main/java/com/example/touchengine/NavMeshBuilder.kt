package com.example.touchengine

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Builds and incrementally patches a directional navigation graph.
 *
 * Edges are always bidirectional. Nodes from different windowId values are never
 * connected. Disconnected components inside one window are bridged using the
 * nearest pair so every committed node is reachable.
 */
object NavMeshBuilder {

    internal data class IncrementalBuildResult(
        val nodes: List<NavNode>,
        val incremental: Boolean,
        val affectedNodeCount: Int,
        val addedNodeCount: Int,
        val updatedNodeCount: Int,
        val removedNodeCount: Int
    )

    fun build(nodes: List<NavNode>, maxConnectDist: Float = 600f) {
        nodes.forEach { it.neighbors.clear() }
        nodes.groupBy { it.windowId }.values.forEach { windowNodes ->
            rebuildNodes(windowNodes, windowNodes.toSet(), maxConnectDist)
            bridgeDisconnectedComponents(windowNodes)
        }
    }

    /**
     * 在不重新执行整张图 O(n²) 建图的前提下应用节点变化。
     *
     * 算法先复制旧边，再只清理和重算变化节点附近的方向连接；若变化覆盖范围过大、
     * stableId 匹配率过低或窗口结构明显变化，则自动回退到完整 build，保证正确性。
     */
    internal fun applyIncremental(
        existingNodes: List<NavNode>,
        desiredNodes: List<NavNode>,
        maxConnectDist: Float = 600f
    ): IncrementalBuildResult {
        val existing = NavNodeIdentity.ensureStableIds(existingNodes)
        val desired = NavNodeIdentity.ensureStableIds(desiredNodes)
        if (existing.isEmpty() || desired.isEmpty()) {
            val rebuilt = desired.map(NavNodeIdentity::cloneWithoutNeighbors)
            if (rebuilt.isNotEmpty()) build(rebuilt, maxConnectDist)
            return IncrementalBuildResult(
                nodes = rebuilt,
                incremental = false,
                affectedNodeCount = rebuilt.size,
                addedNodeCount = desired.size,
                updatedNodeCount = 0,
                removedNodeCount = existing.size
            )
        }

        val oldById = existing.associateBy { it.stableId!! }
        val desiredById = desired.associateBy { it.stableId!! }
        val matchedIds = oldById.keys intersect desiredById.keys
        val addedIds = desiredById.keys - oldById.keys
        val removedIds = oldById.keys - desiredById.keys
        val updatedIds = matchedIds.filterTo(linkedSetOf()) { id ->
            !NavNodeIdentity.samePayload(oldById.getValue(id), desiredById.getValue(id))
        }
        val geometryChangedIds = updatedIds.filterTo(linkedSetOf()) { id ->
            !NavNodeIdentity.sameGeometry(oldById.getValue(id), desiredById.getValue(id))
        }

        val matchRatio = matchedIds.size.toFloat() / max(existing.size, desired.size).coerceAtLeast(1)
        val windowChanged = existing.map { it.windowId }.toSet() != desired.map { it.windowId }.toSet()
        if (matchRatio < MIN_STABLE_MATCH_RATIO || windowChanged) {
            return fullFallback(
                desired = desired,
                maxConnectDist = maxConnectDist,
                addedCount = addedIds.size,
                updatedCount = updatedIds.size,
                removedCount = removedIds.size
            )
        }

        val clonedById = desired.associate { node ->
            node.stableId!! to NavNodeIdentity.cloneWithoutNeighbors(node)
        }

        // 先把旧图中仍有效的边映射到新节点副本。这样未受影响区域无需重新计算。
        existing.forEach oldNodeLoop@ { oldNode ->
            val newNode = oldNode.stableId?.let(clonedById::get) ?: return@oldNodeLoop
            oldNode.neighbors.forEach neighborLoop@ { oldNeighbor ->
                val newNeighbor = oldNeighbor.stableId?.let(clonedById::get) ?: return@neighborLoop
                connectBidirectional(newNode, newNeighbor)
            }
        }

        val affectedIds = linkedSetOf<String>()
        affectedIds += addedIds
        affectedIds += geometryChangedIds

        // 被删除或移动节点的旧邻居必须重新选择方向目标。
        (removedIds + geometryChangedIds).forEach { id ->
            oldById[id]?.neighbors?.mapNotNullTo(affectedIds) { it.stableId }
        }

        // 任何位于变化节点有效连接半径内的节点都可能改变某个方向的最近邻。
        val changedGeometryNodes = buildList {
            addedIds.mapNotNullTo(this) { desiredById[it] }
            geometryChangedIds.mapNotNullTo(this) { desiredById[it] }
            geometryChangedIds.mapNotNullTo(this) { oldById[it] }
            removedIds.mapNotNullTo(this) { oldById[it] }
        }
        if (changedGeometryNodes.isNotEmpty()) {
            val largestChangedExtent = changedGeometryNodes.maxOf { node ->
                max(node.bounds.width(), node.bounds.height()).toFloat()
            }
            // 白纸更新只重算变化附近。远处节点即使理论上位于 maxConnectDist 内，
            // 其既有方向边仍可继续使用；新节点自身会连接进图，连通性修复负责兜底。
            val localImpactRadius = minOf(
                maxConnectDist,
                max(MIN_LOCAL_IMPACT_RADIUS_PX, largestChangedExtent * 2.5f)
            )
            desired.forEach { candidate ->
                if (changedGeometryNodes.any { changed ->
                        candidate.windowId == changed.windowId &&
                            centerDistance(candidate, changed) <= localImpactRadius
                    }
                ) {
                    candidate.stableId?.let(affectedIds::add)
                }
            }
        }

        val affectedDesiredIds = affectedIds intersect desiredById.keys
        val fallbackThreshold = max(MIN_LOCAL_PATCH_NODE_COUNT, (desired.size * MAX_LOCAL_PATCH_RATIO).toInt())
        if (affectedDesiredIds.size > fallbackThreshold) {
            return fullFallback(
                desired = desired,
                maxConnectDist = maxConnectDist,
                addedCount = addedIds.size,
                updatedCount = updatedIds.size,
                removedCount = removedIds.size
            )
        }

        val clonedNodes = desired.map { clonedById.getValue(it.stableId!!) }
        if (affectedDesiredIds.isNotEmpty()) {
            // 删除所有指向受影响节点的旧边，再局部重算，避免旧最近邻残留。
            clonedNodes.forEach { node ->
                node.neighbors.removeAll { neighbor -> neighbor.stableId in affectedDesiredIds }
            }
            affectedDesiredIds.forEach { id -> clonedById[id]?.neighbors?.clear() }

            clonedNodes.groupBy { it.windowId }.forEach { (_, windowNodes) ->
                val affectedInWindow = windowNodes.filter { it.stableId in affectedDesiredIds }.toSet()
                if (affectedInWindow.isNotEmpty()) {
                    rebuildNodes(windowNodes, affectedInWindow, maxConnectDist)
                }
                bridgeDisconnectedComponents(windowNodes)
            }
        }

        return IncrementalBuildResult(
            nodes = clonedNodes,
            incremental = true,
            affectedNodeCount = affectedDesiredIds.size,
            addedNodeCount = addedIds.size,
            updatedNodeCount = updatedIds.size,
            removedNodeCount = removedIds.size
        )
    }

    private fun fullFallback(
        desired: List<NavNode>,
        maxConnectDist: Float,
        addedCount: Int,
        updatedCount: Int,
        removedCount: Int
    ): IncrementalBuildResult {
        val rebuilt = desired.map(NavNodeIdentity::cloneWithoutNeighbors)
        build(rebuilt, maxConnectDist)
        return IncrementalBuildResult(
            nodes = rebuilt,
            incremental = false,
            affectedNodeCount = rebuilt.size,
            addedNodeCount = addedCount,
            updatedNodeCount = updatedCount,
            removedNodeCount = removedCount
        )
    }

    private fun rebuildNodes(
        windowNodes: List<NavNode>,
        nodesToRebuild: Set<NavNode>,
        maxConnectDist: Float
    ) {
        nodesToRebuild.forEach { first ->
            val sectorBest = Array<Pair<Float, NavNode>?>(8) { null }
            windowNodes.forEach candidateLoop@ { second ->
                if (first === second) return@candidateLoop
                val dx = second.centerX - first.centerX
                val dy = second.centerY - first.centerY
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (distance >= maxConnectDist) return@candidateLoop
                val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                val sector = (((angle + 180.0 + 22.5) / 45.0).toInt()) % 8
                val current = sectorBest[sector]
                if (current == null || distance < current.first) {
                    sectorBest[sector] = distance to second
                }
            }
            sectorBest.forEach { nearest ->
                nearest?.second?.let { connectBidirectional(first, it) }
            }
        }
    }

    private fun connectBidirectional(first: NavNode, second: NavNode) {
        if (first === second) return
        if (second !in first.neighbors) first.neighbors += second
        if (first !in second.neighbors) second.neighbors += first
    }

    private fun bridgeDisconnectedComponents(nodes: List<NavNode>) {
        while (true) {
            val components = connectedComponents(nodes)
            if (components.size <= 1) return
            var bridge: Pair<NavNode, NavNode>? = null
            var bridgeDistance = Float.MAX_VALUE
            for (firstIndex in 0 until components.lastIndex) {
                for (secondIndex in firstIndex + 1 until components.size) {
                    components[firstIndex].forEach { first ->
                        components[secondIndex].forEach { second ->
                            val distance = centerDistance(first, second)
                            if (distance < bridgeDistance) {
                                bridgeDistance = distance
                                bridge = first to second
                            }
                        }
                    }
                }
            }
            val nearest = bridge ?: return
            connectBidirectional(nearest.first, nearest.second)
        }
    }

    private fun centerDistance(first: NavNode, second: NavNode): Float {
        return hypot(
            (second.centerX - first.centerX).toDouble(),
            (second.centerY - first.centerY).toDouble()
        ).toFloat()
    }

    internal fun connectedComponents(nodes: List<NavNode>): List<List<NavNode>> {
        val remaining = nodes.toMutableSet()
        val components = mutableListOf<List<NavNode>>()
        while (remaining.isNotEmpty()) {
            val start = remaining.first()
            val queue = ArrayDeque<NavNode>()
            val component = mutableListOf<NavNode>()
            queue.add(start)
            remaining.remove(start)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component += current
                current.neighbors.forEach { neighbor ->
                    if (neighbor.windowId == start.windowId && remaining.remove(neighbor)) {
                        queue.add(neighbor)
                    }
                }
            }
            components += component
        }
        return components
    }

    internal fun reachableNodes(nodes: List<NavNode>): List<NavNode> {
        val reachable = linkedSetOf<NavNode>()
        nodes.groupBy { it.windowId }.values.forEach { windowNodes ->
            val start = windowNodes.firstOrNull() ?: return@forEach
            val allowed = windowNodes.toSet()
            val queue = ArrayDeque<NavNode>()
            queue.add(start)
            reachable.add(start)
            while (queue.isNotEmpty()) {
                queue.removeFirst().neighbors.forEach { neighbor ->
                    if (neighbor in allowed && reachable.add(neighbor)) queue.add(neighbor)
                }
            }
        }
        return reachable.toList()
    }

    private const val MIN_STABLE_MATCH_RATIO = 0.45f
    private const val MAX_LOCAL_PATCH_RATIO = 0.72f
    private const val MIN_LOCAL_PATCH_NODE_COUNT = 24
    private const val MIN_LOCAL_IMPACT_RADIUS_PX = 320f
}
