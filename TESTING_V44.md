# TESTING V44 - Final Target Gate

## Summary
- Added a final target gate: raw accessibility and vision nodes must pass filter, merge, score, and promotion before entering the joystick navigation graph.
- Only green final targets should be selectable and clickable.
- Purple OCR text remains diagnostic by default and upgrades only when it behaves like an independent action button.

## What Changed
- Visual green targets now click through gesture fallback instead of being locked.
- Purple OCR action words can promote when they are reasonable size, in a common action area, and not already inside a green parent target.
- Added action words such as reply, send, confirm, next, finish, login, view more, and refresh.
- WeChat list rows prefer whole-row final targets; avatar, title, summary, and time fragments are suppressed when a row target exists.
- Visual candidates overlapping the joystick are filtered instead of hiding the joystick before screenshots.
- After a click or visual gesture tap, a delayed lightweight validation refresh rebuilds targets so page switches do not keep stale nodes.

## Promotion Rules
- Upgrade purple to green only for independent action-like text: expand, reply, send, search, confirm, cancel, agree, allow, next, finish, login, buy, add to cart, view more, refresh.
- Do not upgrade if the purple text is already covered by a green parent container.
- Do not upgrade ordinary summaries, product descriptions, video copy, article text, timestamps, counts, prices, or long explanatory text.

## Test Focus
- Douyin video feed:
  - Keep v43 behavior: accessibility-first, no full-screen OCR/YOLO on the video body.
  - Joystick should not flash or disappear.
  - Video captions, hashtags, and in-video text should not become green targets.
- Douyin search:
  - Keyboard region should remain blocked.
  - Search suggestions and action text above the keyboard may promote when they look like independent targets.
- Taobao:
  - Product cards and entry tiles should remain green.
  - Product description text should remain purple unless it is an independent action button.
  - Page switches should refresh without leaving stale targets.
- WeChat:
  - Conversation list should prefer green whole-row targets.
  - Text fragments inside a row should stay diagnostic.

## Verification
- Run `testDebugUnitTest assembleDebug`.
- Output APK: `dist/touchengine-perf-v44-final-target-gate-debug.apk`.
