# XR Glasses Launcher 图标

现代简洁的 XR 眼镜轮廓，右镜片中的右上箭头代表启动与外接显示。

- `xr-launcher.svg`：512 × 512 展示版 SVG，圆角深蓝底。
- `xr-launcher.png`：同款 PNG 预览（不参与 APK 构建）。
- `xr-launcher-foreground.svg`：108 × 108 坐标系的透明前景。
- `xr-launcher-monochrome.svg`：透明单色版本。
- `generate.cjs`：统一维护路径和颜色，执行 `node design/icon/generate.cjs` 更新 SVG 和 Android XML；PNG 预览需另行导出。

## 配色

| 用途 | 色值 |
| --- | --- |
| 深夜蓝背景 | `#101C2C` |
| 冰白眼镜框 | `#E2FFF8` |
| 薄荷绿启动箭头 | `#43E8C4` |

## Android 接入

Manifest 的 `icon` 和 `roundIcon` 共用自适应图标，由启动器决定最终蒙版。Android 13+ 资源额外提供 monochrome 层，供支持主题图标的启动器使用。项目最低 Android API 为 29，因此无需旧版位图回退资源。

Android 背景是完整不透明色层，不预裁圆角；前景采用 VectorDrawable。SVG 与 VectorDrawable 使用相同路径。主体位于以 (54, 54) 为中心、半径 33 的安全圆内；展示版 SVG 则裁取中央 72 × 72 区域，便于预览图标的正常视觉比例。

验证：执行 `gradlew.bat :app:assembleDebug --offline`。不同桌面蒙版、主题配色及最终显示效果仍可在真机上检查。
