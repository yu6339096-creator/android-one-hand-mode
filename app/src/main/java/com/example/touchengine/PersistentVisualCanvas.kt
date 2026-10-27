package com.example.touchengine

import android.graphics.Rect
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 白纸机制：视觉节点的持久画布、页面指纹状态与 Pending 安装状态。
 *
 * 本类不截图、不运行模型、不安装 NavMesh。YOLO/OCR 每次只提交新的视觉观察，本类负责：
 * 1. 用 stableId 将新观察与已提交视觉节点对应；
 * 2. 抑制轻微坐标抖动；
 * 3. 生成 ADD / UPDATE / REMOVE 节点补丁；
 * 4. 对一次短暂漏检保留旧节点；
 * 5. 在最终 NavMesh 成功提交后，才原子提交白纸内容。
 *
 * 所有缓存都绑定 VisualScanToken。页面会话或视口变化后，旧结果不能再次写回。
 * 所有方法均由主线程调用，不额外加锁。
 */
internal class PersistentVisualCanvas {

    private var cachedNodes: List<NavNode> = emptyList()
    private var cachedPackage: String? = null
    private var cachedBands: Set<EdgeInteractionRules.GenericContentBand> = emptySet()
    private var surfaceKey: VisualSurfaceKey? = null
    private var fingerprints: Map<EdgeInteractionRules.GenericContentBand, Long> = emptyMap()
    private var actionableSignature: Int = 0
    private var lastCommitAtMsValue: Long = 0L
    private var pendingValue: PendingVisualCache? = null
    private var preparedUpdateValue: VisualCanvasUpdate? = null
    private var cachedToken: VisualScanToken? = null
    private var missingCounts: Map<String, Int> = emptyMap()
    /** OCR 与 YOLO 在融合前各自的快速观察；只在 FUSED 提交后一起收束。 */
    private var directObservations: Map<VisualUpdateStage, List<NavNode>> = emptyMap()
    private var stableSequence: Long = 0L

    val hasCachedNodes: Boolean
        get() = cachedNodes.isNotEmpty()

    val nodeCount: Int
        get() = cachedNodes.size

    /** 当前已提交的视觉节点只读快照。 */
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
        return (accessibilityNodes + cachedNodes).distinctBy { node ->
            node.stableId ?: "${node.windowId}:${node.bounds.left}:${node.bounds.top}:" +
                "${node.bounds.right}:${node.bounds.bottom}"
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
        preparedUpdateValue = null
    }

    fun discardPending() {
        pendingValue = null
        preparedUpdateValue = null
    }

    fun isPending(pending: PendingVisualCache): Boolean = pendingValue == pending

