# TESTING_V39

## Build
- `versionCode = 39`
- `versionName = 1.0-perf.39-douyin-video-mask`
- APK: `dist/touchengine-perf-v39-douyin-video-mask-debug.apk`

## What Changed
- Replaced the v38 Douyin rail-only shortcut with screenshot-based video-surface detection.
- Douyin video pages skip OCR/YOLO for the video area and install only the right rail visual targets.
- Douyin search/history/suggestion pages continue to use OCR/YOLO.
- Visual cache keys now include a lightweight page signature.
- Strong refreshes and same-package window changes clear stale visual nodes before probing.

## Test Focus
- Douyin search page:
  - History and suggestions should be recognized.
  - It should not show only empty right-side rail boxes.
- Douyin video page:
  - Video subtitles and in-video text should not become focus targets.
  - Like/comment/favorite/share should remain stable.
  - The joystick should not flash continuously from video frame updates.
- Manual switching:
  - Switching WeChat/Taobao/Douyin/Desktop should not leave previous-page visual nodes.
  - A brief empty visual state is acceptable while the new page is being detected.
- Regression:
  - Taobao fake full-width row boxes should stay fixed.
  - WeChat list row merging should still work.
