package com.example.touchengine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Service 提供给边缘交互状态机的最小能力集合。 */
internal class EdgeInteractionEnvironment(
    val screenWidth: Int,
    val screenHeight: Int,
    val configProvider: () -> ScrollConfig,
    val enabledProvider: () -> Boolean,
    val activePackageProvider: () -> String?,
    val homePackageProvider: () -> String?,
    val currentFocusProvider: () -> NavNode?,
    val updateFocus: (NavNode?) -> Unit,
    val resetNavigation: () -> Unit,
    val computePageFingerprint: () -> String,
    val invalidateVisualSession: (VisualCacheInvalidationReason) -> Unit,
    val prepareContinuousScroll: () -> Unit,
    val finishContinuousScroll: (restoreX: Float, restoreY: Float, contentChanged: Boolean) -> Unit,
    val resetCapabilityProbes: (VisualCacheInvalidationReason) -> Unit,
    val rebuildNavMesh: (forceApply: Boolean, onDone: (List<NavNode>) -> Unit) -> Unit,
    val postDelayed: (delayMs: Long, block: () -> Unit) -> Unit
)

/**
 * 摇杆到达边缘后的翻页/连续滑动状态机。
 *
 * Controller 只持有边缘交互状态。无障碍查询交给 AccessibilityScrollResolver，
 * 系统手势交给 GestureDispatcher，页面循环历史交给 PageFlipCycleTracker。
 */
