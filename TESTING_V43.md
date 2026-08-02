# TESTING V43 - Douyin Accessibility Video No-Hide

## Summary
- Douyin video feed no longer uses synthetic visual boxes or full-screen OCR/YOLO.
- Douyin video feed uses a deeper accessibility-tree pass to recover real top, right-rail, and bottom controls.
- Screenshot capture no longer hides the joystick by default; self-overlay filtering removes joystick-like visual candidates instead.

## What Changed
- Added `DOUYIN_VIDEO_DEEP` accessibility scan pass with deeper traversal and controlled node limits.
- Douyin video pages pause visual OCR/YOLO and clear cached visual nodes.
- Removed the old Douyin `video-mask` path that installed four right-rail visual boxes.
- Douyin search still allows visual recognition above the keyboard and keeps the keyboard region blocked.

## Test Focus
- Douyin video feed:
  - Joystick should not flash or temporarily disappear.
  - No old four green visual boxes should appear.
  - Top tabs, right rail, and bottom tabs should come from accessibility nodes when exposed by Douyin.
  - Captions, author copy, hashtags, and in-video text should not become visual targets.
- Douyin search:
  - Joystick and navigation should remain visible.
  - Keyboard should not receive boxes.
  - Search suggestions above the keyboard should remain detectable.
- Taobao and WeChat regression:
  - Taobao page switches should trigger recognition again.
  - WeChat list rows should remain whole-row targets.

## Verification
- Run `testDebugUnitTest assembleDebug`.
- Output APK: `dist/touchengine-perf-v43-douyin-a11y-video-no-hide-debug.apk`.
