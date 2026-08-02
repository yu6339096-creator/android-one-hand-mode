package com.example.touchengine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class YoloBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                YoloBenchmarkScreen(onRun = {
                    withContext(Dispatchers.Default) {
                        YoloTfliteBenchmark.run(this@YoloBenchmarkActivity)
                    }
                })
            }
        }
    }
}

@Composable
private fun YoloBenchmarkScreen(
    onRun: suspend () -> YoloBenchmarkReport
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<YoloBenchmarkReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("YOLO UI Detector Benchmark", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Offline only: no NavNode, no click, no Accessibility changes.", fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !running,
            onClick = {
                running = true
                error = null
                scope.launch {
                    try {
                        report = onRun()
                    } catch (throwable: Throwable) {
                        error = throwable.javaClass.simpleName + ": " + throwable.message
                    } finally {
                        running = false
                    }
                }
            }
        ) {
            Text(if (running) "Running..." else "Run benchmark")
        }
        if (running) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
        }
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        report?.let { YoloReportView(it) }
    }
}

@Composable
private fun YoloReportView(report: YoloBenchmarkReport) {
    Spacer(Modifier.height(16.dp))
    Text(
        "Model ${(report.modelBytes / 1024f / 1024f).format1()} MB, input ${report.inputSize}, labels ${report.labels.size}",
        fontSize = 13.sp
    )
    Text(
        "Backend warmup ${report.backendWarmupRuns}, sample warmup ${report.sampleWarmupRuns}, measured ${report.measuredRuns}",
        fontSize = 12.sp
    )
    Spacer(Modifier.height(12.dp))
    report.backends.forEach { backend ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(backend.backendName, fontWeight = FontWeight.Bold)
                backend.failedReason?.let {
                    Text("Failed: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                backend.samples.forEach { sample ->
                    Spacer(Modifier.height(6.dp))
                    Text(sample.sampleName, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("det ${sample.detections}", fontSize = 12.sp)
                        Text("p50 ${sample.totalP50Ms.format1()}ms", fontSize = 12.sp)
                        Text("p90 ${sample.totalP90Ms.format1()}ms", fontSize = 12.sp)
                        Text("max ${sample.totalMaxMs.format1()}ms", fontSize = 12.sp)
                    }
                    Text(
                        "pre ${sample.preprocessP50Ms.format1()} / infer ${sample.inferenceP50Ms.format1()} / post ${sample.postprocessP50Ms.format1()}",
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
    report.previewBitmap?.let { bitmap ->
        Text("Preview boxes (${report.previewDetections.size})", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "YOLO preview",
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun Float.format1(): String = String.format("%.1f", this)
