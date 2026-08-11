package com.example.touchengine

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VisualMarqueeGuardTest {

    private val token = VisualScanToken(
        pageEpoch = 1L,
        viewportRevision = 1L,
        scanId = 1L,
        targetPackage = "com.example.test",
        primaryWindowId = 1
    )
    private val surface = VisualSurfaceKey(
        targetPackage = "com.example.test",
        viewportWidthBucket = 10,
        viewportHeightBucket = 20,
        holeBands = EdgeInteractionRules.GenericContentBand.entries.toSet()
    )

    private fun ocrNode(text: String, bounds: Rect = Rect(200, 800, 520, 880)): NavNode {
        val sources = setOf(TargetSource.OCR)
        return NavNode(
            bounds = Rect(bounds),
            description = text,
            windowId = 1,
            origin = NavNodeOrigin.VISION,
            sourceSet = sources,
            visualEvidence = VisualTargetEvidence(
                sources = sources,
                ocrText = text,
                role = TargetRole.ACTION,
                score = 70,
                clickMethod = ClickMethod.GESTURE_CENTER,
                directEligible = true
            )
        )
    }

    @Test
    fun changingOcrTextIsQuarantinedAfterThreeCommittedChanges() {
        val guard = VisualMarqueeGuard()
        var committed = emptyList<NavNode>()

        listOf("弹幕一", "弹幕二", "弹幕三").forEachIndexed { index, text ->
            val node = ocrNode(text)
            val decision = guard.evaluate(
                token = token,
                surfaceKey = surface,
                visualNodes = listOf(node),
                committedNodes = committed,
                screenWidth = 1080,
                screenHeight = 2400,
                density = 3f,
                nowMs = index * 1_000L
            )
            assertEquals(1, decision.nodes.size)
            guard.recordBuildOutcome(decision.observation, committed = true, nowMs = index * 1_000L)
            committed = listOf(node)
        }

        val blocked = guard.evaluate(
            token = token,
            surfaceKey = surface,
            visualNodes = listOf(ocrNode("弹幕四")),
            committedNodes = committed,
            screenWidth = 1080,
            screenHeight = 2400,
            density = 3f,
            nowMs = 3_000L
        )

        assertTrue(blocked.nodes.isEmpty())
        assertTrue(blocked.requiresInstall)
    }

    @Test
    fun quarantinedRegionStillRetainsUnchangedOcrControl() {
        val guard = VisualMarqueeGuard()
        var committed = emptyList<NavNode>()
        listOf("变化一", "变化二", "固定按钮").forEachIndexed { index, text ->
            val node = ocrNode(text)
            val decision = guard.evaluate(
                token,
                surface,
                listOf(node),
                committed,
                1080,
                2400,
                3f,
                index * 1_000L
            )
            guard.recordBuildOutcome(decision.observation, committed = true, nowMs = index * 1_000L)
            committed = listOf(node)
        }

        val stable = guard.evaluate(
            token,
            surface,
            listOf(ocrNode("固定按钮")),
            committed,
            1080,
            2400,
            3f,
            3_000L
        )

        assertEquals(1, stable.nodes.size)
        assertEquals("固定按钮", stable.nodes.single().visualEvidence?.ocrText)
    }
}
