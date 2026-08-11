package com.example.touchengine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.roundToInt

/**
 * 将 ML Kit OCR 原始结果转换为视觉文字证据。
 *
 * 本类只做同步、无状态的结果解析，不访问 Service、缓存、焦点或 NavMesh。
 * 调用方拥有传入 Bitmap 的生命周期；本类不会回收 Bitmap。
 */
internal class OcrEvidenceExtractor {

    fun extract(
        text: Text,
        cropped: Bitmap,
        offset: Rect,
        context: OcrExtractionContext
    ): List<VisualOcrCandidate> {
        val candidates = mutableListOf<VisualOcrCandidate>()
        text.textBlocks.forEach { block ->
            block.lines.forEach lineLoop@ { line ->
                val lineBounds = line.boundingBox ?: return@lineLoop
                val value = line.text.trim()
                if (value.isBlank() ||
                    value.contains("visual-hole", ignoreCase = true) ||
                    value.contains("OCR cache", ignoreCase = true) ||
                    isLikelySelfOverlayText(value)
                ) {
                    return@lineLoop
                }

                val elementCandidates = line.elements.mapNotNull { element ->
                    val elementText = element.text.trim()
                    val elementBounds = element.boundingBox ?: return@mapNotNull null
                    if (elementText.isBlank() ||
                        elementText.contains("visual-hole", ignoreCase = true) ||
                        elementText.contains("OCR cache", ignoreCase = true) ||
                        isLikelySelfOverlayText(elementText)
                    ) {
                        return@mapNotNull null
                    }
                    val absolute = Rect(elementBounds).apply { offset(offset.left, offset.top) }
                    if (isUsableOcrEvidenceBounds(absolute, context)) {
                        VisualOcrCandidate(absolute, elementText)
                    } else {
                        null
                    }
                }
                if (elementCandidates.size >= 2) {
                    candidates.addAll(elementCandidates)
                    return@lineLoop
                }

                val splitRects = splitOcrLineBounds(line, cropped)
                val rects = if (splitRects.isNotEmpty()) splitRects else listOf(Rect(lineBounds))
                rects.forEach { local ->
                    val absolute = Rect(local).apply { offset(offset.left, offset.top) }
                    if (isUsableOcrEvidenceBounds(absolute, context)) {
                        candidates.add(VisualOcrCandidate(absolute, value))
                    }
                }
            }
        }
        return candidates.distinctBy {
            "${it.bounds.left / 8}:${it.bounds.top / 8}:${it.bounds.right / 8}:${it.bounds.bottom / 8}"
        }.take(96)
    }

    private fun isUsableOcrEvidenceBounds(
        bounds: Rect,
        context: OcrExtractionContext
    ): Boolean {
        return bounds.width() >= 10 &&
            bounds.height() >= 10 &&
            bounds.height() < context.screenHeight * 0.13f &&
            bounds.width() < context.screenWidth * 0.88f &&
            !isLikelyStatusBarCandidate(bounds, context) &&
            !isLikelySystemNavigationCandidate(bounds, context) &&
            !isWithinKeyboardRegion(bounds, context) &&
            !isLikelySelfOverlayCandidate(bounds, context)
    }

    private fun isLikelyStatusBarCandidate(
        bounds: Rect,
        context: OcrExtractionContext
    ): Boolean {
        return bounds.centerY() < context.screenHeight * 0.045f || bounds.top < 2
    }

    private fun isLikelySystemNavigationCandidate(
        bounds: Rect,
        context: OcrExtractionContext
    ): Boolean {
        return bounds.centerY() > context.screenHeight * 0.965f
    }

    private fun isWithinKeyboardRegion(
        bounds: Rect,
        context: OcrExtractionContext
    ): Boolean {
        val top = context.keyboardTop ?: return false
        return bounds.bottom > top - 8
    }

