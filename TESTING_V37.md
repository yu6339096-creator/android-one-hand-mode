# TESTING_V37

## Build
- `versionCode = 37`
- `versionName = 1.0-perf.37-quiet-overlay-capture`
- APK: `dist/touchengine-perf-v37-quiet-overlay-capture-debug.apk`

## What Changed
- Kept v36 message-row gating.
- Reduced visible overlay flicker during visual refresh:
  - First visual capture or strong refresh still hides the focus overlay and joystick for 220 ms before screenshot.
  - Cache-probe captures no longer hide the overlays and no longer wait 220 ms.
  - Existing self-overlay filtering still removes joystick-like OCR/YOLO candidates from final targets.

## Why
- v35 fixed the "joystick appears in screenshots" issue by hiding overlays for every screenshot.
- That was correct for clean capture, but it became visually noisy when complex pages triggered repeated probes.
- v37 keeps clean capture for important refreshes while making ordinary probes visually quiet.

## Test Focus
- The joystick should not keep flashing while staying on Taobao/WeChat pages.
- After app switch, click, scroll, or first recognition, a brief hide can still happen.
- Taobao product feeds should still avoid the v36 fake full-width message rows.
- If a cache-probe misses a target hidden under the joystick, moving or clicking should trigger a strong refresh and re-capture cleanly.
