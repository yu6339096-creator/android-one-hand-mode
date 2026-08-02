package com.example.touchengine

enum class MeshSource { STABLE, OVERLAY }

enum class MeshQuality { FAST, DEEP }

enum class RefreshDirtyReason { CONTENT, SCROLL, WINDOW, SELECTION, POPUP }

internal enum class VisualCacheInvalidationReason {
    APP_SWITCH,
    WINDOW_REPLACED,
    SURFACE_REPLACED,
    VERTICAL_SCROLL,
    PAGE_FLIP,
    POPUP_CHANGE,
    AUTO_SCROLL_FINISHED,
    USER_CLICK,
    CONTENT_CHANGE,
    DYNAMIC_HOLE_CHANGE,
    ACTIONABLE_CHANGE,
    ACTIONABLE_NOISE,
    OUTSIDE_HOLE_NOISE,
    THROTTLED,
    SELECTION_FROZEN
}

internal data class VisualSurfaceKey(
    val targetPackage: String?,
    val viewportWidthBucket: Int,
    val viewportHeightBucket: Int,
    val holeBands: Set<EdgeInteractionRules.GenericContentBand>,
    val pageSignature: Int = 0,
    val sceneTag: String = ""
)

data class NavMeshSnapshot(
    val nodes: List<NavNode>,
    val targetPackage: String?,
    val windowIds: Set<Int>,
    val version: Int,
    val createdAtMs: Long,
    val signature: Int,
    val source: MeshSource,
    val quality: MeshQuality = MeshQuality.FAST
)

data class PopupProbeResult(
    val windowId: Int,
    val nodes: List<NavNode>
)

internal data class GestureMeshSession(
    val snapshot: NavMeshSnapshot?,
    val nodes: List<NavNode>,
    val source: MeshSource?
)

internal data class OverlayMeshActivation(
    val activated: Boolean,
    val snapshot: NavMeshSnapshot? = null,
    val initialFocus: NavNode? = null
)

internal enum class NavMeshDiscardReason {
    STALE_GENERATION,
    SELECTION_ACTIVE,
    COMMIT_GUARD_REJECTED
}

internal data class NavMeshBuildRequest(
    val nodes: List<NavNode>,
    val targetPackage: String?,
    val signature: Int,
    val source: MeshSource,
    val quality: MeshQuality,
    val generation: Int
)

internal sealed interface NavMeshBuildResult {
    data class Committed(
        val snapshot: NavMeshSnapshot,
        val buildMs: Long,
        val discardedResults: Int,
        /** true 表示复用了旧图边，只局部重算受影响节点。 */
        val incremental: Boolean = false,
        val affectedNodeCount: Int = snapshot.nodes.size,
        val addedNodeCount: Int = 0,
        val updatedNodeCount: Int = 0,
        val removedNodeCount: Int = 0
    ) : NavMeshBuildResult

    data class Discarded(
        val generation: Int,
        val reason: NavMeshDiscardReason,
        val buildMs: Long,
        val discardedResults: Int
    ) : NavMeshBuildResult
}

internal data class VisionStripResult(
    val removedNodeCount: Int,
    val changed: Boolean
)
