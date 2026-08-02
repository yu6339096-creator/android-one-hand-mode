# TouchEnginePerf v28 - YOLO/OCR fusion restore

## Scope

- Keeps v27 full-screen capture stability fixes.
- Keeps YOLO text detections filtered out of navigation targets.
- Restores the intended YOLO + ML Kit division of labor:
  - YOLO provides visual/actionable structure boxes.
  - ML Kit provides text evidence.
  - Text inside a YOLO target labels that target.
  - ML Kit text can still fall back when YOLO coverage is sparse.

## Changes

- Stops the YOLO fusion branch from returning before structured fallbacks are built.
- Combines YOLO fusion, structured candidates, supplemental OCR text, and sparse OCR fallback in one candidate set.
- For WeChat list pages, prefers rebuilt full-width list-item targets when at least three rows are detected.
- Prevents small YOLO avatar/icon fragments from replacing WeChat row targets.
- Adds sparse ML Kit text fallback for high-frequency apps when YOLO fuses fewer than eight targets.

## Expected Behavior

- WeChat list pages should look closer to the earlier row-target behavior instead of many small avatar/text fragments.
- Taobao should still use YOLO boxes as primary targets, with ML Kit text attached where it falls inside those boxes.
- When YOLO misses most boxes, ML Kit text should still produce fallback targets for testing.

## Verification

- `testDebugUnitTest assembleDebug`
  - Passed.

## APK

`dist/touchengine-perf-v28-yolo-ocr-fusion-restore-debug.apk`
