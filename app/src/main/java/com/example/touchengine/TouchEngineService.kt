package com.example.touchengine

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlin.math.roundToInt

class TouchEngineService : AccessibilityService(), AccessibilityTreeParser.Host {

    companion object {
        /** 由 MainActivity 实时写入的外观 & 服务开关，服务启动时从 SharedPreferences 恢复 */
        val liveBallSize    = mutableFloatStateOf(0.65f)
        val liveBallOpacity = mutableFloatStateOf(0.80f)
        val liveEnabled     = mutableStateOf(true)
        val liveObjectEvidenceEnabled = mutableStateOf(false)
        val liveYoloInputSize = mutableStateOf(YoloInputSize.SIZE_640)
        private const val FULL_SCREEN_VISUAL_TEST_MODE = true
    }

    // ── Engine ────────────────────────────────────────────
    internal val navEngine = ContagionNavEngine()
    private val ui       = TouchEngineUI(this)  // UI 层（见 TouchEngineUI.kt）
    private val parser   = AccessibilityTreeParser(this)  // 无障碍树解析（见 AccessibilityTreeParser.kt）
    private val targetFusionEngine = TargetFusionEngine()

    // [FIX 1] Service 级 CoroutineScope，生命周期与 Service 一致。
    // 边缘交互与视觉扫描协程共享该 Service 级 Scope，生命周期与 Service 一致。
    // 不依赖 Compose 的 rememberCoroutineScope，避免重组时后台状态机被意外取消。
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // ── Window ────────────────────────────────────────────
    internal lateinit var windowManager: WindowManager
    internal var joystickView: ComposeView? = null
    private var focusBoxView: ComposeView? = null
    internal lateinit var joystickParams: WindowManager.LayoutParams

