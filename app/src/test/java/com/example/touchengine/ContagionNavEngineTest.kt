package com.example.touchengine

import android.graphics.Rect
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ContagionNavEngineTest {
    private fun connectedNodes(): Pair<NavNode, NavNode> {
        val left = NavNode(Rect(0, 0, 100, 100), "left")
        val right = NavNode(Rect(150, 0, 250, 100), "right")
        left.neighbors.add(right)
        return left to right
    }

    @Test
    fun originalPlanBPreservesFirstJumpConfirmationDelay() {
        val (left, _) = connectedNodes()
        val engine = ContagionNavEngine()

        engine.beginGesture(FocusResponseMode.ORIGINAL_PLAN_B)

        assertSame(left, engine.onDrag(left, 100f, 0f, 100f).node)
    }

    @Test
    fun instantResponseJumpsAsSoonAsDeadZoneIsCrossed() {
        val (left, right) = connectedNodes()
        val engine = ContagionNavEngine()

        engine.beginGesture(FocusResponseMode.INSTANT_RESPONSE)

        assertSame(right, engine.onDrag(left, 100f, 0f, 100f).node)
    }
}
