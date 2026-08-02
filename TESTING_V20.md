# TouchEnginePerf v20 - no-opencv cleanup

## APK

`dist/touchengine-perf-v20-no-opencv-debug.apk`

SHA-256:

`393B0BBCA03284B1C343A54DE202928819D457EA2653BF58795B2C2F22B0BCC8`

## Scope

- Removed the v17 CV diagnostic runtime path, settings toggle, and green evidence overlay.
- Removed the old v17 diagnostic APK and `TESTING_V17.md`.
- Kept ML Kit Text, ML Kit Object Evidence, YOLO TFLite benchmark, Accessibility focus selection, and VISION click lock behavior.
- Bumped test package version to `versionCode = 20` / `versionName = 1.0-perf.20-no-opencv`.

## Verification

- `rg -n "OpenCV|openCv|OPENCV" app\src\main app\build.gradle.kts`
  - No matches.
- `rg -n "Object evidence|Yolo|YoloTfliteBenchmark" app\src\main app\build.gradle.kts`
  - Object Evidence and YOLO benchmark entries remain.
- `testDebugUnitTest assembleDebug`
  - Passed.

## Notes

- This version does not change YOLO benchmark behavior or integrate YOLO into navigation.
- This version does not change ML Kit visual OCR candidate generation.
