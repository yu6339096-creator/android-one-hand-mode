package com.example.touchengine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EdgeInteractionRulesTest {
    @Test
    fun heldDirectionContinuesOnlyInsideDeadZoneAndAngleLimit() {
        assertTrue(EdgeInteractionRules.isHeldInDirection(90.0, 0f, 80f, 100f))
        assertFalse(EdgeInteractionRules.isHeldInDirection(90.0, 80f, 0f, 100f))
        assertFalse(EdgeInteractionRules.isHeldInDirection(90.0, 0f, 10f, 100f))
    }

    @Test
    fun genericHorizontalTargetAcceptsLocalWideModulesAndHomeButNotLists() {
        assertTrue(EdgeInteractionRules.acceptsGenericHorizontalTarget(900, 300, 2000, false, false))
        assertTrue(EdgeInteractionRules.acceptsGenericHorizontalTarget(900, 1600, 2000, false, true))
        assertFalse(EdgeInteractionRules.acceptsGenericHorizontalTarget(900, 1600, 2000, false, false))
        assertFalse(EdgeInteractionRules.acceptsGenericHorizontalTarget(900, 300, 2000, true, false))
    }

    @Test
    fun taobaoBandsIgnoreHeaderAndBottomTabsButSplitContent() {
        assertTrue(
            EdgeInteractionRules.taobaoContentBand(300f, 2000) ==
                EdgeInteractionRules.TaobaoContentBand.UPPER
        )
        assertTrue(
            EdgeInteractionRules.taobaoContentBand(900f, 2000) ==
                EdgeInteractionRules.TaobaoContentBand.MIDDLE
        )
        assertTrue(
            EdgeInteractionRules.taobaoContentBand(1500f, 2000) ==
                EdgeInteractionRules.TaobaoContentBand.LOWER
        )
        assertTrue(EdgeInteractionRules.taobaoContentBand(100f, 2000) == null)
        assertTrue(EdgeInteractionRules.taobaoContentBand(1900f, 2000) == null)
    }

    @Test
    fun taobaoFallbackOnlyBackfillsAContentBandWithARealHole() {
        assertTrue(EdgeInteractionRules.shouldBackfillTaobaoBand(0, 4))
        assertTrue(EdgeInteractionRules.shouldBackfillTaobaoBand(1, 4))
        assertFalse(EdgeInteractionRules.shouldBackfillTaobaoBand(2, 4))
        assertFalse(EdgeInteractionRules.shouldBackfillTaobaoBand(0, 0))
    }

    @Test
    fun douyinPackageAndSearchCandidatesRequireContentAboveKeyboard() {
        assertTrue(EdgeInteractionRules.isDouyinPackage("com.ss.android.ugc.aweme"))
        assertFalse(EdgeInteractionRules.isDouyinPackage("com.taobao.taobao"))
        assertTrue(EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(260, 72, 720f, 2000, 1200))
        assertFalse(EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(260, 72, 1350f, 2000, 1200))
        assertFalse(EdgeInteractionRules.acceptsDouyinSearchSemanticTarget(260, 72, 720f, 2000, null))
    }

    @Test
    fun complexContentUsesTighterCollectionBudgets() {
        assertTrue(EdgeInteractionRules.traversalBudget(true) < EdgeInteractionRules.traversalBudget(false))
        assertTrue(EdgeInteractionRules.nodeLimit(true) < EdgeInteractionRules.nodeLimit(false))
        assertTrue(EdgeInteractionRules.maxTraversalDepth(true) < EdgeInteractionRules.maxTraversalDepth(false))
    }

    @Test
    fun dynamicChineseAppsUseHighFrequencyStrategy() {
        assertTrue(EdgeInteractionRules.isHighFrequencyPackage("com.taobao.taobao"))
        assertTrue(EdgeInteractionRules.isHighFrequencyPackage("com.ss.android.ugc.aweme"))
        assertTrue(EdgeInteractionRules.isHighFrequencyPackage("com.smile.gifmaker"))
        assertFalse(EdgeInteractionRules.isHighFrequencyPackage("com.android.launcher"))
    }

    @Test
    fun genericCoverageRequestsDeepScanOnlyForEmptyBodyBands() {
        val upper = NavNode(android.graphics.Rect(100, 500, 240, 620), "upper", 1)
        val lower = NavNode(android.graphics.Rect(100, 1300, 240, 1420), "lower", 1)
        val topTab = NavNode(android.graphics.Rect(100, 80, 240, 150), "tab", 1)

        assertTrue(EdgeInteractionRules.needsExpandedContentScan(listOf(topTab), 2000))
        assertFalse(EdgeInteractionRules.needsExpandedContentScan(listOf(upper, lower), 2000))
        assertEquals(
            setOf(EdgeInteractionRules.GenericContentBand.BODY_LOWER),
            EdgeInteractionRules.missingContentBands(listOf(upper), 2000)
        )
    }

    @Test
    fun missingNavigationAnchorsRequestExpandedScanWithoutFillingBodyHoles() {
        val upper = NavNode(android.graphics.Rect(100, 500, 240, 620), "upper", 1)
        val lower = NavNode(android.graphics.Rect(100, 1300, 240, 1420), "lower", 1)
        val topTab = NavNode(android.graphics.Rect(100, 120, 240, 180), "top", 1)
        val bottomTab = NavNode(android.graphics.Rect(100, 1770, 240, 1840), "bottom", 1)

        assertTrue(EdgeInteractionRules.needsExpandedScan(listOf(upper, lower), 2000))
        assertFalse(EdgeInteractionRules.needsExpandedScan(listOf(upper, lower, topTab, bottomTab), 2000))
        assertTrue(EdgeInteractionRules.acceptsNavigationSemanticTarget(140, 60, 150f, 1000, 2000))
        assertTrue(EdgeInteractionRules.acceptsNavigationSemanticTarget(140, 60, 1800f, 1000, 2000))
        assertFalse(EdgeInteractionRules.acceptsNavigationSemanticTarget(800, 500, 1000f, 1000, 2000))
    }

    @Test
    fun capabilityProbeTracksRecoveredBandsAndNavigationEdgesSeparately() {
        val upper = NavNode(android.graphics.Rect(100, 500, 240, 620), "upper", 1)
        val bottomTab = NavNode(android.graphics.Rect(100, 1770, 240, 1840), "bottom", 1)
        val requested = setOf(
            EdgeInteractionRules.GenericContentBand.BODY_UPPER,
            EdgeInteractionRules.GenericContentBand.BODY_LOWER
        )

        assertEquals(setOf(true), EdgeInteractionRules.missingNavigationAnchors(listOf(bottomTab), 2000))
        assertEquals(
            setOf(EdgeInteractionRules.GenericContentBand.BODY_LOWER),
            EdgeInteractionRules.unresolvedBandsAfterProbe(requested, listOf(upper), 2000)
        )
    }

    @Test
    fun capabilityProbeTreatsSeamSpanningNodesAsRecoveredForBothBodyBands() {
        val seamNode = NavNode(android.graphics.Rect(120, 980, 420, 1060), "seam", 1)
        val requested = setOf(
            EdgeInteractionRules.GenericContentBand.BODY_UPPER,
            EdgeInteractionRules.GenericContentBand.BODY_LOWER
        )

        assertEquals(
            emptySet<EdgeInteractionRules.GenericContentBand>(),
            EdgeInteractionRules.unresolvedBandsAfterProbe(requested, listOf(seamNode), 2000)
        )
    }

    @Test
    fun visualOcrRequiresStructuredContentInsteadOfIsolatedSubtitleLines() {
        val productGrid = listOf(
            150 to 600, 620 to 600,
            150 to 900, 620 to 900,
            150 to 1220
        )
        val subtitles = listOf(
            400 to 950, 430 to 1000, 460 to 1050, 420 to 1100
        )

        assertTrue(EdgeInteractionRules.acceptsVisualCandidateBatch(productGrid, 1000, 2000))
        assertFalse(EdgeInteractionRules.acceptsVisualCandidateBatch(subtitles, 1000, 2000))
        assertFalse(EdgeInteractionRules.acceptsVisualCandidateBatch(productGrid.take(3), 1000, 2000))
    }

    @Test
    fun visualCandidatesKeepCommandsInActionOnlyModeButSuppressNarrativeText() {
        assertTrue(EdgeInteractionRules.isHighIntentVisualText("立即购买"))
        assertTrue(EdgeInteractionRules.isHighIntentVisualText("关闭 X"))
        assertTrue(EdgeInteractionRules.permitsVisualTextCandidate("商品标题", false))
        assertFalse(EdgeInteractionRules.permitsVisualTextCandidate("视频讲解中出现的字幕内容", true))
        assertTrue(EdgeInteractionRules.permitsVisualTextCandidate("分享", true))
    }

    @Test
    fun actionableVisionTextRecognizesRealChineseCommandsButNotTitles() {
        assertTrue(EdgeInteractionRules.isActionableVisualText("\u641c\u7d22"))
        assertTrue(EdgeInteractionRules.isActionableVisualText("\u53bb\u9886\u53d6"))
        assertTrue(EdgeInteractionRules.isActionableVisualText("\u9000\u6b3e/\u552e\u540e"))
        assertFalse(EdgeInteractionRules.isActionableVisualText("\u8131\u9aa8\u4fa0\u65e0\u9aa8\u9e21\u722a\u67e0\u6aac\u9178\u8fa3\u53bb"))
    }

    @Test
    fun visualListShapeIsNotAcceptedAsTwoLaneGrid() {
        val listLike = listOf(
            android.graphics.Rect(44, 360, 140, 456),
            android.graphics.Rect(178, 370, 520, 420),
            android.graphics.Rect(790, 372, 880, 410),
            android.graphics.Rect(44, 530, 140, 626),
            android.graphics.Rect(178, 540, 560, 590),
            android.graphics.Rect(790, 542, 880, 580),
            android.graphics.Rect(44, 700, 140, 796),
            android.graphics.Rect(178, 710, 610, 760),
            android.graphics.Rect(790, 712, 880, 750),
            android.graphics.Rect(44, 870, 140, 966),
            android.graphics.Rect(178, 880, 560, 930),
            android.graphics.Rect(790, 882, 880, 920)
        )

        assertTrue(EdgeInteractionRules.isVisualSingleColumnList(listLike, 1080, 2400))
        assertFalse(EdgeInteractionRules.isTwoLaneVisualGrid(listLike, 1080, 2400))
    }

    @Test
    fun visualTwoLaneGridRequiresBalancedLeftAndRightCells() {
        val gridLike = listOf(
            android.graphics.Rect(36, 980, 430, 1030),
            android.graphics.Rect(42, 1060, 280, 1110),
            android.graphics.Rect(560, 970, 990, 1020),
            android.graphics.Rect(566, 1058, 820, 1110),
            android.graphics.Rect(38, 1480, 450, 1530),
            android.graphics.Rect(42, 1560, 290, 1610),
            android.graphics.Rect(560, 1470, 990, 1520),
            android.graphics.Rect(566, 1558, 830, 1610)
        )

        assertFalse(EdgeInteractionRules.isVisualSingleColumnList(gridLike, 1080, 2400))
        assertTrue(EdgeInteractionRules.isTwoLaneVisualGrid(gridLike, 1080, 2400))
    }

    @Test
    fun actionableVisualSignatureIgnoresCardTextButKeepsActionLabels() {
        val cardA = listOf(
            NavNode(android.graphics.Rect(40, 500, 460, 900), "VISION[CARD]: \u9e21\u722a", origin = NavNodeOrigin.VISION),
            NavNode(android.graphics.Rect(520, 500, 940, 900), "VISION[ACTION]: \u53bb\u9886\u53d6", origin = NavNodeOrigin.VISION)
        )
        val cardTextChanged = listOf(
            NavNode(android.graphics.Rect(42, 502, 462, 902), "VISION[CARD]: \u978b\u5b50", origin = NavNodeOrigin.VISION),
            NavNode(android.graphics.Rect(520, 500, 940, 900), "VISION[ACTION]: \u53bb\u9886\u53d6", origin = NavNodeOrigin.VISION)
        )
        val actionChanged = listOf(
            NavNode(android.graphics.Rect(40, 500, 460, 900), "VISION[CARD]: \u9e21\u722a", origin = NavNodeOrigin.VISION),
            NavNode(android.graphics.Rect(520, 500, 940, 900), "VISION[ACTION]: \u7ed3\u7b97", origin = NavNodeOrigin.VISION)
        )

        assertEquals(
            EdgeInteractionRules.actionableVisualSignature(cardA),
            EdgeInteractionRules.actionableVisualSignature(cardTextChanged)
        )
        assertNotEquals(
            EdgeInteractionRules.actionableVisualSignature(cardA),
            EdgeInteractionRules.actionableVisualSignature(actionChanged)
        )
    }

    @Test
    fun sparseDouyinRightRailPrefersActionOnlyVisualCandidates() {
        assertTrue(
            EdgeInteractionRules.shouldPreferActionOnlyVisionCandidates(
                "com.ss.android.ugc.aweme",
                10,
                3
            )
        )
        assertFalse(
            EdgeInteractionRules.shouldPreferActionOnlyVisionCandidates(
                "com.ss.android.ugc.aweme",
                30,
                3
            )
        )
        assertFalse(
            EdgeInteractionRules.shouldPreferActionOnlyVisionCandidates(
                "com.taobao.taobao",
                10,
                3
            )
        )
    }

    @Test
    fun meshSignatureIgnoresSmallBoundJitterButDetectsLayoutMovement() {
        val base = listOf(NavNode(android.graphics.Rect(100, 100, 220, 180), "A", 1))
        val jitter = listOf(NavNode(android.graphics.Rect(103, 103, 223, 183), "A", 1))
        val moved = listOf(NavNode(android.graphics.Rect(145, 100, 265, 180), "A", 1))
        assertEquals(EdgeInteractionRules.meshSignature(base), EdgeInteractionRules.meshSignature(jitter))
        assertNotEquals(EdgeInteractionRules.meshSignature(base), EdgeInteractionRules.meshSignature(moved))
    }

    @Test
    fun visualCacheInvalidatesOnlyForClearVerticalContentMovement() {
        assertTrue(EdgeInteractionRules.isMeaningfulVerticalVisualScroll(0, 60, 2000))
        assertFalse(EdgeInteractionRules.isMeaningfulVerticalVisualScroll(0, 0, 2000))
        assertFalse(EdgeInteractionRules.isMeaningfulVerticalVisualScroll(120, 4, 2000))
        assertFalse(EdgeInteractionRules.isMeaningfulVerticalVisualScroll(0, 24, 2000))
    }

    @Test
    fun visualSurfaceMarkerIgnoresDynamicTextAndWindowIdButDetectsAnchorMovement() {
        val initial = listOf(
            NavNode(android.graphics.Rect(80, 110, 220, 180), "频道倒计时 01", 1),
            NavNode(android.graphics.Rect(80, 1800, 220, 1870), "首页", 1)
        )
        val noisySameSurface = listOf(
            NavNode(android.graphics.Rect(83, 111, 223, 181), "频道倒计时 02", 77),
            NavNode(android.graphics.Rect(83, 1801, 223, 1871), "首页刷新", 77)
        )
        val switchedSurface = listOf(
            NavNode(android.graphics.Rect(330, 110, 470, 180), "新频道", 77),
            NavNode(android.graphics.Rect(80, 1800, 220, 1870), "首页", 77)
        )
        assertEquals(
            EdgeInteractionRules.visualSurfaceAnchorMarker(initial, 1000, 2000),
            EdgeInteractionRules.visualSurfaceAnchorMarker(noisySameSurface, 1000, 2000)
        )
        assertNotEquals(
            EdgeInteractionRules.visualSurfaceAnchorMarker(initial, 1000, 2000),
            EdgeInteractionRules.visualSurfaceAnchorMarker(switchedSurface, 1000, 2000)
        )
    }

    @Test
    fun visualFingerprintOnlyRefreshesAfterSignificantChangeAndThrottle() {
        val base = 0x0000000000000000L
        val smallNoise = 0x000000000000000FL
        val changed = 0x000000000000FFFFL

        assertFalse(EdgeInteractionRules.hasMeaningfulVisualChange(base, smallNoise))
        assertTrue(EdgeInteractionRules.hasMeaningfulVisualChange(base, changed))
        assertFalse(EdgeInteractionRules.mayRefreshDynamicVisualCache(1_000L, 3_999L))
        assertTrue(EdgeInteractionRules.mayRefreshDynamicVisualCache(1_000L, 4_000L))
    }

    @Test
    fun visualReplacementPreservesFocusOnlyForEquivalentGeometry() {
        val oldNodes = listOf(
            NavNode(android.graphics.Rect(10, 10, 110, 110), "old-a", origin = NavNodeOrigin.VISION),
            NavNode(android.graphics.Rect(150, 10, 250, 110), "old-b", origin = NavNodeOrigin.VISION)
        )
        val equivalent = listOf(
            NavNode(android.graphics.Rect(12, 12, 112, 112), "new-a", origin = NavNodeOrigin.VISION),
            NavNode(android.graphics.Rect(152, 12, 252, 112), "new-b", origin = NavNodeOrigin.VISION)
        )
        val replaced = listOf(
            NavNode(android.graphics.Rect(400, 400, 500, 500), "moved", origin = NavNodeOrigin.VISION)
        )

        assertTrue(EdgeInteractionRules.hasEquivalentVisualGeometry(oldNodes, equivalent))
        assertEquals(equivalent.first(), EdgeInteractionRules.closestEquivalentNode(oldNodes.first(), equivalent))
        assertFalse(EdgeInteractionRules.hasEquivalentVisualGeometry(oldNodes, replaced))
    }

    @Test
    fun visualSurfaceKeyChangesForHoleLayoutButNotDynamicContent() {
        val surface = VisualSurfaceKey(
            "com.taobao.taobao",
            45,
            90,
            setOf(EdgeInteractionRules.GenericContentBand.BODY_UPPER)
        )
        val sameSurfaceAfterDynamicContent = surface.copy()
        val newHoleLayout = surface.copy(
            holeBands = setOf(EdgeInteractionRules.GenericContentBand.BODY_LOWER)
        )

        assertEquals(surface, sameSurfaceAfterDynamicContent)
        assertNotEquals(surface, newHoleLayout)
    }
}
