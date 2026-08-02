# TouchEnginePerf v26 - fullscreen vision test

## Scope

- Temporarily changes live VISION from hole-based scanning to full-screen scanning.
- Keeps the existing Accessibility collection and mesh merge in place.
- Keeps VISION click, edge page flip, and auto-scroll locked.
- Keeps v25 YOLO live backend and Douyin search OCR fallback.

## Changes

- Added a test switch: `FULL_SCREEN_VISUAL_TEST_MODE = true`.
- Visual capture now requests both body bands even when no Accessibility hole is confirmed.
- The visual-hole overlay draws one full-screen rectangle.
- Screenshot crop for ML Kit OCR, YOLO fusion, object evidence, and visual fingerprinting is full screen.
- Hole-confirm probing is skipped while this test mode is enabled, so visual refresh does not wait for unresolved bands.

## Expected Behavior

- VISION boxes should appear across the whole screen, not only the two body-hole regions.
- This should make YOLO + ML Kit output easier to judge on Douyin search/video and QQ pages.
- Full-screen scans may be slower than hole scans; this version is meant for recognition quality testing first.

## Rollback Plan

- Set `FULL_SCREEN_VISUAL_TEST_MODE` to `false` to return to the v25 hole-based behavior.

## Verification

- `testDebugUnitTest assembleDebug`
  - Passed via `subst V:`.
  - Kotlin incremental compilation reported a mixed-root cache warning and fell back to non-incremental compilation.

## APK

`dist/touchengine-perf-v26-fullscreen-vision-test-debug.apk`
