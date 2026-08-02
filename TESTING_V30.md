# TESTING V30 - Final Target Score

## Summary

v30 is a follow-up diagnostic build after v29 screenshots showed too many final green targets.

The YOLO and ML Kit inference path is unchanged. This build only changes final target scoring and diagnostic sampling.

## What Changed

- Final green targets now pass a second scoring layer before entering navigation.
- High-frequency apps such as Taobao get stricter filtering for:
  - ordinary short text fragments;
  - prices and coupon fragments;
  - promotional copy that is not an action;
  - text fragments already covered by a larger card/list/image target.
- WeChat list mode keeps row-first behavior.
- Common non-action entry words are still allowed, such as red packet, coupon, collection, footprint, recharge, shopping cart, VIP, Taobao Factory, and Tmall Supermarket.
- Diagnostic sampling is now quota-based:
  - blue YOLO structure evidence is kept;
  - purple text evidence is kept;
  - gray filtered YOLO evidence is explicitly sampled;
  - yellow OCR-only promotions remain limited.
- Status now includes:
  - `rawYolo`
  - `keptYolo`
  - `filteredYolo`
  - `ocrPromoted`
  - `installed`

## Expected Results

- Taobao pages should show fewer green text fragments than v29.
- Product cards/images and real entry icons should remain green.
- Price-only and promo-only text should mostly be purple or gray, not green.
- WeChat should continue to prefer whole green list rows.
- Gray boxes should be easier to see when YOLO produces low-score or filtered detections.
- Yellow boxes may still be rare. That is acceptable if YOLO already covers the structure.

## Test Focus

- Compare with v29 screenshots:
  - v29 had too many green short text/price/promo boxes.
  - v30 should make those diagnostic-only unless they have entry/action structure.
- Watch whether useful non-action entries disappear:
  - `红包`
  - `优惠券`
  - `收藏`
  - `足迹`
  - `购物车`
  - `充值`
  - `88VIP`
- If too many useful entries disappear, lower the final score threshold or expand entry-word evidence.

## Verification

- Passed: `testDebugUnitTest assembleDebug`
