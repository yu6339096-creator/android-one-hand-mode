# 安卓单手模式

基于 Android 无障碍服务的单手操作辅助项目。应用通过悬浮球与视觉识别，帮助用户选择和操作屏幕上的界面目标。

## 获取与打开

1. 在 GitHub 页面点击 **Code** → **Download ZIP**，或使用 Git 克隆此仓库。
2. 使用 Android Studio 打开下载后的项目根目录。
3. 等待 Gradle 自动同步完成，然后连接 Android 设备或启动模拟器并运行 `app`。

项目使用 Gradle Wrapper，因此不需要另外安装 Gradle。首次同步需要联网下载 Android/Gradle 依赖。

## 模型文件

视觉识别模型已经包含在仓库中：

`app/src/main/assets/yolo/ui-detector-fp32-960.tflite`

同时提供标签文件和若干示例图，下载完整仓库即可编译和使用；无需再单独获取模型。

## 架构图

架构图位于 [`docs/architecture`](docs/architecture)：

日语版本：

- [系统整体架构](docs/architecture/system-architecture.png)
- [Navigation Mesh 构建流程](docs/architecture/navigation-mesh-construction.png)
- [目标传染导航算法流程](docs/architecture/contagion-navigation-flow.png)
- [连续导航状态机](docs/architecture/continuous-navigation-state-machine.png)

中文版本：

- [系统整体架构（中文）](docs/architecture/system-architecture-zh-cn.png)
- [Navigation Mesh 构建流程（中文）](docs/architecture/navigation-mesh-construction-zh-cn.png)
- [目标传染导航算法流程（中文）](docs/architecture/contagion-navigation-flow-zh-cn.png)
- [连续导航状态机（中文）](docs/architecture/continuous-navigation-state-machine-zh-cn.jpg)

## 注意事项

- 运行后需要在系统设置中为应用启用无障碍服务，并按应用提示授予所需权限。
- 本仓库不包含本机配置、构建缓存和编译产物；这些文件会在本地自动生成。
