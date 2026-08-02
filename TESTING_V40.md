# TESTING_V40

## Build
- `versionCode = 40`
- `versionName = 1.0-perf.40-douyin-fixed-video-ui`
- APK: `dist/touchengine-perf-v40-douyin-fixed-video-ui-debug.apk`

## What Changed
- Douyin video feed pages now use a fixed portrait UI policy instead of full-screen OCR/YOLO.
- Video content, subtitles, large in-video text, stickers, and picture-in-picture content should not become navigation targets.
- Douyin search/input pages crop visual recognition above the IME and reject keyboard-region evidence.
- Visual cache keys include a scene tag so Douyin video/search/general pages do not reuse stale visual nodes.
- The fixed Douyin structure keeps a future landscape policy slot separate from the recognition pipeline.

## Test Focus
- Douyin video home:
  - Keep top tabs, right-side like/comment/favorite/share/mute, and bottom tabs.
  - Do not show targets on subtitles, video text, author copy, hashtags, or embedded screenshots.
  - Joystick should not flash continuously while video frames change.
- Douyin search:
  - Search box, history, suggestions, and voice search should remain usable.
  - Keyboard letters and keys should not be boxed.
- Switching:
  - Switching between Douyin video, Douyin search, Taobao, and WeChat should clear stale previous-page visual targets.
- Regression:
  - Taobao product cards and key entry points should remain similar to v39.
  - WeChat list rows should still merge into row targets.