internal class EdgeInteractionController(
    private val resolver: AccessibilityScrollResolver,
    private val gestureDispatcher: GestureDispatcher,
    private val scope: CoroutineScope,
    private val environment: EdgeInteractionEnvironment,
    private val cycleTracker: PageFlipCycleTracker = PageFlipCycleTracker()
) {
    private enum class EdgeActionType { NONE, PAGE_FLIP, GESTURE_SCROLL }

    private data class EdgeAction(
        val type: EdgeActionType,
        val dir: ScrollDir
    )

    var state: ScrollState = ScrollState.IDLE
        private set

    val hintState = mutableStateOf<ScrollHintData?>(null)

    private var edgeCount = 0
    private var edgeDir: ScrollDir? = null
    private var rejectedEdgeDir: ScrollDir? = null
    private var hintJob: Job? = null
    private var actionJob: Job? = null

    private var previousFocusX = 0f
    private var previousFocusY = 0f

    private var autoScrollDir: ScrollDir? = null
    private var autoScrollStartX = 0f
    private var autoScrollStartY = 0f
    private var autoScrollPackage: String? = null
    private var autoScrollWindowId: Int? = null
    private var pendingRefreshAfterAutoScroll = false

    fun handleHeldEdge(focus: NavNode, dir: ScrollDir, ratio: Float) {
        if (focus.origin == NavNodeOrigin.VISION) return
        val config = environment.configProvider()
        if (!config.autoScrollEnabled || ratio < 0.28f || rejectedEdgeDir == dir) return

        if (dir == edgeDir) {
            edgeCount++
        } else {
            cancel()
            edgeDir = dir
            edgeCount = 1
        }

        if (edgeCount < config.edgeTriggerFrames) return
        edgeCount = 0

        val action = decideEdgeAction(focus, dir)
        if (action.type == EdgeActionType.NONE) {
            rejectedEdgeDir = dir
            edgeDir = null
            return
        }

        if (action.type == EdgeActionType.PAGE_FLIP &&
            cycleTracker.isBlocked(dir, System.currentTimeMillis())
        ) {
            rejectedEdgeDir = dir
            edgeDir = null
            return
        }

        previousFocusX = environment.currentFocusProvider()?.centerX ?: 0f
        previousFocusY = environment.currentFocusProvider()?.centerY ?: 0f
        startHint(focus, action)
    }

    fun handleFlickEdge(focus: NavNode, dir: ScrollDir, ratio: Float) {
        if (focus.origin == NavNodeOrigin.VISION) return
        val config = environment.configProvider()
        if (!config.autoScrollEnabled || ratio < 0.5f) return

        val action = decideEdgeAction(focus, dir)
        if (action.type != EdgeActionType.PAGE_FLIP) return
        if (cycleTracker.isBlocked(dir, System.currentTimeMillis())) return

        previousFocusX = environment.currentFocusProvider()?.centerX ?: 0f
        previousFocusY = environment.currentFocusProvider()?.centerY ?: 0f
        environment.invalidateVisualSession(VisualCacheInvalidationReason.PAGE_FLIP)
        cycleTracker.prepareAttempt(environment.computePageFingerprint())
        if (tryPageFlip(focus, dir)) {
            environment.updateFocus(null)
            environment.resetNavigation()
            cycleTracker.recordSuccess(dir, System.currentTimeMillis())
            resetEdgeProgress()
            environment.postDelayed(400L) {
                afterPageFlip(previousFocusX, previousFocusY)
            }
        }
    }

    fun isHoldingAutoScrollDirection(dragX: Float, dragY: Float, maxRadius: Float): Boolean {
        val dir = autoScrollDir ?: return false
        return EdgeInteractionRules.isHeldInDirection(
            expectedAngle = directionAngle(dir),
            dragX = dragX,
            dragY = dragY,
            maxRadius = maxRadius
        )
    }

    fun isPullingAwayFromEdge(dragX: Float, dragY: Float): Boolean {
        val expectedDir = edgeDir ?: return false
        val currentAngle = Math.toDegrees(atan2(dragY.toDouble(), dragX.toDouble()))
        var angleDiff = abs(currentAngle - directionAngle(expectedDir))
        if (angleDiff > 180.0) angleDiff = 360.0 - angleDiff
        return angleDiff > 90.0
    }

    fun isSameAutoScrollTarget(packageName: String?, windowId: Int): Boolean {
        val expectedPackage = autoScrollPackage ?: return false
        if (packageName != expectedPackage) return false
        val expectedWindow = autoScrollWindowId ?: return true
        return windowId == expectedWindow || windowId < 0
    }

    fun markAutoScrollContentChanged() {
        pendingRefreshAfterAutoScroll = true
    }

    /** 与旧 UI 的 edgeCount=0 / edgeDir=null 行为一致，不清除 rejectedEdgeDir。 */
    fun resetEdgeProgress() {
        edgeCount = 0
        edgeDir = null
    }

    fun cancel() {
        if (state == ScrollState.PLAN_B) {
            stopContinuousScroll()
            return
        }
        hintJob?.cancel()
        actionJob?.cancel()
        state = ScrollState.IDLE
        hintState.value = null
        edgeCount = 0
        edgeDir = null
        rejectedEdgeDir = null
    }

    fun stopContinuousScroll(refreshNodes: Boolean = true) {
        if (state != ScrollState.PLAN_B) return
        val restoreX = autoScrollStartX
        val restoreY = autoScrollStartY
        val contentChanged = pendingRefreshAfterAutoScroll

        actionJob?.cancel()
        state = ScrollState.IDLE
        hintState.value = null
        edgeCount = 0
        edgeDir = null
        rejectedEdgeDir = null
        autoScrollDir = null
        autoScrollPackage = null
        autoScrollWindowId = null
        pendingRefreshAfterAutoScroll = false

        if (refreshNodes) {
            environment.finishContinuousScroll(restoreX, restoreY, contentChanged)
        }
    }

    fun close() {
        hintJob?.cancel()
        actionJob?.cancel()
        hintState.value = null
        state = ScrollState.IDLE
    }

    private fun decideEdgeAction(focus: NavNode, dir: ScrollDir): EdgeAction {
        if (dir.isHorizontal()) {
            val pageNode = resolver.findLocalHorizontalPageNode(focus, dir)
            pageNode?.recycle()
            val activePackage = environment.activePackageProvider()
            val isHome = activePackage != null && activePackage == environment.homePackageProvider()
            val isTaobao = activePackage?.contains("taobao", ignoreCase = true) == true
            return if (pageNode != null || isHome || (isTaobao && isMiddleContentFocus(focus))) {
                EdgeAction(EdgeActionType.PAGE_FLIP, dir)
            } else {
                EdgeAction(EdgeActionType.NONE, dir)
            }
        }

        if (dir.isVertical()) {
            return EdgeAction(EdgeActionType.GESTURE_SCROLL, dir)
        }
        return EdgeAction(EdgeActionType.NONE, dir)
    }

    private fun tryPageFlip(focus: NavNode?, dir: ScrollDir): Boolean {
        if (!dir.isHorizontal()) return false
        val pageNode: AccessibilityNodeInfo? = if (focus != null) {
            resolver.findLocalHorizontalPageNode(focus, dir)
        } else {
            resolver.findAnyHorizontalPageNode(dir)
        }
        if (pageNode != null) {
            val bounds = resolver.boundsOf(pageNode)
            val success = resolver.scrollActionId(pageNode, dir)
                ?.let(pageNode::performAction) == true
            pageNode.recycle()
            if (success) return true
            return gestureDispatcher.dispatchHorizontalPage(dir, bounds)
        }

        val activePackage = environment.activePackageProvider()
        val isTaobao = activePackage?.contains("taobao", ignoreCase = true) == true
        if (focus != null && isTaobao && isMiddleContentFocus(focus)) {
            return gestureDispatcher.dispatchHorizontalPage(dir, taobaoHorizontalGestureBounds(focus))
        }
        val isHome = activePackage != null && activePackage == environment.homePackageProvider()
        return isHome && gestureDispatcher.dispatchHorizontalPage(dir, null)
    }

    private fun startHint(focus: NavNode, action: EdgeAction) {
        if (state != ScrollState.IDLE) return
        val hintType = when (action.type) {
            EdgeActionType.PAGE_FLIP -> HintType.PAGE_FLIP
            EdgeActionType.GESTURE_SCROLL -> HintType.SCROLL
            EdgeActionType.NONE -> return
        }
        state = ScrollState.HINT
        hintJob?.cancel()
        hintJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startMs
                val progress = (
                    elapsed.toFloat() / environment.configProvider().hintDurationMs
                    ).coerceAtMost(1f)
                hintState.value = ScrollHintData(action.dir, progress, hintType)
                if (progress >= 1f) {
                    startEdgeAction(focus, action)
                    return@launch
                }
                delay(16L)
            }
        }
    }

    private fun startEdgeAction(focus: NavNode, action: EdgeAction) {
        hintState.value = null
        actionJob?.cancel()
        actionJob = scope.launch {
            if (action.type == EdgeActionType.PAGE_FLIP) {
                environment.invalidateVisualSession(VisualCacheInvalidationReason.PAGE_FLIP)
                cycleTracker.prepareAttempt(environment.computePageFingerprint())
            }
            if (action.type == EdgeActionType.PAGE_FLIP && tryPageFlip(focus, action.dir)) {
                environment.updateFocus(null)
                environment.resetNavigation()
                cycleTracker.recordSuccess(action.dir, System.currentTimeMillis())
                state = ScrollState.IDLE
                environment.postDelayed(400L) {
                    afterPageFlip(previousFocusX, previousFocusY)
                }
                return@launch
            }

            if (action.type != EdgeActionType.GESTURE_SCROLL) {
                state = ScrollState.IDLE
                return@launch
            }

            beginContinuousScroll(focus, action.dir)
            while (state == ScrollState.PLAN_B && environment.enabledProvider()) {
                gestureDispatcher.dispatchContinuousScroll(action.dir)
                delay(environment.configProvider().scrollGestureDurationMs + 50L)
            }
            if (state == ScrollState.PLAN_B) {
                stopContinuousScroll()
            }
        }
    }

    private fun beginContinuousScroll(focus: NavNode, dir: ScrollDir) {
        autoScrollDir = dir
        autoScrollStartX = focus.centerX
        autoScrollStartY = focus.centerY
        autoScrollPackage = environment.activePackageProvider()
        autoScrollWindowId = focus.windowId
        pendingRefreshAfterAutoScroll = false
        environment.invalidateVisualSession(VisualCacheInvalidationReason.VERTICAL_SCROLL)
        environment.prepareContinuousScroll()
        state = ScrollState.PLAN_B
    }

    private fun afterPageFlip(previousX: Float, previousY: Float) {
        environment.updateFocus(null)
        environment.resetNavigation()
        resetEdgeProgress()
        environment.resetCapabilityProbes(VisualCacheInvalidationReason.PAGE_FLIP)

        val pending = cycleTracker.consumePending()
        environment.rebuildNavMesh(true) { newNodes ->
            val evaluation = cycleTracker.evaluate(
                pending = pending,
                afterFingerprint = environment.computePageFingerprint()
            )
            if (evaluation is PageFlipEvaluation.Circular) {
                tryPageFlip(null, evaluation.reverseDirection)
                environment.postDelayed(350L) {
                    environment.updateFocus(null)
                    environment.resetNavigation()
                    environment.rebuildNavMesh(true) { nodes ->
                        cycleTracker.restorePageFingerprint(evaluation.originalFingerprint)
                        environment.updateFocus(
                            NavMeshFocusResolver.nearest(nodes, previousX, previousY)
                        )
                    }
                }
                return@rebuildNavMesh
            }
            environment.updateFocus(
                NavMeshFocusResolver.nearest(newNodes, previousX, previousY)
            )
        }
    }

    private fun isMiddleContentFocus(focus: NavNode): Boolean {
        return focus.centerY > environment.screenHeight * 0.12f &&
            focus.centerY < environment.screenHeight * 0.86f
    }

    private fun taobaoHorizontalGestureBounds(focus: NavNode): Rect {
        val halfHeight = maxOf(
            (focus.bounds.height() * 1.5f).roundToInt(),
            (environment.screenHeight * 0.06f).roundToInt()
        )
        return Rect(
            0,
            (focus.centerY.roundToInt() - halfHeight).coerceAtLeast(0),
            environment.screenWidth,
            (focus.centerY.roundToInt() + halfHeight).coerceAtMost(environment.screenHeight)
        )
    }

    private fun directionAngle(dir: ScrollDir): Double {
        return when (dir) {
            ScrollDir.UP -> -90.0
            ScrollDir.DOWN -> 90.0
            ScrollDir.LEFT -> 180.0
            ScrollDir.RIGHT -> 0.0
        }
    }
}
