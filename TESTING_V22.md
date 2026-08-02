# TouchEnginePerf v22 - yolo benchmark stability

## APK

`dist/touchengine-perf-v22-yolo-benchmark-stability-debug.apk`

SHA-256:

`E4F693A5033B4EF47D8234F5FA7A362B103E54D1DCA3E0922F04ADA8A9035AD0`

## Scope

- Kept YOLO benchmark offline-only: no NavNode generation, no click behavior, and no Accessibility changes.
- Reordered benchmark display to match the intended runtime fallback chain:
  - `NNAPI preferred`
  - `GPU Delegate FP32`
  - `GPU Delegate FP16`
  - `CPU XNNPACK 2 threads`
  - `CPU XNNPACK 4 threads`
  - `CPU XNNPACK 1 thread`
- Added backend-level warmup before measuring each backend.
- Increased per-sample warmup before measured runs.
- Shows warmup and measured-run counts in the benchmark page.
- Bumped test package version to `versionCode = 22` / `versionName = 1.0-perf.22-yolo-benchmark-stability`.

## Why

Earlier CPU results could include cold-start noise from app startup, interpreter initialization, CPU governor ramp-up, ART/JIT, and cache warming. This made CPU appear to jump to about 500ms in some first-run cases, then return to about 270ms after unrelated actions such as toggling Accessibility.

## Verification

- `testDebugUnitTest assembleDebug`
  - Passed.

## Notes

- This version does not claim CPU is faster. It makes benchmark readings less sensitive to cold-start noise.
- NNAPI remains the preferred path when initialization succeeds and measured latency is good.
