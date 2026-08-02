# TESTING V31 - Grid Entry Promotion

## Summary

v31 keeps the v30 final scoring behavior, but promotes a narrow set of high-frequency app grid entry text from yellow diagnostic OCR-only evidence into final green navigation targets.

## Why

On Taobao "My Taobao", entries such as `红包`, `优惠券`, `淘金币`, `天猫积分`, and `充值金` were yellow. That means OCR and structure evidence found them, but they did not enter final navigation.

## Changes

- Add high-frequency grid entry promotion for short known entry labels.
- Only applies to high-frequency apps, excluding WeChat.
- Requires:
  - plain short OCR text;
  - known entry wording;
  - entry-like screen position;
  - non-price text;
  - compact width.
- Raise supplemental text quota for high-frequency apps from 16 to 24.

## Expected Results

- Taobao asset/grid entries should become green:
  - `红包`
  - `优惠券`
  - `淘金币`
  - `天猫积分`
  - `充值金`
- Product cards remain green.
- Product titles, prices, and long promo copy should remain purple unless covered by a green card/button.
- WeChat row behavior should not change.

## Verification

- Passed: `testDebugUnitTest assembleDebug`