    private fun isLikelySelfOverlayCandidate(
        bounds: Rect,
        context: OcrExtractionContext
    ): Boolean {
        val joystickBounds = context.visualOcclusionBounds ?: return false
        val candidateArea = bounds.areaLong().coerceAtLeast(1L)
        val joystickArea = joystickBounds.areaLong().coerceAtLeast(1L)
        val areaRatio = candidateArea / joystickArea.toFloat()
        val compactCandidate = areaRatio in 0.18f..2.8f &&
            bounds.width() < context.screenWidth * 0.28f &&
            bounds.height() < context.screenHeight * 0.20f
        if (!compactCandidate) return false
        return rectIou(bounds, joystickBounds) > 0.08f ||
            containsMost(joystickBounds, bounds, 0.35f) ||
            containsMost(bounds, joystickBounds, 0.70f)
    }

    private fun splitOcrLineBounds(line: Text.Line, bitmap: Bitmap): List<Rect> {
        val lineBounds = line.boundingBox ?: return emptyList()
        val elements = line.elements.mapNotNull { element ->
            val bounds = element.boundingBox ?: return@mapNotNull null
            if (element.text.trim().isEmpty()) null else Rect(bounds)
        }.sortedBy { it.left }
        if (elements.size >= 2) {
            val chars = line.text.count { !it.isWhitespace() }.coerceAtLeast(1)
            val averageCharWidth = lineBounds.width() / chars.toFloat()
            val splitGap = maxOf(
                12,
                maxOf(lineBounds.height() * 0.58f, averageCharWidth * 1.25f).roundToInt()
            )
            val groups = mutableListOf<Rect>()
            var current = Rect(elements.first())
            elements.drop(1).forEach { next ->
                if (next.left - current.right > splitGap) {
                    groups.add(current)
                    current = Rect(next)
                } else {
                    current.union(next)
                }
            }
            groups.add(current)
            if (groups.size > 1) return groups
        }
        return splitLineAtVisualWhitespace(lineBounds, line.text, bitmap)
    }

    private fun splitLineAtVisualWhitespace(
        bounds: Rect,
        text: String,
        bitmap: Bitmap
    ): List<Rect> {
        if (text.count { !it.isWhitespace() } < 6 ||
            bounds.width() <= bounds.height() * 8 ||
            bounds.width() < 30 ||
            bounds.height() < 8
        ) {
            return emptyList()
        }
        val clipped = Rect(
            bounds.left.coerceAtLeast(0),
            bounds.top.coerceAtLeast(0),
            bounds.right.coerceAtMost(bitmap.width),
            bounds.bottom.coerceAtMost(bitmap.height)
        )
        val inkByColumn = IntArray(clipped.width())
        for (x in 0 until clipped.width()) {
            for (y in 0 until clipped.height()) {
                val color = bitmap.getPixel(clipped.left + x, clipped.top + y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val luminance = (red * 299 + green * 587 + blue * 114) / 1000
                if (luminance < 222 ||
                    (maxOf(red, green, blue) - minOf(red, green, blue) > 38 && luminance < 246)
                ) {
                    inkByColumn[x]++
                }
            }
        }
        val maximumGapInk = maxOf(1, (clipped.height() * 0.04f).roundToInt())
        val minimumGapWidth = maxOf(10, (clipped.height() * 0.46f).roundToInt())
        val startScan = (clipped.width() * 0.12f).roundToInt()
        val endScan = (clipped.width() * 0.88f).roundToInt()
        var blankStart = -1
        var bestStart = -1
        var bestEnd = -1
        for (x in startScan..endScan) {
            val blank = x < endScan && inkByColumn[x] <= maximumGapInk
            if (blank && blankStart < 0) {
                blankStart = x
            } else if (!blank && blankStart >= 0) {
                if (x - blankStart >= minimumGapWidth &&
                    inkByColumn.sliceArray(0 until blankStart).sum() > 3 &&
                    inkByColumn.sliceArray(x until inkByColumn.size).sum() > 3
                ) {
                    bestStart = blankStart
                    bestEnd = x
                }
                blankStart = -1
            }
        }
        if (bestStart < 0) return emptyList()
        return listOf(
            Rect(clipped.left, clipped.top, clipped.left + bestStart, clipped.bottom),
            Rect(clipped.left + bestEnd, clipped.top, clipped.right, clipped.bottom)
        ).filter { it.width() >= clipped.height() / 2 }
    }
}
