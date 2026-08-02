# TESTING V33 - Clean Capture And Refresh Follow

## Summary

v33 focuses on capture cleanliness and stale visual target refresh.

## Changes

- Hide TouchEngine overlays before visual screenshot capture.
- Wait one frame after hiding overlays before calling `takeScreenshot`.
- Restore overlays after screenshot success or failure.
- If the delayed capture becomes unsafe because the user starts selecting, scrolling, or switches app, cancel the capture and restore overlays.
- Clearing visual OCR cache now also removes `VISION` nodes from the active meshes, so stale visual targets are not kept in navigation after a page change.
- Content changes, same-package window noise, manual clicks, and meaningful scrolls now request a throttled visual refresh.
- Added `CONTENT_CHANGE` as a visual invalidation reason.

## Expected Results

- The floating ball and focus overlay should no longer appear in OCR/YOLO input most of the time.
- After manual scroll or tab/content change, old green targets should disappear quickly instead of staying from the previous screen.
- If the screen updates but accessibility events are sparse, visual refresh should still be retried through the content-change path.
- The refresh is still throttled to avoid constantly running OCR/YOLO on small animations.

## Test Focus

- Move the floating ball over content and verify it does not become a green/purple/blue target after refresh.
- Manually scroll Taobao product feeds and check whether product-card targets update within about one second.
- Tap Taobao tabs or message list entries and check whether old targets disappear.
- Switch between WeChat, Taobao, and Douyin and verify old targets do not remain.

## Verification

- Passed: `testDebugUnitTest assembleDebug`
