package com.example.touchengine

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class V45VisualNavigationTest {
    private val processingContext = VisualProcessingContext(
        screenWidth = 1080,
        screenHeight = 2400,
        density = 3f,
        keyboardTop = null,
        visualOcclusionBounds = null,
        inputMethodVisible = false,
        accessibilityNodes = emptyList()
    )

    private fun buildNodes(
        detections: List<YoloDetection>,
        ocr: List<VisualOcrCandidate> = emptyList(),
        context: VisualProcessingContext = processingContext
    ): List<NavNode> {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            VisualCandidateEngine(context).buildNavigationNodes(
                candidates = ocr,
                targetPackage = "com.example.test",
                cropped = bitmap,
                cropBounds = Rect(0, 0, 1080, 2400),
                allowTextStructures = false,
                yoloResult = YoloUiResult(
                    detections = detections,
                    backendName = "test",
                    elapsedMs = 1,
                    rawCandidateCount = detections.size
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun cleanWindowCaptureDoesNotTreatJoystickAsVisualOcclusion() {
        val detection = YoloDetection(
            "TextButton",
            0.90f,
            RectF(760f, 1740f, 1040f, 2020f)
        )
        val joystick = Rect(740, 1720, 1060, 2040)

        val cleanNodes = buildNodes(
            detections = listOf(detection),
            context = processingContext.copy(visualOcclusionBounds = null)
        )
        val fallbackNodes = buildNodes(
            detections = listOf(detection),
            context = processingContext.copy(visualOcclusionBounds = joystick)
        )

        assertEquals(1, cleanNodes.size)
        assertTrue(fallbackNodes.isEmpty())
    }

    @Test
    fun verifiedWeChatRowSurvivesJoystickOverlapAndKeepsSafeLeftClick() {
        val sources = setOf(TargetSource.OCR, TargetSource.VISUAL_FUSION, TargetSource.YOLO)
        val row = NavNode(
            bounds = Rect(12, 1400, 1068, 1580),
            description = "服务通知",
            windowId = 1,
            origin = NavNodeOrigin.VISION,
            clickMethod = ClickMethod.GESTURE_SAFE_LEFT,
            sourceSet = sources,
            visualEvidence = VisualTargetEvidence(
                sources = sources,
                ocrText = "服务通知",
                role = TargetRole.LIST_ITEM,
                score = 90,
                clickMethod = ClickMethod.GESTURE_SAFE_LEFT,
                directEligible = true
            )
        )
        val result = TargetFusionEngine().fuse(
            rawNodes = listOf(row),
            context = TargetFusionContext(
                screenWidth = 1080,
                screenHeight = 2400,
                density = 3f,
                keyboardTop = null,
                visualOcclusionBounds = Rect(330, 1380, 750, 1800),
                targetPackage = "com.tencent.mm",
                douyinScene = DouyinScene.GENERAL,
                useDouyinVideoAccessibility = false
            )
        )

        assertEquals(1, result.nodes.size)
        assertEquals(ClickMethod.GESTURE_SAFE_LEFT, result.nodes.single().clickMethod)
    }

    @Test
    fun weChatAvatarAnchorKeepsRowWhenJoystickHidesItsTitle() {
        val anchors = listOf(500, 700, 900).mapIndexed { index, centerY ->
            NavNode(
                bounds = Rect(36, centerY - 48, 132, centerY + 48),
                description = "avatar-$index",
                windowId = 1
            )
        }
        val context = processingContext.copy(
            visualOcclusionBounds = Rect(300, 610, 760, 790),
            accessibilityNodes = anchors
        )
        val ocr = listOf(
            VisualOcrCandidate(Rect(430, 180, 570, 230), "微信"),
            VisualOcrCandidate(Rect(180, 470, 420, 530), "第一位联系人"),
            // 中间一行标题被摇杆遮住，只剩左侧头像锚点。
            VisualOcrCandidate(Rect(180, 870, 420, 930), "第三位联系人")
        )
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val nodes = try {
            VisualCandidateEngine(context).buildNavigationNodes(
                candidates = ocr,
                targetPackage = "com.tencent.mm",
                cropped = bitmap,
                cropBounds = Rect(0, 0, 1080, 2400),
                allowTextStructures = true,
                yoloResult = null
            )
        } finally {
            bitmap.recycle()
        }

        val rows = nodes.filter { it.visualEvidence?.role == TargetRole.LIST_ITEM }
        assertEquals(3, rows.size)
        assertTrue(rows.any { it.description == "微信聊天项" })
        assertTrue(rows.all { it.clickMethod == ClickMethod.GESTURE_SAFE_LEFT })
    }

    private fun pending(
        stage: VisualUpdateStage,
        scanId: Long,
        nodes: List<NavNode>,
        scanMode: VisualScanMode = VisualScanMode.BASE_SCAN
    ): PendingVisualCache = PendingVisualCache(
        token = VisualScanToken(
            pageEpoch = 1L,
            viewportRevision = 1L,
            scanId = scanId,
            targetPackage = "com.example.test",
            primaryWindowId = 0,
            scanMode = scanMode
        ),
        stage = stage,
        nodes = nodes,
        candidates = emptyList(),
        targetPackage = "com.example.test",
        bands = emptySet(),
        surfaceKey = VisualSurfaceKey("com.example.test", 10, 20, emptySet()),
        fingerprints = emptyMap(),
        actionableSignature = 0,
        textEvidenceCount = 0,
        rejectedEvidenceCount = 0,
        elapsedMs = 1L,
        reason = VisualCacheInvalidationReason.CONTENT_CHANGE
    )

    private fun commit(canvas: PersistentVisualCanvas, pending: PendingVisualCache) {
        canvas.stage(pending)
        val update = canvas.prepareUpdate(pending)!!
        canvas.commit(update, null)!!
    }

    @Test
    fun nmsSuppressesOnlyWithinCanonicalClass() {
        val input = listOf(
            YoloDetection("CheckBox", 0.90f, RectF(0f, 0f, 100f, 100f)),
            YoloDetection("Checkbox", 0.80f, RectF(2f, 2f, 98f, 98f)),
            YoloDetection("Icon", 0.70f, RectF(2f, 2f, 98f, 98f))
        )

        val kept = YoloUiDetector.classAwareNms(input)

        assertEquals(2, kept.size)
        assertTrue(kept.any { it.label == "CheckBox" })
        assertTrue(kept.any { it.label == "Icon" })
    }

    @Test
    fun lowConfidenceStrongControlEntersNavigationWithoutOcr() {
        val nodes = buildNodes(
            listOf(YoloDetection("TextButton", 0.05f, RectF(300f, 600f, 620f, 680f)))
        )

        assertEquals(1, nodes.size)
        val evidence = nodes.single().visualEvidence!!
        assertTrue(evidence.directEligible)
        assertEquals(setOf(TargetSource.YOLO), evidence.sources)
        assertEquals(0.05f, evidence.yoloConfidence!!, 0.0001f)
        assertTrue(evidence.score >= 50)
        val fused = TargetFusionEngine().fuse(
            rawNodes = nodes,
            context = TargetFusionContext(
                screenWidth = 1080,
                screenHeight = 2400,
                density = 3f,
                keyboardTop = null,
                visualOcclusionBounds = null,
                targetPackage = "com.example.test",
                douyinScene = DouyinScene.GENERAL,
                useDouyinVideoAccessibility = false
            )
        )
        assertEquals(1, fused.nodes.size)
    }

    @Test
    fun actionYoloUsesFastPathWithoutWaitingForOcr() {
        val engine = VisualCandidateEngine(processingContext)
        val result = YoloUiResult(
            detections = listOf(YoloDetection("TextButton", 0.05f, RectF(300f, 600f, 620f, 680f))),
            backendName = "test",
            elapsedMs = 1,
            rawCandidateCount = 1
        )

        val direct = engine.buildDirectYoloNavigationNodes(
            result = result,
            cropBounds = Rect(0, 0, 1080, 2400),
            targetPackage = "com.example.test"
        )
        val nonAction = engine.buildDirectYoloNavigationNodes(
            result = result.copy(detections = listOf(
                YoloDetection("Toolbar", 0.9f, RectF(0f, 0f, 1080f, 120f))
            )),
            cropBounds = Rect(0, 0, 1080, 2400),
            targetPackage = "com.example.test"
        )

        assertEquals(1, direct.size)
        assertTrue(direct.single().visualEvidence!!.directEligible)
        assertEquals(setOf(TargetSource.YOLO), direct.single().sourceSet)
        assertTrue(nonAction.isEmpty())
    }

    @Test
    fun actionableAndOrdinaryShortOcrUseFastPathWithoutYolo() {
        val engine = VisualCandidateEngine(processingContext)
        val direct = engine.buildDirectOcrNavigationNodes(
            candidates = listOf(VisualOcrCandidate(Rect(300, 600, 520, 680), "搜索")),
            targetPackage = "com.example.test"
        )
        val plainText = engine.buildDirectOcrNavigationNodes(
            candidates = listOf(VisualOcrCandidate(Rect(300, 600, 520, 680), "商品推荐")),
            targetPackage = "com.example.test"
        )

        assertEquals(1, direct.size)
        assertEquals(setOf(TargetSource.OCR), direct.single().sourceSet)
        assertTrue(direct.single().visualEvidence?.directEligible == true)
        assertTrue((direct.single().visualEvidence?.score ?: 0) >= 70)
        assertEquals(1, plainText.size)
        assertTrue(plainText.single().visualEvidence?.directEligible == true)
    }

    @Test
    fun ocrAdmissionKeepsLongAndNoisyTextOutOfDirectNavigation() {
        val engine = VisualCandidateEngine(processingContext)
        val candidates = listOf(
            VisualOcrCandidate(Rect(120, 500, 500, 560), "abcdefghijklmn"),
            VisualOcrCandidate(Rect(120, 600, 360, 660), "https://a.com"),
            VisualOcrCandidate(Rect(120, 700, 260, 760), "￥128"),
            VisualOcrCandidate(Rect(120, 800, 260, 860), "12:30"),
            VisualOcrCandidate(Rect(120, 900, 260, 960), "12345"),
            VisualOcrCandidate(Rect(120, 1000, 360, 1060), "#热门话题")
        )

        val direct = engine.buildDirectOcrNavigationNodes(candidates, "com.example.test")

        assertTrue(direct.isEmpty())
    }

    @Test
    fun positionAwareGlyphsEnterButCentralGlyphDoesNot() {
        val engine = VisualCandidateEngine(processingContext)
        val direct = engine.buildDirectOcrNavigationNodes(
            candidates = listOf(
                VisualOcrCandidate(Rect(24, 130, 84, 190), "←"),
                VisualOcrCandidate(Rect(960, 130, 1020, 190), "×"),
                VisualOcrCandidate(Rect(500, 900, 560, 960), "←")
            ),
            targetPackage = "com.example.test"
        )

        assertEquals(2, direct.size)
        assertTrue(direct.any { it.bounds.centerX() < 100 })
        assertTrue(direct.any { it.bounds.centerX() > 900 })
        assertTrue(direct.none { it.bounds.centerY() > 800 })
    }

    @Test
    fun trailingChevronPromotesItsTextRowInsteadOfTinyGlyph() {
        val engine = VisualCandidateEngine(processingContext)
        val direct = engine.buildDirectOcrNavigationNodes(
            candidates = listOf(
                VisualOcrCandidate(Rect(120, 600, 360, 670), "订单详情"),
                VisualOcrCandidate(Rect(930, 605, 980, 665), ">")
            ),
            targetPackage = "com.example.test"
        )

        assertTrue(direct.any { it.bounds.left == 120 && it.bounds.right == 980 })
        assertTrue(direct.none { it.bounds == Rect(930, 605, 980, 665) })
    }

    @Test
    fun douyinVideoOnlyAdmitsFixedRegionsAndExactActions() {
        val videoContext = processingContext.copy(
            douyinScene = DouyinScene.VIDEO_FEED_PORTRAIT,
            regionPolicy = visualRegionPolicyFor(DouyinScene.VIDEO_FEED_PORTRAIT, 1080, 2400)
        )
        val engine = VisualCandidateEngine(videoContext)
        val direct = engine.buildDirectOcrNavigationNodes(
            candidates = listOf(
                VisualOcrCandidate(Rect(300, 900, 520, 970), "中央弹幕"),
                VisualOcrCandidate(Rect(300, 180, 520, 250), "热门推荐"),
                VisualOcrCandidate(Rect(860, 900, 1030, 970), "作者主页"),
                VisualOcrCandidate(Rect(300, 2050, 520, 2120), "朋友频道"),
                VisualOcrCandidate(Rect(300, 1100, 450, 1170), "关注")
            ),
            targetPackage = "com.ss.android.ugc.aweme"
        )
        val labels = direct.mapNotNull { it.visualEvidence?.ocrText }.toSet()

        assertTrue("中央弹幕" !in labels)
        assertTrue("热门推荐" in labels)
        assertTrue("作者主页" in labels)
        assertTrue("朋友频道" in labels)
        assertTrue("关注" in labels)
    }

    @Test
    fun directObservationsKeepOneStableNodeUntilFusionCorrectsIt() {
        val node = buildNodes(
            listOf(YoloDetection("TextButton", 0.05f, RectF(300f, 600f, 620f, 680f)))
        ).single()
        val canvas = PersistentVisualCanvas()

        commit(canvas, pending(VisualUpdateStage.DIRECT_YOLO, 1L, listOf(node)))
        val stableId = canvas.committedNodes.single().stableId
        // OCR 尚未给出同位置结果，不得把先到的 YOLO 节点删除。
        commit(canvas, pending(VisualUpdateStage.DIRECT_OCR, 1L, emptyList()))
        assertEquals(1, canvas.nodeCount)
        assertEquals(stableId, canvas.committedNodes.single().stableId)

        val fusedNode = node.copy(description = "已融合目标")
        commit(canvas, pending(VisualUpdateStage.FUSED, 1L, listOf(fusedNode)))
        assertEquals(1, canvas.nodeCount)
        assertEquals(stableId, canvas.committedNodes.single().stableId)
    }

    @Test
    fun fullRefreshFusionCannotEraseUncoveredDirectOcrKeyword() {
        val keyword = VisualCandidateEngine(processingContext)
            .buildDirectOcrNavigationNodes(
                candidates = listOf(VisualOcrCandidate(Rect(80, 900, 220, 970), "待付款")),
                targetPackage = "com.taobao.taobao"
            )
            .single()
        val canvas = PersistentVisualCanvas()

        commit(
            canvas,
            pending(
                VisualUpdateStage.DIRECT_OCR,
                scanId = 7L,
                nodes = listOf(keyword),
                scanMode = VisualScanMode.FULL_REFRESH
            )
        )
        commit(
            canvas,
            pending(
                VisualUpdateStage.FUSED,
                scanId = 7L,
                nodes = emptyList(),
                scanMode = VisualScanMode.FULL_REFRESH
            )
        )

        assertEquals(1, canvas.nodeCount)
        assertEquals("待付款", canvas.committedNodes.single().visualEvidence?.ocrText)
        assertTrue(canvas.committedNodes.single().visualEvidence?.directEligible == true)
    }

    @Test
    fun fullRefreshFusionCannotEraseUncoveredOrdinaryShortOcr() {
        val direct = VisualCandidateEngine(processingContext)
            .buildDirectOcrNavigationNodes(
                candidates = listOf(VisualOcrCandidate(Rect(80, 900, 260, 970), "商品推荐")),
                targetPackage = "com.example.test"
            )
            .single()
        val canvas = PersistentVisualCanvas()

        commit(canvas, pending(VisualUpdateStage.DIRECT_OCR, 8L, listOf(direct)))
        commit(canvas, pending(VisualUpdateStage.FUSED, 8L, emptyList()))

        assertEquals(1, canvas.nodeCount)
        assertEquals("商品推荐", canvas.committedNodes.single().visualEvidence?.ocrText)
    }

    @Test
    fun fusionKeepsDirectOcrKeywordInsideLargerVisualContainer() {
        val keyword = VisualCandidateEngine(processingContext)
            .buildDirectOcrNavigationNodes(
                candidates = listOf(VisualOcrCandidate(Rect(80, 900, 220, 970), "待付款")),
                targetPackage = "com.taobao.taobao"
            )
            .single()
        val cardSources = setOf(TargetSource.YOLO)
        val card = NavNode(
            bounds = Rect(30, 820, 1030, 1080),
            description = "订单卡片",
            windowId = keyword.windowId,
            origin = NavNodeOrigin.VISION,
            sourceSet = cardSources,
            visualEvidence = VisualTargetEvidence(
                sources = cardSources,
                yoloClass = "card",
                yoloConfidence = 0.9f,
                role = TargetRole.CARD,
                score = 80,
                clickMethod = ClickMethod.GESTURE_CENTER,
                directEligible = false
            )
        )
        val result = TargetFusionEngine().fuse(
            rawNodes = listOf(card, keyword),
            context = TargetFusionContext(
                screenWidth = 1080,
                screenHeight = 2400,
                density = 3f,
                keyboardTop = null,
                visualOcclusionBounds = null,
                targetPackage = "com.taobao.taobao",
                douyinScene = DouyinScene.GENERAL,
                useDouyinVideoAccessibility = false
            )
        )

        assertTrue(result.nodes.any { it.visualEvidence?.ocrText == "待付款" })
    }

    @Test
    fun yoloTextAndOcrActionFuseIntoNamedAction() {
        val nodes = buildNodes(
            detections = listOf(
                YoloDetection("Text", 0.20f, RectF(200f, 800f, 520f, 890f))
            ),
            ocr = listOf(
                VisualOcrCandidate(
                    bounds = Rect(250, 820, 410, 875),
                    text = "\u641c\u7d22"
                )
            )
        )

        assertEquals(1, nodes.size)
        val evidence = nodes.single().visualEvidence!!
        assertEquals(TargetRole.ACTION, evidence.role)
        assertEquals("\u641c\u7d22", evidence.ocrText)
        assertEquals(setOf(TargetSource.YOLO, TargetSource.OCR), evidence.sources)
        assertTrue(evidence.score >= 85)
    }

    @Test
    fun cardKeepsSmallIndependentIconChild() {
        val nodes = buildNodes(
            listOf(
                YoloDetection("Card", 0.20f, RectF(100f, 500f, 600f, 900f)),
                YoloDetection("Icon", 0.20f, RectF(150f, 550f, 220f, 620f))
            )
        )

        assertTrue(nodes.any { it.visualEvidence?.role == TargetRole.CARD })
        assertTrue(nodes.any { it.visualEvidence?.role == TargetRole.ICON_ENTRY })
    }

    @Test
    fun accessibilityConfirmationAddsScoreAndUsesNativeClick() {
        val visual = buildNodes(
            listOf(YoloDetection("TextButton", 0.05f, RectF(300f, 600f, 620f, 680f)))
        ).single()
        val accessibility = NavNode(
            bounds = Rect(300, 600, 620, 680),
            description = "native action",
            windowId = visual.windowId
        )
        val result = TargetFusionEngine().fuse(
            rawNodes = listOf(accessibility, visual),
            context = TargetFusionContext(
                screenWidth = 1080,
                screenHeight = 2400,
                density = 3f,
                keyboardTop = null,
                visualOcclusionBounds = null,
                targetPackage = "com.example.test",
                douyinScene = DouyinScene.GENERAL,
                useDouyinVideoAccessibility = false
            )
        )

        assertEquals(1, result.targets.size)
        val target = result.targets.single()
        assertTrue(TargetSource.ACCESSIBILITY in target.sourceSet)
        assertTrue(TargetSource.YOLO in target.sourceSet)
        assertTrue(target.priority >= 130)
        assertEquals(ClickMethod.ACCESSIBILITY_ACTION, target.clickMethod)
    }

    @Test
    fun navMeshIsBidirectionalConnectedAndWindowIsolated() {
        val first = NavNode(Rect(0, 0, 40, 40), "first", windowId = 1)
        val second = NavNode(Rect(400, 0, 440, 40), "second", windowId = 1)
        val third = NavNode(Rect(800, 0, 840, 40), "third", windowId = 1)
        val popup = NavNode(Rect(20, 100, 60, 140), "popup", windowId = 2)
        val nodes = listOf(first, second, third, popup)

        NavMeshBuilder.build(nodes, maxConnectDist = 100f)

        assertEquals(1, NavMeshBuilder.connectedComponents(listOf(first, second, third)).size)
        nodes.forEach { node ->
            node.neighbors.forEach { neighbor ->
                assertTrue(node in neighbor.neighbors)
                assertEquals(node.windowId, neighbor.windowId)
            }
        }
        assertFalse(popup.neighbors.any { it.windowId == 1 })
        assertEquals(nodes.size, NavMeshBuilder.reachableNodes(nodes).size)
    }
}
