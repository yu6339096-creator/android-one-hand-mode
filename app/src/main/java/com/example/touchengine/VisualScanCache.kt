package com.example.touchengine

import android.os.SystemClock

/**
 * 视觉节点缓存与页面指纹状态。
 *
 * 本类不截图、不运行模型、不安装 NavMesh，只负责缓存判断、暂存和提交。
 * 所有缓存都绑定 VisualScanToken；页面会话或视口变化后，旧缓存不能再次参与合并。
 * 所有方法均由主线程调用，不额外加锁。
 */
internal class VisualScanCache {

    private var cachedNodes: List<NavNode> = emptyList()
    private var cachedPackage: String? = null
    private var cachedBands: Set<EdgeInteractionRules.GenericContentBand> = emptySet()
    private var surfaceKey: VisualSurfaceKey? = null
    private var fingerprints: Map<EdgeInteractionRules.GenericContentBand, Long> = emptyMap()
    private var actionableSignature: Int = 0
    private var lastCommitAtMsValue: Long = 0L
    private var pendingValue: PendingVisualCache? = null
    private var cachedToken: VisualScanToken? = null

    val hasCachedNodes: Boolean
        get() = cachedNodes.isNotEmpty()

    val nodeCount: Int
        get() = cachedNodes.size

    /**
     * 当前已提交的视觉节点只读快照。跑马灯门卫用它保留稳定槽位，
     * 不会直接修改缓存中的节点。
     */
    val committedNodes: List<NavNode>
        get() = cachedNodes

    val currentSurfaceKey: VisualSurfaceKey?
        get() = surfaceKey

    val currentActionableSignature: Int
        get() = actionableSignature

    val lastCommitAtMs: Long
        get() = lastCommitAtMsValue

    val pending: PendingVisualCache?
        get() = pendingValue

    fun retainedBandsFor(
        targetPackage: String?,
        pageEpoch: Long,
        viewportRevision: Long
    ): Set<EdgeInteractionRules.GenericContentBand> {
        return if (
            targetPackage == cachedPackage &&
            cachedNodes.isNotEmpty() &&
            cachedToken.matches(pageEpoch, viewportRevision)
        ) {
            cachedBands
        } else {
            emptySet()
        }
    }

    fun hasActiveCache(
        requestedSurfaceKey: VisualSurfaceKey,
        requestedBands: Set<EdgeInteractionRules.GenericContentBand>,
        pageEpoch: Long,
        viewportRevision: Long
    ): Boolean {
        return cachedNodes.isNotEmpty() &&
            surfaceKey == requestedSurfaceKey &&
            cachedBands.containsAll(requestedBands) &&
            cachedToken.matches(pageEpoch, viewportRevision)
    }

    fun mergeCachedNodes(
        base: List<NavNode>,
        sampledPackage: String?,
        pageSignature: Int,
        sceneTag: String,
        pageEpoch: Long,
        viewportRevision: Long
    ): List<NavNode> {
        val accessibilityNodes = base.filterNot { it.origin == NavNodeOrigin.VISION }
        if (
            sampledPackage != cachedPackage ||
            cachedNodes.isEmpty() ||
            surfaceKey?.pageSignature != pageSignature ||
            surfaceKey?.sceneTag != sceneTag ||
            !cachedToken.matches(pageEpoch, viewportRevision)
        ) {
            return accessibilityNodes
        }
        return (accessibilityNodes + cachedNodes).distinctBy {
            "${it.windowId}:${it.bounds.left}:${it.bounds.top}:${it.bounds.right}:${it.bounds.bottom}"
        }
    }

    fun hasMeaningfulFingerprintChange(
        newFingerprints: Map<EdgeInteractionRules.GenericContentBand, Long>
    ): Boolean {
        return newFingerprints.any { (band, fingerprint) ->
            EdgeInteractionRules.hasMeaningfulVisualChange(fingerprints[band], fingerprint)
        }
    }

    fun fingerprintDistance(
        newFingerprints: Map<EdgeInteractionRules.GenericContentBand, Long>
    ): Int {
        return newFingerprints.mapNotNull { (band, fingerprint) ->
            fingerprints[band]?.let {
                EdgeInteractionRules.visualFingerprintDistance(it, fingerprint)
            }
        }.maxOrNull() ?: -1
    }

