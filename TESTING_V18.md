# TouchEnginePerf v18 - YOLO UI Detector Mobile Benchmark

## APK

`dist/touchengine-perf-v18-yolo-benchmark-debug.apk`

SHA-256:

`06C7612A4373A62AA11989EC66A5DA2A90E08A17E3BDAE9E34761BAD8F775FB9`

## Scope

- This version only adds an offline YOLO UI detector benchmark.
- YOLO results are not added to `NavNode`.
- YOLO does not replace ML Kit, Accessibility, focus selection, clicking, edge paging, or scrolling.
- Existing ML Kit/Object diagnostic paths remain unchanged for comparison.

## Model Assets

- `app/src/main/assets/yolo/ui-detector-fp32.tflite`
- `app/src/main/assets/yolo/labels.txt`
- Model input: `640 x 640`
- Classes: `BackgroundImage`, `Bottom_Navigation`, `Card`, `CheckBox`, `Checkbox`, `CheckedTextView`, `Drawer`, `EditText`, `Icon`, `Image`, `Map`, `Modal`, `Multi_Tab`, `PageIndicator`, `Remember`, `Spinner`, `Switch`, `Text`, `TextButton`, `Toolbar`, `UpperTaskBar`

## Benchmark Modes

The benchmark page runs each bundled screenshot with:

- CPU + XNNPACK, 1 thread
- CPU + XNNPACK, 2 threads
- CPU + XNNPACK, 4 threads
- GPU Delegate
- NNAPI

Each sample uses:

- 1 warmup run
- 10 measured runs
- `preprocess / inference / postprocess / total`
- `p50 / p90 / max`

If GPU Delegate or NNAPI is unsupported on the device or by this model, the page records the failure reason instead of crashing.

## PC TFLite Baseline

PC benchmark output:

`C:\Users\yu'ch'yi\Documents\Codex\2026-05-27\files-mentioned-by-the-user-mvimg\ui-detector-eval\output\tflite-benchmark`

Current PC CPU TFLite rough baseline:

- `640`: about `50-58ms p50`
- `960`: about `110-121ms p50`

The phone benchmark is the real decision point. PC timing only shows the model is relatively light.

## How To Test

1. Install `dist/touchengine-perf-v18-yolo-benchmark-debug.apk`.
2. Open the app.
3. Go to the advanced page.
4. Tap `Open YOLO TFLite benchmark`.
5. Tap `Run benchmark`.
6. Record p50 / p90 / max for CPU 1/2/4, GPU Delegate, and NNAPI.

## Decision Rule

- `640 + CPU/GPU/NNAPI <= 100ms`: candidate for realtime or near-realtime visual structure detection.
- `640 <= 300ms`: candidate for idle-period visual cache.
- `> 500ms`: do not integrate into live navigation yet.

## Notes

- Redmi K70 Ultra / Dimensity 9300+ should be tested especially with GPU Delegate and NNAPI.
- GPU and NNAPI may fail or fall back depending on TensorFlow Lite delegate support for this exported graph.
- INT8 quantization is intentionally not included in this APK.
