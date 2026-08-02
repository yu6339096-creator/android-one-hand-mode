# TouchEnginePerf v11 测试记录

## 安装

1. 安装 `dist/touchengine-perf-v11-actionable-vision-debug.apk`
2. 本版版本号：
   - `versionCode = 11`
   - `versionName = "1.0-perf.11-actionable-vision"`
3. APK 信息：
   - SHA-256: `10B30C298F0B079062CC2433C4A3A88397BC5660C89F26678ADFF6C571BBCF35`
   - Size: `110150476`

## 本版重点

- ML Kit OCR 结果先作为文字证据，不再默认把每行文字变成 VISION 节点。
- VISION 节点改为三类可操作目标：
  - `ACTION`: 搜索、领取、购买、结算、客服、设置、退款/售后等动作文字。
  - `ICON`: 带稳定文字标签的入口类目标，例如客服、设置、待付款、待发货。
  - `CARD`: 商品/视频卡片级目标，一个卡片只生成一个候选。
- 淘宝首页补洞区域内的轮播文案、搜索热词、图片内广告字变化，会被当作 `ACTIONABLE_NOISE` 保留旧缓存。
- 只有可操作候选签名变化、真实纵向滚动、切页、弹窗变化、App 切换等，才提交新的 VISION cache。
- Object Evidence 仍是默认关闭的紫色证据框实验，不进入导航。

## 验收用例

1. 淘宝首页停留 30 秒：
   - 活动条、搜索热词或图片内文字变化时，`VISION cache` 数量不应反复跳动。
   - 当前蓝色候选和焦点不应短暂消失。
2. 淘宝首页真实纵向滚动：
   - 空闲后允许刷新一次。
   - 刷新完成前旧候选持续可见。
3. 淘宝商品流：
   - 商品卡片应尽量以整卡作为一个 `CARD` 目标。
   - 商品标题、价格、促销语不应逐行大量进入导航。
4. 淘宝“我的淘宝”、购物车、消息页：
   - 按钮入口和带标签图标应保留。
   - 普通说明文字不应大量污染候选。
5. 抖音视频页/网格页：
   - 视觉补洞能力保持。
   - 拖动期间不截图、不 OCR。
6. QQ、LINE、桌面：
   - Accessibility 完整时不应启动不必要 OCR。

## 已执行验证

- `testDebugUnitTest`：通过。
- `assembleDebug`：通过。

## 注意

- ML Kit Text Recognition 本身不能判断“可点击图标语义”；v11 只把 OCR 文字、Accessibility 入口和视觉布局证据组合成更接近可操作的候选。
- PP-OCRv5 未接入，仍留到 ML Kit 稳定后的下一阶段。
