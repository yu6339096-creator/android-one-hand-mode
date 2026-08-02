# TESTING_V42

## Build
- `versionCode = 42`
- `versionName = 1.0-perf.42-douyin-video-segments`
- APK: `dist/touchengine-perf-v42-douyin-video-segments-debug.apk`

## What Changed
- Douyin special handling is restricted to the portrait video feed scene.
- Douyin video feed no longer installs whole top/right/bottom strips as targets.
- Video feed targets are now coarse segments inside those UI regions:
  - top menu/tabs/search segments
  - right author/like/comment/favorite/share segments
  - bottom navigation segments
- Douyin search/input pages return to the normal OCR/YOLO path, with the keyboard area cropped and filtered.
- Douyin video-feed classification is stricter and no longer treats an empty accessibility label set as a video page.
- Taobao content changes can requeue visual probing when visual targets are missing, reducing blank recognition after page switches.

## Test Focus
- Douyin video:
  - Should show segmented targets, not one whole long strip.
  - Video content, subtitles, and embedded images should still not become targets.
- Douyin search:
  - Should not use video-strip logic.
  - Search suggestions should be detected; keyboard should remain unboxed.
- Taobao:
  - Switching pages/tabs should recover visual boxes without needing to reopen the app.
- Regression:
  - WeChat list row merging should remain unchanged.
