# TouchEnginePerf v21 - yolo backend tuning

## APK

`dist/touchengine-perf-v21-yolo-backend-tuning-debug.apk`

SHA-256:

`499AF0112BE7CF8AD06AF23FDFFC7601D7AF9B32A1C22DB011E98A2189ABF529`

## Scope

- Kept the YOLO benchmark offline-only: no NavNode generation, no click behavior, and no Accessibility changes.
- Added two GPU Delegate attempts:
  - `GPU Delegate FP32`
  - `GPU Delegate FP16`
- Kept NNAPI and CPU XNNPACK benchmark paths for fallback comparison.
- Reused YOLO preprocessing buffers, bitmap, canvas, and pixel array across benchmark iterations to reduce Kotlin-side allocation overhead.
- Bumped test package version to `versionCode = 21` / `versionName = 1.0-perf.21-yolo-backend-tuning`.

## Expected Backend Strategy

- Product integration should prefer NNAPI when initialization and measured latency are good.
- If NNAPI is unavailable or too slow, try GPU Delegate.
- If GPU Delegate fails or performs poorly, fall back to CPU XNNPACK.

## Verification

- `testDebugUnitTest assembleDebug`
  - Passed.

## Notes

- GPU Delegate may still fail on some devices or driver/runtime combinations; this version reports FP32 and FP16 failures separately.
- The main remaining total-latency cost outside inference is preprocessing. This version removes repeated large allocations, but bitmap scaling and pixel normalization are still measured in `pre`.
