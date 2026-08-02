# TouchEnginePerf v29 - YOLO score diagnostics

## Scope

- Keeps v28 YOLO + ML Kit fusion direction.
- Keeps full-screen VISION test mode and v27 bitmap stability fixes.
- Adds a diagnostic overlay to separate raw model evidence from final installed navigation targets.

## Changes

- Relaxed YOLO non-text thresholds:
  - Button/EditText/Tab/Navigation: `0.18`
  - Icon: `0.20`
  - Card: `0.22`
  - Image: `0.26`
- Relaxed YOLO useful-target size filters so small icons/buttons/image entries survive diagnostics and candidate selection.
- YOLO `Text` still does not become a navigation target.
- Added diagnostic candidates:
  - Green: final installed clickable VISION targets.
  - Blue: YOLO non-text structure candidates.
  - Yellow: OCR-only text with structural fallback evidence.
  - Purple: raw YOLO Text or OCR text evidence only.
  - Gray: low-score or filtered YOLO evidence.
- Status text now reports `rawYolo`, `keptYolo`, `ocrPromoted`, `installed`, and elapsed time.

## Expected Behavior

- Taobao pages should show many purple text evidence boxes, but only selected green final targets should be clickable.
- If YOLO sees a target but it is not installed, it should usually appear as blue or gray diagnostic evidence.
- WeChat list pages should still prefer full-width list rows when list structure is available.
- This version is for diagnosis first; final target count remains capped.

## Verification

- `testDebugUnitTest assembleDebug`
  - Passed.

## APK

`dist/touchengine-perf-v29-yolo-score-diagnostics-debug.apk`
