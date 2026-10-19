# XR Desktop 发布指南

版权所有 © 2026 Ckar。应用内第三方组件的 Apache 2.0 致谢不等于本应用源码的许可授权。

## Release 签名

工程的 Release 不使用 Debug 密钥。构建机需要本地 `keystore.properties`，示例见根目录的 `keystore.properties.example`：

1. 首次发布创建专用签名，之后每次发布都使用同一密钥，不能重新生成来替代。
2. 本机签名文件为 `signing/xrdesktop-release.p12`，配置为根目录 `keystore.properties`，别名为 `xrdesktop`。
3. 将密钥与密码保存在可靠的加密备份或密码管理器中；本机文件不是备份。备份密钥后应验证可读取并核对证书指纹。
4. `.gitignore` 排除了签名目录、密钥及真实配置。**不要将这些文件提交、放进 GitHub Release 或发到公开聊天中。**
5. 新克隆工程可直接编译 Debug 和运行测试；缺少签名配置时 Release 打包会明确失败，不会悄悄产生可误发布的未签名 APK。

需要在另一台机器发布时，安全恢复原密钥，复制配置示例并填写本地值。不要为了通过构建临时改成 Debug 签名。

## 编译和验证

在 Android Studio 中完成 Gradle Sync，使用 Release 构建变体或终端：

```powershell
.\gradlew.bat :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

依赖和 SDK 已缓存时可加 `--offline`。APK 输出为 `app/build/outputs/apk/release/app-release.apk`。用 Android SDK Build-Tools 的 `apksigner verify --verbose --print-certs` 验证签名；用 `aapt dump badging` 核对包名、版本和最低系统要求，再计算 SHA-256。

发布附件只包含签名 APK 和校验文件，不上传密钥、密码、设备日志、构建缓存或本地配置。发布说明使用 `docs/releases/0.4.7.md`，正式发布前完成文中的真机验收。

## 升级注意事项

- 官方 Release 使用独立发布证书，通常不能直接覆盖之前的 Debug 版本；Android 会拒绝不同证书的更新。
- 如需从 Debug 切换，先自行记录收藏、设置和必要草稿，再卸载 Debug、安装 Release。**卸载会清空应用数据；目前没有应用内完整备份功能。**不要由发布脚本自动卸载。
- 同一证书的后续版本应提高 `versionCode`；保留包名 `com.xrglasses.launcher` 和原签名。
- 安装后检查并重新授权无障碍和悬浮窗权限。无障碍无需日常反复关闭。

## GitHub 发布

先推送源码，再在对应提交上创建 `v0.4.7` 标签及 Release 草稿，附上签名 APK 与 SHA-256。核对附件、签名指纹、真机测试和更新说明之后，再手动发布草稿。

仓库私有时，Release 仅对有访问权限的用户可见。公开发布前检查仓库文件与完整提交历史，确认不含签名材料、本机配置、设备日志或不希望公开的个人信息。公开源码的许可授权需由版权所有者明确决定。
