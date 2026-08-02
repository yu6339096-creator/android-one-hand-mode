# TESTING V34 - Stable Capture Probe

## Summary

v34 tunes v33 to avoid over-sensitive refresh loops while keeping manual navigation responsive.

## Changes

- Overlay hide delay before screenshot capture increased from `48ms` to `120ms`.
- `CONTENT_CHANGE` is no longer treated as a strong visual refresh reason.
- Ordinary content-change events now schedule a visual fingerprint probe without immediately clearing old visual targets.
- OCR/YOLO only runs after the screenshot fingerprint confirms meaningful visual change, and dynamic refresh throttling still applies.
- Manual click and meaningful scroll still clear old visual targets before probing, so stale targets should not remain after direct user action.

## Expected Results

- The floating ball/focus overlay should be less likely to appear in screenshots on slower phones.
- Taobao home should not keep running OCR/YOLO because of invisible or tiny background content-change events.
- Manual scroll/click remains responsive enough: old targets are removed quickly and a new visual probe is scheduled.
- If a screen visibly changes but only sends noisy content events, it may wait for the fingerprint/throttle path instead of immediately rescanning every event.

## Test Focus

- Put the floating ball over content and refresh several times.
- Stay on Taobao home for a few minutes and watch whether status text keeps refreshing without visible page changes.
- Manually scroll product feed and check whether stale targets are removed.
- Tap a tab or list row and check whether the new page eventually gets fresh visual targets.

## Verification

- Passed: `testDebugUnitTest assembleDebug`
