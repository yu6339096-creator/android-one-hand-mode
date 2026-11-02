package com.example.touchengine

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class VisualServiceStopTest {
    private class Fixture {
        var enabled = true
        var captures = 0
        var requests = 0
        var callback: ((VisualScreenshotFrame) -> Unit)? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = VisualScanRuntime("test", 1, 1080, 2400, 3f, null, null,
            false, emptyList(), emptyList(), false, true, false, YoloInputSize.SIZE_640)
        val request = VisualScanRequest(runtime, emptySet(), Rect(0, 0, 1080, 2400), emptyMap(),
            VisualSurfaceKey("test", 1, 1, emptySet()), DouyinScene.GENERAL, fullScreenTestMode = true)
        val coordinator = VisualScanCoordinator(
            VisualRecognizer(RuntimeEnvironment.getApplication()), PersistentVisualCanvas(),
            object : VisualScreenshotProvider {
                override fun capture(request: VisualCaptureRequest, onSuccess: (VisualScreenshotFrame) -> Unit, onFailure: (Int) -> Unit) {
                    captures++
                    callback = onSuccess
                }
            }, scope, Handler(Looper.getMainLooper()), { runtime }, { requests++; request },
            { _, _, _ -> fail("Disabled/stale capture must not install") }, {}, {}, { enabled }
        )
        fun close() { coordinator.close(); scope.cancel() }
    }

    @Test fun disabledAndScheduledWorkCannotCapture() {
        val f = Fixture()
        try {
            f.coordinator.scheduleScan(100)
            f.enabled = false
            f.coordinator.stop()
            f.coordinator.scheduleScan(0)
            f.coordinator.scanIfIdle()
            assertFalse(f.coordinator.resumeAfterSelection(0))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(0, f.requests)
            assertEquals(0, f.captures)
            assertFalse(f.coordinator.hasScanInFlight)
        } finally { f.close() }
    }

    @Test fun lateScreenshotCannotReviveStoppedOrRestartedSession() {
        val f = Fixture()
        try {
            f.coordinator.scanIfIdle()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, f.captures)
            val oldCallback = f.callback!!
            f.enabled = false
            f.coordinator.stop()
            f.enabled = true
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            oldCallback(VisualScreenshotFrame(bitmap, VisualCaptureMode.WINDOW_CLEAN, 1, Rect(0, 0, 2, 2)))
            assertTrue(bitmap.isRecycled)
            assertTrue(f.coordinator.visualOcrState.value.candidates.isEmpty())
            assertNull(f.coordinator.visualOcrState.value.status)
            f.coordinator.scheduleScan(0)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, f.captures)
        } finally { f.close() }
    }
}
