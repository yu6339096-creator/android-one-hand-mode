package com.example.touchengine

import android.graphics.Rect

internal enum class OcrAdmissionDecision {
    DIRECT,
    EVIDENCE_ONLY,
    REJECT
}

internal data class OcrAdmissionResult(
    val decision: OcrAdmissionDecision,
    val normalizedText: String,
    val kind: VisualCandidateKind = VisualCandidateKind.TEXT_EVIDENCE,
    val reason: String
)

internal data class OcrAdmissionContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val keyboardTop: Int?,
    val visualOcclusionBounds: Rect?,
    val targetPackage: String?,
    val douyinScene: DouyinScene,
    val regionPolicy: VisualRegionPolicy
)

/**
 * OCR is an independent navigation source. This policy decides whether one ML Kit
 * observation may enter DIRECT_OCR; TargetFusionEngine is deliberately not involved.
 */
internal class OcrNavigationAdmissionPolicy {

    fun evaluate(candidate: VisualOcrCandidate, context: OcrAdmissionContext): OcrAdmissionResult {
        val text = normalizedVisualText(candidate.text)
        if (text.isBlank()) return reject(text, "blank")
        if (!hasUsableGeometry(candidate.bounds, context)) return reject(text, "unsafeGeometry")
        if (isLikelySelfOverlayText(text)) return reject(text, "selfOverlayText")
        if (isWithinKeyboard(candidate.bounds, context)) return reject(text, "keyboard")
        if (overlapsVisualOcclusion(candidate.bounds, context)) return reject(text, "visualOcclusion")

        classifyGlyph(text, candidate.bounds, context)?.let { return it }

        if (EdgeInteractionRules.isActionableVisualText(text)) {
            return direct(
                text,
                if (isIconEntryText(text)) VisualCandidateKind.ICON_ENTRY else VisualCandidateKind.ACTION_TEXT,
                "knownAction"
            )
        }

        if (text.length !in MIN_SHORT_TEXT_LENGTH..MAX_SHORT_TEXT_LENGTH) {
            return evidence(text, "length")
        }
        if (isHighRiskText(text)) return evidence(text, "textNoise")
        if (context.douyinScene == DouyinScene.VIDEO_FEED_PORTRAIT &&
            candidate.bounds.exactCenterX() >= context.screenWidth * 0.78f &&
            candidate.bounds.exactCenterY() in context.screenHeight * 0.18f..context.screenHeight * 0.82f &&
            candidate.bounds.width() > context.screenWidth * 0.24f
        ) {
            return evidence(text, "wideVideoRailText")
        }
        if (!isAllowedByRegion(candidate.bounds, context.regionPolicy)) {
            return evidence(text, "outsideAllowedRegion")
        }

        return direct(
            text,
            if (isIconEntryText(text)) VisualCandidateKind.ICON_ENTRY else VisualCandidateKind.ACTION_TEXT,
            "shortText"
        )
    }

    private fun classifyGlyph(
        text: String,
        bounds: Rect,
        context: OcrAdmissionContext
    ): OcrAdmissionResult? {
        if (text.length != 1 && text !in MORE_GLYPHS) return null
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val top = centerY <= context.screenHeight * TOP_REGION_RATIO
        val left = centerX <= context.screenWidth * LEFT_REGION_RATIO
        val right = centerX >= context.screenWidth * RIGHT_REGION_RATIO

        return when {
            text in BACK_GLYPHS && top && left -> direct(text, VisualCandidateKind.ICON_ENTRY, "backGlyph")
            text in CLOSE_GLYPHS && right &&
                (top || centerY in context.screenHeight * 0.20f..context.screenHeight * 0.80f) ->
                direct(text, VisualCandidateKind.ICON_ENTRY, "closeGlyph")
            text in MORE_GLYPHS && right && top ->
                direct(text, VisualCandidateKind.ICON_ENTRY, "moreGlyph")
            else -> evidence(text, "untrustedGlyph")
        }
    }