    fun stage(pending: PendingVisualCache) {
        pendingValue = pending
    }

    fun discardPending() {
        pendingValue = null
    }

    fun isPending(pending: PendingVisualCache): Boolean = pendingValue == pending

    /** ACTIONABLE_NOISE 或空结果时只推进当前会话的观察基准，不替换现有节点。 */
    fun markObserved(
        token: VisualScanToken,
        newFingerprints: Map<EdgeInteractionRules.GenericContentBand, Long>,
        observedAtMs: Long = SystemClock.elapsedRealtime()
    ) {
        if (cachedToken != null && !cachedToken.matches(token.pageEpoch, token.viewportRevision)) return
        fingerprints = newFingerprints
        lastCommitAtMsValue = observedAtMs
    }

    fun clear() {
        cachedNodes = emptyList()
        cachedPackage = null
        cachedBands = emptySet()
        surfaceKey = null
        fingerprints = emptyMap()
        actionableSignature = 0
        lastCommitAtMsValue = 0L
        pendingValue = null
        cachedToken = null
    }


    /**
     * Selection confirm diff 判断为“无有意义变化”时调用。
     * 只推进观察基准与 pending 状态，不替换 cachedNodes，避免 stable navigation / Overlay 闪烁。
     */
    fun retainPendingWithoutReplacingNodes(
        pending: PendingVisualCache,
        status: String,
        observedAtMs: Long = SystemClock.elapsedRealtime()
    ): VisualCacheCommitResult? {
        if (pendingValue != pending) return null
        if (cachedToken != null && !cachedToken.matches(pending.token.pageEpoch, pending.token.viewportRevision)) return null

        cachedPackage = pending.targetPackage
        cachedBands = cachedBands + pending.bands
        surfaceKey = pending.surfaceKey
        fingerprints = pending.fingerprints
        actionableSignature = if (cachedNodes.isNotEmpty()) actionableSignature else pending.actionableSignature
        lastCommitAtMsValue = observedAtMs
        pendingValue = null
        if (cachedToken == null) cachedToken = pending.token

        return VisualCacheCommitResult(
            replacementFocus = null,
            preservesFocus = true,
            display = VisualOcrDisplay(
                candidates = pending.candidates,
                diagnostics = pending.diagnostics,
                elapsedMs = pending.elapsedMs,
                status = status
            )
        )
    }

    fun commit(
        pending: PendingVisualCache,
        selectedFocus: NavNode?
    ): VisualCacheCommitResult? {
        if (pendingValue != pending) return null

        val previousNodes = cachedNodes
        val preservesFocus = EdgeInteractionRules.hasEquivalentVisualGeometry(previousNodes, pending.nodes)
        val replacementFocus = if (preservesFocus && selectedFocus?.origin == NavNodeOrigin.VISION) {
            EdgeInteractionRules.closestEquivalentNode(selectedFocus, pending.nodes)
        } else {
            null
        }

        cachedNodes = pending.nodes
        cachedPackage = pending.targetPackage
        cachedBands = pending.bands
        surfaceKey = pending.surfaceKey
        fingerprints = pending.fingerprints
        actionableSignature = pending.actionableSignature
        lastCommitAtMsValue = SystemClock.elapsedRealtime()
        pendingValue = null
        cachedToken = pending.token

        return VisualCacheCommitResult(
            replacementFocus = replacementFocus,
            preservesFocus = preservesFocus,
            display = VisualOcrDisplay(
                candidates = pending.candidates,
                diagnostics = pending.diagnostics,
                elapsedMs = pending.elapsedMs,
                status = "VISION ${pending.diagnosticSummary ?: "cache ${pending.candidates.size}"} / " +
                    "${pending.elapsedMs}ms (${pending.textEvidenceCount} text)"
            )
        )
    }

    private fun VisualScanToken?.matches(pageEpoch: Long, viewportRevision: Long): Boolean {
        return this != null &&
            this.pageEpoch == pageEpoch &&
            this.viewportRevision == viewportRevision
    }
}
