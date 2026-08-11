package com.example.touchengine

import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TargetFusionOverlapTest {
    private val context = TargetFusionContext(
        screenWidth = 1080,
        screenHeight = 2400,
        density = 3f,
        keyboardTop = null,
        visualOcclusionBounds = null,
        targetPackage = "com.example.test",
        douyinScene = DouyinScene.GENERAL,
        useDouyinVideoAccessibility = false
    )

    @Test
    fun containedOcrLabelUsesStructuralBoundsAndName() {
        val outer = visualNode(
            bounds = Rect(100, 300, 700, 470),
            source = TargetSource.YOLO,
            role = TargetRole.ACTION,
            yoloClass = "textbutton",
            stableId = "outer"
        )
        val label = visualNode(
            bounds = Rect(150, 345, 330, 420),
            source = TargetSource.OCR,
            role = TargetRole.ACTION,
            text = "\u84dd\u7259",
            stableId = "label"
        )

        val result = TargetFusionEngine().fuse(listOf(outer, label), context)

        assertEquals(1, result.nodes.size)
        val merged = result.nodes.single()
        assertEquals(outer.bounds, merged.bounds)
        assertEquals(outer.bounds, merged.interactionBounds)
        assertEquals("\u84dd\u7259", merged.description)
        assertEquals("outer", merged.stableId)
        assertEquals(setOf(TargetSource.YOLO, TargetSource.OCR), merged.sourceSet)
        assertTrue(FusionMergeRelation.CONTAINED_LABEL in merged.visualEvidence!!.mergeRelations)
        assertTrue("label" in merged.visualEvidence!!.absorbedStableIds)
    }

    @Test
    fun accessibilityRowAbsorbsOrdinaryOcrTitleAndKeepsNativeClick() {
        val row = NavNode(
            bounds = Rect(40, 500, 1040, 680),
            description = "native-row",
            windowId = 1,
            stableId = "row"
        )
        val title = visualNode(
            bounds = Rect(90, 545, 330, 620),
            source = TargetSource.OCR,
            role = TargetRole.ACTION,
            text = "\u9ad8\u7ea7\u8bbe\u7f6e",
            stableId = "title"
        )

        val result = TargetFusionEngine().fuse(listOf(row, title), context)

        assertEquals(1, result.nodes.size)
        assertEquals(row.bounds, result.nodes.single().bounds)
        assertEquals("\u9ad8\u7ea7\u8bbe\u7f6e", result.nodes.single().description)
        assertEquals(ClickMethod.ACCESSIBILITY_ACTION, result.nodes.single().clickMethod)
    }

    @Test
    fun containedLabelsPreferSemanticTitleOverModelValue() {
        val row = visualNode(
            Rect(40, 500, 1040, 680),
            TargetSource.YOLO,
            TargetRole.ACTION,
            yoloClass = "textbutton",
            stableId = "row"
        )
        val title = visualNode(
            Rect(90, 545, 310, 615),
            TargetSource.OCR,
            TargetRole.ACTION,
            text = "\u8bbe\u5907\u540d\u79f0",
            stableId = "title"
        )
        val value = visualNode(
            Rect(650, 545, 770, 615),
            TargetSource.OCR,
            TargetRole.ACTION,
            text = "K70",
            stableId = "value"
        )

        val result = TargetFusionEngine().fuse(listOf(row, title, value), context)

        assertEquals(1, result.nodes.size)
        assertEquals("\u8bbe\u5907\u540d\u79f0", result.nodes.single().description)
        assertTrue("title" in result.nodes.single().visualEvidence!!.absorbedStableIds)
        assertTrue("value" in result.nodes.single().visualEvidence!!.absorbedStableIds)
    }

    @Test
    fun largeContainerKeepsIndependentInnerAction() {
        val container = visualNode(
            bounds = Rect(40, 500, 1040, 800),
            source = TargetSource.YOLO,
            role = TargetRole.ACTION,
            yoloClass = "textbutton",
            stableId = "container"
        )
        val action = visualNode(
            bounds = Rect(800, 650, 970, 725),
            source = TargetSource.OCR,
            role = TargetRole.ACTION,
            text = "\u53bb\u4f7f\u7528",
            stableId = "action"
        )

        val result = TargetFusionEngine().fuse(listOf(container, action), context)

        assertEquals(2, result.nodes.size)
        assertTrue(result.nodes.any { it.stableId == "action" })
        assertTrue(
            result.nodes.first { it.stableId == "action" }.visualEvidence?.mergeRelations
                ?.contains(FusionMergeRelation.KEEP_INNER_ACTION) == true
        )
    }

    @Test
    fun iconAndLabelBecomeOneEntryWithIconInteractionBounds() {
        val icon = visualNode(
            bounds = Rect(100, 600, 190, 690),
            source = TargetSource.YOLO,
            role = TargetRole.ICON_ENTRY,
            yoloClass = "icon",
            stableId = "icon"
        )
        val label = visualNode(
            bounds = Rect(90, 705, 200, 755),
            source = TargetSource.OCR,
            role = TargetRole.ACTION,
            text = "\u6536\u85cf",
            stableId = "label"
        )

        val result = TargetFusionEngine().fuse(listOf(icon, label), context)

        assertEquals(1, result.nodes.size)
        val merged = result.nodes.single()
        assertEquals(Rect(90, 600, 200, 755), merged.bounds)
        assertEquals(icon.bounds, merged.interactionBounds)
        assertEquals(TargetRole.ICON_ENTRY, merged.visualEvidence?.role)
        assertEquals("\u6536\u85cf", merged.description)
        assertTrue(FusionMergeRelation.ICON_LABEL_PAIR in merged.visualEvidence!!.mergeRelations)
    }

    @Test
    fun gridPairsAreOneToOneAndDoNotChainAcrossColumns() {
        val nodes = listOf(
            visualNode(Rect(100, 600, 180, 680), TargetSource.YOLO, TargetRole.ICON_ENTRY, yoloClass = "icon", stableId = "i1"),
            visualNode(Rect(340, 600, 420, 680), TargetSource.YOLO, TargetRole.ICON_ENTRY, yoloClass = "icon", stableId = "i2"),
            visualNode(Rect(90, 695, 190, 745), TargetSource.OCR, TargetRole.ACTION, text = "\u5feb\u9012", stableId = "l1"),
            visualNode(Rect(320, 695, 440, 745), TargetSource.OCR, TargetRole.ACTION, text = "\u6536\u85cf", stableId = "l2")
        )

        val result = TargetFusionEngine().fuse(nodes, context)

        assertEquals(2, result.nodes.size)
        assertEquals(setOf("\u5feb\u9012", "\u6536\u85cf"), result.nodes.mapNotNull { it.description }.toSet())
        assertTrue(result.nodes.all { it.visualEvidence?.role == TargetRole.ICON_ENTRY })
        assertTrue(result.nodes.first { it.description == "\u5feb\u9012" }.bounds.centerX() < 250)
        assertTrue(result.nodes.first { it.description == "\u6536\u85cf" }.bounds.centerX() > 250)
    }

    @Test
    fun sideOrDistantTextRemainsIndependent() {
        val icon = visualNode(
            Rect(100, 600, 180, 680),
            TargetSource.YOLO,
            TargetRole.ICON_ENTRY,
            yoloClass = "icon",
            stableId = "icon"
        )
        val sideText = visualNode(
            Rect(260, 610, 400, 665),
            TargetSource.OCR,
            TargetRole.ACTION,
            text = "\u6211\u7684\u6536\u85cf",
            stableId = "label"
        )

        val result = TargetFusionEngine().fuse(listOf(icon, sideText), context)

        assertEquals(2, result.nodes.size)
    }

    @Test
    fun gestureClickUsesInteractionBoundsInsteadOfDisplayUnion() {
        var dispatched = false
        val executor = TargetClickExecutor(object : TargetClickExecutor.Host {
            override val screenWidth: Int = 1080
            override val screenHeight: Int = 2400
            override val density: Float = 3f
            override fun activeTargetPackage(): String = "com.example.test"
            override fun obtainPrimaryContentRoot(): AccessibilityNodeInfo? = null
            override fun dispatchGesture(gesture: GestureDescription): Boolean {
                dispatched = true
                return true
            }
        })
        val target = NavNode(
            bounds = Rect(80, 500, 220, 720),
            description = "entry",
            origin = NavNodeOrigin.VISION,
            clickMethod = ClickMethod.GESTURE_CENTER,
            interactionBounds = Rect(100, 500, 200, 600)
        )

        val result = executor.performClick(target)

        assertTrue(dispatched)
        assertEquals(150f, result.x!!, 0.01f)
        assertEquals(550f, result.y!!, 0.01f)
    }

    @Test
    fun fusedPairConsumesBothDirectStableNodesAndMigratesFocus() {
        val icon = visualNode(
            Rect(100, 600, 190, 690),
            TargetSource.YOLO,
            TargetRole.ICON_ENTRY,
            yoloClass = "icon"
        )
        val label = visualNode(
            Rect(90, 705, 200, 755),
            TargetSource.OCR,
            TargetRole.ACTION,
            text = "\u6536\u85cf"
        )
        val canvas = PersistentVisualCanvas()
        commit(canvas, pending(VisualUpdateStage.DIRECT_YOLO, 1L, listOf(icon)))
        commit(canvas, pending(VisualUpdateStage.DIRECT_OCR, 1L, listOf(label)))
        assertEquals(2, canvas.nodeCount)
        val committedIcon = canvas.committedNodes.first { TargetSource.YOLO in it.sourceSet }
        val committedLabel = canvas.committedNodes.first { it.sourceSet == setOf(TargetSource.OCR) }
        val fused = TargetFusionEngine().fuse(canvas.committedNodes, context).nodes.single()

        val fusedPending = pending(VisualUpdateStage.FUSED, 1L, listOf(fused))
        canvas.stage(fusedPending)
        val update = canvas.prepareUpdate(fusedPending)!!
        val commitResult = canvas.commit(update, committedLabel)

        assertNotNull(commitResult)
        assertEquals(1, canvas.nodeCount)
        assertEquals(committedIcon.stableId, canvas.committedNodes.single().stableId)
        assertEquals(committedIcon.stableId, commitResult!!.replacementFocus?.stableId)
        assertTrue(committedLabel.stableId in update.patch.removedStableIds)
        assertEquals(committedIcon.stableId, update.stableIdAliases[committedLabel.stableId])
    }

    private fun visualNode(
        bounds: Rect,
        source: TargetSource,
        role: TargetRole,
        text: String? = null,
        yoloClass: String? = null,
        stableId: String? = null
    ): NavNode {
        val sources = setOf(source)
        return NavNode(
            bounds = Rect(bounds),
            description = text ?: yoloClass,
            windowId = 1,
            origin = NavNodeOrigin.VISION,
            clickMethod = ClickMethod.GESTURE_CENTER,
            sourceSet = sources,
            visualEvidence = VisualTargetEvidence(
                sources = sources,
                yoloClass = yoloClass,
                yoloConfidence = if (source == TargetSource.YOLO) 0.9f else null,
                ocrText = text,
                role = role,
                score = if (source == TargetSource.YOLO) 78 else 72,
                clickMethod = ClickMethod.GESTURE_CENTER,
                directEligible = true
            ),
            stableId = stableId
        )
    }

    private fun pending(
        stage: VisualUpdateStage,
        scanId: Long,
        nodes: List<NavNode>
    ) = PendingVisualCache(
        token = VisualScanToken(
            pageEpoch = 1L,
            viewportRevision = 1L,
            scanId = scanId,
            targetPackage = "com.example.test",
            primaryWindowId = 1
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
}