    private fun hasUsableGeometry(bounds: Rect, context: OcrAdmissionContext): Boolean {
        if (bounds.width() < 10 || bounds.height() < 10) return false
        if (bounds.centerX() !in 0..context.screenWidth || bounds.centerY() !in 0..context.screenHeight) return false
        if (bounds.centerY() < context.screenHeight * 0.045f || bounds.top < 2) return false
        if (bounds.centerY() > context.screenHeight * 0.965f) return false
        if (bounds.height() >= context.screenHeight * 0.13f || bounds.width() >= context.screenWidth * 0.88f) return false
        val area = bounds.width().toLong() * bounds.height().toLong()
        val screenArea = context.screenWidth.toLong().coerceAtLeast(1L) * context.screenHeight.toLong().coerceAtLeast(1L)
        return area.toFloat() / screenArea.toFloat() <= 0.05f
    }

    private fun isHighRiskText(text: String): Boolean {
        if (URL_PATTERN.containsMatchIn(text)) return true
        if (text.startsWith("#") || text.startsWith("@")) return true
        if (text.contains("KB/s", ignoreCase = true) || text.contains("MB/s", ignoreCase = true)) return true
        if (TIME_PATTERN.matches(text) || PURE_NUMBER_PATTERN.matches(text)) return true
        if (PRICE_PATTERN.matches(text) || PERCENT_PATTERN.matches(text)) return true
        if (text.any { it in SENTENCE_PUNCTUATION }) return true
        return false
    }

    private fun isAllowedByRegion(bounds: Rect, policy: VisualRegionPolicy): Boolean {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        if (policy.blockedRegions.any { it.contains(centerX, centerY) }) return false
        return policy.allowedRegions.isEmpty() || policy.allowedRegions.any { it.contains(centerX, centerY) }
    }

    private fun isWithinKeyboard(bounds: Rect, context: OcrAdmissionContext): Boolean {
        val top = context.keyboardTop ?: return false
        return bounds.bottom > top - 8
    }

    private fun overlapsVisualOcclusion(bounds: Rect, context: OcrAdmissionContext): Boolean {
        val occlusion = context.visualOcclusionBounds ?: return false
        return rectIou(bounds, occlusion) > 0.08f ||
            containsMost(occlusion, bounds, 0.35f) ||
            containsMost(bounds, occlusion, 0.70f)
    }

    private fun direct(text: String, kind: VisualCandidateKind, reason: String) =
        OcrAdmissionResult(OcrAdmissionDecision.DIRECT, text, kind, reason)

    private fun evidence(text: String, reason: String) =
        OcrAdmissionResult(OcrAdmissionDecision.EVIDENCE_ONLY, text, reason = reason)

    private fun reject(text: String, reason: String) =
        OcrAdmissionResult(OcrAdmissionDecision.REJECT, text, reason = reason)

    private companion object {
        const val MIN_SHORT_TEXT_LENGTH = 2
        const val MAX_SHORT_TEXT_LENGTH = 12
        const val TOP_REGION_RATIO = 0.18f
        const val LEFT_REGION_RATIO = 0.24f
        const val RIGHT_REGION_RATIO = 0.68f

        val BACK_GLYPHS = setOf("<", "‹", "←", "«")
        val CLOSE_GLYPHS = setOf("x", "X", "×")
        val MORE_GLYPHS = setOf("…", "...", "⋮")
        val SENTENCE_PUNCTUATION = setOf('，', ',', '。', '！', '!', '？', '?', '；', ';')
        val URL_PATTERN = Regex("(?i)(https?://|www\\.|[a-z0-9-]+\\.(com|cn|net|jp)(/|$))")
        val TIME_PATTERN = Regex("^\\d{1,2}[:：]\\d{2}(:\\d{2})?$")
        val PURE_NUMBER_PATTERN = Regex("^[+\\-]?\\d+[.,]?\\d*$")
        val PRICE_PATTERN = Regex("^[¥￥$€]?\\d+(?:[.,]\\d+)?(?:元|円)?$")
        val PERCENT_PATTERN = Regex("^\\d+(?:[.,]\\d+)?%$")
    }
}
