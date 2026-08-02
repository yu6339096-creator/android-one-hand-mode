package com.example.touchengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegateFactory
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class YoloUiResult(
    val detections: List<YoloDetection>,
    val backendName: String,
    val elapsedMs: Long,
    val rawCandidateCount: Int = detections.size
)

class YoloUiDetector private constructor(
    private val labels: List<String>,
    private val runner: Runner,
    val backendName: String
) : Closeable {
    companion object {
        private const val LABEL_ASSET = "yolo/labels.txt"
        private const val CONFIDENCE_THRESHOLD = 0.05f
        private const val NMS_THRESHOLD = 0.45f
        private const val PRE_NMS_PER_CLASS_LIMIT = 100
        private const val POST_NMS_GLOBAL_LIMIT = 160

        fun createPreferred(
            context: Context,
            inputSize: YoloInputSize = YoloInputSize.SIZE_640
        ): YoloUiDetector {
            return createWithSpecs(
                context,
                inputSize,
                listOf(
                    BackendSpec("NNAPI preferred", threads = 1, useNnapi = true),
                    BackendSpec("GPU Delegate FP32", threads = 1, useGpu = true, gpuPrecisionLossAllowed = false),
                    BackendSpec("GPU Delegate FP16", threads = 1, useGpu = true, gpuPrecisionLossAllowed = true),
                    BackendSpec("CPU XNNPACK 2 threads", threads = 2),
                    BackendSpec("CPU XNNPACK 4 threads", threads = 4),
                    BackendSpec("CPU XNNPACK 1 thread", threads = 1)
                )
            )
        }

        fun createLivePreferred(
            context: Context,
            inputSize: YoloInputSize = YoloInputSize.SIZE_640
        ): YoloUiDetector {
            return createWithSpecs(
                context,
                inputSize,
                listOf(
                    BackendSpec("NNAPI preferred", threads = 1, useNnapi = true),
                    BackendSpec("CPU XNNPACK 2 threads", threads = 2),
                    BackendSpec("CPU XNNPACK 4 threads", threads = 4),
                    BackendSpec("CPU XNNPACK 1 thread", threads = 1)
                )
            )
        }

        internal fun canonicalYoloClass(label: String): String {
            val compact = label.trim().lowercase().replace("_", "").replace(" ", "")
            return when (compact) {
                "checkbox" -> "checkbox"
                else -> compact
            }
        }

        internal fun classAwareNms(
            input: List<YoloDetection>,
            iouThreshold: Float = NMS_THRESHOLD,
            perClassLimit: Int = PRE_NMS_PER_CLASS_LIMIT,
            globalLimit: Int = POST_NMS_GLOBAL_LIMIT
        ): List<YoloDetection> {
            val kept = ArrayList<YoloDetection>()
            input.groupBy { canonicalYoloClass(it.label) }.values.forEach { sameClass ->
                val classKept = ArrayList<YoloDetection>()
                sameClass.sortedByDescending { it.confidence }
                    .take(perClassLimit)
                    .forEach { detection ->
                        if (classKept.none { overlap(it.box, detection.box) >= iouThreshold }) {
                            classKept += detection
                        }
                    }
                kept += classKept
            }
            return kept.sortedByDescending { it.confidence }.take(globalLimit)
        }

        private fun overlap(a: RectF, b: RectF): Float {
            val left = max(a.left, b.left)
            val top = max(a.top, b.top)
            val right = min(a.right, b.right)
            val bottom = min(a.bottom, b.bottom)
            val intersection = max(0f, right - left) * max(0f, bottom - top)
            val areaA = max(0f, a.width()) * max(0f, a.height())
            val areaB = max(0f, b.width()) * max(0f, b.height())
            val union = areaA + areaB - intersection
            return if (union <= 0f) 0f else intersection / union
        }

        private fun createWithSpecs(
            context: Context,
            inputSize: YoloInputSize,
            specs: List<BackendSpec>
        ): YoloUiDetector {
            val labels = context.assets.open(LABEL_ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
            val modelBytes = context.assets.open(inputSize.modelAsset).use { it.readBytes() }
            var lastError: Throwable? = null
            for (spec in specs) {
                try {
                    val runner = Runner(modelBytes, labels, spec, inputSize.pixels)
                    return YoloUiDetector(labels, runner, spec.name)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw IllegalStateException("No YOLO backend available", lastError)
        }
    }

    @Synchronized
    fun detect(bitmap: Bitmap): YoloUiResult {
        val startedAt = SystemClock.elapsedRealtime()
        val detections = runner.run(bitmap)
        return YoloUiResult(
            detections = detections,
            backendName = backendName,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            rawCandidateCount = runner.lastRawCandidateCount
        )
    }

    override fun close() {
        runner.close()
    }

    private data class BackendSpec(
        val name: String,
        val threads: Int,
        val useGpu: Boolean = false,
        val useNnapi: Boolean = false,
        val gpuPrecisionLossAllowed: Boolean = true
    )

    private data class LetterboxInfo(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val inputSize: Int
    )

    private class Runner(
        modelBytes: ByteArray,
        private val labels: List<String>,
        spec: BackendSpec,
        expectedInputSize: Int
    ) : Closeable {
        private val modelBuffer: ByteBuffer = ByteBuffer.allocateDirect(modelBytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(modelBytes)
                rewind()
            }
        private val gpuDelegateFactory: GpuDelegateFactory? = if (spec.useGpu) {
            GpuDelegateFactory(
                GpuDelegateFactory.Options()
                    .setPrecisionLossAllowed(spec.gpuPrecisionLossAllowed)
                    .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER)
            )
        } else {
            null
        }
        private val nnApiDelegate: NnApiDelegate? = if (spec.useNnapi) NnApiDelegate() else null
        private val interpreter: Interpreter
        private val inputSize: Int
        private val outputShape: IntArray
        private val outputBuffer: Array<Array<FloatArray>>
        private val inputBuffer: ByteBuffer
        private val letterboxBitmap: Bitmap
        private val letterboxCanvas: Canvas
        private val letterboxPixels: IntArray
        private val letterboxPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val srcRect = Rect()
        private val dstRect = RectF()
        var lastRawCandidateCount: Int = 0
            private set

        init {
            val options = Interpreter.Options().apply {
                setNumThreads(spec.threads)
                setUseXNNPACK(!spec.useGpu && !spec.useNnapi)
                gpuDelegateFactory?.let { addDelegateFactory(it) }
                nnApiDelegate?.let { addDelegate(it) }
            }
            interpreter = Interpreter(modelBuffer, options)
            val inputShape = interpreter.getInputTensor(0).shape()
            inputSize = inputShape[1]
            require(inputShape.size == 4 && inputSize == expectedInputSize && inputShape[2] == expectedInputSize) {
                "YOLO model input ${inputShape.contentToString()} does not match ${expectedInputSize}x${expectedInputSize}"
            }
            outputShape = interpreter.getOutputTensor(0).shape()
            outputBuffer = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
            inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
            letterboxBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            letterboxCanvas = Canvas(letterboxBitmap)
            letterboxPixels = IntArray(inputSize * inputSize)
        }

        fun run(bitmap: Bitmap): List<YoloDetection> {
            val letterbox = preprocess(bitmap, inputSize)
            interpreter.run(inputBuffer, outputBuffer)
            return postprocess(outputBuffer, outputShape, letterbox, bitmap.width, bitmap.height)
        }

        private fun preprocess(bitmap: Bitmap, size: Int): LetterboxInfo {
            val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
            val scaledWidth = (bitmap.width * scale).roundToInt()
            val scaledHeight = (bitmap.height * scale).roundToInt()
            val padX = (size - scaledWidth) / 2f
            val padY = (size - scaledHeight) / 2f
            letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            dstRect.set(padX, padY, padX + scaledWidth, padY + scaledHeight)
            letterboxCanvas.drawBitmap(bitmap, srcRect, dstRect, letterboxPaint)

            letterboxBitmap.getPixels(letterboxPixels, 0, size, 0, 0, size, size)
            inputBuffer.clear()
            for (pixel in letterboxPixels) {
                inputBuffer.putFloat(((pixel shr 16) and 0xff) / 255f)
                inputBuffer.putFloat(((pixel shr 8) and 0xff) / 255f)
                inputBuffer.putFloat((pixel and 0xff) / 255f)
            }
            inputBuffer.rewind()
            return LetterboxInfo(scale, padX, padY, size)
        }

        private fun postprocess(
            output: Array<Array<FloatArray>>,
            shape: IntArray,
            letterbox: LetterboxInfo,
            originalWidth: Int,
            originalHeight: Int
        ): List<YoloDetection> {
            val channels = shape[1]
            val anchors = shape[2]
            val candidates = ArrayList<YoloDetection>()
            for (anchor in 0 until anchors) {
                var bestClass = 0
                var bestScore = 0f
                for (classIndex in 4 until channels) {
                    val score = output[0][classIndex][anchor]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = classIndex - 4
                    }
                }
                if (bestScore < CONFIDENCE_THRESHOLD) continue

                var cx = output[0][0][anchor]
                var cy = output[0][1][anchor]
                var w = output[0][2][anchor]
                var h = output[0][3][anchor]
                if (max(max(cx, cy), max(w, h)) <= 2f) {
                    cx *= letterbox.inputSize
                    cy *= letterbox.inputSize
                    w *= letterbox.inputSize
                    h *= letterbox.inputSize
                }
                val left = ((cx - w / 2f - letterbox.padX) / letterbox.scale).coerceIn(0f, originalWidth.toFloat())
                val top = ((cy - h / 2f - letterbox.padY) / letterbox.scale).coerceIn(0f, originalHeight.toFloat())
                val right = ((cx + w / 2f - letterbox.padX) / letterbox.scale).coerceIn(0f, originalWidth.toFloat())
                val bottom = ((cy + h / 2f - letterbox.padY) / letterbox.scale).coerceIn(0f, originalHeight.toFloat())
                if (right - left < 4f || bottom - top < 4f) continue
                candidates += YoloDetection(
                    label = labels.getOrElse(bestClass) { bestClass.toString() },
                    confidence = bestScore,
                    box = RectF(left, top, right, bottom)
                )
            }
            lastRawCandidateCount = candidates.size
            return classAwareNms(candidates)
        }

        override fun close() {
            interpreter.close()
            nnApiDelegate?.close()
            letterboxBitmap.recycle()
        }
    }
}
