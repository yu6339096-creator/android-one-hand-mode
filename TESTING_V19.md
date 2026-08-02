# TouchEnginePerf v19 - YOLO GPU Delegate Fix

## APK

`dist/touchengine-perf-v19-yolo-benchmark-gpu-fix-debug.apk`

SHA-256:

`55C4ADE995E95F83262FDE92724D11D9794004F541461B9B52058C61CC1F5AA4`

## Scope

- This version only fixes the YOLO benchmark GPU Delegate dependency path.
- YOLO still does not create `NavNode`, does not replace ML Kit or Accessibility, and does not affect click/scroll/focus behavior.
- NNAPI and CPU benchmark behavior is otherwise unchanged from v18.

## Change

v18 reported this GPU failure on device:

`NoClassDefFoundError: Failed resolution of: Lorg/tensorflow/lite/gpu/GpuDelegateFactory$Options;`

v19 changes the TensorFlow Lite GPU setup:

- Adds `org.tensorflow:tensorflow-lite-gpu-api:2.17.0`.
- Adds `org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4`.
- Uses `GpuDelegateFactory` through `Interpreter.Options.addDelegateFactory(...)`.

The build now packages `libgpu_delegate_plugin.so`.

## User Benchmark Notes From v18

Redmi K70 Ultra / Dimensity 9300+ results showed NNAPI is the strongest current route:

- `douyin_search.jpg`: p50 `127.4ms`, infer `55.4ms`
- `douyin_video.jpg`: p50 `123.4ms`, infer `54.8ms`
- `taobao_home.jpg`: p50 `152.0ms`, infer `59.2ms`
- `taobao_mine.jpg`: p50 `150.7ms`, infer `57.5ms`
- `wechat_list.jpg`: p50 `155.1ms`, infer `58.4ms`

CPU XNNPACK was generally under `300ms`, but inference was around `206ms`, making it better suited for idle fallback than realtime.

## Current Interpretation

- NNAPI is already good enough for idle visual cache and may be close to near-realtime if preprocessing is optimized.
- Current total time is limited more by preprocessing than by NNAPI inference.
- If GPU Delegate now runs, compare it against NNAPI; if it still fails, treat GPU as secondary and continue with NNAPI first.

## Next Optimization Targets

- Reuse input buffers instead of allocating every run.
- Avoid creating a new letterbox bitmap and canvas for every benchmark pass.
- Add a live screenshot benchmark path before integrating YOLO into navigation.
