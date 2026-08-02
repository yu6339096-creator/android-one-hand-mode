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
        joystickBounds = null,
        inputMethodVisible = false,
        accessibilityNodes = emptyList()
    )

    private fun buildNodes(
        detections: List<YoloDetection>,
        ocr: List<VisualOcrCandidate> = emptyList()
    ): List<NavNode> {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            VisualCandidateEngine(processingContext).buildNavigationNodes(
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

    private fun pending(
        stage: VisualUpdateStage,
        scanId: Long,
        nodes: List<NavNode>
    ): PendingVisualCache = PendingVisualCache(
        token = VisualScanToken(
            pageEpoch = 1L,
            viewportRevision = 1L,
            scanId = scanId,
            targetPackage = "com.example.test",
            primaryWindowId = 0
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
                joystickBounds = null,
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
    fun actionableOcrUsesFastPathWithoutYolo() {
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
        assertTrue(plainText.isEmpty())
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
                joystickBounds = null,
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
