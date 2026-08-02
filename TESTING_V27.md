# TouchEnginePerf v27 - fullscreen vision stability

## Scope

- Keeps v26 full-screen VISION test mode.
- Does not change the YOLO + ML Kit fusion rules.
- Focuses only on preventing service crashes and keeping the overlay available after leaving the settings app.

## Changes

- Pauses VISION capture while the active foreground package is this test app itself.
- Keeps the full-screen visual-hole overlay off on the settings page.
- Reuses the screenshot bitmap directly for full-screen OCR/YOLO instead of copying a second full-screen crop.
- Reuses the full-screen crop for visual fingerprints instead of creating one full-screen bitmap per band.
- Keeps full-screen scanning enabled for launcher/desktop and other target apps.

## Expected Behavior

- Opening the TouchEngine settings app after enabling Accessibility should not crash the service.
- After exiting to desktop or another app, the full-screen visual-hole overlay should appear again.
- Desktop and app pages should proceed to ML Kit + YOLO recognition without the v26 memory spike.

## Verification

- `testDebugUnitTest assembleDebug`
  - Passed.

## APK

`dist/touchengine-perf-v27-fullscreen-vision-stability-debug.apk`