    // ── State ─────────────────────────────────────────────
    internal val currentFocusState = mutableStateOf<NavNode?>(null)
    override val screenWidth  = Resources.getSystem().displayMetrics.widthPixels
    override val screenHeight = Resources.getSystem().displayMetrics.heightPixels
    private val homePackageName: String? by lazy {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    // ── 边缘交互配置与状态入口（状态由 EdgeInteractionController 持有）────
    internal var scrollConfig = ScrollConfig()
    private val fallbackScrollHintState = mutableStateOf<ScrollHintData?>(null)
    private lateinit var accessibilityScrollResolver: AccessibilityScrollResolver
    private lateinit var gestureDispatcher: GestureDispatcher
    private lateinit var targetClickExecutor: TargetClickExecutor
    private lateinit var edgeInteractionController: EdgeInteractionController

    internal val scrollState: ScrollState
        get() = if (::edgeInteractionController.isInitialized) {
            edgeInteractionController.state
        } else {
            ScrollState.IDLE
        }

    internal val scrollHintState
        get() = if (::edgeInteractionController.isInitialized) {
            edgeInteractionController.hintState
        } else {
            fallbackScrollHintState
        }
    internal var isJoystickSelecting = false
    internal var selectionPackage: String? = null
    internal var pendingRefreshAfterSelection = false
    private var meshPackage: String? = null
    private var idleContentRefreshPending = false
    private var lastLightValidationAtMs = 0L

    // ── NavMesh 防抖与稳定侦测 ─────────────────────────────
    internal val meshHandler  = Handler(Looper.getMainLooper())
    internal val meshRunnable = Runnable { rebuildNavMesh() }
    private val anchorScanRunnable = Runnable {
        if (isJoystickSelecting) {
            deferRefreshUntilSelectionEnds()
        } else {
            rebuildNavMesh(forceApply = true, scanPass = MeshScanPass.ANCHOR_RESCUE)
        }
    }
    private val holeConfirmRunnable = Runnable {
        if (isJoystickSelecting) {
            deferRefreshUntilSelectionEnds()
        } else if (pendingHoleBands.isNotEmpty()) {
            rebuildNavMesh(
                forceApply = true,
                scanPass = MeshScanPass.HOLE_CONFIRM,
                probeBands = pendingHoleBands
            )
        }
    }
    
    // 动态稳定探测参数
    private val DEBOUNCE_STATE_MS   = 30L   // 状态切页首帧抢跑延迟：30ms
    private val DEBOUNCE_DETECT_MS  = 80L   // 空间变化侦测防抖延迟：80ms
    private val COMPLEX_SCROLL_REFRESH_MS = 420L
    private val COMPLEX_WINDOW_REFRESH_MS = 200L
    private val MAX_SETTLE_WINDOW_MS = 450L // 稳定判定的最大时间上限，防止微小变化导致无法稳定
    
    @Volatile internal var isWindowSettled = true   // 默认窗口已稳定（日常静默待机）
    private var stateChangedTimeMs = 0L            // 记录最近一次切页的时间戳

    // Phase 8C.1: foreground/root reconciliation for app switches.
    // MIUI can emit a window event before the new app root becomes readable, or emit only
    // TYPE_WINDOWS_CHANGED. A one-shot rebuild then samples the old/null root and is lost.
    private var foregroundReconcileGeneration = 0
    private var foregroundReconcileAttempt = 0
    private var pendingForegroundExpectedPackage: String? = null
    private var pendingForegroundReason: String = "unknown"
    private var lastForegroundRefreshKey: String? = null
    private var lastForegroundRefreshAtMs = 0L
    private val foregroundReconcileDelaysMs = longArrayOf(
        0L, 40L, 90L, 160L, 280L, 450L, 700L, 1_000L
    )
    private val foregroundWatchdogDelaysMs = longArrayOf(180L, 420L, 850L, 1_400L)
    
    private lateinit var navMeshCoordinator: NavMeshCoordinator
    internal val currentNodes: List<NavNode>
        get() = navMeshCoordinator.currentNodes
    private val stableMesh: NavMeshSnapshot?
        get() = navMeshCoordinator.stableSnapshot
    private val visualHoleState = mutableStateOf<List<Rect>>(emptyList())
    internal val dirtyReasons = mutableSetOf<RefreshDirtyReason>()
    private var selectionDeferredRefreshes = 0
    internal var slowGestureIntervals = 0
    override val screenArea = screenWidth * screenHeight
    private var pendingAnchorScanKey: String? = null
    private var pendingHoleBands: Set<EdgeInteractionRules.GenericContentBand> = emptySet()
    private var confirmedHoleBands: Set<EdgeInteractionRules.GenericContentBand> = emptySet()
    private val holeConfirmAttempts = mutableMapOf<EdgeInteractionRules.GenericContentBand, Int>()
    private val persistentVisualCanvas = PersistentVisualCanvas()
    private lateinit var visualRecognizer: VisualRecognizer
    private lateinit var visualScanCoordinator: VisualScanCoordinator
    private lateinit var pageInvalidationController: PageInvalidationController

    private var lastTaobaoParseDiagnostic: String? = null
    private var lastDouyinParseDiagnostic: String? = null

    // =====================================================
    // Service lifecycle
    // =====================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // 从 SharedPreferences 恢复外观与服务开关状态
        val prefs = getSharedPreferences("TouchEnginePrefs", Context.MODE_PRIVATE)
        liveBallSize.floatValue    = prefs.getFloat("ball_size", 0.65f)
        liveBallOpacity.floatValue = prefs.getFloat("ball_opacity", 0.80f)
        liveEnabled.value          = prefs.getBoolean("service_running", true)
        liveYoloInputSize.value    = YoloInputSize.fromPreference(prefs.getString("yolo_input_size", null))
        navMeshCoordinator = NavMeshCoordinator(
            scope = serviceScope,
            maxConnectDistance = (screenHeight * 0.45f).coerceAtLeast(600f),
            selectionActiveProvider = { isJoystickSelecting }
        )
        accessibilityScrollResolver = AccessibilityScrollResolver(
            windowsProvider = ::accessibilityWindowsSnapshot,
            primaryRootProvider = ::obtainPrimaryContentRoot,
            servicePackageProvider = { packageName },
            homePackageProvider = { homePackageName },
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        gestureDispatcher = GestureDispatcher(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            configProvider = { scrollConfig },
            dispatch = { gesture: GestureDescription -> dispatchGesture(gesture, null, null) }
        )
        targetClickExecutor = TargetClickExecutor(
            object : TargetClickExecutor.Host {
                override val screenWidth: Int
                    get() = this@TouchEngineService.screenWidth
                override val screenHeight: Int
                    get() = this@TouchEngineService.screenHeight
                override val density: Float
                    get() = resources.displayMetrics.density

                override fun activeTargetPackage(): String? =
                    this@TouchEngineService.activeTargetPackage()

                override fun obtainPrimaryContentRoot(): AccessibilityNodeInfo? =
                    this@TouchEngineService.obtainPrimaryContentRoot()

                override fun dispatchGesture(gesture: GestureDescription): Boolean =
                    this@TouchEngineService.dispatchGesture(gesture, null, null)
            }
        )
        edgeInteractionController = EdgeInteractionController(
            resolver = accessibilityScrollResolver,
            gestureDispatcher = gestureDispatcher,
            scope = serviceScope,
            environment = EdgeInteractionEnvironment(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                configProvider = { scrollConfig },
                enabledProvider = { liveEnabled.value },
                activePackageProvider = ::activeTargetPackage,
                homePackageProvider = { homePackageName },
                currentFocusProvider = { currentFocusState.value },
                updateFocus = { node: NavNode? -> currentFocusState.value = node },
                resetNavigation = { navEngine.reset() },
                computePageFingerprint = ::computeScreenFingerprint,
                invalidateVisualSession = { reason: VisualCacheInvalidationReason ->
                    resetCapabilityProbes(invalidationReason = reason)
                },
                prepareContinuousScroll = {
                    pendingRefreshAfterSelection = false
                    meshHandler.removeCallbacks(meshRunnable)
                    navMeshCoordinator.invalidatePendingBuilds()
                    currentFocusState.value = null
                    navEngine.reset()
                },
                finishContinuousScroll = { restoreX: Float, restoreY: Float, contentChanged: Boolean ->
                    isWindowSettled = false
                    stateChangedTimeMs = System.currentTimeMillis()
                    meshHandler.removeCallbacks(meshRunnable)
                    resetCapabilityProbes(
                        invalidationReason = VisualCacheInvalidationReason.AUTO_SCROLL_FINISHED
                    )
                    val refreshDelay = if (contentChanged) DEBOUNCE_DETECT_MS else 0L
                    meshHandler.postDelayed({
                        rebuildNavMesh(forceApply = true) { nodes ->
                            currentFocusState.value =
                                NavMeshFocusResolver.nearest(nodes, restoreX, restoreY)
                        }
                    }, refreshDelay)
                },
                resetCapabilityProbes = { reason: VisualCacheInvalidationReason ->
                    resetCapabilityProbes(invalidationReason = reason)
                },
                rebuildNavMesh = { forceApply: Boolean, onDone: (List<NavNode>) -> Unit ->
                    rebuildNavMesh(forceApply = forceApply, onDone = onDone)
                },
                postDelayed = { delayMs: Long, block: () -> Unit ->
                    meshHandler.postDelayed({ block() }, delayMs)
                }
            )
        )
        visualRecognizer = VisualRecognizer(this)
        visualScanCoordinator = VisualScanCoordinator(
            recognizer = visualRecognizer,
            cache = persistentVisualCanvas,
            screenshotProvider = createVisualScreenshotProvider(),
            scope = serviceScope,
            handler = meshHandler,
            runtimeProvider = ::buildVisualScanRuntime,
            requestProvider = ::buildVisualScanRequest,
            installVisualNodes = ::installVisualNodesInStableMesh,
            invalidateActiveVisualNodes = ::stripVisionNodesFromActiveMeshes,
            clearVisualHoles = { visualHoleState.value = emptyList() }
        )
        pageInvalidationController = PageInvalidationController(
            handler = meshHandler,
            rootProvider = ::obtainPrimaryContentRoot,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            onDecision = ::handlePageInvalidationDecision
        )
        initFloatingUI()
        visualScanCoordinator.prewarm()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        // 先关闭边缘交互状态机，再取消 Service 共享协程。
        if (::edgeInteractionController.isInitialized) {
            edgeInteractionController.close()
        }
        serviceScope.cancel()
        foregroundReconcileGeneration++
        meshHandler.removeCallbacks(meshRunnable)
        meshHandler.removeCallbacks(anchorScanRunnable)
        meshHandler.removeCallbacks(holeConfirmRunnable)
        if (::pageInvalidationController.isInitialized) {
            pageInvalidationController.close()
        }
        if (::visualScanCoordinator.isInitialized) {
            visualScanCoordinator.close()
        } else if (::visualRecognizer.isInitialized) {
            visualRecognizer.close()
        }
        joystickView?.let { windowManager.removeView(it) }
        focusBoxView?.let { windowManager.removeView(it) }
    }

    // =====================================================
    // 滚动逻辑（方案A + 方案B）
    // =====================================================

    /** 从 SharedPreferences 刷新滚动配置（每次 NavMesh 重建时调用）*/
    internal fun refreshScrollConfig() {
        scrollConfig = ScrollConfig.load(this)
    }

    /**
     * 判断某角度方向是否真的没有邻居（30度窄扇区确认，防误判）。
     *
     * [FIX 9] 孤立节点（完全没有邻居）不触发边缘检测：
     * 孤立节点会对所有方向返回 true，导致任何方向推杆都误触翻页。
     * 加此判断后，孤立节点静默忽略，不参与翻页或滑动触发。
     */
    internal fun isRealEdge(node: NavNode, angleDeg: Double): Boolean {
        if (node.neighbors.isEmpty()) return false  // 孤立节点，不触发
        for (nb in node.neighbors) {
            val dx = nb.centerX - node.centerX
            val dy = nb.centerY - node.centerY
            val nbAngle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble()))
            var diff = kotlin.math.abs(angleDeg - nbAngle)
            if (diff > 180) diff = 360 - diff
            if (diff <= 30.0) return false
        }
        return true
    }

    /** 根据角度判断滚动方向 */
    internal fun dirFromAngle(angleDeg: Double): ScrollDir {
        return when {
            angleDeg > -45  && angleDeg <= 45  -> ScrollDir.RIGHT
            angleDeg > 45   && angleDeg <= 135 -> ScrollDir.DOWN
            angleDeg > 135  || angleDeg <= -135 -> ScrollDir.LEFT
            else                                -> ScrollDir.UP
        }
    }

    internal fun handleEdge(focus: NavNode, dir: ScrollDir, ratio: Float) {
        edgeInteractionController.handleHeldEdge(focus, dir, ratio)
    }

    internal fun handleFlickEdge(focus: NavNode, dir: ScrollDir, ratio: Float) {
        edgeInteractionController.handleFlickEdge(focus, dir, ratio)
    }

    internal fun isHoldingAutoScrollDirection(
        dragX: Float,
        dragY: Float,
        maxRadius: Float
    ): Boolean {
        return edgeInteractionController.isHoldingAutoScrollDirection(dragX, dragY, maxRadius)
    }

    internal fun isPullingAwayFromEdge(dragX: Float, dragY: Float): Boolean {
        return edgeInteractionController.isPullingAwayFromEdge(dragX, dragY)
    }

    internal fun resetEdgeTracking() {
        edgeInteractionController.resetEdgeProgress()
    }

    internal fun cancelScroll() {
        edgeInteractionController.cancel()
    }

    internal fun stopPlanB(refreshNodes: Boolean = true) {
        edgeInteractionController.stopContinuousScroll(refreshNodes)
    }

    private fun isSameAutoScrollTarget(event: AccessibilityEvent): Boolean {
        return edgeInteractionController.isSameAutoScrollTarget(
            packageName = event.packageName?.toString(),
            windowId = event.windowId
        )
    }

    private fun markAutoScrollContentChanged() {
        edgeInteractionController.markAutoScrollContentChanged()
    }

    /**
     * 计算当前屏幕内容指纹，用于检测 Circular Launcher 的循环翻页。
     * 采集最多 25 个可见节点的 contentDescription/text，排序后拼接。
     * 相同页面内容 → 相同指纹；不同页面 → 不同指纹。
     */
    private fun computeScreenFingerprint(): String {
        return try {
            val root = obtainPrimaryContentRoot() ?: return ""
            val texts = mutableListOf<String>()
            fun traverse(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 6 || texts.size >= 25) return
                val desc = node.contentDescription?.toString()?.trim()
                        ?: node.text?.toString()?.trim()
                if (!desc.isNullOrBlank()) texts.add(desc)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        traverse(child, depth + 1)
                        child.recycle()
                    }
                }
            }
            traverse(root, 0)
            root.recycle()
            texts.sorted().joinToString("|")
        } catch (e: Exception) { "" }
    }

    override fun obtainPrimaryContentRoot(): AccessibilityNodeInfo? {
        val sortedWindows = windows?.sortedByDescending { it.layer }.orEmpty()
        for (window in sortedWindows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() == packageName) {
                root.recycle()
                continue
            }
            return root
        }
        val root = rootInActiveWindow ?: return null
        return if (root.packageName?.toString() == packageName) {
            root.recycle()
            null
        } else {
            root
        }
    }

    override fun keyboardTop(): Int? {
        val keyboardWindow = windows?.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } ?: return null
        val bounds = Rect()
        keyboardWindow.getBoundsInScreen(bounds)
        return bounds.top.takeIf { it in 1 until screenHeight }
    }

    private fun isLikelyKeyboardCandidate(bounds: Rect): Boolean {
        val top = keyboardTop() ?: return false
        return bounds.centerY() >= top - 8 && bounds.bottom > top
    }

    private fun douyinSceneTag(targetPackage: String?): String {
        return classifyDouyinScene(targetPackage).name
    }

    private fun classifyDouyinScene(targetPackage: String?): DouyinScene {
        if (!EdgeInteractionRules.isDouyinPackage(targetPackage)) return DouyinScene.GENERAL
        if (isInputMethodWindowVisible() || hasDouyinTextPageAccessibilitySignal(targetPackage)) {
            return DouyinScene.SEARCH_TEXT
        }
        return if (screenHeight >= screenWidth && hasDouyinVideoFeedAccessibilitySignal(targetPackage)) {
            DouyinScene.VIDEO_FEED_PORTRAIT
        } else {
            DouyinScene.GENERAL
        }
    }

    private fun currentAccessibilityLabels(targetPackage: String?, limit: Int = 80): List<String> {
        val nodes = stableMesh
            ?.takeIf { it.targetPackage == targetPackage }
            ?.nodes
            ?: currentNodes
        val meshLabels = nodes.asSequence()
            .filterNot { it.origin == NavNodeOrigin.VISION }
            .map { normalizedVisualText(it.description.orEmpty()) }
            .filter { it.isNotBlank() }
            .take(limit)
            .toList()
        return meshLabels.ifEmpty { currentRootAccessibilityLabels(targetPackage, limit) }
    }

    private fun currentRootAccessibilityLabels(targetPackage: String?, limit: Int = 80): List<String> {
        val root = obtainPrimaryContentRoot() ?: return emptyList()
        val labels = mutableListOf<String>()
        try {
            if (targetPackage != null && root.packageName?.toString() != targetPackage) {
                return emptyList()
            }
            fun traverse(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 8 || labels.size >= limit) return
                val text = node.contentDescription?.toString()?.trim()
                    ?: node.text?.toString()?.trim()
                if (!text.isNullOrBlank()) labels.add(normalizedVisualText(text))
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    try {
                        traverse(child, depth + 1)
                    } finally {
                        child.recycle()
                    }
                }
            }
            traverse(root, 0)
        } catch (e: Exception) {
            Log.d("TouchEngineDouyin", "root label scan failed: ${e.message}")
        } finally {
            root.recycle()
        }
        return labels.filter { it.isNotBlank() }.take(limit)
    }

    private fun hasDouyinVideoFeedAccessibilitySignal(targetPackage: String?): Boolean {
        if (!EdgeInteractionRules.isDouyinPackage(targetPackage)) return false
        val labels = currentAccessibilityLabels(targetPackage)
        if (labels.isEmpty()) return false
        val joined = labels.joinToString("|")
        val topTabs = listOf(
            "\u76f4\u64ad", "\u7cbe\u9009", "\u540c\u57ce", "\u5173\u6ce8", "\u63a8\u8350"
        ).count { joined.contains(it) }
        val bottomTabs = listOf(
            "\u9996\u9875", "\u670b\u53cb", "\u6d88\u606f", "\u6211"
        ).count { joined.contains(it) }
        val railActions = listOf(
            "\u70b9\u8d5e", "\u8bc4\u8bba", "\u6536\u85cf", "\u5206\u4eab", "\u53d6\u6d88\u9759\u97f3"
        ).count { joined.contains(it) }
        return (topTabs >= 2 && bottomTabs >= 2) ||
            (topTabs >= 2 && railActions >= 2) ||
            (bottomTabs >= 2 && railActions >= 2)
    }

    private fun douyinRegionPolicy(scene: DouyinScene): VisualRegionPolicy {
        if (scene == DouyinScene.SEARCH_TEXT) {
            val top = keyboardTop()
            return VisualRegionPolicy(
                blockedRegions = if (top != null) listOf(Rect(0, top - 8, screenWidth, screenHeight)) else emptyList()
            )
        }
        return visualRegionPolicyFor(scene, screenWidth, screenHeight)
    }

    private fun isDouyinVideoDeepTarget(node: NavNode): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        if (screenHeight < screenWidth) return false
        val bounds = node.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val xRatio = node.centerX / screenWidth.toFloat()
        val yRatio = node.centerY / screenHeight.toFloat()
        val areaRatio = bounds.areaLong() / screenArea.toFloat().coerceAtLeast(1f)
        if (areaRatio > 0.20f) return false
        val topNav = yRatio in 0.035f..0.18f &&
            bounds.height() <= screenHeight * 0.14f &&
            bounds.width() <= screenWidth * 0.36f
        val rightRail = xRatio >= 0.70f &&
            yRatio in 0.28f..0.94f &&
            bounds.height() <= screenHeight * 0.18f &&
            bounds.width() <= screenWidth * 0.36f
        val bottomTab = yRatio >= 0.86f &&
            bounds.height() <= screenHeight * 0.16f &&
            bounds.width() <= screenWidth * 0.36f
        val floatingAction = xRatio in 0.35f..0.78f &&
            yRatio in 0.76f..0.92f &&
            bounds.height() <= screenHeight * 0.12f &&
            bounds.width() <= screenWidth * 0.52f
        if (!(topNav || rightRail || bottomTab || floatingAction)) return false
        val label = normalizedVisualText(node.description.orEmpty())
        if (label.length > 18 && !(rightRail || floatingAction)) return false
        if (label.contains("#") && !rightRail) return false
        return true
    }

    private fun hasDouyinVideoAccessibilityCoverage(nodes: List<NavNode>): Boolean {
        val accessibilityNodes = nodes.filter { it.origin == NavNodeOrigin.ACCESSIBILITY }
        val top = accessibilityNodes.count {
            it.centerY in (screenHeight * 0.035f)..(screenHeight * 0.18f)
        }
        val right = accessibilityNodes.count {
            it.centerX >= screenWidth * 0.70f &&
                it.centerY in (screenHeight * 0.28f)..(screenHeight * 0.94f)
        }
        val bottom = accessibilityNodes.count {
            it.centerY >= screenHeight * 0.86f
        }
        return top >= 2 && right >= 2 && bottom >= 2
    }

    private fun shouldUseDouyinVideoAccessibility(targetPackage: String?): Boolean {
        return EdgeInteractionRules.isDouyinPackage(targetPackage) &&
            classifyDouyinScene(targetPackage) == DouyinScene.VIDEO_FEED_PORTRAIT
    }

    private fun isIgnoredWindowEvent(event: AccessibilityEvent): Boolean {
        val eventPackage = event.packageName?.toString()
        if (eventPackage == packageName) return true
        val eventWindow = windows?.firstOrNull { it.id == event.windowId }
        if (eventWindow?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            eventWindow?.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
            eventWindow?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        ) {
            return true
        }

        // A genuine app transition often arrives before activeTargetPackage() has moved from
        // the outgoing app. Rejecting it because the packages temporarily disagree loses the
        // only refresh event. Transition events are reconciled asynchronously instead.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return false
        }

        val contentPackage = activeTargetPackage()
        return eventPackage != null && contentPackage != null && eventPackage != contentPackage
    }

    override val servicePackageName: String get() = packageName?.toString() ?: ""
    override fun accessibilityWindowsSnapshot(): List<android.view.accessibility.AccessibilityWindowInfo>? =
        super.getWindows()
    override fun lastTaobaoDiagnostic(): String? = lastTaobaoParseDiagnostic
    override fun lastDouyinDiagnostic(): String? = lastDouyinParseDiagnostic
    override fun updateTaobaoDiagnostic(value: String) { lastTaobaoParseDiagnostic = value }
    override fun updateDouyinDiagnostic(value: String) { lastDouyinParseDiagnostic = value }

    private fun activeTargetIdentity(): Pair<String?, Int> {
        val root = obtainPrimaryContentRoot() ?: return null to -1
        return try {
            root.packageName?.toString() to root.windowId
        } finally {
            root.recycle()
        }
    }

    override fun activeTargetPackage(): String? {
        return activeTargetIdentity().first
    }

    private fun isDefaultHomePackage(packageName: CharSequence?): Boolean {
        val targetPackage = packageName?.toString() ?: return false
        return targetPackage == homePackageName
    }

    private fun isDefaultHomeActive(): Boolean = isDefaultHomePackage(activeTargetPackage())

    private fun isTaobaoPackage(packageName: CharSequence?): Boolean {
        return packageName?.toString()?.contains("taobao", ignoreCase = true) == true
    }

    private fun isTaobaoActive(): Boolean = isTaobaoPackage(activeTargetPackage())

    private fun isDouyinPackage(packageName: CharSequence?): Boolean {
        return EdgeInteractionRules.isDouyinPackage(packageName?.toString())
    }

    private fun isSameSelectionTarget(event: AccessibilityEvent): Boolean {
        val expectedPackage = selectionPackage ?: return false
        return event.packageName?.toString() == expectedPackage
    }

    private fun shouldInvalidateVisualCacheForScroll(event: AccessibilityEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return event.scrollDeltaX != 0 ||
                event.scrollDeltaY != 0 ||
                event.scrollX >= 0 ||
                event.scrollY >= 0 ||
                event.fromIndex >= 0 ||
                event.toIndex >= 0
        }
        // Phase 8A 以正确性优先：收到真实内容窗口的滚动事件就结束旧视觉会话。
        return true
    }

    private fun isLikelyIndependentPopup(windowId: Int): AccessibilityWindowInfo? {
        val knownIds = navMeshCoordinator.activeWindowIds
        if (windowId < 0 || windowId in knownIds) return null
        val window = windows?.firstOrNull { it.id == windowId } ?: return null
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
            (!window.isActive && !window.isFocused)
        ) return null
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        val area = bounds.width() * bounds.height()
        return window.takeIf { area in (screenArea * 0.08f).toInt()..(screenArea * 0.85f).toInt() }
    }

    private fun activatePopupMeshIfPresent(windowId: Int): Boolean {
        val window = isLikelyIndependentPopup(windowId) ?: return false
        val root = window.root ?: return false
        val nodes = try {
            collectPopupNodes(root)
        } finally {
            root.recycle()
        }
        if (nodes.isEmpty()) return false
        val activation = navMeshCoordinator.activateOverlay(
            nodes = nodes,
            targetPackage = selectionPackage,
            windowId = windowId,
            nowMs = System.currentTimeMillis()
        )
        if (!activation.activated) return false
        currentFocusState.value = activation.initialFocus
        joystickView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        Log.d("TouchEnginePerf", "overlay takeover window=$windowId nodes=${nodes.size}")
        return true
    }

    private fun collectPopupNodes(root: AccessibilityNodeInfo): List<NavNode> {
        val result = mutableListOf<NavNode>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(AccessibilityNodeInfo.obtain(root) to 0)
        var visited = 0
        val deadlineNs = System.nanoTime() + 6_000_000L
        try {
            while (queue.isNotEmpty() && visited < 120 && result.size < 40 &&
                System.nanoTime() < deadlineNs
            ) {
                val (node, depth) = queue.removeFirst()
                try {
                    visited++
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val label = node.contentDescription?.toString() ?: node.text?.toString()
                    if (node.isVisibleToUser && node.isClickable && rect.width() > 0 && rect.height() > 0) {
                        result.add(NavNode(rect, label, node.windowId))
                    }
                    if (depth < 5) {
                        for (index in 0 until node.childCount) {
                            node.getChild(index)?.let { child -> queue.add(child to depth + 1) }
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().first.recycle()
        }
        return result.distinctBy { "${it.windowId}:${it.bounds}" }
    }

    private fun deferRefreshUntilSelectionEnds() {
        dirtyReasons.add(RefreshDirtyReason.SELECTION)
        selectionDeferredRefreshes++
        if (pendingRefreshAfterSelection) return
        pendingRefreshAfterSelection = true
        meshHandler.removeCallbacks(meshRunnable)
        if (navMeshCoordinator.hasCurrentNodes) {
            navMeshCoordinator.invalidatePendingBuilds() // 已有可用图时，本次手势不允许动态结果覆盖它
        }
    }

    internal fun refreshAfterSelectionIfNeeded() {
        // Phase 8C: visual scan recovery is owned by VisualScanCoordinator and must not
        // depend on accessibility/window events setting pendingRefreshAfterSelection.
        visualScanCoordinator.resumeAfterSelection()

        if (!pendingRefreshAfterSelection || scrollState == ScrollState.PLAN_B) return
        pendingRefreshAfterSelection = false
        isWindowSettled = false
        stateChangedTimeMs = System.currentTimeMillis()
        meshHandler.removeCallbacks(meshRunnable)
        meshHandler.postDelayed(meshRunnable, DEBOUNCE_DETECT_MS)
    }

    internal fun beginNavMeshGesture(
        targetPackage: String?,
        nowMs: Long = System.currentTimeMillis()
    ): GestureMeshSession {
        val session = navMeshCoordinator.beginGesture(targetPackage, nowMs)
        visualScanCoordinator.showCommittedMesh(session.nodes, "ActiveMesh")
        return session
    }

    internal fun activeNavGestureNodes(): List<NavNode> {
        return navMeshCoordinator.activeNodes
    }

    internal fun endNavMeshGesture() {
        navMeshCoordinator.endGesture()
        visualScanCoordinator.showCommittedMesh(
            navMeshCoordinator.stableSnapshot?.nodes.orEmpty(),
            "StableMesh"
        )
    }


    // =====================================================
    // Foreground surface reconciliation (Phase 8C.1)
    // =====================================================

    private fun scheduleForegroundReconciliation(
        event: AccessibilityEvent,
        reason: String
    ) {
        val eventWindow = windows?.firstOrNull { it.id == event.windowId }
        val expectedPackage = event.packageName?.toString()?.takeIf {
            it != packageName &&
                eventWindow?.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                eventWindow?.type != AccessibilityWindowInfo.TYPE_SYSTEM &&
                eventWindow?.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        }

        foregroundReconcileGeneration++
        foregroundReconcileAttempt = 0
        pendingForegroundExpectedPackage = expectedPackage
        pendingForegroundReason = reason
        val generation = foregroundReconcileGeneration
        meshHandler.post { attemptForegroundReconciliation(generation) }
    }

    private fun attemptForegroundReconciliation(generation: Int) {
        if (generation != foregroundReconcileGeneration) return
        if (isJoystickSelecting || scrollState == ScrollState.PLAN_B) {
            postForegroundReconcileRetry(generation, "interactionBusy")
            return
        }

        val (activePackage, activeWindowId) = activeTargetIdentity()
        val expectedPackage = pendingForegroundExpectedPackage
        if (activePackage.isNullOrBlank()) {
            postForegroundReconcileRetry(
                generation,
                "rootNotReady active=$activePackage expected=$expectedPackage"
            )
            return
        }
        // Some ROMs report the outgoing package in the last WINDOWS_CHANGED event.
        // Prefer the expected package briefly, then trust the actually active root.
        if (expectedPackage != null &&
            activePackage != expectedPackage &&
            foregroundReconcileAttempt < 4
        ) {
            postForegroundReconcileRetry(
                generation,
                "waitingExpected active=$activePackage expected=$expectedPackage"
            )
            return
        }

        val snapshot = stableMesh
        val packageChanged = snapshot?.targetPackage != activePackage
        val windowChanged = snapshot != null &&
            activeWindowId >= 0 &&
            activeWindowId !in snapshot.windowIds
        val missingStableMesh = snapshot == null
        val missingVisualScan = !visualScanCoordinator.hasCachedNodes &&
            !visualScanCoordinator.hasScanInFlight

        if (packageChanged || windowChanged || missingStableMesh) {
            val invalidationReason = if (snapshot?.targetPackage != null &&
                snapshot.targetPackage != activePackage
            ) {
                VisualCacheInvalidationReason.APP_SWITCH
            } else {
                VisualCacheInvalidationReason.WINDOW_REPLACED
            }
            forceForegroundSurfaceRefresh(
                targetPackage = activePackage,
                windowId = activeWindowId,
                invalidationReason = invalidationReason,
                diagnostic = pendingForegroundReason
            )
            return
        }

        if (missingVisualScan) {
            Log.d(
                "TouchEngineSurface",
                "foreground visual watchdog pkg=$activePackage window=$activeWindowId reason=$pendingForegroundReason"
            )
            updateVisualHoleDisplay()
        }
    }

    private fun postForegroundReconcileRetry(generation: Int, reason: String) {
        if (generation != foregroundReconcileGeneration) return
        if (foregroundReconcileAttempt >= foregroundReconcileDelaysMs.lastIndex) {
            Log.w(
                "TouchEngineSurface",
                "foreground reconcile exhausted reason=$reason expected=$pendingForegroundExpectedPackage"
            )
            return
        }
        foregroundReconcileAttempt++
        val delayMs = foregroundReconcileDelaysMs[foregroundReconcileAttempt]
        meshHandler.postDelayed(
            { attemptForegroundReconciliation(generation) },
            delayMs
        )
        Log.d(
            "TouchEngineSurface",
            "foreground reconcile retry=$foregroundReconcileAttempt delay=${delayMs}ms $reason"
        )
    }

    private fun forceForegroundSurfaceRefresh(
        targetPackage: String,
        windowId: Int,
        invalidationReason: VisualCacheInvalidationReason,
        diagnostic: String
    ) {
        val now = SystemClock.elapsedRealtime()
        val key = "$targetPackage:$windowId"
        val duplicate = key == lastForegroundRefreshKey &&
            now - lastForegroundRefreshAtMs < 240L
        if (!duplicate) {
            lastForegroundRefreshKey = key
            lastForegroundRefreshAtMs = now
            currentFocusState.value = null
            navMeshCoordinator.clearAll()
            resetCapabilityProbes(
                invalidationReason = invalidationReason,
                event = null
            )
            idleContentRefreshPending = false
            navEngine.reset()
            if (scrollState == ScrollState.HINT) cancelScroll()
            isWindowSettled = false
            stateChangedTimeMs = System.currentTimeMillis()
            meshHandler.removeCallbacks(meshRunnable)
            meshHandler.postDelayed(meshRunnable, DEBOUNCE_STATE_MS)
            Log.d(
                "TouchEngineSurface",
                "foreground refresh pkg=$targetPackage window=$windowId reason=$invalidationReason source=$diagnostic"
            )
        }
        armForegroundRefreshWatchdog(
            expectedPackage = targetPackage,
            generation = foregroundReconcileGeneration
        )
    }

    private fun armForegroundRefreshWatchdog(
        expectedPackage: String,
        generation: Int
    ) {
        foregroundWatchdogDelaysMs.forEach { delayMs ->
            meshHandler.postDelayed(watchdog@{
                if (generation != foregroundReconcileGeneration ||
                    isJoystickSelecting ||
                    scrollState != ScrollState.IDLE
                ) {
                    return@watchdog
                }
                val activePackage = activeTargetPackage()
                if (activePackage != expectedPackage) return@watchdog

                if (stableMesh?.targetPackage != expectedPackage) {
                    meshHandler.removeCallbacks(meshRunnable)
                    meshHandler.post(meshRunnable)
                }
                if (!visualScanCoordinator.hasCachedNodes &&
                    !visualScanCoordinator.hasScanInFlight
                ) {
                    updateVisualHoleDisplay()
                }
            }, delayMs)
        }
    }


    // =====================================================
    // Accessibility Events
    // =====================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            scheduleForegroundReconciliation(
                event = event,
                reason = if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                    "TYPE_WINDOWS_CHANGED"
                } else {
                    "TYPE_WINDOW_STATE_CHANGED"
                }
            )
        }

        if (isIgnoredWindowEvent(event)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // The asynchronous reconciliation owns this event. It waits for the new root
                // instead of sampling the outgoing/null root once and losing the refresh.
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (scrollState == ScrollState.PLAN_B && isSameAutoScrollTarget(event)) {
                    markAutoScrollContentChanged()
                    return
                }
                if (isJoystickSelecting) {
                    if (!activatePopupMeshIfPresent(event.windowId)) deferRefreshUntilSelectionEnds()
                    return
                }
                if (scrollState == ScrollState.PLAN_B) stopPlanB(refreshNodes = false)

                val changedPackage = event.packageName?.toString()
                val existingMesh = stableMesh
                val samePackage = existingMesh != null &&
                    changedPackage == existingMesh.targetPackage
                val independentPopup = isLikelyIndependentPopup(event.windowId) != null
                val staysInVisibleContentWindow = existingMesh != null &&
                    samePackage &&
                    (event.windowId < 0 || event.windowId in existingMesh.windowIds)
                if (staysInVisibleContentWindow || (samePackage && !independentPopup)) {
                    // Preserve the displayed graph while same-window noise settles.
                    // The replacement graph re-anchors the selected node when ready.
                    dirtyReasons.add(RefreshDirtyReason.WINDOW)
                    resetCapabilityProbes(
                        invalidationReason = VisualCacheInvalidationReason.WINDOW_REPLACED,
                        event = event
                    )
                    requestVisualRefreshForContentChange(event, DEBOUNCE_DETECT_MS, clearBeforeProbe = false)
                    navMeshCoordinator.clearOverlay()
                    isWindowSettled = false
                    stateChangedTimeMs = System.currentTimeMillis()
                    meshHandler.removeCallbacks(meshRunnable)
                    idleContentRefreshPending = false
                    meshHandler.postDelayed(meshRunnable, DEBOUNCE_STATE_MS)
                    return
                }
                // App switches and confirmed popup surfaces must not display stale targets.
                // Same-package window id churn was handled above as content noise.
                currentFocusState.value = null
                navMeshCoordinator.clearAll()
                val invalidationReason = when {
                    existingMesh != null && changedPackage != existingMesh.targetPackage ->
                        VisualCacheInvalidationReason.APP_SWITCH
                    independentPopup -> VisualCacheInvalidationReason.POPUP_CHANGE
                    else -> VisualCacheInvalidationReason.WINDOW_REPLACED
                }
                resetCapabilityProbes(invalidationReason = invalidationReason, event = event)
                idleContentRefreshPending = false
                navEngine.reset()
                if (scrollState == ScrollState.HINT) cancelScroll()
                
                // 激活新窗口动态稳定探测机制
                isWindowSettled = false
                stateChangedTimeMs = System.currentTimeMillis()
                
                // 首帧抢跑：30ms 后极速执行第一次基础建图（对秒开设备最友好）
                meshHandler.removeCallbacks(meshRunnable)
                idleContentRefreshPending = false
                // Once old targets are hidden, rebuild a light anchor mesh promptly; expanded content
                // work remains an idle follow-up and cannot stall an active joystick gesture.
                val stateDelay = DEBOUNCE_STATE_MS
                meshHandler.postDelayed(meshRunnable, stateDelay)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (scrollState == ScrollState.PLAN_B) {
                    markAutoScrollContentChanged()
                    return
                }
                if (isJoystickSelecting) {
                    if (isSameSelectionTarget(event)) deferRefreshUntilSelectionEnds()
                    return
                }
                // Phase 8A：滚动发生时立即结束旧视觉会话，禁止旧截图/旧节点进入新视口。
                if (scrollState == ScrollState.HINT) cancelScroll()
                val invalidateVisualCache = shouldInvalidateVisualCacheForScroll(event)
                val delayMs = if (EdgeInteractionRules.isHighFrequencyPackage(event.packageName?.toString())) {
                    COMPLEX_SCROLL_REFRESH_MS
                } else {
                    DEBOUNCE_DETECT_MS
                }
                if (invalidateVisualCache) {
                    resetCapabilityProbes(
                        invalidationReason = VisualCacheInvalidationReason.VERTICAL_SCROLL,
                        event = event
                    )
                    requestVisualRefreshForContentChange(event, delayMs, clearBeforeProbe = false)
                } else if (visualScanCoordinator.hasCachedNodes) {
                    logRetainedVisualCache("SCROLL_NO_MOVEMENT", event)
                }
                isWindowSettled = false
                stateChangedTimeMs = System.currentTimeMillis()
                meshHandler.removeCallbacks(meshRunnable)
                idleContentRefreshPending = false
                dirtyReasons.add(RefreshDirtyReason.SCROLL)
                meshHandler.postDelayed(meshRunnable, delayMs)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (scrollState == ScrollState.PLAN_B) {
                    markAutoScrollContentChanged()
                    return
                }
                if (isJoystickSelecting) {
                    if (isSameSelectionTarget(event)) deferRefreshUntilSelectionEnds()
                    return
                }
                if (::pageInvalidationController.isInitialized) {
                    pageInvalidationController.submit(event)
                } else {
                    // Service startup fallback. Normally the controller is initialized before
                    // any event reaches this branch.
                    dirtyReasons.add(RefreshDirtyReason.CONTENT)
                    requestVisualRefreshForContentChange(
                        event,
                        DEBOUNCE_DETECT_MS,
                        clearBeforeProbe = false
                    )
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (isJoystickSelecting || scrollState == ScrollState.PLAN_B) return
                resetCapabilityProbes(
                    invalidationReason = VisualCacheInvalidationReason.USER_CLICK,
                    event = event
                )
                isWindowSettled = false
                stateChangedTimeMs = System.currentTimeMillis()
                meshHandler.removeCallbacks(meshRunnable)
                idleContentRefreshPending = false
                requestVisualRefreshForContentChange(event, DEBOUNCE_DETECT_MS, clearBeforeProbe = true)
                meshHandler.postDelayed(meshRunnable, DEBOUNCE_DETECT_MS)
            }
        }
    }

    /**
     * 重建 NavMesh。
     *
     * [FIX 4] 拆分为两个阶段：
     *  - 第一阶段（主线程）：采集节点（ANI 只能在主线程访问）
     *  - 第二阶段（Default 线程）：构建图（O(n²)，不阻塞 UI）
     *  - 完成后回到主线程应用结果，并通过版本号丢弃过期结果。
     *
     * @param onDone 图构建完成后在主线程执行的回调（可选）
     */
    private fun rebuildNavMesh(
        forceApply: Boolean = false,
        scanPass: MeshScanPass = MeshScanPass.FAST,
        probeBands: Set<EdgeInteractionRules.GenericContentBand> = emptySet(),
        onDone: ((List<NavNode>) -> Unit)? = null
    ) {
        if (isJoystickSelecting) {
            deferRefreshUntilSelectionEnds()
            return
        }
        refreshScrollConfig()
        idleContentRefreshPending = false
        val version = navMeshCoordinator.invalidatePendingBuilds()  // 本次重建的版本号
        val sampledPackage = activeTargetPackage()
        val collectStartedAt = System.nanoTime()

        // 第一阶段：主线程采集节点
        val rawNodes: List<NavNode>
        try {
            rawNodes = parser.collectNodesFromWindows(scanPass, probeBands)
        } catch (e: Exception) {
            Log.e("TouchEngine", "collectNodes error: ${e.message}")
            return
        }
        val collectMs = (System.nanoTime() - collectStartedAt) / 1_000_000L

        // 容差化签名忽略动态角标与轻微抖动，复杂页面不再因为像素变化频繁重建。
        val previousSnapshot = stableMesh
        var candidateNodes = rawNodes
        if (scanPass == MeshScanPass.FAST) {
            if (shouldUseDouyinVideoAccessibility(sampledPackage)) {
                val fastVideoNodes = rawNodes.filter(::isDouyinVideoDeepTarget)
                val deepNodes = if (hasDouyinVideoAccessibilityCoverage(fastVideoNodes)) {
                    emptyList()
                } else {
                    parser.collectNodesFromWindows(MeshScanPass.DOUYIN_VIDEO_DEEP, probeBands)
                }
                candidateNodes = mergeNavigationNodes(fastVideoNodes, deepNodes)
                pendingAnchorScanKey = null
                pendingHoleBands = emptySet()
                confirmedHoleBands = EdgeInteractionRules.GenericContentBand.entries.toSet()
                holeConfirmAttempts.clear()
                meshHandler.removeCallbacks(anchorScanRunnable)
                meshHandler.removeCallbacks(holeConfirmRunnable)
                updateVisualHoleDisplay()
                Log.d(
                    "TouchEngineDouyin",
                    "videoAccessibility fast=${fastVideoNodes.size} deep=${deepNodes.size} total=${candidateNodes.size} pkg=$sampledPackage"
                )
            } else {
                val missingBands = EdgeInteractionRules.missingContentBands(rawNodes, screenHeight)
                val recoveredBands = EdgeInteractionRules.GenericContentBand.values().toSet() - missingBands
                if (recoveredBands.isNotEmpty()) {
                    confirmedHoleBands = confirmedHoleBands - recoveredBands
                    pendingHoleBands = pendingHoleBands - recoveredBands
                    recoveredBands.forEach { holeConfirmAttempts.remove(it) }
                    updateVisualHoleDisplay()
                }
                queueHoleConfirmation(missingBands, 220L)

                val missingAnchors = EdgeInteractionRules.missingNavigationAnchors(rawNodes, screenHeight)
                val anchorKey = "$sampledPackage:${missingAnchors.joinToString()}:${rawNodes.map { it.windowId }.toSet()}"
                if (missingAnchors.isNotEmpty() && anchorKey != pendingAnchorScanKey) {
                    pendingAnchorScanKey = anchorKey
                    meshHandler.removeCallbacks(anchorScanRunnable)
                    meshHandler.postDelayed(anchorScanRunnable, 160L)
                } else if (missingAnchors.isEmpty()) {
                    pendingAnchorScanKey = null
                    meshHandler.removeCallbacks(anchorScanRunnable)
                }
                Log.d(
                    "TouchEngineCapability",
                    "FAST nodes=${rawNodes.size} anchorsMissing=$missingAnchors missing=$missingBands pkg=$sampledPackage"
                )
            }
        } else if (scanPass == MeshScanPass.HOLE_CONFIRM) {
            if (probeBands.isEmpty()) return
            val unresolved = EdgeInteractionRules.unresolvedBandsAfterProbe(probeBands, rawNodes, screenHeight)
            val recovered = probeBands - unresolved
            recovered.forEach { holeConfirmAttempts.remove(it) }
            confirmedHoleBands = confirmedHoleBands - recovered
            unresolved.forEach { band ->
                val attempts = (holeConfirmAttempts[band] ?: 0) + 1
                holeConfirmAttempts[band] = attempts
                if (attempts >= 2) confirmedHoleBands = confirmedHoleBands + band
            }
            updateVisualHoleDisplay()
            pendingHoleBands = unresolved.filterTo(linkedSetOf()) {
                (holeConfirmAttempts[it] ?: 0) < 2
            }
            meshHandler.removeCallbacks(holeConfirmRunnable)
            if (pendingHoleBands.isNotEmpty()) {
                meshHandler.postDelayed(holeConfirmRunnable, 360L)
            }
            Log.d(
                "TouchEngineCapability",
                "HOLE_CONFIRM attempt=$holeConfirmAttempts recovered=$recovered unresolved=$unresolved confirmed=$confirmedHoleBands pkg=$sampledPackage"
            )
            if (recovered.isEmpty()) return
            candidateNodes = mergeNavigationNodes(previousSnapshot?.nodes.orEmpty(), rawNodes)
        } else {
            pendingAnchorScanKey = null
            meshHandler.removeCallbacks(anchorScanRunnable)
            if (rawNodes.isEmpty()) return
            candidateNodes = mergeNavigationNodes(previousSnapshot?.nodes.orEmpty(), rawNodes)
        }
        candidateNodes = buildFinalTargetNavNodes(
            mergeCachedVisualNodes(candidateNodes, sampledPackage),
            sampledPackage
        )
        val signature = EdgeInteractionRules.meshSignature(candidateNodes)
        val hasChanged = previousSnapshot == null ||
            previousSnapshot.targetPackage != sampledPackage ||
            previousSnapshot.signature != signature
        if (isTaobaoPackage(sampledPackage)) {
            Log.d("TouchEngineTaobao", "pass=$scanPass collect=${collectMs}ms nodes=${candidateNodes.size} changed=$hasChanged force=$forceApply signature=$signature")
        }
        if (isDouyinPackage(sampledPackage)) {
            Log.d("TouchEngineDouyin", "pass=$scanPass collect=${collectMs}ms nodes=${candidateNodes.size} changed=$hasChanged force=$forceApply signature=$signature pkg=$sampledPackage")
        } else if (collectMs >= 16L) {
            Log.d("TouchEnginePerf", "slowCollect=${collectMs}ms pass=$scanPass nodes=${candidateNodes.size} pkg=$sampledPackage")
        }
        
        if (!forceApply && !hasChanged) {
            // 节点没有发生改变，证明页面已完全稳定（Settled）！
            // 立即标记为稳定状态，日常 CONTENT_CHANGED 自动失效，CPU 进入极致休眠
            isWindowSettled = true
            meshPackage = sampledPackage ?: meshPackage
            dirtyReasons.clear()
            if (::pageInvalidationController.isInitialized) {
                pageInvalidationController.refreshBaseline("meshStableNoChange")
            }
            onDone?.invoke(previousSnapshot?.nodes ?: currentNodes)
            return
        }

        // 第二阶段：Coordinator 在后台构建图，并只在 generation 仍有效时提交。
        navMeshCoordinator.submitStableBuild(
            request = NavMeshBuildRequest(
                nodes = candidateNodes,
                targetPackage = sampledPackage,
                signature = signature,
                source = MeshSource.STABLE,
                quality = if (scanPass == MeshScanPass.FAST) MeshQuality.FAST else MeshQuality.DEEP,
                generation = version
            )
        ) { result ->
            val buildMs = when (result) {
                is NavMeshBuildResult.Committed -> result.buildMs
                is NavMeshBuildResult.Discarded -> result.buildMs
            }
            if (isTaobaoPackage(sampledPackage)) {
                Log.d("TouchEngineTaobao", "build=${buildMs}ms nodes=${candidateNodes.size}")
            }
            if (isDouyinPackage(sampledPackage)) {
                Log.d("TouchEngineDouyin", "build=${buildMs}ms nodes=${candidateNodes.size}")
            }

            when (result) {
                is NavMeshBuildResult.Discarded -> {
                    if (result.reason == NavMeshDiscardReason.SELECTION_ACTIVE) {
                        deferRefreshUntilSelectionEnds()
                    }
                }
                is NavMeshBuildResult.Committed -> {
                    isWindowSettled = true
                    meshPackage = sampledPackage ?: meshPackage
                    dirtyReasons.clear()
                    if (::pageInvalidationController.isInitialized) {
                        pageInvalidationController.refreshBaseline("meshCommitted")
                    }
                    Log.d(
                        "TouchEnginePerf",
                        "stable pass=$scanPass collect=${collectMs}ms build=${result.buildMs}ms nodes=${candidateNodes.size} " +
                            "deferred=$selectionDeferredRefreshes discarded=${result.discardedResults} pkg=$sampledPackage"
                    )
                    val previousFocus = currentFocusState.value
                    if (previousFocus != null) {
                        currentFocusState.value = NavMeshFocusResolver.reconcile(
                            previous = previousFocus,
                            candidates = result.snapshot.nodes
                        )
                    }
                    visualScanCoordinator.showCommittedMesh(
                        result.snapshot.nodes,
                        "StableMesh"
                    )
                    onDone?.invoke(result.snapshot.nodes)
                }
            }
        }
    }

    private fun mergeNavigationNodes(base: List<NavNode>, additions: List<NavNode>): List<NavNode> {
        val mergedByBounds = linkedMapOf<String, NavNode>()
        (base + additions).forEach { candidate ->
            val key = "${candidate.windowId}:${candidate.bounds.left}:${candidate.bounds.top}:" +
                "${candidate.bounds.right}:${candidate.bounds.bottom}"
            val existing = mergedByBounds[key]
            mergedByBounds[key] = if (existing == null) {
                candidate.copy(
                    bounds = Rect(candidate.bounds),
                    sourceSet = resolvedNodeSources(candidate)
                )
            } else {
                mergeNavigationNodeEvidence(existing, candidate)
            }
        }
        return mergedByBounds.values.toList()
    }

    /**
     * 相同 windowId + bounds 的节点不能在进入 TargetFusionEngine 前被 distinctBy
     * 丢弃。几何保持一份，但多来源证据与点击策略必须合并后继续向下传递。
     */
    private fun mergeNavigationNodeEvidence(first: NavNode, second: NavNode): NavNode {
        val mergedSources = resolvedNodeSources(first) + resolvedNodeSources(second)
        val preferred = chooseNavigationMetadataWinner(first, second)
        val mergedMethod = when {
            first.clickMethod == ClickMethod.GESTURE_SAFE_LEFT ||
                second.clickMethod == ClickMethod.GESTURE_SAFE_LEFT -> ClickMethod.GESTURE_SAFE_LEFT
            TargetSource.ACCESSIBILITY in mergedSources -> ClickMethod.ACCESSIBILITY_ACTION
            else -> ClickMethod.GESTURE_CENTER
        }
        return preferred.copy(
            bounds = Rect(preferred.bounds),
            clickMethod = mergedMethod,
            sourceSet = mergedSources,
            visualEvidence = preferred.visualEvidence?.copy(
                sources = mergedSources,
                clickMethod = mergedMethod
            )
        )
    }

    private fun chooseNavigationMetadataWinner(first: NavNode, second: NavNode): NavNode {
        val firstStructured = first.description.orEmpty().startsWith("VISION[")
        val secondStructured = second.description.orEmpty().startsWith("VISION[")
        return when {
            secondStructured && !firstStructured -> second
            firstStructured && !secondStructured -> first
            first.description.isNullOrBlank() && !second.description.isNullOrBlank() -> second
            else -> first
        }
    }

    private fun resolvedNodeSources(node: NavNode): Set<TargetSource> {
        if (node.sourceSet.isNotEmpty()) return node.sourceSet
        if (node.origin == NavNodeOrigin.ACCESSIBILITY) {
            return setOf(TargetSource.ACCESSIBILITY)
        }
        val description = node.description.orEmpty()
        val kind = description.substringAfter("VISION[", "").substringBefore("]", "")
        return when (kind) {
            "TEXT" -> setOf(TargetSource.OCR)
            "ACTION" -> {
                val compact = description.lowercase()
                if (compact.contains("button") || compact.contains("edit") ||
                    compact.contains("tab") || compact.contains("navigation") ||
                    compact.contains("icon") || compact.contains("image")
                ) {
                    setOf(TargetSource.YOLO)
                } else {
                    setOf(TargetSource.OCR)
                }
            }
            "ICON", "RAIL", "CARD" -> setOf(TargetSource.YOLO)
            "ITEM" -> setOf(TargetSource.VISUAL_FUSION)
            else -> setOf(TargetSource.VISUAL_FUSION)
        }
    }

    private fun mergeCachedVisualNodes(base: List<NavNode>, sampledPackage: String?): List<NavNode> {
        val accessibilityNodes = base.filterNot { it.origin == NavNodeOrigin.VISION }
        return visualScanCoordinator.mergeCachedNodes(
            base = accessibilityNodes,
            sampledPackage = sampledPackage,
            pageSignature = visualPageSignature(accessibilityNodes, sampledPackage),
            sceneTag = douyinSceneTag(sampledPackage)
        )
    }

    private fun buildFinalTargetNavNodes(
        rawNodes: List<NavNode>,
        targetPackage: String?
    ): List<NavNode> {
        if (rawNodes.isEmpty()) return emptyList()

        val scene = classifyDouyinScene(targetPackage)
        val result = targetFusionEngine.fuse(
            rawNodes = rawNodes,
            context = TargetFusionContext(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                density = resources.displayMetrics.density,
                keyboardTop = keyboardTop(),
                visualOcclusionBounds = if (
                    ::visualScanCoordinator.isInitialized &&
                    visualScanCoordinator.currentVisualCaptureMode == VisualCaptureMode.WINDOW_CLEAN
                ) {
                    null
                } else {
                    currentJoystickBounds()?.let { Rect(it) }
                },
                targetPackage = targetPackage,
                douyinScene = scene,
                useDouyinVideoAccessibility = shouldUseDouyinVideoAccessibility(targetPackage)
            )
        )

        if (targetPackage != null &&
            (isTaobaoPackage(targetPackage) || isDouyinPackage(targetPackage) || targetPackage == "com.tencent.mm")
        ) {
            val stats = result.stats
            Log.d(
                "TouchEngineFinal",
                "raw=${stats.rawNodeCount} candidates=${stats.candidateCount} final=${stats.finalCount} " +
                    "merged=${stats.mergedCandidateCount} a11y=${stats.accessibilityCount} vision=${stats.visionCount} " +
                    "contained=${stats.containedLabelMergeCount} iconLabel=${stats.iconLabelPairCount} " +
                    "keptAction=${stats.keptInnerActionCount} overlap=${stats.overlapDuplicateCount} " +
                    "srcYolo=${stats.yoloInputCount} srcOcr=${stats.ocrInputCount} srcFusion=${stats.fusionInputCount} " +
                    "listIn=${stats.listItemInputCount} listFinal=${stats.listItemFinalCount} page=${stats.pageKey}"
            )
        }
        return result.nodes
    }

    private fun buildVisualSurfaceKey(
        targetPackage: String?,
        bands: Set<EdgeInteractionRules.GenericContentBand>
    ): VisualSurfaceKey {
        return VisualSurfaceKey(
            targetPackage = targetPackage,
            viewportWidthBucket = screenWidth / 24,
            viewportHeightBucket = screenHeight / 24,
            holeBands = bands.toSet(),
            pageSignature = currentVisualPageSignature(targetPackage),
            sceneTag = douyinSceneTag(targetPackage)
        )
    }

    private fun currentVisualPageSignature(targetPackage: String?): Int {
        val snapshotNodes = stableMesh
            ?.takeIf { it.targetPackage == targetPackage }
            ?.nodes
            ?: currentNodes
        return visualPageSignature(snapshotNodes.filterNot { it.origin == NavNodeOrigin.VISION }, targetPackage)
    }

    private fun visualPageSignature(nodes: List<NavNode>, targetPackage: String?): Int {
        if (nodes.isEmpty()) return targetPackage.orEmpty().hashCode()
        val parts = nodes.asSequence()
            .filterNot { it.origin == NavNodeOrigin.VISION }
            .sortedWith(compareBy({ it.windowId }, { it.bounds.top }, { it.bounds.left }))
            .take(36)
            .map { node ->
                val text = normalizedVisualText(node.description.orEmpty())
                    .take(12)
                val r = node.bounds
                "${node.windowId}:${r.left / 32}:${r.top / 32}:${r.right / 32}:${r.bottom / 32}:$text"
            }
            .joinToString("|")
        return "${targetPackage.orEmpty()}|$parts".hashCode()
    }

    private fun eventDiagnostic(event: AccessibilityEvent?): String {
        if (event == null) return "event=internal"
        val deltas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            " dx=${event.scrollDeltaX} dy=${event.scrollDeltaY}"
        } else {
            ""
        }
        return "event=${event.eventType} pkg=${event.packageName} window=${event.windowId}$deltas"
    }

    private fun logRetainedVisualCache(reason: String, event: AccessibilityEvent?) {
        visualScanCoordinator.logRetained(
            reason = reason,
            eventSummary = eventDiagnostic(event),
            selecting = isJoystickSelecting,
            scrollState = scrollState
        )
    }

    private fun handlePageInvalidationDecision(decision: PageInvalidationDecision) {
        Log.d(
            "TouchEngineInvalidation",
            "decision=${decision.level} structure=${decision.structuralDistance} " +
                "content=${decision.contentDistance} area=${decision.changedAreaRatio} " +
                decision.eventSummary
        )

        when (decision.level) {
            PageInvalidationLevel.NONE -> {
                visualScanCoordinator.logRetained(
                    reason = "CONTENT_STRUCTURE_STABLE",
                    eventSummary = decision.eventSummary,
                    selecting = isJoystickSelecting,
                    scrollState = scrollState
                )
            }

            PageInvalidationLevel.CONTENT_ONLY_SCAN -> {
                // Keep the accessibility graph and current visual targets visible. A same-layout
                // content replacement may refresh OCR/YOLO, but it must not clear or rebuild NavMesh.
                requestVisualRefreshForContentChange(
                    eventSummary = decision.eventSummary,
                    eventPackage = decision.eventPackage,
                    delayMs = if (EdgeInteractionRules.isHighFrequencyPackage(decision.eventPackage)) {
                        COMPLEX_WINDOW_REFRESH_MS
                    } else {
                        320L
                    },
                    clearBeforeProbe = false
                )
            }

            PageInvalidationLevel.FULL_SCAN,
            PageInvalidationLevel.DEFERRED_FULL_SCAN -> {
                dirtyReasons.add(RefreshDirtyReason.CONTENT)
                isWindowSettled = false
                stateChangedTimeMs = System.currentTimeMillis()
                idleContentRefreshPending = true
                meshHandler.removeCallbacks(meshRunnable)

                val delayMs = if (decision.level == PageInvalidationLevel.DEFERRED_FULL_SCAN) {
                    180L
                } else {
                    DEBOUNCE_DETECT_MS
                }
                requestVisualRefreshForContentChange(
                    eventSummary = decision.eventSummary,
                    eventPackage = decision.eventPackage,
                    delayMs = delayMs,
                    clearBeforeProbe = true
                )
                meshHandler.postDelayed(meshRunnable, delayMs)
            }
        }
    }

    private fun resetCapabilityProbes(
        clearVisibleHoles: Boolean = true,
        invalidationReason: VisualCacheInvalidationReason? = null,
        event: AccessibilityEvent? = null
    ) {
        if (::pageInvalidationController.isInitialized &&
            invalidationReason != null &&
            invalidationReason != VisualCacheInvalidationReason.CONTENT_CHANGE &&
            invalidationReason != VisualCacheInvalidationReason.DYNAMIC_HOLE_CHANGE &&
            invalidationReason != VisualCacheInvalidationReason.ACTIONABLE_CHANGE &&
            invalidationReason != VisualCacheInvalidationReason.ACTIONABLE_NOISE &&
            invalidationReason != VisualCacheInvalidationReason.OUTSIDE_HOLE_NOISE &&
            invalidationReason != VisualCacheInvalidationReason.THROTTLED &&
            invalidationReason != VisualCacheInvalidationReason.SELECTION_FROZEN
        ) {
            pageInvalidationController.reset("visualInvalidation:$invalidationReason")
        }
        pendingAnchorScanKey = null
        pendingHoleBands = emptySet()
        confirmedHoleBands = emptySet()
        holeConfirmAttempts.clear()
        meshHandler.removeCallbacks(anchorScanRunnable)
        meshHandler.removeCallbacks(holeConfirmRunnable)
        if (clearVisibleHoles) {
            visualHoleState.value = emptyList()
            clearVisualOcrCache(invalidationReason, event)
        }
    }

    private fun requestVisualRefreshForContentChange(
        event: AccessibilityEvent?,
        delayMs: Long,
        clearBeforeProbe: Boolean = false
    ) {
        requestVisualRefreshForContentChange(
            eventSummary = eventDiagnostic(event),
            eventPackage = event?.packageName?.toString(),
            delayMs = delayMs,
            clearBeforeProbe = clearBeforeProbe
        )
    }

    private fun requestVisualRefreshForContentChange(
        eventSummary: String,
        eventPackage: String?,
        delayMs: Long,
        clearBeforeProbe: Boolean = false
    ) {
        visualScanCoordinator.requestContentRefresh(
            eventSummary = eventSummary,
            eventPackage = eventPackage,
            delayMs = delayMs,
            clearBeforeProbe = clearBeforeProbe,
            clearCapabilityProbes = {
                // Coordinator already advanced pageEpoch/viewportRevision and invalidated the
                // visual cache. Only reset capability probes here; do not advance the session twice.
                resetCapabilityProbes(
                    clearVisibleHoles = false,
                    invalidationReason = null,
                    event = null
                )
            },
            refreshHoleDisplay = ::updateVisualHoleDisplay
        )
    }

    private fun queueHoleConfirmation(
        missingBands: Set<EdgeInteractionRules.GenericContentBand>,
        delayMs: Long
    ) {
        if (FULL_SCREEN_VISUAL_TEST_MODE) {
            pendingHoleBands = emptySet()
            meshHandler.removeCallbacks(holeConfirmRunnable)
            updateVisualHoleDisplay()
            return
        }
        val eligible = (missingBands - confirmedHoleBands).filterTo(linkedSetOf()) {
            (holeConfirmAttempts[it] ?: 0) < 2
        }
        pendingHoleBands = eligible
        meshHandler.removeCallbacks(holeConfirmRunnable)
        if (eligible.isNotEmpty()) {
            meshHandler.postDelayed(holeConfirmRunnable, delayMs)
        }
    }

    private fun visualHoleRects(
        bands: Set<EdgeInteractionRules.GenericContentBand>
    ): List<Rect> {
        if (FULL_SCREEN_VISUAL_TEST_MODE && bands.isNotEmpty()) {
            return listOf(Rect(0, 0, screenWidth, screenHeight))
        }
        return bands.map { band ->
            val (topRatio, bottomRatio) = when (band) {
                EdgeInteractionRules.GenericContentBand.BODY_UPPER -> 0.18f to 0.54f
                EdgeInteractionRules.GenericContentBand.BODY_LOWER -> 0.48f to 0.85f
            }
            Rect(
                (screenWidth * 0.03f).toInt(),
                (screenHeight * topRatio).toInt(),
                (screenWidth * 0.97f).toInt(),
                (screenHeight * bottomRatio).toInt()
            )
        }
    }

    private fun updateVisualHoleDisplay() {
        val targetPackage = activeTargetPackage()
        if (shouldPauseVisualCaptureForPackage(targetPackage)) {
            visualScanCoordinator.setPausedOnSettings()
            visualHoleState.value = emptyList()
            return
        }
        if (shouldUseDouyinVideoAccessibility(targetPackage)) {
            val policy = douyinRegionPolicy(DouyinScene.VIDEO_FEED_PORTRAIT)
            visualHoleState.value = policy.allowedRegions.map(::Rect)
            if (!isJoystickSelecting && scrollState == ScrollState.IDLE) {
                visualScanCoordinator.scheduleScan(visualScanCoordinator.scheduleDelayMs())
            }
            return
        }
        val retainedBands = visualScanCoordinator.retainedBandsFor(targetPackage)
        val visibleBands = visualRecognitionBands(confirmedHoleBands + retainedBands)
        visualHoleState.value = visualHoleRects(visibleBands)
        if (visibleBands.isNotEmpty() && !isJoystickSelecting && scrollState == ScrollState.IDLE) {
            visualScanCoordinator.scheduleScan(visualScanCoordinator.scheduleDelayMs())
        }
    }

    private fun shouldPauseVisualCaptureForPackage(targetPackage: String?): Boolean {
        return FULL_SCREEN_VISUAL_TEST_MODE && targetPackage == packageName
    }

    private fun visualRecognitionBands(
        baseBands: Set<EdgeInteractionRules.GenericContentBand>
    ): Set<EdgeInteractionRules.GenericContentBand> {
        return if (FULL_SCREEN_VISUAL_TEST_MODE) {
            EdgeInteractionRules.GenericContentBand.entries.toSet()
        } else {
            baseBands
        }
    }

    private fun buildVisualScanRuntime(): VisualScanRuntime {
        val (targetPackage, primaryWindowId) = activeTargetIdentity()
        return VisualScanRuntime(
            targetPackage = targetPackage,
            primaryWindowId = primaryWindowId,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = resources.displayMetrics.density,
            keyboardTop = keyboardTop(),
            joystickInteractionBounds = currentJoystickBounds()?.let { Rect(it) },
            inputMethodVisible = isInputMethodWindowVisible(),
            activeNodes = currentNodes,
            stableNodes = stableMesh?.nodes.orEmpty(),
            isJoystickSelecting = isJoystickSelecting,
            scrollIdle = scrollState == ScrollState.IDLE,
            objectEvidenceEnabled = liveObjectEvidenceEnabled.value,
            yoloInputSize = liveYoloInputSize.value
        )
    }

    private fun buildVisualScanRequest(): VisualScanRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            isJoystickSelecting ||
            scrollState != ScrollState.IDLE
        ) {
            return null
        }

        val runtime = buildVisualScanRuntime()
        val targetPackage = runtime.targetPackage
        if (shouldPauseVisualCaptureForPackage(targetPackage)) return null

        val douyinScene = classifyDouyinScene(targetPackage)
        if (douyinScene == DouyinScene.VIDEO_FEED_PORTRAIT) {
            val requestedBands = EdgeInteractionRules.GenericContentBand.entries.toSet()
            return VisualScanRequest(
                runtime = runtime,
                requestedBands = requestedBands,
                cropBounds = Rect(0, 0, screenWidth, screenHeight),
                bandBounds = requestedBands.associateWith { Rect(0, 0, screenWidth, screenHeight) },
                surfaceKey = buildVisualSurfaceKey(targetPackage, requestedBands),
                douyinScene = douyinScene,
                regionPolicy = douyinRegionPolicy(douyinScene),
                fullScreenTestMode = FULL_SCREEN_VISUAL_TEST_MODE
            )
        }

        val retainedBands = visualScanCoordinator.retainedBandsFor(targetPackage)
        val requestedBands = visualRecognitionBands(confirmedHoleBands + retainedBands)
        if (requestedBands.isEmpty()) return null

        val holes = visualHoleRects(requestedBands)
        if (holes.isEmpty()) return null

        val cropBounds = Rect(holes.first()).also { union ->
            holes.drop(1).forEach { union.union(it) }
            union.intersect(0, 0, screenWidth, screenHeight)
        }
        if (douyinScene == DouyinScene.SEARCH_TEXT) {
            keyboardTop()?.let { imeTop ->
                cropBounds.bottom = minOf(cropBounds.bottom, imeTop - 8)
            }
        }
        if (cropBounds.width() <= 0 || cropBounds.height() <= 0) return null

        val bandBounds = requestedBands.associateWith { band ->
            Rect(visualHoleRects(setOf(band)).first())
        }
        return VisualScanRequest(
            runtime = runtime,
            requestedBands = requestedBands,
            cropBounds = cropBounds,
            bandBounds = bandBounds,
            surfaceKey = buildVisualSurfaceKey(targetPackage, requestedBands),
            douyinScene = douyinScene,
            regionPolicy = douyinRegionPolicy(douyinScene),
            fullScreenTestMode = FULL_SCREEN_VISUAL_TEST_MODE
        )
    }

    private fun createVisualScreenshotProvider(): VisualScreenshotProvider {
        return object : VisualScreenshotProvider {
            override fun capture(
                request: VisualCaptureRequest,
                onSuccess: (VisualScreenshotFrame) -> Unit,
                onFailure: (Int) -> Unit
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    onFailure(-1)
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val targetWindow = resolveVisualTargetWindow(request)
                    if (targetWindow != null) {
                        try {
                            takeScreenshotOfWindow(
                                targetWindow.id,
                                mainExecutor,
                                object : TakeScreenshotCallback {
                                override fun onSuccess(screenshot: ScreenshotResult) {
                                    val bitmap = screenshotToBitmap(screenshot)
                                    if (bitmap == null) {
                                        captureDisplayScreenshot(
                                            request,
                                            "windowBitmapNull",
                                            onSuccess,
                                            onFailure
                                        )
                                        return
                                    }
                                    val frameBounds = when {
                                        bitmap.width == request.screenWidth &&
                                            bitmap.height == request.screenHeight -> {
                                            Rect(0, 0, request.screenWidth, request.screenHeight)
                                        }
                                        bitmap.width == targetWindow.bounds.width() &&
                                            bitmap.height == targetWindow.bounds.height() -> {
                                            Rect(targetWindow.bounds)
                                        }
                                        else -> null
                                    }
                                    if (frameBounds == null ||
                                        !Rect.intersects(frameBounds, request.requiredBounds)
                                    ) {
                                        val reason = "windowBoundsMismatch:" +
                                            "bitmap=${bitmap.width}x${bitmap.height},window=${targetWindow.bounds}"
                                        bitmap.recycle()
                                        captureDisplayScreenshot(request, reason, onSuccess, onFailure)
                                        return
                                    }
                                    onSuccess(
                                        VisualScreenshotFrame(
                                            bitmap = bitmap,
                                            captureMode = VisualCaptureMode.WINDOW_CLEAN,
                                            windowId = targetWindow.id,
                                            boundsInScreen = frameBounds
                                        )
                                    )
                                }

                                override fun onFailure(errorCode: Int) {
                                    if (shouldPropagateScreenshotFailure(errorCode)) {
                                        onFailure(errorCode)
                                    } else {
                                        captureDisplayScreenshot(
                                            request,
                                            "windowError:$errorCode",
                                            onSuccess,
                                            onFailure
                                        )
                                    }
                                }
                                }
                            )
                        } catch (error: Throwable) {
                            captureDisplayScreenshot(
                                request,
                                "windowException:${error.javaClass.simpleName}",
                                onSuccess,
                                onFailure
                            )
                        }
                        return
                    }
                    captureDisplayScreenshot(
                        request,
                        "windowUnavailable",
                        onSuccess,
                        onFailure
                    )
                    return
                }

                captureDisplayScreenshot(
                    request,
                    "apiBelow34",
                    onSuccess,
                    onFailure
                )
            }
        }
    }

    private data class VisualTargetWindow(
        val id: Int,
        val bounds: Rect
    )

    /** 只接受本次请求锁定的应用窗口，避免异步期间误截到弹窗或已经切换后的应用。 */
    private fun resolveVisualTargetWindow(request: VisualCaptureRequest): VisualTargetWindow? {
        if (request.primaryWindowId < 0 || request.targetPackage.isNullOrBlank()) return null
        val window = windows?.firstOrNull {
            it.id == request.primaryWindowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        } ?: return null
        val root = window.root ?: return null
        val windowPackage = try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
        if (windowPackage == packageName || windowPackage != request.targetPackage) return null

        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0 ||
            !Rect.intersects(bounds, request.requiredBounds)
        ) {
            return null
        }
        return VisualTargetWindow(window.id, bounds)
    }

    private fun screenshotToBitmap(screenshot: ScreenshotResult): Bitmap? {
        return try {
            Bitmap.wrapHardwareBuffer(
                screenshot.hardwareBuffer,
                screenshot.colorSpace
            )?.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            screenshot.hardwareBuffer.close()
        }
    }

    private fun shouldPropagateScreenshotFailure(errorCode: Int): Boolean {
        return errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ||
            errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
    }

    private fun captureDisplayScreenshot(
        request: VisualCaptureRequest,
        fallbackReason: String,
        onSuccess: (VisualScreenshotFrame) -> Unit,
        onFailure: (Int) -> Unit
    ) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = screenshotToBitmap(screenshot)
                    if (bitmap == null) {
                        onFailure(-2)
                        return
                    }
                    onSuccess(
                        VisualScreenshotFrame(
                            bitmap = bitmap,
                            captureMode = VisualCaptureMode.DISPLAY_FALLBACK,
                            windowId = request.primaryWindowId,
                            boundsInScreen = Rect(0, 0, bitmap.width, bitmap.height),
                            fallbackReason = fallbackReason
                        )
                    )
                }

                override fun onFailure(errorCode: Int) {
                    onFailure(errorCode)
                }
            }
        )
    }




    private fun clearVisualOcrCache(
        reason: VisualCacheInvalidationReason?,
        event: AccessibilityEvent?
    ) {
        visualScanCoordinator.clear(
            reason = reason,
            eventSummary = eventDiagnostic(event),
            selecting = isJoystickSelecting,
            scrollState = scrollState
        )
    }

    private fun stripVisionNodesFromActiveMeshes() {
        val previousFocus = currentFocusState.value
        val result = navMeshCoordinator.stripVisionNodes()
        if (!result.changed) return

        currentFocusState.value = when {
            previousFocus == null -> null
            previousFocus.origin == NavNodeOrigin.VISION -> null
            else -> NavMeshFocusResolver.reconcile(
                previous = previousFocus,
                candidates = navMeshCoordinator.currentNodes
            )
        }
        navEngine.reset()
    }

    internal fun suspendVisualOcrForSelection() {
        visualScanCoordinator.suspendForSelection()
    }

    private fun currentJoystickBounds(): Rect? {
        if (!::joystickParams.isInitialized) return null
        val width = joystickParams.width.takeIf { it > 0 } ?: dpToPx(96f)
        val height = joystickParams.height.takeIf { it > 0 } ?: width
        val padding = dpToPx(24f)
        return Rect(
            joystickParams.x - padding,
            joystickParams.y - padding,
            joystickParams.x + width + padding,
            joystickParams.y + height + padding
        )
    }











    private fun hasDouyinTextPageAccessibilitySignal(targetPackage: String?): Boolean {
        if (!EdgeInteractionRules.isDouyinPackage(targetPackage)) return false
        val signals = currentAccessibilityLabels(targetPackage, 48)
        val joined = signals.joinToString("|")
        val directSearchPage = joined.contains("\u5386\u53f2\u8bb0\u5f55") ||
            joined.contains("\u731c\u4f60\u60f3\u641c") ||
            joined.contains("\u6362\u4e00\u6362")
        val searchSignals = signals.count {
            it.contains("\u641c\u7d22") ||
                it.contains("\u5c55\u5f00") ||
                it.contains("\u5386\u53f2") ||
                it.contains("\u731c\u4f60")
        }
        return directSearchPage || searchSignals >= 2
    }



    private fun isLikelyBottomNavigationCandidate(bounds: Rect): Boolean {
        return bounds.centerY() > screenHeight * 0.895f &&
            bounds.height() < screenHeight * 0.095f &&
            bounds.width() < screenWidth * 0.34f
    }

    private fun isLikelyTopNavigationCandidate(bounds: Rect): Boolean {
        return bounds.centerY() in 0..(screenHeight * 0.16f).roundToInt() &&
            bounds.height() < screenHeight * 0.085f &&
            bounds.width() < screenWidth * 0.42f
    }

    private fun dpToPx(value: Float): Int {
        return (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }

    private fun isInputMethodWindowVisible(): Boolean {
        return windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
    }
    private fun isActionNoiseText(text: String): Boolean {
        return text.matches(Regex("^[0-9:：.]+$")) ||
            text.contains("KB/s", ignoreCase = true) ||
            text.contains("MB/s", ignoreCase = true)
    }
    private fun overlapMostly(first: Rect, second: Rect): Boolean {
        val intersection = Rect(first)
        if (!intersection.intersect(second)) return false
        val intersectionArea = intersection.width() * intersection.height()
        val firstArea = first.width() * first.height()
        return firstArea > 0 && intersectionArea.toFloat() / firstArea.toFloat() > 0.6f
    }

    private fun installVisualNodesInStableMesh(
        pending: PendingVisualCache,
        generation: Int,
        onFinished: () -> Unit
    ) {
        val baseSnapshot = navMeshCoordinator.stableForPackage(pending.targetPackage) ?: run {
            onFinished()
            return
        }
        val effectivePending = if (
            pending.stage == VisualUpdateStage.FUSED &&
            pending.token.scanMode == VisualScanMode.SELECTION_CONFIRM_DIFF
        ) {
            when (val decision = SelectionConfirmDiffPolicy.decide(baseSnapshot.nodes, pending.nodes)) {
                is SelectionConfirmDiffPolicy.Decision.NoMeaningfulChange -> {
                    visualScanCoordinator.retainPendingWithoutInstall(
                        pending = pending,
                        candidateGeneration = generation,
                        status = "VISION retained / selection unchanged " +
                            "match=${decision.matchedOldCount}/${decision.oldCount} " +
                            "new=${decision.matchedNewCount}/${decision.newCount}"
                    )
                    Log.d(
                        "TouchEngineVision",
                        "selectionConfirm noChange old=${decision.oldCount} new=${decision.newCount} " +
                            "matchedOld=${decision.matchedOldCount} matchedNew=${decision.matchedNewCount}"
                    )
                    onFinished()
                    return
                }

                is SelectionConfirmDiffPolicy.Decision.PatchChangedNodes -> {
                    val patched = pending.copy(
                        nodes = decision.patchedVisualNodes,
                        actionableSignature = EdgeInteractionRules.actionableVisualSignature(
                            decision.patchedVisualNodes
                        ),
                        diagnosticSummary = (pending.diagnosticSummary ?: "") +
                            " patch=${decision.patchedVisualNodes.size} " +
                            "match=${decision.matchedOldCount}/${decision.oldCount}"
                    )
                    visualScanCoordinator.replacePendingForInstall(patched)
                    Log.d(
                        "TouchEngineVision",
                        "selectionConfirm patch old=${decision.oldCount} new=${decision.newCount} " +
                            "patched=${decision.patchedVisualNodes.size} " +
                            "matchedOld=${decision.matchedOldCount} matchedNew=${decision.matchedNewCount}"
                    )
                    patched
                }

                is SelectionConfirmDiffPolicy.Decision.MajorChange -> {
                    Log.d(
                        "TouchEngineVision",
                        "selectionConfirm major old=${decision.oldCount} new=${decision.newCount} " +
                            "matchedOld=${decision.matchedOldCount} matchedNew=${decision.matchedNewCount} " +
                            "reason=${decision.reason}"
                    )
                    pending
                }
            }
        } else {
            pending
        }

        // 白纸机制先把本次 YOLO/OCR 观察稳定化为带 stableId 的节点集合与 Patch。
        // 此时尚未修改已提交白纸，NavMesh 提交失败时仍可安全重试同一份更新。
        val canvasUpdate = visualScanCoordinator.prepareCanvasUpdate(
            pending = effectivePending,
            candidateGeneration = generation
        ) ?: run {
            onFinished()
            return
        }

        val baseNodes = baseSnapshot.nodes.filterNot { it.origin == NavNodeOrigin.VISION }
        // 快速路径只把可靠的单源目标补进白纸与导航图；完整融合只在 FUSED 阶段发生。
        val mergedInput = mergeNavigationNodes(baseNodes, canvasUpdate.nodes)
        val fused = if (effectivePending.stage == VisualUpdateStage.FUSED) {
            buildFinalTargetNavNodes(mergedInput, effectivePending.targetPackage)
        } else {
            mergedInput
        }
        val merged = VisualNavigationProjection.project(baseSnapshot.nodes, fused)
        val visualMeshLabel = when (effectivePending.stage) {
            VisualUpdateStage.DIRECT_YOLO -> "StableMesh Direct YOLO"
            VisualUpdateStage.DIRECT_OCR -> "StableMesh Direct OCR"
            VisualUpdateStage.FUSED -> "StableMesh Fusion"
        }
        val pendingListItems = canvasUpdate.nodes.count {
            it.visualEvidence?.role == TargetRole.LIST_ITEM
        }
        val mergedListItems = merged.count {
            it.visualEvidence?.role == TargetRole.LIST_ITEM
        }
        val signature = EdgeInteractionRules.meshSignature(merged)
        val requiresMeshApply = !VisualNavigationProjection.sameTargets(baseSnapshot.nodes, merged)
        if (!requiresMeshApply) {
            // Commit recognition evidence, but keep Mesh, green boxes and Focus untouched.
            visualScanCoordinator.commitPending(
                update = canvasUpdate,
                candidateGeneration = generation,
                selectedFocus = currentFocusState.value
            )
            onFinished()
            return
        }

        val version = navMeshCoordinator.invalidatePendingBuilds()
        navMeshCoordinator.submitStablePatch(
            request = NavMeshBuildRequest(
                nodes = merged,
                targetPackage = effectivePending.targetPackage,
                signature = signature,
                source = MeshSource.STABLE,
                quality = baseSnapshot.quality,
                generation = version
            ),
            commitGuard = {
                visualScanCoordinator.isCurrentInstall(
                    pending = effectivePending,
                    candidateGeneration = generation,
                    targetPackage = effectivePending.targetPackage
                )
            }
        ) { result ->
            when (result) {
                is NavMeshBuildResult.Discarded -> {
                    if (result.reason == NavMeshDiscardReason.SELECTION_ACTIVE) {
                        pendingRefreshAfterSelection = true
                        dirtyReasons.add(RefreshDirtyReason.SELECTION)
                    }
                    onFinished()
                }
                is NavMeshBuildResult.Committed -> {
                    val commit = visualScanCoordinator.commitPending(
                        update = canvasUpdate,
                        candidateGeneration = generation,
                        selectedFocus = currentFocusState.value,
                        recordMarqueeCommit = true
                    )
                    val previousFocus = currentFocusState.value
                    if (previousFocus != null) {
                        currentFocusState.value = NavMeshFocusResolver.reconcile(
                            previous = commit?.replacementFocus ?: previousFocus,
                            candidates = result.snapshot.nodes
                        )
                    }
                    if (commit != null) {
                        visualScanCoordinator.showCommittedMesh(
                            result.snapshot.nodes,
                            if (result.incremental) "$visualMeshLabel Patch" else "$visualMeshLabel Rebuild"
                        )
                    }
                    Log.d(
                        "TouchEngineVision",
                        "stableVisual mode=${effectivePending.token.scanMode} observed=${effectivePending.nodes.size} " +
                            "canvas=${canvasUpdate.nodes.size} items=${pendingListItems} merged=${merged.size} " +
                            "mergedItems=${mergedListItems} patch=+${canvasUpdate.patch.addedStableIds.size}/" +
                            "~${canvasUpdate.patch.updatedStableIds.size}/-${canvasUpdate.patch.removedStableIds.size} " +
                            "meshIncremental=${result.incremental} affected=${result.affectedNodeCount} " +
                            "meshDelta=+${result.addedNodeCount}/~${result.updatedNodeCount}/-${result.removedNodeCount} " +
                            "build=${result.buildMs}ms pkg=${effectivePending.targetPackage}"
                    )
                    onFinished()
                }
            }
        }
    }




    // collectNodesFromWindows / parseAccessibilityTree 及相关 helper 已迁移至 AccessibilityTreeParser.kt

    // =====================================================
    // UI Init
    // =====================================================

    @SuppressLint("RtlHardcoded")
    private fun initFloatingUI() {
        // Focus overlay — 全屏，不拦截触摸
        val focusParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        focusBoxView = ComposeView(this).apply {
            ui.setupComposeLifecycle(this)
            setContent {
                FocusHighlightUI(
                    targetNode            = currentFocusState.value,
                    hintData              = scrollHintState.value,
                    visualHoles           = visualHoleState.value,
                    visualOcr             = visualScanCoordinator.visualOcrState.value,
                    objectEvidence        = visualScanCoordinator.objectEvidenceState.value,
                    objectEvidenceEnabled = liveObjectEvidenceEnabled.value,
                    scrollConfig          = scrollConfig,
                )
            }
        }
        windowManager.addView(focusBoxView, focusParams)

        // Joystick overlay
        val basePx = (64 * Resources.getSystem().displayMetrics.density).toInt()
        joystickParams = WindowManager.LayoutParams(
            basePx,
            basePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = screenWidth / 2 - basePx / 2
            y = (screenHeight * 0.7f).toInt()
        }
        joystickView = ComposeView(this).apply {
            ui.setupComposeLifecycle(this)
            setContent { ui.JoystickUI() }
        }
        windowManager.addView(joystickView, joystickParams)
    }


    // =====================================================
    // Click
    // =====================================================

    private fun scheduleLightValidationAfterPotentialPageChange(delayMs: Long = 420L) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLightValidationAtMs < 700L) return
        lastLightValidationAtMs = now
        resetCapabilityProbes(invalidationReason = VisualCacheInvalidationReason.USER_CLICK)
        isWindowSettled = false
        stateChangedTimeMs = System.currentTimeMillis()
        idleContentRefreshPending = false
        meshHandler.removeCallbacks(meshRunnable)
        meshHandler.postDelayed({
            if (!isJoystickSelecting && scrollState == ScrollState.IDLE) {
                rebuildNavMesh(forceApply = true)
            }
        }, delayMs)
    }

    internal fun performClickOnTarget(target: NavNode?) {
        if (target == null) return
        val result = targetClickExecutor.performClick(target)
        if (target.origin == NavNodeOrigin.VISION) {
            visualScanCoordinator.markVisualTapResult(result.clicked)
        }
        Log.d(
            "TouchEngineClick",
            "clicked=${result.clicked} method=${result.requestedMethod} path=${result.path} " +
                "sources=${target.sourceSet.sortedBy { it.name }} origin=${target.origin} " +
                "x=${result.x} y=${result.y} bounds=${target.bounds} target=${target.description}"
        )
        if (result.clicked) {
            scheduleLightValidationAfterPotentialPageChange()
        }
    }

}
