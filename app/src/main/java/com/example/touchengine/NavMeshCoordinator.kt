package com.example.touchengine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理 NavMesh 的构建、版本、Stable/Overlay/Active 三类快照与手势冻结。
 *
 * 只接收普通数据，不读取 AccessibilityService、AccessibilityNodeInfo 或窗口对象。
 * 后台建图始终使用 NavNode 深复制，避免被丢弃的构建修改正在使用的 neighbors。
 */
internal class NavMeshCoordinator(
    private val scope: CoroutineScope,
    private val maxConnectDistance: Float,
    private val selectionActiveProvider: () -> Boolean
) {
    private var currentNodesState: List<NavNode> = emptyList()
    private var stableMeshState: NavMeshSnapshot? = null
    private var overlayMeshState: NavMeshSnapshot? = null
    private var activeMeshState: NavMeshSnapshot? = null
    private var overlayExpireAtMsState: Long = 0L
    private var generationState: Int = 0
    private var discardedResultsState: Int = 0

    val currentNodes: List<NavNode>
        get() = currentNodesState

    val stableSnapshot: NavMeshSnapshot?
        get() = stableMeshState

    val overlaySnapshot: NavMeshSnapshot?
        get() = overlayMeshState

    val activeSnapshot: NavMeshSnapshot?
        get() = activeMeshState

    val activeNodes: List<NavNode>
        get() = activeMeshState?.nodes.orEmpty()

    val stableWindowIds: Set<Int>
        get() = stableMeshState?.windowIds.orEmpty()

    val activeWindowIds: Set<Int>
        get() = activeMeshState?.windowIds ?: stableWindowIds

    val hasCurrentNodes: Boolean
        get() = currentNodesState.isNotEmpty()

    val generation: Int
        get() = generationState

    val discardedResults: Int
        get() = discardedResultsState

    fun invalidatePendingBuilds(): Int {
        generationState += 1
        return generationState
    }

    fun clearAll() {
        // 先废弃所有后台结果，防止 clear 后旧页面 Mesh 再次提交。
        invalidatePendingBuilds()
        currentNodesState = emptyList()
        stableMeshState = null
        overlayMeshState = null
        activeMeshState = null
        overlayExpireAtMsState = 0L
    }

    fun clearOverlay() {
        val removed = overlayMeshState
        overlayMeshState = null
        overlayExpireAtMsState = 0L
        if (removed != null && activeMeshState === removed) {
            activeMeshState = null
        }
    }

    fun stableForPackage(targetPackage: String?): NavMeshSnapshot? {
        return stableMeshState?.takeIf { it.targetPackage == targetPackage }
    }

    fun beginGesture(targetPackage: String?, nowMs: Long): GestureMeshSession {
        val version = invalidatePendingBuilds()
        val usableOverlay = overlayMeshState?.takeIf {
            nowMs < overlayExpireAtMsState &&
                (it.targetPackage == null || it.targetPackage == targetPackage)
        }
        val selected = usableOverlay
            ?: stableMeshState?.takeIf { it.targetPackage == targetPackage }
            ?: currentNodesState.takeIf { it.isNotEmpty() }?.let { nodes ->
                NavMeshSnapshot(
                    nodes = nodes,
                    targetPackage = targetPackage,
                    windowIds = nodes.map { it.windowId }.toSet(),
                    version = version,
                    createdAtMs = nowMs,
                    signature = EdgeInteractionRules.meshSignature(nodes),
                    source = MeshSource.STABLE
                )
            }
        activeMeshState = selected
        return GestureMeshSession(
            snapshot = selected,
            nodes = selected?.nodes.orEmpty(),
            source = selected?.source
        )
    }

    fun endGesture() {
        activeMeshState = null
    }

    fun activateOverlay(
        nodes: List<NavNode>,
        targetPackage: String?,
        windowId: Int,
        nowMs: Long
    ): OverlayMeshActivation {
        if (nodes.isEmpty()) return OverlayMeshActivation(activated = false)
        val builtNodes = cloneNodes(nodes)
        NavMeshBuilder.build(builtNodes, maxConnectDist = maxConnectDistance)
        val snapshot = NavMeshSnapshot(
            nodes = builtNodes,
            targetPackage = targetPackage,
            windowIds = setOf(windowId),
            version = invalidatePendingBuilds(),
            createdAtMs = nowMs,
            signature = EdgeInteractionRules.meshSignature(builtNodes),
            source = MeshSource.OVERLAY
        )
        overlayMeshState = snapshot
        overlayExpireAtMsState = nowMs + 5_000L
        activeMeshState = snapshot
        return OverlayMeshActivation(
            activated = true,
            snapshot = snapshot,
            initialFocus = builtNodes.firstOrNull()
        )
    }

    fun submitStableBuild(
        request: NavMeshBuildRequest,
        commitGuard: () -> Boolean = { true },
        onResult: (NavMeshBuildResult) -> Unit
    ) {
        scope.launch(Dispatchers.Default) {
            // 绝不直接修改 request.nodes。旧快照和正在进行的手势可能仍在使用这些对象。
            val builtNodes = cloneNodes(request.nodes)
            val buildStartedAt = System.nanoTime()
            NavMeshBuilder.build(
                builtNodes,
                maxConnectDist = maxConnectDistance
            )
            val buildMs = (System.nanoTime() - buildStartedAt) / 1_000_000L
            withContext(Dispatchers.Main.immediate) {
                val discardReason = when {
                    selectionActiveProvider() ->
                        NavMeshDiscardReason.SELECTION_ACTIVE
                    request.generation != generationState ->
                        NavMeshDiscardReason.STALE_GENERATION
                    !commitGuard() ->
                        NavMeshDiscardReason.COMMIT_GUARD_REJECTED
                    else -> null
                }
                if (discardReason != null) {
                    if (discardReason != NavMeshDiscardReason.COMMIT_GUARD_REJECTED) {
                        discardedResultsState += 1
                    }
                    onResult(
                        NavMeshBuildResult.Discarded(
                            generation = request.generation,
                            reason = discardReason,
                            buildMs = buildMs,
                            discardedResults = discardedResultsState
                        )
                    )
                    return@withContext
                }

                val snapshot = NavMeshSnapshot(
                    nodes = builtNodes,
                    targetPackage = request.targetPackage,
                    windowIds = builtNodes.map { it.windowId }.toSet(),
                    version = request.generation,
                    createdAtMs = System.currentTimeMillis(),
                    signature = request.signature,
                    source = request.source,
                    quality = request.quality
                )
                stableMeshState = snapshot
                currentNodesState = builtNodes
                onResult(
                    NavMeshBuildResult.Committed(
                        snapshot = snapshot,
                        buildMs = buildMs,
                        discardedResults = discardedResultsState
                    )
                )
            }
        }
    }

    /**
     * 白纸机制专用提交入口。
     *
     * 与 [submitStableBuild] 不同，本方法会复用当前 StableMesh 的既有边，只重算新增、
     * 删除或几何变化节点附近的连接。变化范围过大时 NavMeshBuilder 会自动回退完整建图。
     */
    fun submitStablePatch(
        request: NavMeshBuildRequest,
        commitGuard: () -> Boolean = { true },
        onResult: (NavMeshBuildResult) -> Unit
    ) {
        val baseSnapshot = stableMeshState
        if (baseSnapshot == null || baseSnapshot.targetPackage != request.targetPackage) {
            submitStableBuild(request, commitGuard, onResult)
            return
        }

        scope.launch(Dispatchers.Default) {
            val buildStartedAt = System.nanoTime()
            val patchResult = NavMeshBuilder.applyIncremental(
                existingNodes = baseSnapshot.nodes,
                desiredNodes = request.nodes,
                maxConnectDist = maxConnectDistance
            )
            val buildMs = (System.nanoTime() - buildStartedAt) / 1_000_000L
            withContext(Dispatchers.Main.immediate) {
                val discardReason = when {
                    selectionActiveProvider() ->
                        NavMeshDiscardReason.SELECTION_ACTIVE
                    request.generation != generationState ->
                        NavMeshDiscardReason.STALE_GENERATION
                    stableMeshState !== baseSnapshot ->
                        NavMeshDiscardReason.STALE_GENERATION
                    !commitGuard() ->
                        NavMeshDiscardReason.COMMIT_GUARD_REJECTED
                    else -> null
                }
                if (discardReason != null) {
                    if (discardReason != NavMeshDiscardReason.COMMIT_GUARD_REJECTED) {
                        discardedResultsState += 1
                    }
                    onResult(
                        NavMeshBuildResult.Discarded(
                            generation = request.generation,
                            reason = discardReason,
                            buildMs = buildMs,
                            discardedResults = discardedResultsState
                        )
                    )
                    return@withContext
                }

                val snapshot = NavMeshSnapshot(
                    nodes = patchResult.nodes,
                    targetPackage = request.targetPackage,
                    windowIds = patchResult.nodes.map { it.windowId }.toSet(),
                    version = request.generation,
                    createdAtMs = System.currentTimeMillis(),
                    signature = request.signature,
                    source = request.source,
                    quality = request.quality
                )
                stableMeshState = snapshot
                currentNodesState = patchResult.nodes
                onResult(
                    NavMeshBuildResult.Committed(
                        snapshot = snapshot,
                        buildMs = buildMs,
                        discardedResults = discardedResultsState,
                        incremental = patchResult.incremental,
                        affectedNodeCount = patchResult.affectedNodeCount,
                        addedNodeCount = patchResult.addedNodeCount,
                        updatedNodeCount = patchResult.updatedNodeCount,
                        removedNodeCount = patchResult.removedNodeCount
                    )
                )
            }
        }
    }

    /**
     * 立即移除所有视觉节点，并为剩余无障碍节点重新建立 neighbors。
     * 不能只 filter 列表，否则旧 neighbors 仍可能指向已经删除的视觉节点。
     */
    fun stripVisionNodes(): VisionStripResult {
        val stableBefore = stableMeshState
        val overlayBefore = overlayMeshState
        val activeBefore = activeMeshState
        val currentBefore = currentNodesState

        val hasVision = currentBefore.any { it.origin == NavNodeOrigin.VISION } ||
            stableBefore?.nodes?.any { it.origin == NavNodeOrigin.VISION } == true ||
            overlayBefore?.nodes?.any { it.origin == NavNodeOrigin.VISION } == true ||
            activeBefore?.nodes?.any { it.origin == NavNodeOrigin.VISION } == true
        if (!hasVision) {
            return VisionStripResult(removedNodeCount = 0, changed = false)
        }

        invalidatePendingBuilds()

        val newStable = stripAndRebuild(stableBefore)
        val newOverlay = stripAndRebuild(overlayBefore)
        val newActive = when {
            activeBefore === stableBefore -> newStable
            activeBefore === overlayBefore -> newOverlay
            else -> stripAndRebuild(activeBefore)
        }

        stableMeshState = newStable
        overlayMeshState = newOverlay
        if (newOverlay == null) overlayExpireAtMsState = 0L
        activeMeshState = newActive
        currentNodesState = when {
            stableBefore != null -> newStable?.nodes.orEmpty()
            else -> stripAndRebuildNodes(currentBefore)
        }

        val removed = currentBefore.count { it.origin == NavNodeOrigin.VISION }
            .coerceAtLeast(stableBefore?.nodes?.count { it.origin == NavNodeOrigin.VISION } ?: 0)
            .coerceAtLeast(overlayBefore?.nodes?.count { it.origin == NavNodeOrigin.VISION } ?: 0)
            .coerceAtLeast(activeBefore?.nodes?.count { it.origin == NavNodeOrigin.VISION } ?: 0)

        return VisionStripResult(
            removedNodeCount = removed,
            changed = true
        )
    }

    private fun stripAndRebuild(snapshot: NavMeshSnapshot?): NavMeshSnapshot? {
        val original = snapshot ?: return null
        if (original.nodes.none { it.origin == NavNodeOrigin.VISION }) return original
        val stripped = stripAndRebuildNodes(original.nodes)
        if (stripped.isEmpty()) return null
        return original.copy(
            nodes = stripped,
            windowIds = stripped.map { it.windowId }.toSet(),
            signature = EdgeInteractionRules.meshSignature(stripped),
            version = generationState,
            createdAtMs = System.currentTimeMillis()
        )
    }

    private fun stripAndRebuildNodes(nodes: List<NavNode>): List<NavNode> {
        val stripped = cloneNodes(nodes.filterNot { it.origin == NavNodeOrigin.VISION })
        if (stripped.isNotEmpty()) {
            NavMeshBuilder.build(stripped, maxConnectDist = maxConnectDistance)
        }
        return stripped
    }

    private fun cloneNodes(nodes: List<NavNode>): List<NavNode> {
        return NavNodeIdentity.ensureStableIds(nodes).map { node ->
            NavNodeIdentity.cloneWithoutNeighbors(node)
        }
    }
}
