package com.example.touchengine

import android.content.res.Resources
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 焦点高亮叠加层。
 *
 * 完全无状态：所有数据从调用方传入，不持有任何 Service 引用。
 * 调用方（TouchEngineService.initFloatingUI）负责将 StateFlow 值传递进来。
 */
@Composable
internal fun FocusHighlightUI(
    targetNode: NavNode?,
    hintData: ScrollHintData?,
    visualHoles: List<Rect>,
    visualOcr: VisualOcrDisplay,
    objectEvidence: ObjectEvidenceDisplay,
    objectEvidenceEnabled: Boolean,
    scrollConfig: ScrollConfig,
) {
    val density = LocalDensity.current
    val accentColor = if (targetNode?.origin == NavNodeOrigin.VISION) {
        Color(0xFF25A6FF)
    } else {
        Color(0xFF00FFCC)
    }

    // [FIX UI-1] 状态栏高度补偿
    val statusBarHeightPx = remember {
        val id = Resources.getSystem().getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) Resources.getSystem().getDimensionPixelSize(id) else 0
    }

    // ── 提示气泡（方案B倒计时）────────────────────────
    AnimatedVisibility(
        visible = hintData != null,
        enter   = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically { it / 2 },
        exit    = fadeOut(tween(200)) + androidx.compose.animation.slideOutVertically { it / 2 }
    ) {
        val hint = hintData ?: return@AnimatedVisibility
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier
                    .padding(bottom = 80.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val actionText = when (hint.type) {
                    HintType.PAGE_FLIP -> "即将翻页"
                    HintType.SCROLL    -> "即将滑动"
                }
                val arrowText = when (hint.dir) {
                    ScrollDir.DOWN  -> "↓"
                    ScrollDir.UP    -> "↑"
                    ScrollDir.RIGHT -> "→"
                    ScrollDir.LEFT  -> "←"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(arrowText, color = Color.White, fontSize = 18.sp)
                    Text(
                        actionText,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    Text(arrowText, color = Color.White, fontSize = 18.sp)
                }
                Spacer(Modifier.height(8.dp))
                val barColors = if (hint.type == HintType.PAGE_FLIP) {
                    listOf(Color(0xFFFFCC00), Color(0xFFFF6B00))
                } else {
                    listOf(Color(0xFF00FFCC), Color(0xFF00AAFF))
                }
                Box(
                    modifier = Modifier
                        .width(160.dp).height(4.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(hint.progress)
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(barColors),
                                RoundedCornerShape(2.dp)
                            )
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${((1f - hint.progress) * (scrollConfig.hintDurationMs / 1000f)).let { "%.1f".format(it) }}s 后执行",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp
                )
            }
        }
    }

    if (visualHoles.isNotEmpty()) {
        Box(modifier = Modifier.fillMaxSize()) {
            visualHoles.forEach { hole ->
                val left   = with(density) { hole.left.toDp() }
                val top    = with(density) { (hole.top - statusBarHeightPx).toDp() }
                val width  = with(density) { hole.width().toDp() }
                val height = with(density) { hole.height().toDp() }
                Box(
                    modifier = Modifier
                        .absoluteOffset(left, top)
                        .size(width, height)
                        .background(Color(0xFFFF8A3D).copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                        .border(2.dp, Color(0xFFFF8A3D).copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Text(text = "visual-hole", color = Color(0xFFFF6B00), fontSize = 12.sp)
                }
            }
        }
    }

    if (visualOcr.candidates.isNotEmpty() || visualOcr.diagnostics.isNotEmpty() || visualOcr.status != null) {
        Box(modifier = Modifier.fillMaxSize()) {
            visualOcr.diagnostics.forEach { diagnostic ->
                val left   = with(density) { diagnostic.bounds.left.toDp() }
                val top    = with(density) { (diagnostic.bounds.top - statusBarHeightPx).toDp() }
                val width  = with(density) { diagnostic.bounds.width().toDp() }
                val height = with(density) { diagnostic.bounds.height().toDp() }
                val color = when (diagnostic.kind) {
                    VisualDebugKind.YOLO_STRUCTURE -> Color(0xFF1E88E5)
                    VisualDebugKind.OCR_PROMOTED   -> Color(0xFF8E24AA)
                    VisualDebugKind.TEXT_EVIDENCE  -> Color(0xFF8E24AA)
                    VisualDebugKind.FUSED_CANDIDATE -> Color(0xFFFFC107)
                    VisualDebugKind.FILTERED_LOW   -> Color(0xFF9E9E9E)
                }
                Box(
                    modifier = Modifier
                        .absoluteOffset(left, top)
                        .size(width, height)
                        .background(color.copy(alpha = 0.035f), RoundedCornerShape(3.dp))
                        .border(1.dp, color.copy(alpha = 0.58f), RoundedCornerShape(3.dp))
                )
            }
            visualOcr.candidates.forEach { candidate ->
                val left   = with(density) { candidate.bounds.left.toDp() }
                val top    = with(density) { (candidate.bounds.top - statusBarHeightPx).toDp() }
                val width  = with(density) { candidate.bounds.width().toDp() }
                val height = with(density) { candidate.bounds.height().toDp() }
                Box(
                    modifier = Modifier
                        .absoluteOffset(left, top)
                        .size(width, height)
                        .background(Color(0xFF20C05C).copy(alpha = 0.07f), RoundedCornerShape(3.dp))
                        .border(2.dp, Color(0xFF20C05C).copy(alpha = 0.92f), RoundedCornerShape(3.dp))
                )
            }
            visualOcr.status?.let { status ->
                val anchor = visualHoles.firstOrNull()
                if (anchor != null) {
                    Text(
                        text = status,
                        color = Color(0xFF0077CC),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .absoluteOffset(
                                with(density) { (anchor.left + 8).toDp() },
                                with(density) { (anchor.top + 28 - statusBarHeightPx).toDp() }
                            )
                            .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    if (objectEvidenceEnabled && (objectEvidence.boxes.isNotEmpty() || objectEvidence.status != null)) {
        Box(modifier = Modifier.fillMaxSize()) {
            objectEvidence.boxes.forEach { evidence ->
                val left   = with(density) { evidence.bounds.left.toDp() }
                val top    = with(density) { (evidence.bounds.top - statusBarHeightPx).toDp() }
                val width  = with(density) { evidence.bounds.width().toDp() }
                val height = with(density) { evidence.bounds.height().toDp() }
                Box(
                    modifier = Modifier
                        .absoluteOffset(left, top)
                        .size(width, height)
                        .background(Color(0xFF9C4DFF).copy(alpha = 0.06f), RoundedCornerShape(3.dp))
                        .border(1.dp, Color(0xFF9C4DFF).copy(alpha = 0.90f), RoundedCornerShape(3.dp))
                )
            }
            objectEvidence.status?.let { status ->
                val anchor = visualHoles.firstOrNull()
                if (anchor != null) {
                    Text(
                        text = status,
                        color = Color(0xFF6D28D9),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .absoluteOffset(
                                with(density) { (anchor.left + 8).toDp() },
                                with(density) { (anchor.top + 48 - statusBarHeightPx).toDp() }
                            )
                            .background(Color.White.copy(alpha = 0.82f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }

    val lastNode = remember(targetNode) { targetNode } ?: return
    AnimatedVisibility(
        visible = targetNode != null,
        enter   = fadeIn(tween(120)),
        exit    = fadeOut(tween(200))
    ) {
        val node        = lastNode
        val targetLeft   = with(density) { node.bounds.left.toDp() }
        val targetTop    = with(density) { (node.bounds.top - statusBarHeightPx).toDp() }
        val targetWidth  = with(density) { node.bounds.width().toDp() }
        val targetHeight = with(density) { node.bounds.height().toDp() }

        val infiniteTransition = rememberInfiniteTransition(label = "lockOn")
        val breathAlpha by infiniteTransition.animateFloat(
            initialValue  = 1f,
            targetValue   = 0.55f,
            animationSpec = infiniteRepeatable(
                animation  = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathAlpha"
        )
        val breathStroke by infiniteTransition.animateFloat(
            initialValue  = 4.5f,
            targetValue   = 2.5f,
            animationSpec = infiniteRepeatable(
                animation  = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathStroke"
        )
        val bgAlpha by infiniteTransition.animateFloat(
            initialValue  = 0.28f,
            targetValue   = 0.12f,
            animationSpec = infiniteRepeatable(
                animation  = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bgAlpha"
        )
        val flowProgress by infiniteTransition.animateFloat(
            initialValue  = 0f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "flow"
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .absoluteOffset(x = targetLeft, y = targetTop)
                    .size(width = targetWidth, height = targetHeight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(accentColor.copy(alpha = bgAlpha), RoundedCornerShape(6.dp))
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w      = size.width
                    val h      = size.height
                    val stroke = breathStroke * density.density
                    val color  = accentColor.copy(alpha = breathAlpha)
                    val armX   = w * 0.20f
                    val armY   = h * 0.20f
                    val perimeter = 2f * (w + h)
                    val offset    = flowProgress * perimeter
                    val paint = androidx.compose.ui.graphics.Paint().apply {
                        this.color       = color
                        this.strokeWidth = stroke
                        this.style       = androidx.compose.ui.graphics.PaintingStyle.Stroke
                        strokeCap        = androidx.compose.ui.graphics.StrokeCap.Round
                    }
                    drawContext.canvas.apply {
                        for (i in 0..3) {
                            val cornerOffset = (offset + i * perimeter / 4f) % perimeter
                            val cx: Float
                            val cy: Float
                            when {
                                cornerOffset < w       -> { cx = cornerOffset; cy = 0f }
                                cornerOffset < w + h   -> { cx = w; cy = cornerOffset - w }
                                cornerOffset < 2*w + h -> { cx = w - (cornerOffset - w - h); cy = h }
                                else                   -> { cx = 0f; cy = h - (cornerOffset - 2*w - h) }
                            }
                            val isTop    = cy == 0f
                            val isRight  = cx == w
                            val isBottom = cy == h
                            when {
                                isTop    -> drawLine(Offset(cx - armX * 0.5f, cy), Offset(cx + armX * 0.5f, cy), paint)
                                isRight  -> drawLine(Offset(cx, cy - armY * 0.5f), Offset(cx, cy + armY * 0.5f), paint)
                                isBottom -> drawLine(Offset(cx - armX * 0.5f, cy), Offset(cx + armX * 0.5f, cy), paint)
                                else     -> drawLine(Offset(cx, cy - armY * 0.5f), Offset(cx, cy + armY * 0.5f), paint)
                            }
                        }
                    }
                }
            }
        }
    }
}
