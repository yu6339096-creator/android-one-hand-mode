# TouchEnginePerf v10 test notes

APK: `dist/touchengine-perf-v10-dynamic-evidence-debug.apk`

## Changes

- `VisualSurfaceKey` no longer depends on accessibility anchor churn inside a live content page.
- Each confirmed `visual-hole` band keeps an 8x8 perceptual fingerprint. OCR refresh is started only when the changed band exceeds `12/64` bits and the previous evaluation is at least `3000ms` old.
- Refresh runs through a pending cache. Existing blue VISION candidates remain visible until a new candidate mesh is ready and committed while the joystick is idle.
- `TouchEngineVision` logs `DYNAMIC_HOLE_CHANGE`, `OUTSIDE_HOLE_NOISE`, `THROTTLED`, `SURFACE_REPLACED`, and existing explicit invalidation reasons.
- Advanced settings now include `Object evidence (experimental)`. It is off by default and is not persisted.
- When enabled, ML Kit Object Detection draws purple evidence boxes in `visual-hole` regions only. These boxes are never navigation nodes and never click.

## Device checks

1. Open Taobao home and leave it idle while the search suggestion changes. Blue VISION boxes should not disappear; log noise should normally report `OUTSIDE_HOLE_NOISE`.
2. Wait for a product/video tile inside the orange hole to visibly change. At most one OCR refresh should commit per three seconds, with old blue boxes present during refresh.
3. Drag the joystick while a refresh might be due. No OCR commit or purple evidence update should replace the active selection during the drag.
4. Scroll vertically, switch a Taobao tab, or open/close a popup. Old page candidates should be discarded and the new visual area scanned once after settling.
5. Check Taobao cart/message pages and QQ/LINE/desktop. Pages with adequate Accessibility coverage should not start unnecessary visual work.
6. On a Taobao or Douyin visual-hole page, enable `Object evidence (experimental)` from Advanced settings and return to the page. Purple boxes and `OBJECT evidence N / Xms` may appear over detected image objects, without changing blue navigation selection.
7. Confirm blue VISION targets remain click-locked and edge-scroll-locked, and product text is not merged across adjacent cards.

## Log filter

Use `TouchEngineVision` and check:

- `ocrStart reason=DYNAMIC_HOLE_CHANGE` only after meaningful in-hole change.
- `retain reason=OUTSIDE_HOLE_NOISE` for harmless content events.
- `retain reason=THROTTLED` when repeated in-hole changes arrive too quickly.
- `commit reason=... preserveFocus=true` when replacement geometry retains the current target.
