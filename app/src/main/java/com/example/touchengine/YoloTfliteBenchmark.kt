package com.example.touchengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class YoloDetection(
    val label: String,
    val confidence: Float,
    val box: RectF
)

data class YoloSampleBenchmark(
    val sampleName: String,
    val detections: Int,
    val preprocessP50Ms: Float,
    val inferenceP50Ms: Float,
    val postprocessP50Ms: Float,
    val totalP50Ms: Float,
    val totalP90Ms: Float,
    val totalMaxMs: Float
)

data class YoloBackendBenchmark(
    val backendName: String,
    val failedReason: String?,
    val samples: List<YoloSampleBenchmark>
)

data class YoloBenchmarkReport(
    val modelBytes: Long,
    val labels: List<String>,
    val inputSize: Int,
    val backendWarmupRuns: Int,
    val sampleWarmupRuns: Int,
    val measuredRuns: Int,
    val backends: List<YoloBackendBenchmark>,
    val previewBitmap: Bitmap?,
    val previewDetections: List<YoloDetection>
)

private data class BackendSpec(
    val name: String,
    val threads: Int,
    val useGpu: Boolean = false,
    val useNnapi: Boolean = false,
    val gpuPrecisionLossAllowed: Boolean = true
)

private data class TimedRun(
    val preprocessMs: Float,
    val inferenceMs: Float,
    val postprocessMs: Float,
    val totalMs: Float,
    val detections: List<YoloDetection>
)

private data class LetterboxInfo(
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val inputSize: Int
)

object YoloTfliteBenchmark {
    private const val MODEL_ASSET = "yolo/ui-detector-fp32.tflite"
    private const val LABEL_ASSET = "yolo/labels.txt"
    private const val SAMPLE_DIR = "yolo/samples"
    private const val BACKEND_WARMUP_RUNS = 2
    private const val SAMPLE_WARMUP_RUNS = 3
    private const val MEASURED_RUNS = 10
    private const val CONFIDENCE_THRESHOLD = 0.25f
    private const val NMS_THRESHOLD = 0.45f

    fun run(context: Context): YoloBenchmarkReport {
        val labels = context.assets.open(LABEL_ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val sampleNames = context.assets.list(SAMPLE_DIR)
            ?.filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) || it.endsWith(".webp", true) }
            ?.sorted()
            .orEmpty()
        val samples = sampleNames.mapNotNull { name ->
            context.assets.open("$SAMPLE_DIR/$name").use { stream ->
                BitmapFactory.decodeStream(stream)?.let { name to it }
            }
        }

        val backends = listOf(
            BackendSpec("NNAPI preferred", 1, useNnapi = true),
            BackendSpec("GPU Delegate FP32", 1, useGpu = true, gpuPrecisionLossAllowed = false),
            BackendSpec("GPU Delegate FP16", 1, useGpu = true, gpuPrecisionLossAllowed = true),
            BackendSpec("CPU XNNPACK 2 threads", 2),
            BackendSpec("CPU XNNPACK 4 threads", 4),
            BackendSpec("CPU XNNPACK 1 thread", 1)
        ).map { spec ->
            runBackend(spec, modelBytes, labels, samples)
        }

