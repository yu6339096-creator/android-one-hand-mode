# TouchEnginePerf v13 测试记录

## 安装

1. 安装 `dist/touchengine-perf-v13-lane-cell-balanced-debug.apk`
2. 本版版本号：
   - `versionCode = 13`
   - `versionName = "1.0-perf.13-lane-cell-balanced"`
3. APK 信息：
   - SHA-256: `850F8E5D19BC5FFFA9B6A9AB796A874266D9F6D1E72C7E93D49B7388BCC3D77E`

## 本版重点

- 修复 v12 过度保守导致的漏识别。
- `SINGLE_COLUMN_LIST` 不再直接拒绝 OCR，而是生成一行一个 `ITEM` 目标。
- 商品/活动区不再只接受严格双列，新增多列 lane-cell 合并，适配淘宝首页 2/3/4 列混合内容。
- 继续禁止整列大框：合并只在同一个 row/cell 内发生。
- `ACTION` / `ICON` / `CARD` / `ITEM` 都继续进入 `ActionableSignature`，动态文案不应反复刷新 cache。

## 验收用例

1. 微信聊天列表：
   - 应看到多条会话行级 `ITEM` 目标。
   - 不应出现 v11 那种头像列、文字列、时间列大蓝框。
2. 淘宝首页：
   - 候选数量应明显多于 v12。
   - 活动小格、商品格、双列商品区应能生成 cell/card 目标。
   - 不应回到 v11 的跨列、跨多个商品的大框。
3. 淘宝消息页：
   - 消息行可生成 `ITEM`，但不跨多行。
4. 动态刷新：
   - 搜索热词、活动文案、图片内促销字变化不应持续提交新 cache。
5. 回归：
   - 拖动/按住悬浮球期间不截图、不 OCR。
   - QQ、LINE、桌面等 Accessibility 完整页面不应被 ML Kit 大量污染。

## 已执行验证

- `testDebugUnitTest`：通过。
- `assembleDebug`：通过。

## 注意

- 本版是“补回漏识别”的平衡版。如果淘宝仍偏少，下一步应接入更可靠的图片块/轮廓证据，而不是继续放宽 OCR 文字合并阈值。
