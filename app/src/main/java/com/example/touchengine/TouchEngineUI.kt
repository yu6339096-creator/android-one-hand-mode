package com.example.touchengine

import android.os.Bundle
import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * TouchEngine UI 层。
 *
 * 作为独立文件中的 UI 协作者，通过 service receiver 访问手势运行状态。
 * 这是过渡性拆分：先保证文件边界与编译正确，再继续缩小与 Service 的耦合。
 *
 * 职责：
 *  - JoystickUI：悬浮球 Composable，所有手势交互逻辑
 *  - setupComposeLifecycle / ComposeLifecycleOwner：Compose 在非 Activity 中的生命周期桥接
 *
 * 不包含：
 *  - FocusHighlightUI（见 FocusHighlightUI.kt，完全无状态，无需 inner class）
 *  - initFloatingUI（保留在 Service，因需要 windowManager.addView）
 */
internal class TouchEngineUI(private val service: TouchEngineService) {

    // =====================================================
    // Joystick UI
    //
    // 结构：外层 Box = 底座（固定不动）
    //        内层 Box = 小球（跟随手指在底座内移动）
    //
    // 操作模式：
    //   单指短拨   → 点拨跳一格
    //   单指按住   → 首跳 + 无极加速
    //   长按后拖动 → 移动整个悬浮球窗口
    //   单击       → 确认点击 / 返回
    //   双击       → Home
    // =====================================================

