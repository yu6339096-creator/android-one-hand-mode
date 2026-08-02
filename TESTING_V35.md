# TESTING V35 - Overlay Filter Narrow

## Summary

v35 fixes a false positive in the self-overlay suppression path.

## Why

The joystick could still cause a missing row even if screenshot hiding worked. The old filter removed any visual candidate overlapping the joystick bounds. A whole chat/list row can overlap the joystick, so the row was filtered out.

## Changes

- Hide overlays with both `alpha = 0` and `visibility = INVISIBLE`.
- Increase the delay before screenshot capture from `120ms` to `220ms`.
- Narrow `isLikelySelfOverlayCandidate()`:
  - only compact candidates similar to joystick size are treated as self-overlay;
  - large list rows/cards are no longer filtered just because they overlap the joystick position.

## Expected Results

- A chat/message row under the joystick should still be detected as a green row.
- The joystick itself should be less likely to appear in OCR/YOLO input.
- If a tiny round candidate appears exactly under the joystick, it can still be filtered.

## Verification

- Passed: `testDebugUnitTest assembleDebug`
