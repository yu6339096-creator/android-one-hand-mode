# TESTING V32 - Keyboard Ignore And Dialog List Rows

## Summary

v32 keeps the v31 recognition strategy, then adds two navigation-stage rules:

- ignore the phone keyboard area;
- generalize chat/message/list row merging beyond WeChat.

## Keyboard Ignore

When an input method window is visible, visual candidates whose center falls inside the keyboard area are ignored.

Expected:

- keyboard letters should not become green targets;
- keyboard letters should not clutter diagnostic evidence;
- the search input, search button, suggestions, history items, and controls above the keyboard can still remain visible.

## Dialog/List Row Merge

Message-style rows are now treated as a generic structure for high-frequency apps, not only WeChat.

Expected:

- WeChat message list remains whole-row green.
- Taobao-style message/shop notification rows should merge into row targets.
- The row internals, such as title, preview text, time, mute dot/icon, should not become separate final targets.
- Product grids and product cards should keep the v31 behavior.

## Verification

- Passed: `testDebugUnitTest assembleDebug`