    @Composable
    fun JoystickUI() = with(service) {
        // scope 仍保留用于边缘吸附计时器和确认超时计时器（纯 UI 行为，与滚动状态无关）
        val scope      = rememberCoroutineScope()
        val density    = LocalDensity.current
        val view       = LocalView.current
        val viewConfig = LocalViewConfiguration.current

        // ── 尺寸常量（响应用户外观设置）─────────────────────
        val ballSizeF      = TouchEngineService.liveBallSize.floatValue          // 0.0~1.0
        // 外观设置只改变外圈（底座）及其触控范围；内部操控球保持固定大小。
        // 0~65% 沿用原范围；65~100% 额外扩展，最大值为原 76dp 的 1.5 倍（114dp）。
        val baseSizeDp = when {
            ballSizeF <= 0.65f -> (44f + 32f * ballSizeF).dp
            else -> {
                val sizeAt65 = 44f + 32f * 0.65f
                (sizeAt65 + (114f - sizeAt65) * ((ballSizeF - 0.65f) / 0.35f)).dp
            }
        }
        val ballSizeDp     = 26.dp
        val baseSizePx     = with(density) { baseSizeDp.toPx() }
        val MAX_BALL_OFFSET = with(density) { (baseSizeDp / 2 - ballSizeDp / 2 - 4.dp).toPx() }

        // ── 状态 ──────────────────────────────────────────
        var windowX by remember { mutableStateOf(joystickParams.x.toFloat()) }
        var windowY by remember { mutableStateOf(joystickParams.y.toFloat()) }
        var ballOffsetX      by remember { mutableStateOf(0f) }
        var ballOffsetY      by remember { mutableStateOf(0f) }
        var isEdgeHidden     by remember { mutableStateOf(false) }
        var isConfirmPending by remember { mutableStateOf(false) }
        var isPressed        by remember { mutableStateOf(false) }
        var isDraggingWindow by remember { mutableStateOf(false) }
        var edgeTimerJob     by remember { mutableStateOf<Job?>(null) }
        var confirmTimerJob  by remember { mutableStateOf<Job?>(null) }
        var tapCount         by remember { mutableStateOf(0) }
        var tapTimeoutJob    by remember { mutableStateOf<Job?>(null) }

        // ── 动效 ──────────────────────────────────────────
        val baseScale by animateFloatAsState(
            targetValue   = if (isPressed) 0.90f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label         = "baseScale"
        )
        val baseAlpha by animateFloatAsState(
            targetValue   = if (isEdgeHidden) 0.45f else 1f,
            animationSpec = tween(300),
            label         = "baseAlpha"
        )
        val edgeTranslationX = 0f

        val breathTransition = rememberInfiniteTransition(label = "breath")
        val breathAlpha by breathTransition.animateFloat(
            initialValue  = 1f,
            targetValue   = 0.2f,
            animationSpec = infiniteRepeatable(
                animation  = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathAlpha"
        )
        val breathScale by breathTransition.animateFloat(
            initialValue  = 1f,
            targetValue   = 1.2f,
            animationSpec = infiniteRepeatable(
                animation  = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathScale"
        )
        val animBallX by animateFloatAsState(
            targetValue   = ballOffsetX,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label         = "ballX"
        )
        val animBallY by animateFloatAsState(
            targetValue   = ballOffsetY,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            label         = "ballY"
        )

        LaunchedEffect(windowX, windowY) {
            joystickParams.x = windowX.roundToInt()
            joystickParams.y = windowY.roundToInt()
            joystickView?.let { windowManager.updateViewLayout(it, joystickParams) }
        }

        // 保持 Compose 内容尺寸和 WindowManager 触控窗口一致，调整时维持中心位置。
        LaunchedEffect(baseSizePx) {
            val previousSizePx = joystickParams.width.takeIf { it > 0 } ?: baseSizePx.roundToInt()
            val targetSizePx = baseSizePx.roundToInt()
            if (previousSizePx != targetSizePx) {
                val centerX = windowX + previousSizePx / 2f
                val centerY = windowY + previousSizePx / 2f
                joystickParams.width = targetSizePx
                joystickParams.height = targetSizePx
                windowX = centerX - targetSizePx / 2f
                windowY = centerY - targetSizePx / 2f
            }
        }

        // ── 边缘吸附逻辑 ──────────────────────────────────
        fun checkAndStartEdgeTimer() {
            edgeTimerJob?.cancel()
            val margin    = 30f
            val nearLeft  = windowX < margin
            val nearRight = windowX > screenWidth - baseSizePx - margin
            if (nearLeft || nearRight) {
                edgeTimerJob = scope.launch {
                    delay(1200)
                    isEdgeHidden = true
                    windowX = if (nearLeft)
                        -(baseSizePx * 0.5f)
                    else
                        screenWidth - baseSizePx * 0.5f
                }
            }
        }

        // ── 单击 / 双击处理 ──────────────────────────────
        fun handleTap() {
            tapCount++
            tapTimeoutJob?.cancel()
            if (tapCount >= 2) {
                tapCount = 0
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                checkAndStartEdgeTimer()
            } else {
                tapTimeoutJob = scope.launch {
                    delay(200)
                    if (tapCount == 1) {
                        if (isConfirmPending && currentFocusState.value != null) {
                            performClickOnTarget(currentFocusState.value)
                            isConfirmPending = false
                            currentFocusState.value = null
                            confirmTimerJob?.cancel()
                            navEngine.reset()
                            meshHandler.removeCallbacks(meshRunnable)
                            meshHandler.postDelayed(meshRunnable, 400L)
                        } else {
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                    }
                    tapCount = 0
                    checkAndStartEdgeTimer()
                }
            }
        }

        // ══════════════════════════════════════════════════
        // 悬浮球 UI
        // ══════════════════════════════════════════════════
        Box(
            modifier = Modifier
                .size(baseSizeDp)
                .graphicsLayer {
                    scaleX       = baseScale
                    scaleY       = baseScale
                    // 透明度 = 边缘自动调暗 × 用户不透明度（休眠时固定35%）
                    alpha        = baseAlpha * (if (TouchEngineService.liveEnabled.value) TouchEngineService.liveBallOpacity.floatValue else 0.35f)
                    translationX = edgeTranslationX
                }
                .background(
                    color = Color(0xFF1C1C1E).copy(alpha = 0.82f),
                    shape = CircleShape
                )
                .border(
                    width = 1.5.dp,
                    color = when {
                        isConfirmPending -> Color(0xFF34C759).copy(alpha = 0.9f)
                        isDraggingWindow -> Color(0xFFFFCC00).copy(alpha = 0.8f)
                        else             -> Color(0xFFFFFFFF).copy(alpha = 0.18f)
                    },
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isPressed = true
                        refreshScrollConfig()
                        navEngine.beginGesture(scrollConfig.focusResponseMode)
                        suspendVisualOcrForSelection()
                        isJoystickSelecting = true
                        selectionPackage = activeTargetPackage()
                        pendingRefreshAfterSelection =
                            pendingRefreshAfterSelection || !isWindowSettled
                        edgeTimerJob?.cancel()

                        // 一次拉球手势由 NavMeshCoordinator 冻结同一份 ActiveMesh。
                        meshHandler.removeCallbacks(meshRunnable)
                        val gestureSession = beginNavMeshGesture(
                            targetPackage = selectionPackage,
                            nowMs = System.currentTimeMillis()
                        )
                        if (gestureSession.snapshot == null) {
                            pendingRefreshAfterSelection = true
                            dirtyReasons.add(RefreshDirtyReason.SELECTION)
                        }
                        Log.d(
                            "TouchEnginePerf",
                            "gesture mode=${scrollConfig.focusResponseMode} source=${gestureSession.source} " +
                                "nodes=${gestureSession.nodes.size}"
                        )
                        fun gestureNodes(): List<NavNode> = activeNavGestureNodes()

                        // 缓存起始节点
                        val priorFocus = currentFocusState.value
                        var dragStartNode: NavNode? = gestureNodes().minByOrNull { n ->
                            val targetX = priorFocus?.centerX ?: (windowX + baseSizePx / 2)
                            val targetY = priorFocus?.centerY ?: (windowY + baseSizePx / 2)
                            hypot(
                                (n.centerX - targetX).toDouble(),
                                (n.centerY - targetY).toDouble()
                            )
                        }
                        if (priorFocus != null && dragStartNode != null) {
                            currentFocusState.value = dragStartNode
                        } else if (priorFocus == null) {
                            currentFocusState.value = null
                        }

                        // ── 唤醒边缘吸附状态 ──────────────────────────
                        if (isEdgeHidden) {
                            isEdgeHidden = false
                            windowX = if (windowX < screenWidth / 2)
                                with(density) { 16.dp.toPx() }
                            else
                                screenWidth - baseSizePx - with(density) { 16.dp.toPx() }
                            do { awaitPointerEvent() }
                            while (awaitPointerEvent().changes.any { it.pressed })
                            isPressed = false
                            isJoystickSelecting = false
                            selectionPackage = null
                            endNavMeshGesture()
                            dragStartNode = null
                            refreshAfterSelectionIfNeeded()
                            return@awaitEachGesture
                        }

                        var dragStarted        = false
                        var longPressTriggered = false
                        var lastDragX          = 0f
                        var lastDragY          = 0f
                        var lastInputEventMs   = System.currentTimeMillis()
                        var gestureSlowEvents  = 0

                        val longPressJob = scope.launch {
                            delay(400)
                            longPressTriggered = true
                            isDraggingWindow   = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }

                        try {
                            do {
                                val event  = awaitPointerEvent()
                                val inputEventMs = System.currentTimeMillis()
                                if (dragStarted && inputEventMs - lastInputEventMs > 32L) {
                                    gestureSlowEvents++
                                }
                                lastInputEventMs = inputEventMs
                                val change = event.changes.first()
                                val rawDx   = change.position.x - down.position.x
                                val rawDy   = change.position.y - down.position.y
                                val rawDist = hypot(rawDx, rawDy)

                                if (!dragStarted && rawDist > viewConfig.touchSlop) {
                                    dragStarted = true
                                    if (!longPressTriggered) longPressJob.cancel()
                                }

                                if (dragStarted) {
                                    if (longPressTriggered) {
                                        // ── 窗口拖拽模式 ──────────────────────
                                        windowX = (windowX + change.positionChange().x)
                                            .coerceIn(0f, screenWidth - baseSizePx)
                                        windowY = (windowY + change.positionChange().y)
                                            .coerceIn(0f, (screenHeight - baseSizePx).toFloat())
                                        ballOffsetX = 0f
                                        ballOffsetY = 0f
                                    } else {
                                        // ── 摇杆模式 ──────────────────────────
                                        val clampedX: Float
                                        val clampedY: Float
                                        if (rawDist > MAX_BALL_OFFSET) {
                                            val s = MAX_BALL_OFFSET / rawDist
                                            clampedX = rawDx * s
                                            clampedY = rawDy * s
                                        } else {
                                            clampedX = rawDx
                                            clampedY = rawDy
                                        }
                                        ballOffsetX = clampedX
                                        ballOffsetY = clampedY
                                        lastDragX   = clampedX
                                        lastDragY   = clampedY

                                        if (scrollState == ScrollState.PLAN_B) {
                                            if (!isHoldingAutoScrollDirection(clampedX, clampedY, MAX_BALL_OFFSET)) {
                                                stopPlanB()
                                                dragStartNode = null
                                            }
                                            change.consume()
                                            continue
                                        }

                                        // 弹窗接管可以替换 ActiveMesh；普通内容更新不得在拖动中改变它。
                                        if (dragStartNode == null && currentFocusState.value == null && gestureNodes().isNotEmpty()) {
                                            dragStartNode = gestureNodes().minByOrNull { n ->
                                                hypot(
                                                    (n.centerX - (windowX + baseSizePx / 2)).toDouble(),
                                                    (n.centerY - (windowY + baseSizePx / 2)).toDouble()
                                                )
                                            }
                                        }

                                        // ── 调用导航引擎 ──────────────────────
                                        val startNode = currentFocusState.value ?: dragStartNode
                                        // [FIX UI] 服务关闭时跳过所有导航逻辑，仅保留球位置显示
                                        if (startNode != null && TouchEngineService.liveEnabled.value) {
                                            val result = navEngine.onDrag(
                                                currentNode = startNode,
                                                dragX       = clampedX,
                                                dragY       = clampedY,
                                                maxRadius   = MAX_BALL_OFFSET
                                            )
                                            if (result.node !== currentFocusState.value) {
                                                currentFocusState.value = result.node
                                                resetEdgeTracking()
                                                cancelScroll() // [FIX 3] 原来是 cancelHint()，现在覆盖 PLAN_B
                                            }
                                            // 边缘检测
                                            val ratio = hypot(clampedX.toDouble(), clampedY.toDouble()).toFloat() / MAX_BALL_OFFSET

                                            // [FIX STUCK NAV] 固定导航栏脱困：
                                            // 底部/顶部固定UI（底部Tab栏、顶部Title栏）的节点，
                                            // 因偏角超出 CONE_HALF_ANGLE(45°) 而找不到上/下邻居。
                                            // 检测到此情况时，直接全局搜索最近的上/下方节点跳过去，
                                            // 避免用户卡在导航栏里无法回到内容区域。
                                            var edgeHandled = false
                                            if (result.atEdge && ratio >= 0.28f && scrollState == ScrollState.IDLE) {
                                                val escDir  = dirFromAngle(result.intentAngle)
                                                val nodeY   = result.node.centerY
                                                val enteringContent =
                                                    (escDir == ScrollDir.UP && nodeY > screenHeight * 0.82f) ||
                                                    (escDir == ScrollDir.DOWN && nodeY < screenHeight * 0.18f)
                                                val escapeNode: NavNode? = when {
                                                    escDir == ScrollDir.UP &&
                                                    nodeY > screenHeight * 0.82f -> {
                                                        // 底部固定区域 → 向上找最近节点
                                                        gestureNodes()
                                                            .filter { n -> n !== result.node && n.centerY < nodeY - 30f }
                                                            .minByOrNull { n -> nodeY - n.centerY }
                                                    }
                                                    escDir == ScrollDir.DOWN &&
                                                    nodeY < screenHeight * 0.18f -> {
                                                        // 顶部固定区域 → 向下找最近节点
                                                        gestureNodes()
                                                            .filter { n -> n !== result.node && n.centerY > nodeY + 30f }
                                                            .minByOrNull { n -> n.centerY - nodeY }
                                                    }
                                                    else -> null
                                                }
                                                if (enteringContent) {
                                                    edgeHandled = true
                                                    if (escapeNode != null) {
                                                        currentFocusState.value = escapeNode
                                                        navEngine.reset()
                                                        resetEdgeTracking()
                                                    } else {
                                                        // 向内容区移动却暂无内容节点时，等待松手刷新，不能误触发滑动。
                                                        pendingRefreshAfterSelection = true
                                                    }
                                                }
                                            }

                                            if (!edgeHandled && result.atEdge && ratio >= 0.28f
                                                && isRealEdge(result.node, result.intentAngle)
                                                && scrollState == ScrollState.IDLE) {
                                                val dir = dirFromAngle(result.intentAngle)
                                                handleEdge(result.node, dir, ratio) // [FIX 1] 无 scope 参数
                                            } else if (!result.atEdge) {
                                                if (result.node !== startNode) {
                                                    resetEdgeTracking()
                                                }
                                            } else if (result.atEdge && scrollState != ScrollState.IDLE) {
                                                // [FIX 6] 摇杆"拉回"检测：
                                                // 处于滚动状态时，如果用户把摇杆拉向与当前边缘方向相反的方向
                                                // （夹角 > 90°），则立即取消滚动状态，释放焦点锁定，
                                                // 让摇杆恢复正常的导航响应，防止焦点卡死在边缘节点出不来。
                                                if (isPullingAwayFromEdge(clampedX, clampedY)) {
                                                    cancelScroll()
                                                }
                                            }
                                        }
                                    }
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            longPressJob.cancel()
                            isPressed        = false
                            isDraggingWindow = false
                            isJoystickSelecting = false
                            selectionPackage = null
                            endNavMeshGesture()
                            dragStartNode    = null  // [FIX 5] 手势结束后清空缓存
                            if (gestureSlowEvents > 0) {
                                slowGestureIntervals += gestureSlowEvents
                                Log.d(
                                    "TouchEnginePerf",
                                    "gestureSlowIntervals=$gestureSlowEvents total=$slowGestureIntervals " +
                                        "mode=${scrollConfig.focusResponseMode}"
                                )
                            }

                            if (dragStarted && !longPressTriggered) {
                                // ── 摇杆松手 ──────────────────────────────
                                if (scrollState == ScrollState.PLAN_B) {
                                    stopPlanB() // [FIX 1] 无 scope 参数
                                } else {
                                    cancelScroll() // [FIX 3] 原来是 cancelHint()
                                }
                                val startNode = currentFocusState.value
                                if (startNode != null) {
                                    val result = navEngine.onRelease(
                                        currentNode = startNode,
                                        dragX       = lastDragX,
                                        dragY       = lastDragY,
                                        maxRadius   = MAX_BALL_OFFSET
                                    )
                                    if (result !== currentFocusState.value) {
                                        currentFocusState.value = result
                                        resetEdgeTracking()
                                    } else {
                                        // 点拨到边缘：尝试单次翻页（仅方案A）
                                        val ratio = hypot(lastDragX.toDouble(), lastDragY.toDouble()).toFloat() / MAX_BALL_OFFSET
                                        val ang   = Math.toDegrees(kotlin.math.atan2(lastDragY.toDouble(), lastDragX.toDouble()))
                                        if (ratio >= 0.5f && isRealEdge(startNode, ang)) {
                                            handleFlickEdge(startNode, dirFromAngle(ang), ratio)
                                        }
                                    }
                                }
                                navEngine.reset()
                                resetEdgeTracking()

                                // 进入确认状态（仅在非滚动状态下）
                                if (currentFocusState.value != null && scrollState == ScrollState.IDLE) {
                                    isConfirmPending = true
                                    confirmTimerJob?.cancel()
                                    confirmTimerJob = scope.launch {
                                        delay(3000)
                                        isConfirmPending = false
                                        currentFocusState.value = null
                                    }
                                }
                            }
                            refreshAfterSelectionIfNeeded()
                            ballOffsetX = 0f
                            ballOffsetY = 0f
                            if (!dragStarted && !longPressTriggered) {
                                handleTap()
                            }
                            if (longPressTriggered) {
                                checkAndStartEdgeTimer()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // ── 底座内部纹理 ─────────────────────────────────
            Box(
                modifier = Modifier
                    .size(baseSizeDp - 8.dp)
                    .background(
                        color = Color(0xFF2C2C2E).copy(alpha = 0.6f),
                        shape = CircleShape
                    )
            )
            // ── 操控小球 ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .size(ballSizeDp)
                    .graphicsLayer {
                        translationX = animBallX
                        translationY = animBallY
                        if (isConfirmPending) {
                            alpha  = breathAlpha
                            scaleX = breathScale
                            scaleY = breathScale
                        }
                    }
                    .background(
                        color = when {
                            isConfirmPending -> Color(0xFF34C759)
                            isDraggingWindow -> Color(0xFFFFCC00)
                            else             -> Color(0xFFFFFFFF)
                        },
                        shape = CircleShape
                    )
            )
        }
    }

    // =====================================================
    // Focus Highlight
    // =====================================================

    // =====================================================
    // Compose Lifecycle
    // =====================================================

    fun setupComposeLifecycle(view: ComposeView) {
        val owner = ComposeLifecycleOwner()
        owner.performRestore(null)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
    }

    class ComposeLifecycleOwner :
        LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry            = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store                        = ViewModelStore()
        override val lifecycle: Lifecycle
            get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore
            get() = store
        fun handleLifecycleEvent(e: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(e)
        fun performRestore(s: Bundle?)               = savedStateRegistryController.performRestore(s)
    }
}
