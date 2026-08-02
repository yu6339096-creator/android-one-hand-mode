# TouchEnginePerf v23 - YOLO OCR Fusion

## Goal

Validate a hybrid visual navigation pipeline:

- Accessibility remains the highest-priority source.
- YOLO detects likely UI targets and owns the final target bounds.
- ML Kit Chinese text recognition only provides text evidence for labeling YOLO targets.
- VISION candidates remain selectable for testing, while click, edge paging, and auto-scroll stay locked.

## Implemented

- Added reusable `YoloUiDetector` with backend fallback order:
  `NNAPI preferred -> GPU Delegate FP32 -> GPU Delegate FP16 -> CPU XNNPACK`.
- Added live YOLO execution after ML Kit OCR, using the same visual-hole crop.
- Added YOLO + OCR fusion:
  - OCR text attaches to a YOLO target when the text center is inside the target, most of the text area is contained, or IoU/overlap is high enough.
  - Final candidate bounds come from YOLO; OCR text never expands the box.
  - Text evidence is split by horizontal gap inside each YOLO target.
- Added target filtering:
  - YOLO `Text` detections do not directly become navigation targets.
  - Status bar, system navigation bar, tiny fragments, and huge background images are filtered.
  - Output is capped at 32 VISION candidates.
- Improved OCR evidence extraction:
  - Prefer ML Kit element-level text boxes.
  - Avoid the old behavior where split boxes inherited the full OCR line text.
- Kept the previous ML Kit structured path as fallback if YOLO is unavailable or returns no usable targets.

## Expected Test Focus

- Taobao: fewer price/promo fragments; search boxes, buttons, icons, cards remain selectable.
- Douyin: right-side icon targets and search/input UI should come from YOLO; video subtitles should not become VISION targets.
- WeChat: existing list behavior should not regress.
- Full Accessibility apps such as QQ, LINE, and desktop should not trigger unnecessary visual candidates.

## Build

- Version: `1.0-perf.23-yolo-ocr-fusion`
- APK: `dist/touchengine-perf-v23-yolo-ocr-fusion-debug.apk`