    /**
     * 将一次完整扫描结果转为白纸增量。该步骤只准备结果，不修改已提交白纸。
     * 同一个 pending 因手势冻结而重试时，返回完全相同的 stableId 与 Patch。
     */
    fun prepareUpdate(pending: PendingVisualCache): VisualCanvasUpdate? {
        if (pendingValue != pending) return null
        preparedUpdateValue?.takeIf { it.pending == pending }?.let { return it }
        if (cachedToken != null && !cachedToken.matches(pending.token.pageEpoch, pending.token.viewportRevision)) {
            return null
        }

        val sameCanvasSession = cachedPackage == null || (
            cachedPackage == pending.targetPackage &&
                cachedToken.matches(pending.token.pageEpoch, pending.token.viewportRevision)
            )
        val previousNodes = if (sameCanvasSession) cachedNodes else emptyList()
        val previousMissingCounts = if (sameCanvasSession) missingCounts else emptyMap()
        val nextDirectObservations = if (sameCanvasSession) {
            directObservations.toMutableMap()
        } else {
            mutableMapOf()
        }
        val incomingNodes = when (pending.stage) {
            VisualUpdateStage.FUSED -> {
                // 融合结果校正同位置的快速节点，但不能删除本帧中未被融合覆盖的可靠
                // DIRECT_YOLO / DIRECT_OCR 节点。否则关键词直入会在 FUSED 到达时闪退。
                val trustedDirectNodes = nextDirectObservations.values
                    .flatten()
                    .filter { it.visualEvidence?.directEligible == true }
                val fusedNodes = pending.nodes.filter { it.origin == NavNodeOrigin.VISION }
                val merged = mergeFusedWithTrustedDirect(fusedNodes, trustedDirectNodes)
                nextDirectObservations.clear()
                merged
            }
            VisualUpdateStage.DIRECT_YOLO,
            VisualUpdateStage.DIRECT_OCR -> {
                nextDirectObservations[pending.stage] = pending.nodes
                    .filter { it.origin == NavNodeOrigin.VISION }
                mergeDirectObservations(nextDirectObservations.values.flatten())
            }
        }
        val isDirectUpdate = pending.stage != VisualUpdateStage.FUSED

        val matches = matchNodes(previousNodes, incomingNodes)
        val oldMatched = matches.map { it.oldIndex }.toMutableSet()
        val explicitlyAbsorbedIds = incomingNodes.flatMapTo(linkedSetOf()) { node ->
            node.visualEvidence?.absorbedStableIds.orEmpty()
        }
        previousNodes.forEachIndexed { index, node ->
            if (node.stableId in explicitlyAbsorbedIds) oldMatched += index
        }
        val addedIds = linkedSetOf<String>()
        val updatedIds = linkedSetOf<String>()
        val removedIds = linkedSetOf<String>()
        val geometryChangedIds = linkedSetOf<String>()
        val retainedMissingIds = linkedSetOf<String>()
        val nextMissing = linkedMapOf<String, Int>()
        val nextNodes = mutableListOf<NavNode>()
        val stableIdAliases = linkedMapOf<String, String>()

        val matchByNew = matches.associateBy { it.newIndex }
        incomingNodes.forEachIndexed { newIndex, observed ->
            val match = matchByNew[newIndex]
            if (match == null) {
                val stableId = allocateStableId(pending, observed)
                nextNodes += observed.copy(
                    bounds = Rect(observed.bounds),
                    centerX = observed.bounds.exactCenterX(),
                    centerY = observed.bounds.exactCenterY(),
                    stableId = stableId,
                    interactionBounds = observed.interactionBounds?.let(::Rect)
                )
                observed.visualEvidence?.absorbedStableIds.orEmpty().forEach { absorbedId ->
                    if (absorbedId != stableId) stableIdAliases[absorbedId] = stableId
                }
                addedIds += stableId
                nextMissing[stableId] = 0
            } else {
                val oldNode = previousNodes[match.oldIndex]
                val stableId = oldNode.stableId ?: allocateStableId(pending, oldNode)
                val stabilized = mergeMatchedNode(oldNode, observed, stableId)
                nextNodes += stabilized
                observed.visualEvidence?.absorbedStableIds.orEmpty().forEach { absorbedId ->
                    if (absorbedId != stableId) stableIdAliases[absorbedId] = stableId
                }
                nextMissing[stableId] = 0
                if (!NavNodeIdentity.samePayload(oldNode, stabilized)) {
                    updatedIds += stableId
                }
                if (!NavNodeIdentity.sameGeometry(oldNode, stabilized)) {
                    geometryChangedIds += stableId
                }
            }
        }

        previousNodes.forEachIndexed { oldIndex, oldNode ->
            if (oldIndex in oldMatched) {
                if (oldNode.stableId in explicitlyAbsorbedIds && matches.none { it.oldIndex == oldIndex }) {
                    oldNode.stableId?.let(removedIds::add)
                }
                return@forEachIndexed
            }
            val stableId = oldNode.stableId ?: allocateStableId(pending, oldNode)
            val previousMissed = previousMissingCounts[stableId] ?: 0
            val missed = if (isDirectUpdate) previousMissed else previousMissed + 1
            // 快速路径只补充节点，绝不因为另一条路径尚未到达而删除已有节点。
            // FULL_REFRESH describes scan coverage, not proof of a scene transition.
            // Real scene invalidation clears this Canvas before preparing an update.
            val retainOnce = isDirectUpdate || missed < MISSING_CONFIRMATION_COUNT
            if (retainOnce) {
                nextNodes += oldNode.copy(
                    bounds = Rect(oldNode.bounds),
                    centerX = oldNode.bounds.exactCenterX(),
                    centerY = oldNode.bounds.exactCenterY(),
                    stableId = stableId,
                    interactionBounds = oldNode.interactionBounds?.let(::Rect)
                )
                nextMissing[stableId] = missed
                retainedMissingIds += stableId
            } else {
                removedIds += stableId
            }
        }

        val orderedNodes = nextNodes
            .distinctBy { it.stableId }
            .sortedWith(compareBy({ it.windowId }, { it.bounds.top }, { it.bounds.left }))
        val update = VisualCanvasUpdate(
            pending = pending,
            nodes = orderedNodes,
            patch = VisualCanvasPatch(
                addedStableIds = addedIds,
                updatedStableIds = updatedIds,
                removedStableIds = removedIds,
                geometryChangedStableIds = geometryChangedIds,
                retainedMissingStableIds = retainedMissingIds
            ),
            nextMissingCounts = nextMissing,
            nextDirectObservations = nextDirectObservations.mapValues { (_, nodes) ->
                nodes.map {
                    it.copy(
                        bounds = Rect(it.bounds),
                        interactionBounds = it.interactionBounds?.let(::Rect)
                    )
                }
            },
            stableIdAliases = stableIdAliases
        )
        preparedUpdateValue = update
        return update
    }

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
        preparedUpdateValue = null
        cachedToken = null
        missingCounts = emptyMap()
        directObservations = emptyMap()
        stableSequence = 0L
    }

    /**
     * Selection confirm diff 判断为“无有意义变化”时调用。
     * 只推进观察基准与 pending 状态，不替换白纸节点，避免 StableMesh/Overlay 闪烁。
     */
    fun retainPendingWithoutReplacingNodes(
        pending: PendingVisualCache,
        status: String,
        observedAtMs: Long = SystemClock.elapsedRealtime()
    ): VisualCacheCommitResult? {
        if (pendingValue != pending) return null
        if (cachedToken != null && !cachedToken.matches(pending.token.pageEpoch, pending.token.viewportRevision)) {
            return null
        }

        cachedPackage = pending.targetPackage
        cachedBands = cachedBands + pending.bands
        surfaceKey = pending.surfaceKey
        fingerprints = pending.fingerprints
        actionableSignature = if (cachedNodes.isNotEmpty()) actionableSignature else pending.actionableSignature
        lastCommitAtMsValue = observedAtMs
        pendingValue = null
        preparedUpdateValue = null
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

    /** 最终 StableMesh 成功后，原子提交先前准备好的白纸更新。 */
    fun commit(
        update: VisualCanvasUpdate,
        selectedFocus: NavNode?
    ): VisualCacheCommitResult? {
        val pending = update.pending
        if (pendingValue != pending || preparedUpdateValue != update) return null

        val replacementFocus = when {
            selectedFocus?.stableId != null -> update.nodes.firstOrNull {
                it.stableId == selectedFocus.stableId
            } ?: update.stableIdAliases[selectedFocus.stableId]?.let { replacementId ->
                update.nodes.firstOrNull { it.stableId == replacementId }
            }
            selectedFocus?.origin == NavNodeOrigin.VISION -> {
                EdgeInteractionRules.closestEquivalentNode(selectedFocus, update.nodes)
            }
            else -> null
        }
        val preservesFocus = selectedFocus?.origin != NavNodeOrigin.VISION || replacementFocus != null

        cachedNodes = update.nodes
        cachedPackage = pending.targetPackage
        cachedBands = pending.bands
        surfaceKey = pending.surfaceKey
        fingerprints = pending.fingerprints
        actionableSignature = EdgeInteractionRules.actionableVisualSignature(update.nodes)
        lastCommitAtMsValue = SystemClock.elapsedRealtime()
        pendingValue = null
        preparedUpdateValue = null
        cachedToken = pending.token
        missingCounts = update.nextMissingCounts
        directObservations = update.nextDirectObservations

        return VisualCacheCommitResult(
            replacementFocus = replacementFocus,
            preservesFocus = preservesFocus,
            display = VisualOcrDisplay(
                candidates = pending.candidates,
                diagnostics = pending.diagnostics,
                elapsedMs = pending.elapsedMs,
                status = "VISION ${pending.diagnosticSummary ?: "canvas ${pending.candidates.size}"} / " +
                    "${pending.elapsedMs}ms (${pending.textEvidenceCount} text) " +
                    "patch=+${update.patch.addedStableIds.size}/" +
                    "~${update.patch.updatedStableIds.size}/-${update.patch.removedStableIds.size}"
            )
        )
    }

    private data class NodeMatch(
        val oldIndex: Int,
        val newIndex: Int,
        val score: Float
    )

    private fun matchNodes(oldNodes: List<NavNode>, newNodes: List<NavNode>): List<NodeMatch> {
        if (oldNodes.isEmpty() || newNodes.isEmpty()) return emptyList()
        val candidates = mutableListOf<NodeMatch>()
        oldNodes.forEachIndexed { oldIndex, oldNode ->
            newNodes.forEachIndexed { newIndex, newNode ->
                val score = matchScore(oldNode, newNode)
                if (score >= MATCH_THRESHOLD) {
                    candidates += NodeMatch(oldIndex, newIndex, score)
                }
            }
        }
        val usedOld = mutableSetOf<Int>()
        val usedNew = mutableSetOf<Int>()
        val result = mutableListOf<NodeMatch>()
        candidates.sortedByDescending { it.score }.forEach { candidate ->
            if (candidate.oldIndex in usedOld || candidate.newIndex in usedNew) return@forEach
            usedOld += candidate.oldIndex
            usedNew += candidate.newIndex
            result += candidate
        }
        return result
    }

    /** 把同一位置的 OCR/YOLO 直入观察先合为一个临时节点，避免两个 stableId。 */
    private fun mergeDirectObservations(nodes: List<NavNode>): List<NavNode> {
        val merged = mutableListOf<NavNode>()
        nodes.forEach { observed ->
            val existingIndex = merged.indexOfFirst { existing ->
                matchScore(existing, observed) >= MATCH_THRESHOLD ||
                    areContainedDirectDuplicates(existing, observed)
            }
            if (existingIndex < 0) {
                merged += observed
            } else {
                val existing = merged[existingIndex]
                val temporaryStableId = existing.stableId ?: observed.stableId ?: "direct-pending"
                merged[existingIndex] = mergeMatchedNode(existing, observed, temporaryStableId).let {
                    if (existing.stableId == null && observed.stableId == null) it.copy(stableId = null) else it
                }
            }
        }
        return merged
    }

    /**
     * FUSED 对同一目标拥有最终几何与元数据决定权；没有对应融合目标的可靠快速节点
     * 则继续保留，体现“先直入、后融合校正”而不是“后融合全量覆盖”。
     */
    private fun mergeFusedWithTrustedDirect(
        fusedNodes: List<NavNode>,
        trustedDirectNodes: List<NavNode>
    ): List<NavNode> {
        val merged = fusedNodes.toMutableList()
        trustedDirectNodes.forEach { direct ->
            val existingIndex = merged.indexOfFirst { fused ->
                direct.stableId in fused.visualEvidence?.absorbedStableIds.orEmpty() ||
                    fusedRepresentsDirectObservation(fused, direct) ||
                    matchScore(fused, direct) >= MATCH_THRESHOLD
            }
            if (existingIndex < 0) {
                merged += direct
            } else {
                val fused = merged[existingIndex]
                val temporaryStableId = fused.stableId ?: direct.stableId ?: "fused-pending"
                merged[existingIndex] = mergeMatchedNode(direct, fused, temporaryStableId).let {
                    if (fused.stableId == null && direct.stableId == null) {
                        it.copy(stableId = null)
                    } else {
                        it
                    }
                }
            }
        }
        return merged
    }

    private fun fusedRepresentsDirectObservation(fused: NavNode, direct: NavNode): Boolean {
        val relations = fused.visualEvidence?.mergeRelations.orEmpty()
        if (relations.none {
                it == FusionMergeRelation.CONTAINED_LABEL ||
                    it == FusionMergeRelation.ICON_LABEL_PAIR
            }
        ) return false
        if (fused.windowId != direct.windowId) return false
        val overlap = intersectionArea(fused.bounds, direct.bounds) /
            direct.bounds.areaLong().toFloat().coerceAtLeast(1f)
        return overlap >= 0.78f
    }

    private fun mergeMatchedNode(oldNode: NavNode, observed: NavNode, stableId: String): NavNode {
        val mergedSources = oldNode.sourceSet + observed.sourceSet
        val clickMethod = when {
            oldNode.clickMethod == ClickMethod.ACCESSIBILITY_ACTION -> oldNode.clickMethod
            observed.clickMethod == ClickMethod.ACCESSIBILITY_ACTION -> observed.clickMethod
            oldNode.clickMethod == ClickMethod.GESTURE_SAFE_LEFT -> oldNode.clickMethod
            observed.clickMethod == ClickMethod.GESTURE_SAFE_LEFT -> observed.clickMethod
            else -> observed.clickMethod
        }
        val preferredGeometry = preferredGeometry(oldNode, observed)
        val preferredMetadata = chooseMetadataWinner(oldNode, observed)
        val evidence = (preferredMetadata.visualEvidence ?: oldNode.visualEvidence ?: observed.visualEvidence)
            ?.copy(sources = mergedSources, clickMethod = clickMethod)
        val interactionBounds = preferredGeometry.interactionBounds
            ?: preferredMetadata.interactionBounds
            ?: preferredGeometry.bounds
        return preferredMetadata.copy(
            bounds = Rect(preferredGeometry.bounds),
            centerX = preferredGeometry.bounds.exactCenterX(),
            centerY = preferredGeometry.bounds.exactCenterY(),
            windowId = preferredGeometry.windowId,
            clickMethod = clickMethod,
            sourceSet = mergedSources,
            visualEvidence = evidence,
            stableId = stableId,
            interactionBounds = Rect(interactionBounds)
        )
    }

    private fun preferredGeometry(first: NavNode, second: NavNode): NavNode {
        val structural = structuralContainerForDirectPair(first, second)
        if (structural != null) return structural
        return if (isTinyGeometryJitter(first, second)) first else second
    }

    private fun areContainedDirectDuplicates(first: NavNode, second: NavNode): Boolean =
        structuralContainerForDirectPair(first, second) != null

    private fun structuralContainerForDirectPair(first: NavNode, second: NavNode): NavNode? {
        val pairs = listOf(first to second, second to first)
        return pairs.firstNotNullOfOrNull { (parent, label) ->
            val parentRole = parent.visualEvidence?.role ?: TargetRole.UNKNOWN
            val labelText = normalizedDescription(label)
            val labelIsOcrOnly = label.sourceSet == setOf(TargetSource.OCR) &&
                label.visualEvidence?.directEligible == true &&
                labelText.any(Char::isLetter)
            val parentIsStructural = parentRole in DIRECT_CONTAINER_ROLES &&
                parent.sourceSet.any { it == TargetSource.YOLO || it == TargetSource.ACCESSIBILITY }
            val overlap = intersectionArea(parent.bounds, label.bounds) /
                label.bounds.areaLong().toFloat().coerceAtLeast(1f)
            val actionNeedsOwnNode = EdgeInteractionRules.isActionableVisualText(labelText) &&
                (parentRole == TargetRole.LIST_ITEM ||
                    parent.bounds.areaLong() > label.bounds.areaLong() * 6L)
            parent.takeIf {
                labelIsOcrOnly && parentIsStructural && !actionNeedsOwnNode &&
                    parent.bounds.areaLong() > label.bounds.areaLong() && overlap >= 0.78f
            }
        }
    }

    private fun chooseMetadataWinner(oldNode: NavNode, observed: NavNode): NavNode {
        val oldScore = oldNode.visualEvidence?.score ?: 0
        val observedScore = observed.visualEvidence?.score ?: 0
        val observedHasText = !observed.description.isNullOrBlank() ||
            !observed.visualEvidence?.ocrText.isNullOrBlank()
        return when {
            observedHasText && observedScore >= oldScore - 8 -> observed
            observedScore > oldScore -> observed
            oldNode.description.isNullOrBlank() && !observed.description.isNullOrBlank() -> observed
            else -> oldNode
        }
    }

    private fun matchScore(oldNode: NavNode, newNode: NavNode): Float {
        if (oldNode.windowId != newNode.windowId) return 0f
        if (oldNode.stableId != null && oldNode.stableId == newNode.stableId) return 1f
        if (!rolesCompatible(oldNode, newNode)) return 0f
        val overlap = iou(oldNode.bounds, newNode.bounds)
        val center = centerCloseness(oldNode.bounds, newNode.bounds)
        val textScore = textSimilarity(oldNode, newNode)
        val sourceBonus = if ((oldNode.sourceSet intersect newNode.sourceSet).isNotEmpty()) 0.05f else 0f
        return (overlap * 0.62f + center * 0.25f + textScore * 0.13f + sourceBonus)
            .coerceIn(0f, 1f)
    }

    private fun rolesCompatible(first: NavNode, second: NavNode): Boolean {
        val firstRole = first.visualEvidence?.role ?: TargetRole.UNKNOWN
        val secondRole = second.visualEvidence?.role ?: TargetRole.UNKNOWN
        if (firstRole == TargetRole.UNKNOWN || secondRole == TargetRole.UNKNOWN) return true
        if (firstRole == secondRole) return true
        val firstAction = firstRole in ACTION_LIKE_ROLES
        val secondAction = secondRole in ACTION_LIKE_ROLES
        return firstAction && secondAction
    }

    private fun textSimilarity(first: NavNode, second: NavNode): Float {
        val firstText = normalizedDescription(first)
        val secondText = normalizedDescription(second)
        if (firstText.isBlank() || secondText.isBlank()) return 0.45f
        if (firstText == secondText) return 1f
        return if (firstText.contains(secondText) || secondText.contains(firstText)) 0.72f else 0f
    }

    private fun normalizedDescription(node: NavNode): String {
        return (node.visualEvidence?.ocrText ?: node.description.orEmpty())
            .substringAfter(":", node.visualEvidence?.ocrText ?: node.description.orEmpty())
            .replace("\\s+".toRegex(), "")
            .lowercase()
            .take(32)
    }

    private fun isTinyGeometryJitter(first: NavNode, second: NavNode): Boolean {
        return first.windowId == second.windowId &&
            iou(first.bounds, second.bounds) >= JITTER_IOU_THRESHOLD &&
            edgeDelta(first.bounds, second.bounds) <= JITTER_EDGE_TOLERANCE_PX
    }

    private fun centerCloseness(first: Rect, second: Rect): Float {
        val distance = hypot(
            (first.exactCenterX() - second.exactCenterX()).toDouble(),
            (first.exactCenterY() - second.exactCenterY()).toDouble()
        ).toFloat()
        val tolerance = max(
            max(first.width(), second.width()),
            max(first.height(), second.height())
        ).coerceAtLeast(24).toFloat()
        return (1f - distance / (tolerance * 1.6f)).coerceIn(0f, 1f)
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

    private fun intersectionArea(first: Rect, second: Rect): Long {
        val width = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)
        val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)
        return width.toLong() * height.toLong()
    }

    private fun allocateStableId(pending: PendingVisualCache, node: NavNode): String {
        stableSequence += 1L
        val role = node.visualEvidence?.role?.name ?: "UNKNOWN"
        return "vision:${pending.token.pageEpoch}:${pending.token.viewportRevision}:" +
            "${node.windowId}:$role:$stableSequence"
    }

    private fun VisualScanToken?.matches(pageEpoch: Long, viewportRevision: Long): Boolean {
        return this != null &&
            this.pageEpoch == pageEpoch &&
            this.viewportRevision == viewportRevision
    }

    private companion object {
        val DIRECT_CONTAINER_ROLES = setOf(
            TargetRole.ACTION,
            TargetRole.ICON_ENTRY,
            TargetRole.LIST_ITEM,
            TargetRole.RIGHT_RAIL
        )
        const val MATCH_THRESHOLD = 0.55f
        const val JITTER_IOU_THRESHOLD = 0.82f
        const val JITTER_EDGE_TOLERANCE_PX = 7
        const val MISSING_CONFIRMATION_COUNT = 2
        val ACTION_LIKE_ROLES = setOf(
            TargetRole.ACTION,
            TargetRole.ICON_ENTRY,
            TargetRole.RIGHT_RAIL
        )
    }
}
