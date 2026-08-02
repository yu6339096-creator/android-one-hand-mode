# TESTING_V38

## Build
- `versionCode = 38`
- `versionName = 1.0-perf.38-douyin-rail-only`
- APK: `dist/touchengine-perf-v38-douyin-rail-only-debug.apk`

## What Changed
- Kept v36 message-row gating.
- Kept v37 quiet overlay capture.
- Added a Douyin video-page fast path:
  - When Douyin is fullscreen and the input method is not visible, visual refresh installs synthetic right-rail targets directly.
  - It skips screenshot, ML Kit OCR, and YOLO for that video surface.
  - Search/input pages are not affected because keyboard-visible Douyin pages still use text recognition.

## Why
- Video subtitles and in-video text are not reliable navigation targets.
- OCR text from the video can overlap the like/comment/share rail and make those controls harder to select.
- Video frames change constantly, so full-screen visual fingerprints can cause repeated refreshes and visible joystick flicker.

## Test Focus
- Douyin video page:
  - Joystick should no longer keep flashing while watching video.
  - Like/comment/favorite/share targets should remain available even when subtitles or video text appear.
  - Video subtitles should not become navigation targets.
- Douyin search page with keyboard:
  - Search suggestions/history text should still be recognized.
- Taobao/WeChat:
  - Behavior should match v36/v37.
