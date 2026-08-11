package com.example.touchengine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/**
 * 视觉扫描调度器。
 *
 * 职责：截图调度、OCR→YOLO 异步链、generation 失效保护、Object Evidence、刷新节流、
 * 视觉显示状态与 PendingVisualCache 暂存。
 *
 * 不负责：无障碍缺口确认、系统 AccessibilityService API、NavMesh 构建与安装。
 */
internal class VisualScanCoordinator(
    private val recognizer: VisualRecognizer,
    private val cache: PersistentVisualCanvas,
    private val screenshotProvider: VisualScreenshotProvider,
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val runtimeProvider: () -> VisualScanRuntime,
    private val requestProvider: () -> VisualScanRequest?,
    private val installVisualNodes: (PendingVisualCache, Int, () -> Unit) -> Unit,
    private val invalidateActiveVisualNodes: () -> Unit,
    private val clearVisualHoles: () -> Unit
) {

    val visualOcrState = mutableStateOf(VisualOcrDisplay())
    val objectEvidenceState = mutableStateOf(ObjectEvidenceDisplay())

    private var generation = 0
    private var pageEpoch = 0L
    private var viewportRevision = 0L
    private var nextScanId = 0L
    /** soft invalidation 时不推进页面 token，因此用 scanId 下限废弃旧截图回调。 */
    private var minimumValidScanId = 0L
    private var ocrInFlight = false
    private var probeInFlight = false
    private var activeOcrScanId: Long? = null
    private var activeProbeScanId: Long? = null
    private var objectEvidenceInFlight = false
    private var objectEvidenceGeneration = 0
    private var lastObjectEvidenceAtMs = 0L
    private var lastContentInvalidationAtMs = 0L
    private var pendingRefreshReason: VisualCacheInvalidationReason? = null
    private val marqueeGuard = VisualMarqueeGuard()

    private data class QueuedVisualInstall(
        val pending: PendingVisualCache,
        val scanGeneration: Int
    )

    /** 白纸安装必须串行；否则稍晚到达的 OCR/FUSED 会覆盖尚未提交的 YOLO 直入。 */
    private val installQueue = ArrayDeque<QueuedVisualInstall>()
    private var activeInstall: QueuedVisualInstall? = null
    private var committedCaptureMode = VisualCaptureMode.DISPLAY_FALLBACK

    /** Service 构建最终融合结果时，使用正在安装批次的截图语义。 */
    val currentVisualCaptureMode: VisualCaptureMode
        get() = activeInstall?.pending?.captureMode ?: committedCaptureMode

    // Phase 9B.1: once a WeChat message-list scan yields stable rows, retain that
    // geometry briefly for the same page/viewport. This prevents one weak OCR/YOLO
    // frame from dropping half the rows, while pageEpoch/viewportRevision changes
    // still invalidate the hold immediately.
    private var retainedWeChatRows: List<NavNode> = emptyList()
    private var retainedWeChatRowsUntilMs = 0L
    private var retainedWeChatPageEpoch = Long.MIN_VALUE
    private var retainedWeChatViewportRevision = Long.MIN_VALUE

    // Phase 8C: joystick selection is a real pause boundary for the visual pipeline.
    // Any scan/screenshot/OCR/YOLO/pending install cancelled by the selection must be
    // deterministically re-queued after the finger is released.
    private var scanScheduled = false
    private var selectionSuspended = false
    private var selectionResumeRequired = false
    private var selectionResumeAttempts = 0
    private var selectionResumeDelayMs = SELECTION_RESUME_INITIAL_DELAY_MS
    private var selectionResumeCause: String? = null

    // Phase 8C.1: scan requests must survive transient foreground/root gaps too.
    // App switches can briefly expose no primary root on MIUI. Previously a scheduled
    // scan reached requestProvider()==null once and was silently lost forever.
    private var scanRetryAttempts = 0
    private var scanRetryReason: String? = null

    private val scanRunnable = Runnable {
        scanScheduled = false
        scanIfIdle()
    }
    private val selectionResumeRunnable = Runnable { attemptSelectionResume() }

    val hasCachedNodes: Boolean
        get() = cache.hasCachedNodes

    val cachedNodeCount: Int
        get() = cache.nodeCount

    val hasScanInFlight: Boolean
        get() = ocrInFlight || probeInFlight

    private fun enqueueVisualInstall(pending: PendingVisualCache, scanGeneration: Int) {
        if (scanGeneration != generation || !isTokenCurrent(pending.token)) return
        installQueue.addLast(QueuedVisualInstall(pending, scanGeneration))
        drainVisualInstallQueue()
    }

    private fun drainVisualInstallQueue() {
        if (activeInstall != null) return
        while (installQueue.isNotEmpty()) {
            val next = installQueue.removeFirst()
            if (next.scanGeneration != generation || !isTokenCurrent(next.pending.token)) continue
            activeInstall = next
            cache.stage(next.pending)
            installVisualNodes(next.pending, next.scanGeneration) {
                finishVisualInstall(next)
            }
            return
        }
    }

    private fun finishVisualInstall(finished: QueuedVisualInstall) {
        if (activeInstall !== finished) return
        activeInstall = null
        drainVisualInstallQueue()
    }

    private fun discardQueuedVisualInstalls() {
        installQueue.clear()
        activeInstall = null
        cache.discardPending()
    }

    private fun clearRetainedWeChatRows() {
        retainedWeChatRows = emptyList()
        retainedWeChatRowsUntilMs = 0L
        retainedWeChatPageEpoch = Long.MIN_VALUE
        retainedWeChatViewportRevision = Long.MIN_VALUE
    }

    private fun stabilizeWeChatMessageRows(
        nodes: List<NavNode>,
        targetPackage: String?,
        token: VisualScanToken,
        ocrCandidates: List<VisualOcrCandidate>,
        screenHeight: Int
    ): List<NavNode> {
        if (targetPackage != "com.tencent.mm") {
            clearRetainedWeChatRows()
            return nodes
        }

        val currentRows = nodes.filter(::isWeChatVisualListNode)
        val nonRows = nodes.filterNot(::isWeChatVisualListNode)
        val now = SystemClock.elapsedRealtime()
        val sameSession = retainedWeChatPageEpoch == token.pageEpoch &&
            retainedWeChatViewportRevision == token.viewportRevision
        val hasHomeChrome = hasWeChatHomeChrome(ocrCandidates, screenHeight)

        if (currentRows.size >= 2) {
            val stabilizedRows = if (
                sameSession &&
                retainedWeChatRows.isNotEmpty() &&
                now <= retainedWeChatRowsUntilMs &&
                hasHomeChrome
            ) {
                mergeCurrentAndRetainedWeChatRows(
                    currentRows = currentRows,
                    retainedRows = retainedWeChatRows,
                    screenHeight = screenHeight
                )
            } else {
                currentRows.map(::cloneNavNodeForRetention)
            }
            retainedWeChatRows = stabilizedRows.map(::cloneNavNodeForRetention)
            retainedWeChatRowsUntilMs = now + WECHAT_LIST_ROW_HOLD_MS
            retainedWeChatPageEpoch = token.pageEpoch
            retainedWeChatViewportRevision = token.viewportRevision
            if (stabilizedRows.size != currentRows.size) {
                Log.d(
                    "TouchEngineWeChat",
                    "hysteresis mergedRows=${stabilizedRows.size} currentRows=${currentRows.size}"
                )
            }
            return nonRows + stabilizedRows
        }

        val canReuse = sameSession &&
            retainedWeChatRows.size >= 2 &&
            now <= retainedWeChatRowsUntilMs &&
            (hasHomeChrome || currentRows.isNotEmpty())
        if (!canReuse) {
            if (!sameSession || now > retainedWeChatRowsUntilMs) clearRetainedWeChatRows()
            return nodes
        }

        val retainedRows = retainedWeChatRows.map(::cloneNavNodeForRetention)
        Log.d(
            "TouchEngineWeChat",
            "hysteresis retainedRows=${retainedRows.size} currentRows=${currentRows.size} " +
                "holdRemaining=${retainedWeChatRowsUntilMs - now}ms"
        )
        return nonRows + retainedRows
    }

    private fun mergeCurrentAndRetainedWeChatRows(
        currentRows: List<NavNode>,
        retainedRows: List<NavNode>,
        screenHeight: Int
    ): List<NavNode> {
        val tolerance = (screenHeight * 0.032f).toInt().coerceIn(36, 84)
        val merged = currentRows
            .map(::cloneNavNodeForRetention)
            .toMutableList()
        for (retained in retainedRows) {
            val hasCurrentMatch = merged.any { current ->
                kotlin.math.abs(current.bounds.centerY() - retained.bounds.centerY()) <= tolerance
            }
            if (!hasCurrentMatch) merged += cloneNavNodeForRetention(retained)
        }
        return merged.sortedBy { it.bounds.top }
    }

    private fun isWeChatVisualListNode(node: NavNode): Boolean {
        return node.origin == NavNodeOrigin.VISION &&
            node.description?.startsWith("VISION[ITEM]:") == true
    }

    private fun cloneNavNodeForRetention(node: NavNode): NavNode {
        val bounds = android.graphics.Rect(node.bounds)
        return node.copy(
            bounds = bounds,
            centerX = bounds.exactCenterX(),
            centerY = bounds.exactCenterY()
        )
    }

    private fun hasWeChatHomeChrome(
        candidates: List<VisualOcrCandidate>,
        screenHeight: Int
    ): Boolean {
        if (screenHeight <= 0) return false
        val header = candidates.any { candidate ->
            candidate.bounds.centerY() < screenHeight * 0.16f &&
                normalizeWeChatChromeText(candidate.text).let {
                    it == "微信" || it.startsWith("微信") ||
                        it.equals("wechat", ignoreCase = true) ||
                        it.startsWith("wechat", ignoreCase = true)
                }
        }
        val bottomCount = candidates.asSequence()
            .filter { it.bounds.centerY() > screenHeight * 0.84f }
            .map { normalizeWeChatChromeText(it.text) }
            .filter {
                it == "微信" || it.equals("wechat", ignoreCase = true) ||
                    it == "通讯录" || it == "发现" || it == "我"
            }
            .map { it.lowercase() }
            .distinct()
            .count()
        return header || bottomCount >= 2
    }

    private fun normalizeWeChatChromeText(text: String): String {
        return text.replace("\\s+".toRegex(), "").trim()
    }

    fun prewarm() {
        scope.launch(Dispatchers.Default) {
            try {
                val result = recognizer.prewarmYolo()
                Log.d(
                    "TouchEngineVision",
                    "yoloPrewarm backend=${result.backendName} elapsed=${result.elapsedMs}ms"
                )
            } catch (error: Throwable) {
                Log.d("TouchEngineVision", "yoloPrewarm failed: ${error.message}")
            }
        }
    }

    fun retainedBandsFor(targetPackage: String?): Set<EdgeInteractionRules.GenericContentBand> {
        return cache.retainedBandsFor(targetPackage, pageEpoch, viewportRevision)
    }

    fun mergeCachedNodes(
        base: List<NavNode>,
        sampledPackage: String?,
        pageSignature: Int,
        sceneTag: String
    ): List<NavNode> {
        return cache.mergeCachedNodes(
            base = base,
            sampledPackage = sampledPackage,
            pageSignature = pageSignature,
            sceneTag = sceneTag,
            pageEpoch = pageEpoch,
            viewportRevision = viewportRevision
        )
    }

    fun scheduleDelayMs(): Long {
        val strongRefresh = isStrongRefreshReason(pendingRefreshReason)
        return when {
            strongRefresh -> 90L
            !cache.hasCachedNodes -> 160L
            else -> 420L
        }
    }

    fun scheduleScan(delayMs: Long) {
        if (selectionSuspended) {
            markSelectionResumeRequired(
                cause = "scheduleScan",
                preferredDelayMs = delayMs
            )
            return
        }
        handler.removeCallbacks(scanRunnable)
        scanRetryAttempts = 0
        scanRetryReason = "scheduled"
        scanScheduled = true
        handler.postDelayed(scanRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleScanRetry(reason: String) {
        if (selectionSuspended) {
            markSelectionResumeRequired(
                cause = "scanRetry:$reason",
                preferredDelayMs = SELECTION_RESUME_RETRY_DELAYS_MS.first()
            )
            return
        }
        if (scanRetryAttempts >= MAX_SCAN_RETRY_ATTEMPTS) {
            Log.w(
                "TouchEngineVision",
                "scanRetry exhausted reason=$reason attempts=$scanRetryAttempts"
            )
            scanRetryAttempts = 0
            scanRetryReason = null
            return
        }
        val index = scanRetryAttempts.coerceAtMost(SCAN_RETRY_DELAYS_MS.lastIndex)
        val delayMs = SCAN_RETRY_DELAYS_MS[index]
        scanRetryAttempts++
        scanRetryReason = reason
        handler.removeCallbacks(scanRunnable)
        scanScheduled = true
        handler.postDelayed(scanRunnable, delayMs)
        Log.d(
            "TouchEngineVision",
            "scanRetry attempt=$scanRetryAttempts reason=$reason delay=${delayMs}ms"
        )
    }

    fun requestContentRefresh(
        eventSummary: String,
        eventPackage: String?,
        delayMs: Long,
        clearBeforeProbe: Boolean,
        clearCapabilityProbes: () -> Unit,
        refreshHoleDisplay: () -> Unit
    ) {
        val runtime = runtimeProvider()
        if (runtime.isJoystickSelecting || selectionSuspended) {
            if (pendingRefreshReason == null) {
                pendingRefreshReason = VisualCacheInvalidationReason.CONTENT_CHANGE
            }
            markSelectionResumeRequired(
                cause = "contentRefresh:$eventSummary",
                preferredDelayMs = delayMs
            )
            return
        }
        if (!runtime.scrollIdle) return

        val hasVisualTargets = hasAnyVisualState(runtime)
        if (!hasVisualTargets && ocrInFlight) return

        val now = SystemClock.elapsedRealtime()
        val minimumInterval = if (EdgeInteractionRules.isHighFrequencyPackage(eventPackage)) {
            650L
        } else {
            900L
        }

        if (clearBeforeProbe) {
            if (hasVisualTargets && now - lastContentInvalidationAtMs >= minimumInterval) {
                lastContentInvalidationAtMs = now
                invalidatePendingWorkPreservingCanvas(
                    reason = VisualCacheInvalidationReason.CONTENT_CHANGE,
                    eventSummary = eventSummary
                )
                clearCapabilityProbes()
            } else if (pendingRefreshReason == null) {
                pendingRefreshReason = VisualCacheInvalidationReason.CONTENT_CHANGE
            }
            refreshHoleDisplay()
            scheduleScan(delayMs)
            return
        }


        if (now - lastContentInvalidationAtMs < minimumInterval && cache.hasCachedNodes) {
            if (pendingRefreshReason == null) {
                pendingRefreshReason = VisualCacheInvalidationReason.CONTENT_CHANGE
            }
            scheduleScan(minimumInterval)
            return
        }

        lastContentInvalidationAtMs = now
        if (pendingRefreshReason == null) {
            pendingRefreshReason = VisualCacheInvalidationReason.CONTENT_CHANGE
        }
        Log.d(
            "TouchEngineVision",
            "probe reason=${VisualCacheInvalidationReason.CONTENT_CHANGE} $eventSummary " +
                "nodes=${cache.nodeCount} delay=${delayMs}ms"
        )
        refreshHoleDisplay()
        scheduleScan(delayMs)
    }

    /**
     * 白纸软失效：取消旧扫描与待安装结果，但保留已提交白纸和当前 StableMesh。
     *
     * 同一页面中的 CONTENT_CHANGED 经常只是节点新增、删除或文字变化，不应先清空视觉
     * 节点再等待下一次识别。generation 的推进足以阻止旧异步结果写回；新扫描完成后由
     * PersistentVisualCanvas 生成节点 Patch。
     */
    private fun invalidatePendingWorkPreservingCanvas(
        reason: VisualCacheInvalidationReason,
        eventSummary: String
    ) {
        generation += 1
        minimumValidScanId = nextScanId + 1L
        activeOcrScanId = null
        activeProbeScanId = null
        ocrInFlight = false
        probeInFlight = false
        handler.removeCallbacks(scanRunnable)
        scanScheduled = false
        discardQueuedVisualInstalls()
        objectEvidenceGeneration += 1
        objectEvidenceInFlight = false
        pendingRefreshReason = reason
        Log.d(
            "TouchEngineVision",
            "canvasSoftInvalidate reason=$reason $eventSummary nodes=${cache.nodeCount} " +
                "epoch=$pageEpoch viewport=$viewportRevision generation=$generation"
        )
    }

    fun logRetained(reason: String, eventSummary: String, selecting: Boolean, scrollState: ScrollState) {
        if (!cache.hasCachedNodes && !ocrInFlight) return
        Log.d(
            "TouchEngineVision",
            "retain reason=$reason $eventSummary surface=${cache.currentSurfaceKey} " +
                "nodes=${cache.nodeCount} selecting=$selecting scroll=$scrollState"
        )
    }

    fun clear(
        reason: VisualCacheInvalidationReason?,
        eventSummary: String,
        selecting: Boolean,
        scrollState: ScrollState
    ) {
        invalidateSession(
            reason = reason,
            eventSummary = eventSummary,
            selecting = selecting,
            scrollState = scrollState,
            force = true
        )
    }

    /**
     * 统一终止当前视觉会话。
     *
     * 页面切换、滚动、翻页或点击开始时必须先调用这里。它会让所有旧截图、OCR、YOLO、
     * Object Evidence 与 PendingVisualCache 立即过期，并同步移除 NavMesh/Overlay 中的视觉节点。
     */
    fun invalidateSession(
        reason: VisualCacheInvalidationReason?,
        eventSummary: String,
        selecting: Boolean,
        scrollState: ScrollState,
        force: Boolean = true
    ) {
        val runtime = runCatching { runtimeProvider() }.getOrNull()
        val hasState = runtime?.let(::hasAnyVisualState) ?: (
            cache.hasCachedNodes || cache.pending != null || installQueue.isNotEmpty() ||
                activeInstall != null || ocrInFlight || probeInFlight
        )
        if (!force && !hasState) {
            if (reason != null) pendingRefreshReason = reason
            return
        }

        if (reason != null) {
            Log.d(
                "TouchEngineVision",
                "invalidate reason=$reason $eventSummary surface=${cache.currentSurfaceKey} " +
                    "nodes=${cache.nodeCount} selecting=$selecting scroll=$scrollState " +
                    "epoch=$pageEpoch viewport=$viewportRevision"
            )
            pendingRefreshReason = reason
        }

        val resumeAfterSelection = selectionSuspended || selecting
        advanceSession(reason)
        generation++
        activeOcrScanId = null
        activeProbeScanId = null
        ocrInFlight = false
        probeInFlight = false
        handler.removeCallbacks(scanRunnable)
        scanScheduled = false
        discardQueuedVisualInstalls()
        cache.clear()
        committedCaptureMode = VisualCaptureMode.DISPLAY_FALLBACK
        clearRetainedWeChatRows()

        objectEvidenceGeneration++
        objectEvidenceInFlight = false
        objectEvidenceState.value = ObjectEvidenceDisplay()
        visualOcrState.value = VisualOcrDisplay()
        clearVisualHoles()
        invalidateActiveVisualNodes()
        if (resumeAfterSelection) {
            markSelectionResumeRequired(
                cause = "invalidate:${reason ?: "unknown"}",
                preferredDelayMs = SELECTION_RESUME_INITIAL_DELAY_MS
            )
        }
    }

    fun setPausedOnSettings() {
        invalidateSession(
            reason = VisualCacheInvalidationReason.SURFACE_REPLACED,
            eventSummary = "settings",
            selecting = false,
            scrollState = ScrollState.IDLE,
            force = true
        )
        handler.removeCallbacks(selectionResumeRunnable)
        selectionSuspended = false
        selectionResumeRequired = false
        selectionResumeAttempts = 0
        selectionResumeDelayMs = SELECTION_RESUME_INITIAL_DELAY_MS
        selectionResumeCause = null
        visualOcrState.value = VisualOcrDisplay(status = "VISION paused on settings")
    }

    fun suspendForSelection() {
        selectionSuspended = true
        handler.removeCallbacks(selectionResumeRunnable)

        val hadScheduledScan = scanScheduled
        val hadScanInFlight = ocrInFlight || probeInFlight
        val hadPendingInstall = cache.pending != null || installQueue.isNotEmpty() || activeInstall != null
        val hadObjectEvidenceInFlight = objectEvidenceInFlight
        val hadRefreshIntent = pendingRefreshReason != null

        handler.removeCallbacks(scanRunnable)
        scanScheduled = false

        if (hadScanInFlight) {
            generation++
            activeOcrScanId = null
            activeProbeScanId = null
            ocrInFlight = false
            probeInFlight = false
        }
        if (hadPendingInstall) {
            discardQueuedVisualInstalls()
        }
        if (hadObjectEvidenceInFlight) {
            objectEvidenceGeneration++
            objectEvidenceInFlight = false
        }

        if (hadScheduledScan || hadScanInFlight || hadPendingInstall ||
            hadObjectEvidenceInFlight || hadRefreshIntent
        ) {
            if (pendingRefreshReason == null) {
                pendingRefreshReason = VisualCacheInvalidationReason.SELECTION_FROZEN
            }
            markSelectionResumeRequired(
                cause = buildString {
                    append("selection")
                    if (hadScheduledScan) append("+scheduled")
                    if (hadScanInFlight) append("+inFlight")
                    if (hadPendingInstall) append("+pendingInstall")
                    if (hadObjectEvidenceInFlight) append("+objectEvidence")
                    if (hadRefreshIntent) append("+refreshIntent")
                },
                preferredDelayMs = SELECTION_RESUME_INITIAL_DELAY_MS
            )
        }

        Log.d(
            "TouchEngineVision",
            "selectionSuspend scheduled=$hadScheduledScan inFlight=$hadScanInFlight " +
                "pending=$hadPendingInstall object=$hadObjectEvidenceInFlight " +
                "resumeRequired=$selectionResumeRequired nodes=${cache.nodeCount}"
        )
    }

    /**
     * Completes the selection pause. If the gesture cancelled any part of the current
     * visual pipeline, a fresh scan is armed even when the page itself emitted no new
     * accessibility/window event.
     */
    fun resumeAfterSelection(delayMs: Long = SELECTION_RESUME_INITIAL_DELAY_MS): Boolean {
        selectionSuspended = false
        if (!selectionResumeRequired) {
            handler.removeCallbacks(selectionResumeRunnable)
            return false
        }

        selectionResumeAttempts = 0
        selectionResumeDelayMs = minOf(selectionResumeDelayMs, delayMs.coerceAtLeast(0L))
        handler.removeCallbacks(selectionResumeRunnable)
        handler.postDelayed(selectionResumeRunnable, selectionResumeDelayMs)
        Log.d(
            "TouchEngineVision",
            "selectionResume armed cause=${selectionResumeCause ?: "unknown"} " +
                "delay=${selectionResumeDelayMs}ms"
        )
        return true
    }

    private fun markSelectionResumeRequired(cause: String, preferredDelayMs: Long) {
        selectionResumeRequired = true
        selectionResumeCause = when {
            selectionResumeCause.isNullOrBlank() -> cause
            selectionResumeCause == cause -> cause
            else -> "${selectionResumeCause}|$cause"
        }
        selectionResumeDelayMs = minOf(
            selectionResumeDelayMs,
            preferredDelayMs.coerceAtLeast(0L)
        )
    }

    private fun attemptSelectionResume() {
        if (!selectionResumeRequired || selectionSuspended) return
        scanIfIdle()
    }

    private fun scheduleSelectionResumeRetry(reason: String) {
        if (!selectionResumeRequired || selectionSuspended) return
        if (selectionResumeAttempts >= MAX_SELECTION_RESUME_ATTEMPTS) {
            Log.w(
                "TouchEngineVision",
                "selectionResume exhausted reason=$reason cause=${selectionResumeCause ?: "unknown"} " +
                    "attempts=$selectionResumeAttempts"
            )
            return
        }

        val index = selectionResumeAttempts.coerceAtMost(
            SELECTION_RESUME_RETRY_DELAYS_MS.lastIndex
        )
        val delayMs = SELECTION_RESUME_RETRY_DELAYS_MS[index]
        selectionResumeAttempts++
        handler.removeCallbacks(selectionResumeRunnable)
        handler.postDelayed(selectionResumeRunnable, delayMs)
        Log.d(
            "TouchEngineVision",
            "selectionResume retry=$selectionResumeAttempts reason=$reason delay=${delayMs}ms " +
                "cause=${selectionResumeCause ?: "unknown"}"
        )
    }

    fun isCurrentInstall(
        pending: PendingVisualCache,
        candidateGeneration: Int,
        targetPackage: String?
    ): Boolean {
        return candidateGeneration == generation &&
            pending.token.targetPackage == targetPackage &&
            isTokenCurrent(pending.token) &&
            cache.isPending(pending)
    }

    /**
     * 将 Pending 视觉观察交给白纸机制做 stableId 对应与增量准备。
     * 该调用不会修改已提交白纸；只有 NavMesh 安装成功后 [commitPending] 才真正提交。
     */
    fun prepareCanvasUpdate(
        pending: PendingVisualCache,
        candidateGeneration: Int
    ): VisualCanvasUpdate? {
        if (candidateGeneration != generation || !isTokenCurrent(pending.token)) return null
        return cache.prepareUpdate(pending)
    }

    fun commitPending(
        update: VisualCanvasUpdate,
        candidateGeneration: Int,
        selectedFocus: NavNode?,
        recordMarqueeCommit: Boolean = false
    ): VisualCacheCommitResult? {
        val pending = update.pending
        if (candidateGeneration != generation || !isTokenCurrent(pending.token)) return null
        val result = cache.commit(update, selectedFocus) ?: return null
        committedCaptureMode = pending.captureMode
        result.replacementFocus?.let { /* Service applies it after return. */ }
        // Keep the previous committed green snapshot until the Service publishes the
        // newly committed StableMesh. Pending candidates may update diagnostics/status
        // but are never allowed to become green boxes.
        visualOcrState.value = result.display.copy(
            candidates = visualOcrState.value.candidates
        )
        Log.d(
            "TouchEngineVision",
            "canvasCommit reason=${pending.reason} mode=${pending.token.scanMode} " +
                "observed=${pending.nodes.size} canvas=${update.nodes.size} " +
                "patch=+${update.patch.addedStableIds.size}/~${update.patch.updatedStableIds.size}/" +
                "-${update.patch.removedStableIds.size} retainedMissing=${update.patch.retainedMissingStableIds.size} " +
                "textEvidence=${pending.textEvidenceCount} rejected=${pending.rejectedEvidenceCount} " +
                "preserveFocus=${result.preservesFocus} surface=${pending.surfaceKey} " +
                "epoch=${pending.token.pageEpoch} viewport=${pending.token.viewportRevision} scan=${pending.token.scanId}"
        )
        marqueeGuard.recordBuildOutcome(
            observation = pending.marqueeObservation,
            committed = recordMarqueeCommit
        )
        return result
    }

    /**
     * Green boxes are a projection of a committed StableMesh or frozen ActiveMesh.
     * Pending recognition candidates must never call this method.
     */
    fun showCommittedMesh(nodes: List<NavNode>, meshName: String) {
        val reachable = NavMeshBuilder.reachableNodes(nodes)
        visualOcrState.value = visualOcrState.value.copy(
            candidates = reachable.map { node ->
                val evidence = node.visualEvidence
                VisualOcrCandidate(
                    bounds = Rect(node.bounds),
                    text = evidence?.ocrText ?: node.description.orEmpty(),
                    kind = when (evidence?.role) {
                        TargetRole.ACTION -> VisualCandidateKind.ACTION_TEXT
                        TargetRole.ICON_ENTRY -> VisualCandidateKind.ICON_ENTRY
                        TargetRole.RIGHT_RAIL -> VisualCandidateKind.RIGHT_RAIL_ICON
                        TargetRole.CARD -> VisualCandidateKind.CARD_TARGET
                        TargetRole.LIST_ITEM -> VisualCandidateKind.LIST_ITEM_TARGET
                        else -> VisualCandidateKind.TEXT_EVIDENCE
                    },
                    sourceSet = evidence?.sources ?: node.sourceSet,
                    yoloClass = evidence?.yoloClass,
                    yoloConfidence = evidence?.yoloConfidence,
                    evidenceScore = evidence?.score ?: 100,
                    directEligible = evidence?.directEligible == true,
                    clickMethod = node.clickMethod
                )
            },
            status = "$meshName nodes=${nodes.size} reachable=${reachable.size}"
        )
    }

    private fun enqueueDirectVisualNodes(
        stage: VisualUpdateStage,
        nodes: List<NavNode>,
        request: VisualScanRequest,
        captureMode: VisualCaptureMode,
        fingerprints: Map<EdgeInteractionRules.GenericContentBand, Long>,
        reason: VisualCacheInvalidationReason,
        token: VisualScanToken,
        scanGeneration: Int,
        startedAt: Long,
        textEvidenceCount: Int,
        marqueeObservation: VisualMarqueeGuard.Observation? = null,
        forceInstall: Boolean = false
    ) {
        if ((nodes.isEmpty() && !forceInstall) || scanGeneration != generation || !isTokenCurrent(token)) return
        val candidates = nodes.map { node ->
            val evidence = node.visualEvidence
            VisualOcrCandidate(
                bounds = Rect(node.bounds),
                text = evidence?.ocrText ?: node.description.orEmpty(),
                kind = when (evidence?.role) {
                    TargetRole.ACTION -> VisualCandidateKind.ACTION_TEXT
                    TargetRole.ICON_ENTRY -> VisualCandidateKind.ICON_ENTRY
                    TargetRole.RIGHT_RAIL -> VisualCandidateKind.RIGHT_RAIL_ICON
                    TargetRole.CARD -> VisualCandidateKind.CARD_TARGET
                    TargetRole.LIST_ITEM -> VisualCandidateKind.LIST_ITEM_TARGET
                    else -> VisualCandidateKind.TEXT_EVIDENCE
                },
                sourceSet = evidence?.sources ?: node.sourceSet,
                yoloClass = evidence?.yoloClass,
                yoloConfidence = evidence?.yoloConfidence,
                evidenceScore = evidence?.score ?: 0,
                directEligible = true,
                clickMethod = node.clickMethod
            )
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val pending = PendingVisualCache(
            token = token,
            stage = stage,
            captureMode = captureMode,
            nodes = nodes,
            candidates = candidates,
            targetPackage = request.runtime.targetPackage,
            bands = request.requestedBands,
            surfaceKey = request.surfaceKey,
            fingerprints = fingerprints,
            actionableSignature = visualActionableSignature(nodes),
            textEvidenceCount = textEvidenceCount,
            rejectedEvidenceCount = 0,
            elapsedMs = elapsedMs,
            reason = reason,
            diagnosticSummary = "$stage direct=${nodes.size}",
            marqueeObservation = marqueeObservation
        )
        Log.d(
            "TouchEngineVision",
            "${stage.name.lowercase()}Ready nodes=${nodes.size} elapsed=${elapsedMs}ms " +
                "epoch=${token.pageEpoch} viewport=${token.viewportRevision} scan=${token.scanId}"
        )
        enqueueVisualInstall(pending, scanGeneration)
    }

    private fun visualContext(
        runtime: VisualScanRuntime,
        visualOcclusionBounds: Rect?,
        douyinScene: DouyinScene = DouyinScene.GENERAL,
        regionPolicy: VisualRegionPolicy = VisualRegionPolicy()
    ): VisualProcessingContext {
        return VisualProcessingContext(
            screenWidth = runtime.screenWidth,
            screenHeight = runtime.screenHeight,
            density = runtime.density,
            keyboardTop = runtime.keyboardTop,
            visualOcclusionBounds = visualOcclusionBounds?.let { Rect(it) },
            inputMethodVisible = runtime.inputMethodVisible,
            accessibilityNodes = runtime.stableNodes,
            douyinScene = douyinScene,
            regionPolicy = regionPolicy
        )
    }

    fun replacePendingForInstall(pending: PendingVisualCache) {
        cache.stage(pending)
    }

    fun retainPendingWithoutInstall(
        pending: PendingVisualCache,
        candidateGeneration: Int,
        status: String
    ): VisualCacheCommitResult? {
        if (candidateGeneration != generation || !isTokenCurrent(pending.token)) return null
        val result = cache.retainPendingWithoutReplacingNodes(pending, status) ?: return null
        // NoMeaningfulChange 的核心是“不安装、不刷新 Overlay”。这里故意不写 visualOcrState，
        // 避免仅仅移动悬浮球后造成调试框/测试框闪烁。
        Log.d(
            "TouchEngineVision",
            "retainPending reason=${pending.reason} mode=${pending.token.scanMode} nodes=${pending.nodes.size} " +
                "status=$status surface=${pending.surfaceKey} " +
                "epoch=${pending.token.pageEpoch} viewport=${pending.token.viewportRevision} scan=${pending.token.scanId}"
        )
        return result
    }

    fun markVisualTapResult(success: Boolean) {
        visualOcrState.value = visualOcrState.value.copy(
            status = if (success) "VISION tapped" else "VISION tap failed"
        )
    }

    fun scanIfIdle() {
        if (selectionSuspended) {
            markSelectionResumeRequired(
                cause = "scanWhileSuspended",
                preferredDelayMs = SELECTION_RESUME_INITIAL_DELAY_MS
            )
            return
        }

        val request = requestProvider()
        if (request == null) {
            if (selectionResumeRequired) {
                scheduleSelectionResumeRetry("requestUnavailable")
            } else {
                scheduleScanRetry("requestUnavailable")
            }
            return
        }
        val initialRuntime = request.runtime
        if (initialRuntime.isJoystickSelecting) {
            markSelectionResumeRequired(
                cause = "runtimeSelecting",
                preferredDelayMs = SELECTION_RESUME_RETRY_DELAYS_MS.first()
            )
            scheduleSelectionResumeRetry("runtimeSelecting")
            return
        }
        if (!initialRuntime.scrollIdle) {
            if (selectionResumeRequired) {
                scheduleSelectionResumeRetry("scrollBusy")
            } else {
                scheduleScanRetry("scrollBusy")
            }
            return
        }
        if (ocrInFlight || probeInFlight || cache.pending != null) {
            // A newer valid scan/install is already covering the interrupted request.
            selectionResumeRequired = false
            selectionResumeAttempts = 0
            selectionResumeCause = null
            return
        }

        val resumeScanWasSelectionConfirm = selectionResumeRequired &&
            !isStrongRefreshReason(pendingRefreshReason)
        val selectedScanMode = when {
            resumeScanWasSelectionConfirm -> VisualScanMode.SELECTION_CONFIRM_DIFF
            isStrongRefreshReason(pendingRefreshReason) -> VisualScanMode.FULL_REFRESH
            else -> VisualScanMode.BASE_SCAN
        }

        selectionResumeRequired = false
        selectionResumeAttempts = 0
        selectionResumeDelayMs = SELECTION_RESUME_INITIAL_DELAY_MS
        selectionResumeCause = null
        handler.removeCallbacks(selectionResumeRunnable)
        scanRetryAttempts = 0
        scanRetryReason = null

        val token = VisualScanToken(
            pageEpoch = pageEpoch,
            viewportRevision = viewportRevision,
            scanId = ++nextScanId,
            targetPackage = initialRuntime.targetPackage,
            primaryWindowId = initialRuntime.primaryWindowId,
            scanMode = selectedScanMode
        )
        val requestedBands = request.requestedBands
        val surfaceKey = request.surfaceKey
        val hadActiveCacheBeforeCapture = cache.hasActiveCache(
            requestedSurfaceKey = surfaceKey,
            requestedBands = requestedBands,
            pageEpoch = token.pageEpoch,
            viewportRevision = token.viewportRevision
        )

        probeInFlight = true
        activeProbeScanId = token.scanId
        handler.postDelayed({
            val runtime = runtimeProvider()
            if (!isProbeTokenCurrent(token, runtime)) {
                endProbe(token)
                return@postDelayed
            }

            screenshotProvider.capture(
                request = VisualCaptureRequest(
                    targetPackage = request.runtime.targetPackage,
                    primaryWindowId = request.runtime.primaryWindowId,
                    requiredBounds = Rect(request.cropBounds),
                    screenWidth = request.runtime.screenWidth,
                    screenHeight = request.runtime.screenHeight
                ),
                onSuccess = { frame ->
                    if (!isTokenCurrent(token)) {
                        frame.bitmap.recycle()
                        endProbe(token)
                        return@capture
                    }
                    handleScreenshot(
                        frame = frame,
                        request = request,
                        token = token,
                        hadActiveCacheBeforeCapture = hadActiveCacheBeforeCapture
                    )
                },
                onFailure = { errorCode ->
                    if (!isTokenCurrent(token)) {
                        endProbe(token)
                        return@capture
                    }
                    endProbe(token)
                    visualOcrState.value = if (cache.hasCachedNodes) {
                        visualOcrState.value.copy(status = "VISION retained / screenshot denied")
                    } else {
                        VisualOcrDisplay(status = "Screenshot denied: $errorCode")
                    }
                    Log.d("TouchEngineVision", "screenshot failed: $errorCode")
                    if (!cache.hasCachedNodes) {
                        scheduleScanRetry("screenshotDenied:$errorCode")
                    }
                }
            )
        }, 0L)
    }

    private fun handleScreenshot(
        frame: VisualScreenshotFrame,
        request: VisualScanRequest,
        token: VisualScanToken,
        hadActiveCacheBeforeCapture: Boolean
    ) {
        val sourceBitmap = frame.bitmap
        if (!isTokenCurrent(token)) {
            sourceBitmap.recycle()
            endProbe(token)
            return
        }
        val cropBounds = Rect(request.cropBounds)
        if (!cropBounds.intersect(frame.boundsInScreen)) {
            sourceBitmap.recycle()
            endProbe(token)
            Log.d(
                "TouchEngineVision",
                "capture has no requested content mode=${frame.captureMode} " +
                    "frame=${frame.boundsInScreen} requested=${request.cropBounds}"
            )
            scheduleScanRetry("captureNoIntersection:${frame.captureMode}")
            return
        }
        val localCropBounds = frameLocalRect(cropBounds, frame)
        if (localCropBounds == null) {
            sourceBitmap.recycle()
            endProbe(token)
            Log.d(
                "TouchEngineVision",
                "capture bounds mismatch mode=${frame.captureMode} frame=${frame.boundsInScreen} crop=$cropBounds"
            )
            scheduleScanRetry("captureBoundsMismatch:${frame.captureMode}")
            return
        }
        val cropIsFullScreen = localCropBounds.left == 0 &&
            localCropBounds.top == 0 &&
            localCropBounds.width() == sourceBitmap.width &&
            localCropBounds.height() == sourceBitmap.height

        val cropped = try {
            if (cropIsFullScreen) {
                sourceBitmap
            } else {
                Bitmap.createBitmap(
                    sourceBitmap,
                    localCropBounds.left,
                    localCropBounds.top,
                    localCropBounds.width(),
                    localCropBounds.height()
                )
            }
        } catch (error: Throwable) {
            sourceBitmap.recycle()
            endProbe(token)
            Log.d("TouchEngineVision", "crop failed: ${error.message}")
            return
        }

        val fingerprints = try {
            if (request.fullScreenTestMode && cropIsFullScreen) {
                request.requestedBands.associateWith { visualHoleFingerprint(cropped) }
            } else {
                request.requestedBands.associateWith { band ->
                    val screenRect = Rect(request.bandBounds.getValue(band))
                    if (!screenRect.intersect(frame.boundsInScreen)) {
                        return@associateWith 0L
                    }
                    val rect = frameLocalRect(screenRect, frame)
                        ?: error("band outside capture frame: $screenRect / ${frame.boundsInScreen}")
                    val bandBitmap = Bitmap.createBitmap(
                        sourceBitmap,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                    try {
                        visualHoleFingerprint(bandBitmap)
                    } finally {
                        bandBitmap.recycle()
                    }
                }
            }
        } catch (error: Throwable) {
            if (cropped !== sourceBitmap) sourceBitmap.recycle()
            cropped.recycle()
            endProbe(token)
            Log.d("TouchEngineVision", "fingerprint failed: ${error.message}")
            return
        }

        if (cropped !== sourceBitmap) sourceBitmap.recycle()
        endProbe(token)
        if (!isTokenCurrent(token)) {
            cropped.recycle()
            return
        }

        val targetPackage = request.runtime.targetPackage
        val visualOcclusionBounds = when (frame.captureMode) {
            VisualCaptureMode.WINDOW_CLEAN -> null
            VisualCaptureMode.DISPLAY_FALLBACK ->
                request.runtime.joystickInteractionBounds?.let { Rect(it) }
        }
        Log.d(
            "TouchEngineVision",
            "capture mode=${frame.captureMode} window=${frame.windowId} frame=${frame.boundsInScreen} " +
                "fallback=${frame.fallbackReason ?: "none"} occlusion=${visualOcclusionBounds ?: "none"}"
        )
        val requestedBands = request.requestedBands
        val surfaceKey = request.surfaceKey
        val hasActiveCache = hadActiveCacheBeforeCapture &&
            cache.hasActiveCache(
                requestedSurfaceKey = surfaceKey,
                requestedBands = requestedBands,
                pageEpoch = token.pageEpoch,
                viewportRevision = token.viewportRevision
            )

        maybeRunObjectEvidence(cropped, cropBounds, targetPackage, token)

        val reason = when {
            !hasActiveCache -> pendingRefreshReason ?: VisualCacheInvalidationReason.SURFACE_REPLACED
            cache.hasMeaningfulFingerprintChange(fingerprints) ->
                VisualCacheInvalidationReason.DYNAMIC_HOLE_CHANGE
            else -> {
                Log.d(
                    "TouchEngineVision",
                    "retain reason=${VisualCacheInvalidationReason.OUTSIDE_HOLE_NOISE} surface=$surfaceKey " +
                        "fingerprintDistance=${cache.fingerprintDistance(fingerprints)} " +
                        "nodes=${cache.nodeCount}"
                )
                cropped.recycle()
                return
            }
        }

        val nowMs = SystemClock.elapsedRealtime()
        val strongRefreshPending = isStrongRefreshReason(pendingRefreshReason)
        if (hasActiveCache &&
            !strongRefreshPending &&
            !EdgeInteractionRules.mayRefreshDynamicVisualCache(cache.lastCommitAtMs, nowMs)
        ) {
            val delayMs = (cache.lastCommitAtMs + EdgeInteractionRules.VISUAL_REFRESH_THROTTLE_MS - nowMs)
                .coerceAtLeast(1L)
            Log.d(
                "TouchEngineVision",
                "retain reason=${VisualCacheInvalidationReason.THROTTLED} surface=$surfaceKey " +
                    "fingerprintDistance=${cache.fingerprintDistance(fingerprints)} retryIn=${delayMs}ms"
            )
            cropped.recycle()
            scheduleScan(delayMs)
            return
        }

        ocrInFlight = true
        activeOcrScanId = token.scanId
        val scanGeneration = ++generation
        pendingRefreshReason = null
        val hiddenActionableProbe = hasActiveCache &&
            reason == VisualCacheInvalidationReason.DYNAMIC_HOLE_CHANGE

        if (!hiddenActionableProbe) {
            visualOcrState.value = if (hasActiveCache) {
                visualOcrState.value.copy(status = "VISION cache ${cache.nodeCount} / refreshing...")
            } else {
                VisualOcrDisplay(status = "OCR scanning...")
            }
        } else if (visualOcrState.value.status == null) {
            visualOcrState.value = VisualOcrDisplay(status = "OCR scanning...")
        }

        Log.d(
            "TouchEngineVision",
            "ocrStart reason=$reason surface=$surfaceKey holes=$requestedBands " +
                "epoch=${token.pageEpoch} viewport=${token.viewportRevision} scan=${token.scanId} " +
                "fingerprintDistance=${cache.fingerprintDistance(fingerprints)} " +
                "sinceCommit=${if (cache.lastCommitAtMs > 0L) nowMs - cache.lastCommitAtMs else -1}ms"
        )

        val startedAt = SystemClock.elapsedRealtime()
        val yoloDeferred: Deferred<Result<YoloUiResult>> = scope.async(Dispatchers.Default) {
            val yoloBitmap = cropped.copy(Bitmap.Config.ARGB_8888, false)
            try {
                runCatching { recognizer.detectYolo(yoloBitmap, request.runtime.yoloInputSize) }
            } finally {
                if (!yoloBitmap.isRecycled) yoloBitmap.recycle()
            }
        }
        // YOLO ACTION 级结果不再等待 OCR。后续融合会按 stableId 校正它们。
        scope.launch(Dispatchers.Default) {
            val yoloResult = yoloDeferred.await().getOrNull() ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                val runtime = runCatching { runtimeProvider() }.getOrNull() ?: return@withContext
                if (scanGeneration != generation || !isTokenCurrent(token, runtime) || runtime.isJoystickSelecting) {
                    return@withContext
                }
                val directNodes = VisualCandidateEngine(
                    visualContext(runtime, visualOcclusionBounds, request.douyinScene, request.regionPolicy)
                )
                    .buildDirectYoloNavigationNodes(
                        result = yoloResult,
                        cropBounds = cropBounds,
                        targetPackage = request.runtime.targetPackage
                    )
                enqueueDirectVisualNodes(
                    stage = VisualUpdateStage.DIRECT_YOLO,
                    nodes = directNodes,
                    request = request,
                    captureMode = frame.captureMode,
                    fingerprints = fingerprints,
                    reason = reason,
                    token = token,
                    scanGeneration = scanGeneration,
                    startedAt = startedAt,
                    textEvidenceCount = 0
                )
            }
        }
        recognizer.recognizeText(
            bitmap = cropped,
            offset = cropBounds,
            context = OcrExtractionContext(
                screenWidth = request.runtime.screenWidth,
                screenHeight = request.runtime.screenHeight,
                keyboardTop = request.runtime.keyboardTop,
                visualOcclusionBounds = visualOcclusionBounds?.let { Rect(it) },
                targetPackage = request.runtime.targetPackage,
                douyinScene = request.douyinScene,
                regionPolicy = request.regionPolicy
            ),
            onSuccess = { recognition ->
                val runtime = runCatching { runtimeProvider() }.getOrNull()
                if (runtime != null && scanGeneration == generation &&
                    isTokenCurrent(token, runtime) && !runtime.isJoystickSelecting
                ) {
                    val directNodes = VisualCandidateEngine(
                        visualContext(runtime, visualOcclusionBounds, request.douyinScene, request.regionPolicy)
                    )
                        .buildDirectOcrNavigationNodes(
                            candidates = recognition.candidates,
                            targetPackage = request.runtime.targetPackage
                        )
                    val directDecision = marqueeGuard.evaluate(
                        token = token,
                        surfaceKey = request.surfaceKey,
                        visualNodes = directNodes,
                        committedNodes = cache.committedNodes,
                        screenWidth = runtime.screenWidth,
                        screenHeight = runtime.screenHeight,
                        density = runtime.density
                    )
                    enqueueDirectVisualNodes(
                        stage = VisualUpdateStage.DIRECT_OCR,
                        nodes = directDecision.nodes,
                        request = request,
                        captureMode = frame.captureMode,
                        fingerprints = fingerprints,
                        reason = reason,
                        token = token,
                        scanGeneration = scanGeneration,
                        startedAt = startedAt,
                        textEvidenceCount = recognition.candidates.size,
                        marqueeObservation = directDecision.observation,
                        forceInstall = directDecision.requiresInstall
                    )
                }
                handleOcrSuccess(
                    recognition = recognition,
                    cropped = cropped,
                    request = request,
                    captureMode = frame.captureMode,
                    visualOcclusionBounds = visualOcclusionBounds,
                    processedBounds = cropBounds,
                    fingerprints = fingerprints,
                    reason = reason,
                    hasActiveCache = hasActiveCache,
                    token = token,
                    scanGeneration = scanGeneration,
                    startedAt = startedAt,
                    yoloDeferred = yoloDeferred
                )
            },
            onFailure = { error ->
                Log.d("TouchEngineVision", "OCR failed: ${error.message}")
                handleOcrSuccess(
                    recognition = OcrRecognitionResult(
                        candidates = emptyList(),
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    ),
                    cropped = cropped,
                    request = request,
                    captureMode = frame.captureMode,
                    visualOcclusionBounds = visualOcclusionBounds,
                    processedBounds = cropBounds,
                    fingerprints = fingerprints,
                    reason = reason,
                    hasActiveCache = hasActiveCache,
                    token = token,
                    scanGeneration = scanGeneration,
                    startedAt = startedAt,
                    yoloDeferred = yoloDeferred,
                    ocrError = error
                )
            }
        )
    }

    private fun frameLocalRect(
        screenRect: Rect,
        frame: VisualScreenshotFrame
    ): Rect? {
        if (!frame.boundsInScreen.contains(screenRect)) return null
        val local = Rect(screenRect).apply {
            offset(-frame.boundsInScreen.left, -frame.boundsInScreen.top)
        }
        return local.takeIf {
            it.left >= 0 && it.top >= 0 &&
                it.right <= frame.bitmap.width && it.bottom <= frame.bitmap.height &&
                it.width() > 0 && it.height() > 0
        }
    }

    private fun handleOcrSuccess(
        recognition: OcrRecognitionResult,
        cropped: Bitmap,
        request: VisualScanRequest,
        captureMode: VisualCaptureMode,
        visualOcclusionBounds: Rect?,
        processedBounds: Rect,
        fingerprints: Map<EdgeInteractionRules.GenericContentBand, Long>,
        reason: VisualCacheInvalidationReason,
        hasActiveCache: Boolean,
        token: VisualScanToken,
        scanGeneration: Int,
        startedAt: Long,
        yoloDeferred: Deferred<Result<YoloUiResult>>,
        ocrError: Throwable? = null
    ) {
        val candidates = recognition.candidates
        val isUsableBatch = EdgeInteractionRules.acceptsVisualCandidateBatch(
            candidates.map { it.bounds.centerX() to it.bounds.centerY() },
            request.runtime.screenWidth,
            request.runtime.screenHeight
        )

        // OCR 回调在主线程进入这里。先在主线程读取 AccessibilityService 运行环境，
        // 禁止在 Dispatchers.Default 中调用 runtimeProvider()，因为它会读取 windows/root 等
        // Android 无障碍对象；部分 ROM 会因此抛出未捕获异常并直接终止进程。
        val shouldRunYolo = try {
            val runtime = runtimeProvider()
            scanGeneration == generation &&
                isTokenCurrent(token, runtime) &&
                !runtime.isJoystickSelecting
        } catch (error: Throwable) {
            Log.e("TouchEngineVision", "runtime snapshot before YOLO failed", error)
            false
        }

        // 保持原时序：OCR 成功后才启动 YOLO。
        scope.launch(Dispatchers.Default) {
            var yoloResult: YoloUiResult? = null
            var yoloError: Throwable? = null
            try {
                if (shouldRunYolo) {
                    val yoloOutcome = yoloDeferred.await()
                    yoloResult = yoloOutcome.getOrNull()
                    yoloError = yoloOutcome.exceptionOrNull()
                }

                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                withContext(Dispatchers.Main.immediate) {
                    val runtime = runtimeProvider()
                    if (scanGeneration == generation &&
                        isTokenCurrent(token, runtime) &&
                        !runtime.isJoystickSelecting
                    ) {
                        val visualCandidateEngine = VisualCandidateEngine(
                            VisualProcessingContext(
                                screenWidth = runtime.screenWidth,
                                screenHeight = runtime.screenHeight,
                                density = runtime.density,
                                keyboardTop = runtime.keyboardTop,
                                visualOcclusionBounds = visualOcclusionBounds?.let { Rect(it) },
                                inputMethodVisible = runtime.inputMethodVisible,
                                accessibilityNodes = runtime.stableNodes,
                                douyinScene = request.douyinScene,
                                regionPolicy = request.regionPolicy
                            )
                        )
                        val generatedVisualNodes = visualCandidateEngine.buildNavigationNodes(
                            candidates,
                            request.runtime.targetPackage,
                            cropped,
                            processedBounds,
                            isUsableBatch,
                            yoloResult
                        )
                        val generatedAndStabilizedNodes = stabilizeWeChatMessageRows(
                            nodes = generatedVisualNodes,
                            targetPackage = request.runtime.targetPackage,
                            token = token,
                            ocrCandidates = candidates,
                            screenHeight = runtime.screenHeight
                        )
                        val marqueeDecision = marqueeGuard.evaluate(
                            token = token,
                            surfaceKey = request.surfaceKey,
                            visualNodes = generatedAndStabilizedNodes,
                            committedNodes = cache.committedNodes,
                            screenWidth = runtime.screenWidth,
                            screenHeight = runtime.screenHeight,
                            density = runtime.density
                        )
                        val visualNodes = marqueeDecision.nodes
                        val actionableSignature = visualActionableSignature(visualNodes)
                        val isActionableNoise = hasActiveCache &&
                            reason == VisualCacheInvalidationReason.DYNAMIC_HOLE_CHANGE &&
                            actionableSignature == cache.currentActionableSignature
                        val shownCandidates = visualNodes.map { node ->
                            val evidence = node.visualEvidence
                            VisualOcrCandidate(
                                bounds = Rect(node.bounds),
                                text = evidence?.ocrText ?: node.description.orEmpty(),
                                kind = when (evidence?.role) {
                                    TargetRole.ACTION -> VisualCandidateKind.ACTION_TEXT
                                    TargetRole.ICON_ENTRY -> VisualCandidateKind.ICON_ENTRY
                                    TargetRole.RIGHT_RAIL -> VisualCandidateKind.RIGHT_RAIL_ICON
                                    TargetRole.CARD -> VisualCandidateKind.CARD_TARGET
                                    TargetRole.LIST_ITEM -> VisualCandidateKind.LIST_ITEM_TARGET
                                    else -> VisualCandidateKind.TEXT_EVIDENCE
                                },
                                sourceSet = evidence?.sources ?: node.sourceSet,
                                yoloClass = evidence?.yoloClass,
                                yoloConfidence = evidence?.yoloConfidence,
                                evidenceScore = evidence?.score ?: 0,
                                directEligible = evidence?.directEligible == true,
                                clickMethod = node.clickMethod
                            )
                        }
                        val visualDiagnostics = visualCandidateEngine.buildDiagnostics(
                            yoloResult,
                            processedBounds,
                            candidates,
                            shownCandidates,
                            request.runtime.targetPackage
                        )
                        val diagnosticSummary =
                            "rawYolo=${visualDiagnostics.rawYoloCount} " +
                                "afterNms=${yoloResult?.detections?.size ?: 0} " +
                                "directYolo=${shownCandidates.count { it.directEligible }} " +
                                "rawOcr=${candidates.size} " +
                                "fused=${shownCandidates.count {
                                    TargetSource.YOLO in it.sourceSet && TargetSource.OCR in it.sourceSet
                                }} " +
                                "fusionNodes=${shownCandidates.count {
                                    TargetSource.VISUAL_FUSION in it.sourceSet
                                }} " +
                                "items=${shownCandidates.count {
                                    it.kind == VisualCandidateKind.LIST_ITEM_TARGET
                                }} " +
                                "rejected=${(
                                    (yoloResult?.detections?.size ?: 0) + candidates.size - shownCandidates.size
                                ).coerceAtLeast(0)} " +
                                "final=${visualNodes.size} reachable=pending"

                        if (isActionableNoise && !marqueeDecision.requiresInstall) {
                            cache.markObserved(token, fingerprints)
                            Log.d(
                                "TouchEngineVision",
                                "retain reason=${VisualCacheInvalidationReason.ACTIONABLE_NOISE} " +
                                    "$diagnosticSummary surface=${request.surfaceKey} textEvidence=${candidates.size} " +
                                    "nodes=${cache.nodeCount} fingerprintDistance=${cache.fingerprintDistance(fingerprints)}"
                            )
                        } else if (visualNodes.isNotEmpty() || marqueeDecision.requiresInstall) {
                            val pending = PendingVisualCache(
                                token = token,
                                captureMode = captureMode,
                                nodes = visualNodes,
                                candidates = shownCandidates,
                                targetPackage = request.runtime.targetPackage,
                                bands = request.requestedBands,
                                surfaceKey = request.surfaceKey,
                                fingerprints = fingerprints,
                                actionableSignature = actionableSignature,
                                textEvidenceCount = candidates.size,
                                rejectedEvidenceCount = (candidates.size - shownCandidates.size).coerceAtLeast(0),
                                elapsedMs = elapsedMs,
                                reason = if (reason == VisualCacheInvalidationReason.DYNAMIC_HOLE_CHANGE) {
                                    VisualCacheInvalidationReason.ACTIONABLE_CHANGE
                                } else {
                                    reason
                                },
                                diagnostics = visualDiagnostics.candidates,
                                diagnosticSummary = "$diagnosticSummary marquee=${marqueeDecision.status}",
                                marqueeObservation = marqueeDecision.observation
                            )
                            enqueueVisualInstall(pending, scanGeneration)
                        } else {
                            cache.markObserved(token, fingerprints)
                            visualOcrState.value = if (cache.hasCachedNodes) {
                                visualOcrState.value.copy(
                                    diagnostics = visualDiagnostics.candidates,
                                    status = "VISION retained / OCR rejected ${elapsedMs}ms $diagnosticSummary"
                                )
                            } else {
                                VisualOcrDisplay(
                                    diagnostics = visualDiagnostics.candidates,
                                    status = "OCR rejected / ${elapsedMs}ms $diagnosticSummary"
                                )
                            }
                        }

                        Log.d(
                            "TouchEngineVision",
                            "pkg=${request.runtime.targetPackage} holes=${request.requestedBands} text=${candidates.size} " +
                                "yolo=${yoloResult?.detections?.size ?: 0} backend=${yoloResult?.backendName ?: "none"} " +
                                "ocrError=${ocrError?.javaClass?.simpleName ?: "none"} " +
                                "yoloError=${yoloError?.javaClass?.simpleName ?: "none"} " +
                                "$diagnosticSummary elapsed=${elapsedMs}ms"
                        )
                    }
                }
            } catch (error: Throwable) {
                Log.e("TouchEngineVision", "visual scan pipeline failed", error)
                try {
                    withContext(Dispatchers.Main.immediate) {
                        if (scanGeneration == generation && isTokenCurrent(token)) {
                            visualOcrState.value = if (cache.hasCachedNodes) {
                                visualOcrState.value.copy(status = "VISION retained / pipeline failed")
                            } else {
                                VisualOcrDisplay(status = "VISION pipeline failed")
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Service 正在销毁时主线程切换可能被取消；这里只保证不再次抛出。
                }
            } finally {
                if (!cropped.isRecycled) cropped.recycle()
                endOcr(token)
            }
        }
    }

    private fun maybeRunObjectEvidence(
        cropped: Bitmap,
        cropBounds: Rect,
        targetPackage: String?,
        token: VisualScanToken
    ) {
        val runtime = runtimeProvider()
        if (!runtime.objectEvidenceEnabled || objectEvidenceInFlight) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastObjectEvidenceAtMs < EdgeInteractionRules.VISUAL_REFRESH_THROTTLE_MS) return

        val bitmap = cropped.copy(Bitmap.Config.ARGB_8888, false)
        objectEvidenceInFlight = true
        val evidenceGeneration = ++objectEvidenceGeneration
        lastObjectEvidenceAtMs = nowMs
        objectEvidenceState.value = objectEvidenceState.value.copy(status = "OBJECT scanning...")

        recognizer.recognizeObjects(
            bitmap = bitmap,
            offset = cropBounds,
            onSuccess = { result ->
                val latestRuntime = runtimeProvider()
                if (evidenceGeneration == objectEvidenceGeneration &&
                    isTokenCurrent(token, latestRuntime) &&
                    latestRuntime.objectEvidenceEnabled &&
                    !latestRuntime.isJoystickSelecting &&
                    latestRuntime.targetPackage == targetPackage
                ) {
                    val boxes = result.boxes.map { absolute ->
                        VisualEvidenceBox(Rect(absolute), "OBJECT")
                    }
                    objectEvidenceState.value = ObjectEvidenceDisplay(
                        boxes = boxes,
                        elapsedMs = result.elapsedMs,
                        status = "OBJECT evidence ${boxes.size} / ${result.elapsedMs}ms"
                    )
                    Log.d(
                        "TouchEngineVision",
                        "objectEvidence pkg=$targetPackage boxes=${boxes.size} elapsed=${result.elapsedMs}ms"
                    )
                }
                bitmap.recycle()
                if (evidenceGeneration == objectEvidenceGeneration) {
                    objectEvidenceInFlight = false
                }
            },
            onFailure = { error ->
                bitmap.recycle()
                if (evidenceGeneration == objectEvidenceGeneration) {
                    objectEvidenceInFlight = false
                }
                val latestRuntime = runCatching { runtimeProvider() }.getOrNull()
                if (latestRuntime != null &&
                    isTokenCurrent(token, latestRuntime) &&
                    latestRuntime.objectEvidenceEnabled
                ) {
                    objectEvidenceState.value = ObjectEvidenceDisplay(status = "OBJECT evidence failed")
                }
                Log.d("TouchEngineVision", "objectEvidence failed: ${error.message}")
            }
        )
    }

    private fun hasAnyVisualState(runtime: VisualScanRuntime): Boolean {
        return cache.hasCachedNodes ||
            cache.pending != null ||
            installQueue.isNotEmpty() ||
            activeInstall != null ||
            ocrInFlight ||
            probeInFlight ||
            objectEvidenceInFlight ||
            runtime.activeNodes.any { it.origin == NavNodeOrigin.VISION } ||
            visualOcrState.value.candidates.isNotEmpty() ||
            visualOcrState.value.diagnostics.isNotEmpty() ||
            objectEvidenceState.value.boxes.isNotEmpty()
    }

    private fun advanceSession(reason: VisualCacheInvalidationReason?) {
        marqueeGuard.reset("sessionAdvance:${reason ?: "unknown"}")
        when (reason) {
            VisualCacheInvalidationReason.VERTICAL_SCROLL,
            VisualCacheInvalidationReason.AUTO_SCROLL_FINISHED -> {
                viewportRevision += 1L
            }

            VisualCacheInvalidationReason.PAGE_FLIP -> {
                pageEpoch += 1L
                viewportRevision += 1L
            }

            else -> {
                pageEpoch += 1L
                viewportRevision = 0L
            }
        }
    }

    private fun isTokenCurrent(
        token: VisualScanToken,
        runtime: VisualScanRuntime = runtimeProvider()
    ): Boolean {
        if (token.scanId < minimumValidScanId ||
            token.pageEpoch != pageEpoch ||
            token.viewportRevision != viewportRevision ||
            token.targetPackage != runtime.targetPackage
        ) {
            return false
        }
        return token.primaryWindowId < 0 ||
            runtime.primaryWindowId < 0 ||
            token.primaryWindowId == runtime.primaryWindowId
    }

    private fun isProbeTokenCurrent(
        token: VisualScanToken,
        runtime: VisualScanRuntime
    ): Boolean {
        return probeInFlight &&
            activeProbeScanId == token.scanId &&
            isTokenCurrent(token, runtime) &&
            !runtime.isJoystickSelecting &&
            runtime.scrollIdle
    }

    private fun endProbe(token: VisualScanToken) {
        if (activeProbeScanId == token.scanId) {
            activeProbeScanId = null
            probeInFlight = false
        }
    }

    private fun endOcr(token: VisualScanToken) {
        if (activeOcrScanId == token.scanId) {
            activeOcrScanId = null
            ocrInFlight = false
        }
    }

    private fun visualHoleFingerprint(bitmap: Bitmap): Long {
        val sampled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val luminance = IntArray(64)
        var sum = 0
        for (index in luminance.indices) {
            val color = sampled.getPixel(index % 8, index / 8)
            val value = (Color.red(color) * 299 +
                Color.green(color) * 587 +
                Color.blue(color) * 114) / 1000
            luminance[index] = value
            sum += value
        }
        if (sampled !== bitmap) sampled.recycle()
        val average = sum / luminance.size
        var fingerprint = 0L
        luminance.forEachIndexed { index, value ->
            if (value >= average) fingerprint = fingerprint or (1L shl index)
        }
        return fingerprint
    }

    private fun visualActionableSignature(nodes: List<NavNode>, quantum: Int = 48): Int {
        if (nodes.isEmpty()) return 0
        return nodes.asSequence()
            .filter { it.origin == NavNodeOrigin.VISION }
            .map { node ->
                val label = node.description.orEmpty()
                    .substringAfter("VISION[", "VISION")
                    .substringBefore("]", "VISION")
                val r = node.bounds
                "$label:${r.left / quantum}:${r.top / quantum}:${r.right / quantum}:${r.bottom / quantum}"
            }
            .sorted()
            .joinToString("|")
            .hashCode()
    }

    private fun isStrongRefreshReason(reason: VisualCacheInvalidationReason?): Boolean {
        return reason == VisualCacheInvalidationReason.APP_SWITCH ||
            reason == VisualCacheInvalidationReason.WINDOW_REPLACED ||
            reason == VisualCacheInvalidationReason.POPUP_CHANGE ||
            reason == VisualCacheInvalidationReason.PAGE_FLIP ||
            reason == VisualCacheInvalidationReason.VERTICAL_SCROLL ||
            reason == VisualCacheInvalidationReason.USER_CLICK ||
            reason == VisualCacheInvalidationReason.SURFACE_REPLACED ||
            reason == VisualCacheInvalidationReason.AUTO_SCROLL_FINISHED
    }

    fun close() {
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(selectionResumeRunnable)
        scanScheduled = false
        selectionSuspended = false
        selectionResumeRequired = false
        selectionResumeAttempts = 0
        selectionResumeDelayMs = SELECTION_RESUME_INITIAL_DELAY_MS
        selectionResumeCause = null
        scanRetryAttempts = 0
        scanRetryReason = null
        advanceSession(null)
        generation++
        objectEvidenceGeneration++
        activeOcrScanId = null
        activeProbeScanId = null
        ocrInFlight = false
        probeInFlight = false
        objectEvidenceInFlight = false
        discardQueuedVisualInstalls()
        committedCaptureMode = VisualCaptureMode.DISPLAY_FALLBACK
        clearRetainedWeChatRows()
        recognizer.close()
    }

    private companion object {
        const val WECHAT_LIST_ROW_HOLD_MS = 4_000L
        const val SELECTION_RESUME_INITIAL_DELAY_MS = 140L
        const val MAX_SELECTION_RESUME_ATTEMPTS = 12
        const val MAX_SCAN_RETRY_ATTEMPTS = 10
        val SCAN_RETRY_DELAYS_MS = longArrayOf(
            90L, 140L, 220L, 320L, 460L,
            650L, 850L, 1_050L, 1_300L, 1_600L
        )
        val SELECTION_RESUME_RETRY_DELAYS_MS = longArrayOf(
            80L, 120L, 180L, 260L, 360L, 500L,
            650L, 800L, 950L, 1_100L, 1_250L, 1_500L
        )
    }
}
