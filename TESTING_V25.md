# TouchEnginePerf v25 - vision seam/search/latency fix

## Scope

- Keeps Accessibility first.
- Keeps VISION click, edge page flip, and auto-scroll locked.
- Keeps YOLO benchmark backend order unchanged.
- Only changes the live visual navigation path and one band-recovery rule.

## Changes

- Overlapped the two visual-hole body bands:
  - upper body: about `18%..54%`
  - lower body: about `48%..85%`
- Treats nodes that span the body-band seam as recovered for both bands during hole confirmation.
- On Douyin search pages with the keyboard visible, preserves uncovered ML Kit OCR text candidates even when YOLO returns a few boxes.
- Keeps that OCR text fallback off for normal Douyin video pages, so subtitles do not become broad navigation targets.
- Live YOLO now prefers CPU XNNPACK first and prewarms after service start.
- The offline YOLO benchmark still tests `NNAPI -> GPU -> CPU` in the previous order.

## Expected Behavior

- Text or icons near the edge of a visual-hole band should be less likely to disappear.
- Douyin search/history/suggestion text should produce more VISION targets instead of only a few YOLO boxes.
- Douyin video pages should avoid the multi-second first visual scan caused by live NNAPI/GPU delegate initialization.

## Verification

- `testDebugUnitTest assembleDebug`
  - Passed via `subst V:` because the original Windows path breaks Gradle test workers.

## APK

`dist/touchengine-perf-v25-vision-seam-search-latency-debug.apk`
