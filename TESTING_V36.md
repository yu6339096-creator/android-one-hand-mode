# TESTING_V36

## Build
- `versionCode = 36`
- `versionName = 1.0-perf.36-message-row-gate`
- APK: `dist/touchengine-perf-v36-message-row-gate-debug.apk`

## What Changed
- Kept the v35 overlay hide delay and narrow self-overlay filtering.
- Kept YOLO-led full-screen diagnostic behavior.
- Added a gate before visual list-row merging:
  - WeChat can still use whole-row message targets.
  - High-frequency Chinese apps can use whole-row targets only when the surface has repeated right-side time/date labels plus same-row title or preview text.
  - Taobao product feeds, coupon grids, and promo cards should no longer be promoted into full-width message rows.

## Test Focus
- Taobao product feed:
  - No large full-width green bars across multiple product cards.
  - Product cards can still be green from YOLO/card evidence.
  - Prices and product copy should mostly remain purple/yellow evidence, not become fake row targets.
- Taobao or WeChat notification/message lists:
  - Rows with avatar/logo, title/preview, and right-side time/date should still merge into one green target.
- WeChat chat list:
  - Whole-row message targets should remain.
  - Top search/plus and bottom tabs should still be detected.

## Expected Interpretation
- If a Taobao page still shows horizontal green bars across unrelated cards, the remaining issue is probably card/grid grouping rather than message-list merging.
- If WeChat rows stop merging, the new timestamp gate is too strict for non-WeChat pages only; WeChat itself is intentionally allowed.