        val firstSuccessful = backends.firstOrNull { it.failedReason == null && it.samples.isNotEmpty() }
        val previewSource = samples.firstOrNull()?.second
        val previewDetections = if (firstSuccessful != null && previewSource != null) {
            runPreview(modelBytes, labels, previewSource)
        } else {
            emptyList()
        }
        return YoloBenchmarkReport(
            modelBytes = modelBytes.size.toLong(),
            labels = labels,
            inputSize = 640,
            backendWarmupRuns = BACKEND_WARMUP_RUNS,
            sampleWarmupRuns = SAMPLE_WARMUP_RUNS,
            measuredRuns = MEASURED_RUNS,
            backends = backends,
            previewBitmap = previewSource?.let { drawPreview(it, previewDetections) },
            previewDetections = previewDetections
        )
    }

    private fun runBackend(
        spec: BackendSpec,
        modelBytes: ByteArray,
        labels: List<String>,
        samples: List<Pair<String, Bitmap>>
    ): YoloBackendBenchmark {
        return try {
            val runner = Runner(modelBytes, labels, spec)
            runner.use { activeRunner ->
                samples.firstOrNull()?.second?.let { firstBitmap ->
                    repeat(BACKEND_WARMUP_RUNS) { activeRunner.run(firstBitmap) }
                }
                val results = samples.map { (name, bitmap) ->
                    val measured = mutableListOf<TimedRun>()
                    repeat(SAMPLE_WARMUP_RUNS + MEASURED_RUNS) { index ->
                        val run = activeRunner.run(bitmap)
                        if (index >= SAMPLE_WARMUP_RUNS) measured += run
                    }
                    YoloSampleBenchmark(
                        sampleName = name,
                        detections = measured.lastOrNull()?.detections?.size ?: 0,
                        preprocessP50Ms = percentile(measured.map { run -> run.preprocessMs }, 0.50f),
                        inferenceP50Ms = percentile(measured.map { run -> run.inferenceMs }, 0.50f),
                        postprocessP50Ms = percentile(measured.map { run -> run.postprocessMs }, 0.50f),
                        totalP50Ms = percentile(measured.map { run -> run.totalMs }, 0.50f),
                        totalP90Ms = percentile(measured.map { run -> run.totalMs }, 0.90f),
                        totalMaxMs = measured.maxOfOrNull { run -> run.totalMs } ?: 0f
                    )
                }
                YoloBackendBenchmark(spec.name, null, results)
            }
        } catch (throwable: Throwable) {
            YoloBackendBenchmark(spec.name, throwable.javaClass.simpleName + ": " + throwable.message, emptyList())
        }
    }

    private fun runPreview(modelBytes: ByteArray, labels: List<String>, bitmap: Bitmap): List<YoloDetection> {
        return try {
            Runner(modelBytes, labels, BackendSpec("preview", 2)).use { it.run(bitmap).detections }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun percentile(values: List<Float>, percentile: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val index = (ceil(sorted.size * percentile).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun drawPreview(source: Bitmap, detections: List<YoloDetection>): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 150, 255)
            style = Paint.Style.STROKE
            strokeWidth = max(3f, bitmap.width / 240f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 70, 180)
            textSize = max(22f, bitmap.width / 34f)
            style = Paint.Style.FILL
        }
        detections.take(80).forEach { detection ->
            canvas.drawRect(detection.box, stroke)
            val label = "${detection.label} ${(detection.confidence * 100).roundToInt()}%"
            val width = textPaint.measureText(label) + 10f
            val top = max(0f, detection.box.top - textPaint.textSize - 8f)
            canvas.drawRoundRect(
                RectF(detection.box.left, top, detection.box.left + width, top + textPaint.textSize + 8f),
                8f,
                8f,
                fill
            )
            canvas.drawText(label, detection.box.left + 5f, top + textPaint.textSize, textPaint)
        }
        return bitmap
    }

    private class Runner(
        modelBytes: ByteArray,
        private val labels: List<String>,
        spec: BackendSpec
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
            outputShape = interpreter.getOutputTensor(0).shape()
            outputBuffer = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
            inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
            letterboxBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
            letterboxCanvas = Canvas(letterboxBitmap)
            letterboxPixels = IntArray(inputSize * inputSize)
        }

        fun run(bitmap: Bitmap): TimedRun {
            val totalStart = SystemClock.elapsedRealtimeNanos()
            val preprocessStart = SystemClock.elapsedRealtimeNanos()
            val (input, letterbox) = preprocess(bitmap, inputSize)
            val preprocessMs = elapsedMs(preprocessStart)

            val inferenceStart = SystemClock.elapsedRealtimeNanos()
            interpreter.run(input, outputBuffer)
            val inferenceMs = elapsedMs(inferenceStart)

            val postprocessStart = SystemClock.elapsedRealtimeNanos()
            val detections = postprocess(outputBuffer, outputShape, letterbox, bitmap.width, bitmap.height)
            val postprocessMs = elapsedMs(postprocessStart)
            return TimedRun(
                preprocessMs = preprocessMs,
                inferenceMs = inferenceMs,
                postprocessMs = postprocessMs,
                totalMs = elapsedMs(totalStart),
                detections = detections
            )
        }

        private fun preprocess(bitmap: Bitmap, size: Int): Pair<ByteBuffer, LetterboxInfo> {
            val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
            val scaledWidth = (bitmap.width * scale).roundToInt()
            val scaledHeight = (bitmap.height * scale).roundToInt()
            val padX = ((size - scaledWidth) / 2f)
            val padY = ((size - scaledHeight) / 2f)
            letterboxCanvas.drawColor(Color.rgb(114, 114, 114))
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            dstRect.set(padX, padY, padX + scaledWidth, padY + scaledHeight)
            letterboxCanvas.drawBitmap(bitmap, srcRect, dstRect, letterboxPaint)

            letterboxBitmap.getPixels(letterboxPixels, 0, size, 0, 0, size, size)
            inputBuffer.clear()
            for (index in letterboxPixels.indices) {
                val pixel = letterboxPixels[index]
                inputBuffer.putFloat(((pixel shr 16) and 0xff) / 255f)
                inputBuffer.putFloat(((pixel shr 8) and 0xff) / 255f)
                inputBuffer.putFloat((pixel and 0xff) / 255f)
            }
            inputBuffer.rewind()
            return inputBuffer to LetterboxInfo(scale, padX, padY, size)
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
            return nonMaxSuppression(candidates)
        }

        private fun nonMaxSuppression(input: List<YoloDetection>): List<YoloDetection> {
            val kept = ArrayList<YoloDetection>()
            input.sortedByDescending { it.confidence }.forEach { detection ->
                if (kept.none { overlap(it.box, detection.box) >= NMS_THRESHOLD }) {
                    kept += detection
                }
                if (kept.size >= 80) return kept
            }
            return kept
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

        override fun close() {
            interpreter.close()
            nnApiDelegate?.close()
            letterboxBitmap.recycle()
        }

        private fun elapsedMs(startNanos: Long): Float {
            return (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000f
        }
    }
}
