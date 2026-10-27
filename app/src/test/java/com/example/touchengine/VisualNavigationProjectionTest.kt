package com.example.touchengine

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VisualNavigationProjectionTest {
    private fun node(id: String, x: Int = 0) = NavNode(
        bounds = Rect(x, 100, x + 100, 200), description = "direct", windowId = 1,
        origin = NavNodeOrigin.VISION, stableId = id, sourceSet = setOf(TargetSource.YOLO)
    )

    @Test fun metadataAndSmallMotionDoNotPublishOrMoveFocus() {
        val old = node("a")
        val observed = old.copy(bounds = Rect(3, 103, 103, 203), description = "OCR label",
            sourceSet = setOf(TargetSource.YOLO, TargetSource.OCR))
        val projected = VisualNavigationProjection.project(listOf(old), listOf(observed))
        assertTrue(VisualNavigationProjection.sameTargets(listOf(old), projected))
        assertSame(old, projected.single())
        assertSame(old, NavMeshFocusResolver.reconcile(old, projected))
    }

    @Test fun cumulativeMotionUsesPublishedBaseline() {
        val old = node("a")
        val small = VisualNavigationProjection.project(listOf(old), listOf(node("a", 4)))
        assertSame(old, small.single())
        val large = VisualNavigationProjection.project(small, listOf(node("a", 8)))
        assertFalse(VisualNavigationProjection.sameTargets(small, large))
        assertEquals(8, large.single().bounds.left)
    }

    @Test fun clickAreaAndMethodChangesMustPropagate() {
        val old = node("a")
        for (changed in listOf(old.copy(interactionBounds = Rect(0, 100, 40, 200)),
            old.copy(clickMethod = ClickMethod.GESTURE_SAFE_LEFT))) {
            assertFalse(VisualNavigationProjection.sameTargets(listOf(old),
                VisualNavigationProjection.project(listOf(old), listOf(changed))))
        }
    }

    @Test fun unrelatedPatchDoesNotPublishMetadataOfUnchangedTarget() {
        val a = node("a")
        val b = node("b", 700)
        val projected = VisualNavigationProjection.project(listOf(a, b),
            listOf(a.copy(description = "new metadata"), node("b", 740)))
        assertSame(a, projected.first())
        assertEquals(740, projected.last().bounds.left)
    }

    @Test fun broadSameSceneChangesStayIncrementalAndDoNotMutateOldGraph() {
        val old = (0..39).map { node("old$it", it * 110) }
        NavMeshBuilder.build(old)
        val edges = old.map { it.neighbors.map { neighbor -> neighbor.stableId } }
        val desired = old.take(8) + (0..31).map { node("new$it", it * 110) }
        val patch = NavMeshBuilder.applyIncremental(old, desired)
        assertTrue(patch.incremental)
        assertEquals(desired.map { it.stableId }.toSet(), patch.nodes.map { it.stableId }.toSet())
        assertEquals(edges, old.map { it.neighbors.map { neighbor -> neighbor.stableId } })
        assertEquals(patch.nodes.size, NavMeshBuilder.reachableNodes(patch.nodes).size)
        val allowed = patch.nodes.toSet()
        assertTrue(patch.nodes.all { n -> n.neighbors.all { it in allowed && n in it.neighbors } })
    }

    @Test fun deletingLastTargetIsStillAPatch() {
        val result = NavMeshBuilder.applyIncremental(listOf(node("a")), emptyList())
        assertTrue(result.incremental)
        assertTrue(result.nodes.isEmpty())
        assertEquals(1, result.removedNodeCount)
    }

    @Test fun deletionAndWindowAdditionKeepEdgesValid() {
        val old = listOf(node("a"), node("b", 200), node("c", 400))
        NavMeshBuilder.build(old)
        val patch = NavMeshBuilder.applyIncremental(old, listOf(old[0], old[2], node("d").copy(windowId = 2)))
        assertTrue(patch.incremental)
        assertTrue(patch.nodes.all { n -> n.neighbors.all { it.stableId != "b" && it.windowId == n.windowId } })
        assertEquals(3, NavMeshBuilder.reachableNodes(patch.nodes).size)
    }
}
