package com.example.touchengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.Closeable

/**
 * OCR、YOLO 与 ML Kit Object Detection 的执行层。
 *
 * 本类拥有模型实例及其生命周期，但不拥有调用方传入的 Bitmap。
 * Bitmap 必须由创建它的调用方统一回收，避免重复 recycle 或提前回收。
 */
internal class VisualRecognizer(
    context: Context,
    private val ocrExtractor: OcrEvidenceExtractor = OcrEvidenceExtractor()
) : Closeable {

    private val appContext = context.applicationContext
    private val yoloLock = Any()

    private var textRecognizer: TextRecognizer? = null
    private var objectDetector: ObjectDetector? = null
    private var yoloUiDetector: YoloUiDetector? = null
    private var yoloInputSize: YoloInputSize? = null

    fun prewarmYolo(): YoloPrewarmResult {
        val startedAt = SystemClock.elapsedRealtime()
        val detector = obtainYoloDetector()
        return YoloPrewarmResult(
            backendName = detector.backendName,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
        )
    }

    fun recognizeText(
        bitmap: Bitmap,
        offset: Rect,
        context: OcrExtractionContext,
        onSuccess: (OcrRecognitionResult) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        obtainTextRecognizer()
            .process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val recognition = try {
                    OcrRecognitionResult(
                        candidates = ocrExtractor.extract(result, bitmap, offset, context),
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    )
                } catch (error: Throwable) {
                    onFailure(error)
                    return@addOnSuccessListener
                }
                onSuccess(recognition)
            }
            .addOnFailureListener { error -> onFailure(error) }
    }

    fun detectYolo(bitmap: Bitmap, inputSize: YoloInputSize): YoloUiResult {
        return obtainYoloDetector(inputSize).detect(bitmap)
    }

    fun recognizeObjects(
        bitmap: Bitmap,
        offset: Rect,
        onSuccess: (ObjectRecognitionResult) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        val detector = obtainObjectDetector()
        val startedAt = SystemClock.elapsedRealtime()
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { results ->
                val recognition = try {
                    val boxes = results.mapNotNull { detected ->
                        val local = detected.boundingBox
                        val absolute = Rect(local).apply { offset(offset.left, offset.top) }
                        if (absolute.width() < 28 || absolute.height() < 28) null else absolute
                    }
                    ObjectRecognitionResult(
                        boxes = boxes,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt
                    )
                } catch (error: Throwable) {
                    onFailure(error)
                    return@addOnSuccessListener
                }
                onSuccess(recognition)
            }
            .addOnFailureListener { error -> onFailure(error) }
    }

    private fun obtainTextRecognizer(): TextRecognizer {
        return textRecognizer ?: TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        ).also { textRecognizer = it }
    }

    private fun obtainObjectDetector(): ObjectDetector {
        return objectDetector ?: ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build()
        ).also { objectDetector = it }
    }

    private fun obtainYoloDetector(inputSize: YoloInputSize = YoloInputSize.SIZE_640): YoloUiDetector {
        synchronized(yoloLock) {
            if (yoloUiDetector != null && yoloInputSize == inputSize) {
                return requireNotNull(yoloUiDetector)
            }
            yoloUiDetector?.close()
            return YoloUiDetector.createLivePreferred(appContext, inputSize)
                .also {
                    yoloUiDetector = it
                    yoloInputSize = inputSize
                }
        }
    }

    override fun close() {
        textRecognizer?.close()
        objectDetector?.close()
        synchronized(yoloLock) {
            yoloUiDetector?.close()
            yoloUiDetector = null
            yoloInputSize = null
        }
        textRecognizer = null
        objectDetector = null
    }
}
