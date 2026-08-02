# TouchEnginePerf v12 测试记录

## 安装

1. 安装 `dist/touchengine-perf-v12-lane-cell-vision-debug.apk`
2. 本版版本号：
   - `versionCode = 12`
   - `versionName = "1.0-perf.12-lane-cell-vision"`
3. APK 信息：
   - SHA-256: `07EE54320B9ADEE15C753AFB52220B238762FCE90FE572B4C6B4043194ABD547`

## 本版重点

- 删除 v11 的固定列宽和固定行高卡片合并。
- 先判断视觉布局，再决定是否生成 VISION 节点：
  - `TWO_COLUMN_GRID`：双列商品/视频网格，允许生成 `CARD`。
  - `SINGLE_COLUMN_LIST`：微信、淘宝消息这类纵向列表，不生成跨行大框。
  - `ICON_GRID`：短动作/入口文字保留为小目标。
  - `BANNER_OR_PROMO` / `UNKNOWN`：只保留明确动作词，不生成大卡片。
- 卡片合并改成 lane -> cell：先分左右 lane，再在 lane 内按行单元合并，禁止跨列表行、跨列、跨大区域合并。
- v11 的 `ActionableSignature` 和动态文案噪声抑制继续保留。

## 验收用例

1. 微信聊天列表：
   - 不应出现头像列、文字列、时间列组成的纵向大蓝框。
   - Accessibility 正常时，ML Kit 不应接管列表导航。
2. 淘宝消息页：
   - 不跨多条消息合并。
   - 店铺名、时间列、摘要文字不应形成大块 VISION。
3. 淘宝首页/推荐流：
   - 双列商品区应尽量一商品一框。
   - 不应出现整列大框、跨两列大框、跨多个商品的大框。
   - 搜索热词、活动条、图片内促销字变化不应触发反复 cache 替换。
4. 功能宫格/订单入口：
   - 带短标签的入口可保留为小目标。
5. 抖音/视频网格：
   - 网格页允许按 cell 生成目标。
   - 单视频页不把字幕当作导航目标。

## 已执行验证

- `testDebugUnitTest`：通过。
- `assembleDebug`：通过。

## 注意

- 本版偏保守：无法确认是稳定双列/网格结构时，宁可少生成 VISION，也不生成乱框。
- ML Kit 仍只用于无障碍空洞补洞；Accessibility 始终优先。
- PP-OCRv5 未接入。
