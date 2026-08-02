# TESTING_V41

## Build
- `versionCode = 41`
- `versionName = 1.0-perf.41-douyin-strip-ui`
- APK: `dist/touchengine-perf-v41-douyin-strip-ui-debug.apk`

## What Changed
- Douyin portrait video feed now installs three coarse strip targets instead of many fixed small boxes:
  - top control strip
  - right action strip
  - bottom navigation strip
- Douyin search/input pages now install stable search strips without screenshot/OCR dependency:
  - search top bar
  - history area
  - suggestion area
  - voice search area
- Keyboard-region evidence remains blocked.

## Test Focus
- Douyin video:
  - Three large green strips should appear and should not need exact per-button alignment.
  - Video subtitles and embedded content should not become targets.
  - The right strip should cover avatar/like/comment/favorite/share/mute even if buttons shift.
- Douyin search:
  - Joystick and navigation overlay should remain visible.
  - Keyboard should not receive boxes.
- Regression:
  - Taobao and WeChat behavior should remain similar to v40.
