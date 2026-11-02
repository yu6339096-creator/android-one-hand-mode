package com.example.touchengine

import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TargetClickCompletionTest {
    private class Host(var accept: Boolean = true) : TargetClickExecutor.Host {
        override val screenWidth = 1080
        override val screenHeight = 2400
        override val density = 3f
        var calls = 0
        var callback: ((TargetClickOutcome) -> Unit)? = null
        override fun activeTargetPackage() = "test"
        override fun obtainPrimaryContentRoot(): AccessibilityNodeInfo? = null
        override fun dispatchGesture(gesture: GestureDescription, onResult: (TargetClickOutcome) -> Unit): Boolean {
            calls++
            callback = onResult
            return accept
        }
    }
    private val target = NavNode(Rect(100, 200, 200, 300), "target", origin = NavNodeOrigin.VISION)

    @Test fun acceptedRequestIsNotReportedAsCompleted() {
        val host = Host()
        val results = mutableListOf<TargetClickResult>()
        val submitted = TargetClickExecutor(host).performClick(target, results::add)
        assertFalse(submitted.clicked)
        assertEquals(TargetClickOutcome.DISPATCHED, submitted.outcome)
        assertTrue(results.isEmpty())
        host.callback!!(TargetClickOutcome.COMPLETED)
        assertTrue(results.single().clicked)
        assertEquals(TargetClickOutcome.COMPLETED, results.single().outcome)
    }

    @Test fun cancellationAndTimeoutDoNotRetryOrClaimSuccess() {
        for (outcome in listOf(TargetClickOutcome.CANCELLED, TargetClickOutcome.TIMEOUT)) {
            val host = Host()
            val results = mutableListOf<TargetClickResult>()
            TargetClickExecutor(host).performClick(target, results::add)
            host.callback!!(outcome)
            host.callback!!(TargetClickOutcome.COMPLETED)
            assertFalse(results.single().clicked)
            assertEquals(outcome, results.single().outcome)
            assertEquals(1, host.calls)
        }
    }

    @Test fun rejectionCompletesOnceWithoutWaitingForCallback() {
        val host = Host(false)
        val results = mutableListOf<TargetClickResult>()
        TargetClickExecutor(host).performClick(target, results::add)
        assertEquals(TargetClickOutcome.REJECTED, results.single().outcome)
        host.callback!!(TargetClickOutcome.CANCELLED)
        assertEquals(1, results.size)
        assertEquals(1, host.calls)
    }
}
