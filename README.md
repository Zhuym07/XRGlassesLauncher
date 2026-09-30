# XR Glasses Launcher

一个面向外接 XR 显示器的 Android 原型工程，目标设备为一加 13 / ColorOS。

## 用 Android Studio 打开

1. 在 Android Studio 中选择 **Open**。
2. 选择本目录：`XRGlassesLauncher`。
3. 等待 Gradle Sync 完成。
4. 使用 Android Studio 的 **Build > Make Project** 编译，或直接点击运行按钮安装到真机。

工程使用标准 Android Gradle Plugin 配置和 Gradle Wrapper，不依赖 Codex 的临时缓存扫描脚本。

## 环境要求

- Android Studio 2026.1.1 或兼容版本
- Android SDK Platform 36
- Android SDK Build-Tools 36.x
- JDK 21（Android Studio 自带 JBR 即可）
- 一加 13 真机，已开启 USB/无线调试

## 原型能力

- 动态发现支持 Presentation 的外接显示器，不固定使用某个 display ID
- 尝试把本应用 launcher 和第三方启动器应用放到外屏
- 手机端全黑、低亮度触控板界面
- 默认通过无障碍服务显示外屏光标，并向指定显示器发送点击、拖拽和滚动触摸手势，无需 Root
- 手机端和外屏均可选择应用，记录启动请求及无障碍可观察到的窗口位置
- 中文文本通过无障碍设置外屏已聚焦的可编辑输入框；失败时保留手机草稿
- 可选 Root 仅用于定向发送外屏返回键，不在启动时自动请求 Root

## 使用方式

1. 连接眼镜，打开 XR Desktop。
2. 点击 Enable accessibility，在系统设置中启用 XR Desktop 服务后返回。
3. 点击 Open external launcher，然后点击 Enter black touchpad。
4. 单指移动光标，短按点击，连续短按双击；单指按住后移动拖拽。
5. 双指滑动滚动；三指上滑或音量加返回外屏 launcher。
6. 三指长按打开文字面板；先在外屏点击目标输入框，再在手机输入并发送。
7. 手机返回操作退出黑色触控板；三指短按或音量减发送外屏返回键，需要勾选 Optional Root。

无障碍光标是不可触摸的外屏覆盖层，点击与滚动是触摸手势，**不是系统原生鼠标**。不支持鼠标悬停、右键或真正的滚轮事件；第三方应用对触摸手势的支持决定其行为。不会用没有显示器参数的全局返回操作误操作手机。

## 权限与限制

- 需要 Root 时，首次执行 Root 操作会由 KernelSU 授权。
- 触控板和中文输入的无障碍服务需要在系统设置中手动启用；不需要另行授予普通悬浮窗权限。
- 当前公开 API 原型不会自动修改 `force_desktop_mode_on_external_displays`，也不会启用 LSPosed Hook。
- 如果系统报告外屏 `canHostTasks=false`，ColorOS 可能拒绝第三方任务真正驻留外屏；应用会保留失败状态，后续再根据日志做定向适配。
- 手机屏幕第一版保持点亮，但显示全黑低亮触控板；物理熄屏不作为当前验收条件。

## 真机调试

连接设备后，在 Android Studio 中选择对应设备运行即可。也可以用 ADB 安装 `app/build/outputs/apk/debug/app-debug.apk`。

建议先验证：外屏发现、外屏 launcher、第三方应用启动、输入注入和断开重连，再考虑任何系统设置或 LSPosed 适配。

自动化验证：`gradlew.bat assembleDebug testDebugUnitTest lintDebug`。测试覆盖坐标边界、移动倍率、点击距离阈值及应用排序，不代替真机多指手势和外屏任务验收。无障碍窗口观察也不等同于完整的系统任务查询。

## 本次验证记录（2026-09-30）

- 一加 13 真机的系统任务信息确认：第三方游戏位于外屏，XR Desktop 手机页面位于主屏。
- 无障碍服务已连接，外屏光标覆盖层已创建，指定外屏的手势收到完成回调。
- 完成回调不等于目标应用已作出预期响应；仍需手动验证点击结果、双击、多指滚动、拖拽和中文输入。
- 未验证断开重连、30 分钟温升、所有应用兼容性或真正的系统鼠标；未修改系统设置或启用 LSPosed。
- 0.2.1 补充服务连接状态通知，服务断开时退出触控板并恢复窗口亮度，无需持续轮询。
