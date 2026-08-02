# TouchEnginePerf v24 - YOLO/OCR fusion tuning

## Scope

- Only changes the TouchEnginePerf test project.
- Keeps Accessibility first.
- Keeps VISION click, edge page flip, and auto-scroll locked.
- Keeps YOLO benchmark and ML Kit Object Evidence.
- Does not restore OpenCV diagnostic code.

## Changes

- Added a faster schedule for strong visual refresh reasons such as app switch, page replacement, popup change, page flip, vertical scroll, user click, and auto-scroll completion.
- Changed dynamic visual cache comparison to use a VISION actionable signature built from YOLO/fused candidate type and quantized bounds, instead of ordinary OCR text noise.
- Added supplemental OCR text recovery outside YOLO boxes for compact navigation/action labels such as `全部`, `更多`, `展开`, `换一换`, and short actionable labels.
- Added WeChat list-item fallback so the good list behavior can recover when YOLO mainly finds avatars/icons.
- Increased OCR evidence cap from 48 to 96 so ML Kit text can still be used for labels and supplemental targets.
- Hidden the service focus/joystick overlays during screenshots and filtered likely self overlay boxes/text from YOLO/OCR candidates.

## Expected Behavior

- Taobao homepage should reduce repeated refreshes caused by carousel/product text churn.
- Manual page changes should schedule visual refresh faster and avoid stale boxes lingering as long.
- Useful standalone labels outside YOLO boxes can re-enter VISION candidates when they look actionable.
- The floating ball, focus ring, and debug labels should not become VISION targets.
- WeChat should keep the v23 avatar/icon benefit while recovering more row/text context.

## APK

`dist/touchengine-perf-v24-yolo-ocr-tune-debug.apk`

## Test Notes

- Test Taobao homepage, Taobao mine/cart, WeChat list, Douyin video/search.
- Watch for VISION candidate count, stale-box delay after page switch, and whether floating ball boxes disappear.
