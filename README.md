# DSH Mobile

使用 Kotlin Multiplatform 与 Compose Multiplatform，在 Android 和 iOS 原生 App 中继续电脑上的 DeepSeek Harness 会话。

配套桌面插件：[GongShichen/dsh-mobile-plugin](https://github.com/GongShichen/dsh-mobile-plugin)。这是独立社区项目，与 DeepSeek 无隶属关系。

## 使用方式

1. 在 Mac 的 DeepSeek Harness 中安装配套插件。
2. 打开桌面“移动访问”，生成配对二维码。
3. 手机扫描并在电脑确认授权。也可以粘贴配对链接。
4. 配对后保存设备身份，后续无需重复扫码。直连不可达时使用 iroh 公共加密中继，不需要部署服务器。
5. 若需要后台完成提醒，在电脑端导入个人 APNs/Firebase 凭据并发送测试通知。

电脑必须保持唤醒且 DSH 正在运行。手机进入后台后连接可能暂停，打开 App 时重新同步。无 GMS 的 Android 仍可使用 P2P 会话功能，但 FCM 后台提醒不可用。

## 功能

- 多电脑配对与切换；连接路径显示、持久授权、撤销。
- 会话搜索、新建、改名、置顶、归档、分支与模型选择。
- 流式消息、思考、工具卡片、审批、问题回复、运行与后台任务状态。
- 工作区文件浏览、预览、下载；附件分块上传。
- 按电脑隔离的缓存与离线草稿；不会自动提交未发送草稿。
- Mac 直发 APNs/FCM 的最小内容提醒，点击通知重新获取真实状态。

桌面 DSH 是权威数据源。历史与文件按需读取，归档不会删除桌面日志。桌面未发送草稿、滚动位置和任意第三方 React 界面不会变成原生页面。

## 构建基线

| 项目 | 版本 |
|---|---|
| Kotlin / Compose Compiler | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| AGP / Gradle | 9.3.1 / 9.5.0 |
| JDK | 21 |
| Android | minSdk 26、targetSdk 36、compileSdk 37 |
| iOS | 17.5+ |
| SQLDelight | 2.4.1 |
| iroh-ffi / iroh | 1.2.0 / 1.4.1 |

Compose 当前依赖要求 compileSdk 37，因此构建 SDK 高于 targetSdk。Xcode 当前验证环境为 27.2；使用 iOS 功能前需完成 Xcode 首次运行与许可设置。

```bash
git clone --recurse-submodules git@github.com:GongShichen/dsh-mobile.git
cd dsh-mobile
scripts/doctor.sh
```

### Android

安装 JDK 21、Android SDK/API 37、完整 NDK、Rust 与对应 Android targets。原生库源代码通过子模块锁定；不要将 macOS JVM 库装进 APK。

```bash
git submodule update --init --recursive
scripts/build-iroh-android.sh
scripts/build-android.sh
```

构建产物位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。完整构建会检查原生库存在，缺少 `.so` 时明确失败。

本机已验证的 NDK 为 `27.0.12077973`。若需要显式指定：

```bash
ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/27.0.12077973" scripts/build-iroh-android.sh
```

原生库按 16 KiB 页大小构建。具体原生架构与测试见 [transport 文档](core/transport/README.md)。

### iOS

模拟器构建：

```bash
scripts/build-ios.sh
```

该脚本构建 arm64 模拟器版本，并使用本地临时签名，确保系统钥匙串正常工作；不需要使用开发者证书。不要为模拟器传入 `CODE_SIGNING_ALLOWED=NO`，否则持久设备身份会因缺少模拟器权限而无法保存。

模拟器产物位于 `.local/DerivedData/Build/Products/Debug-iphonesimulator/DshMobile.app`。可重复运行原生页面测试：

```bash
DSH_IOS_SIMULATOR_ID=<模拟器 UUID> scripts/test-ios.sh
```

个人签名真机构建需要先在 Xcode 完成许可与账号设置，选择自己的开发者团队，并为目标设备准备有效的开发签名和 provisioning profile。脚本不选择默认 Team、不创建账号或密钥，也不自动更新 provisioning。查看设备标识可运行 `xcrun devicectl list devices`。

```bash
export DSH_DEVELOPMENT_TEAM=YOURTEAMID
scripts/build-ios-device.sh

# 明确指定设备后构建并安装；需要设备已配对、解锁并开启 Developer Mode。
scripts/build-ios-device.sh --device YOUR_DEVICE_ID --install

# 可选：归档并导出个人调试 IPA；仍使用 Debug 配置和 development APNs entitlement。
scripts/build-ios-device.sh --export-ipa
```

真机 `.app` 默认输出到 `.local/ios-device/DerivedData/Build/Products/Debug-iphoneos/DshMobile.app`；`--archive` 输出 `.local/ios-device/DshMobile.xcarchive`，`--export-ipa` 使用 Xcode 的 `debugging` 导出方式并将 IPA 放在 `.local/ios-device/ipa`。已有构建也可手动安装：

```bash
xcrun devicectl device install app --device YOUR_DEVICE_ID \
  .local/ios-device/DerivedData/Build/Products/Debug-iphoneos/DshMobile.app
```

若默认 Bundle ID 无法用于自己的团队，可通过 `DSH_IOS_BUNDLE_ID` 指定已配置的个人 Bundle ID。个人推送必须使用与安装包签名匹配的 Team、Bundle ID、APNs key 和环境；开发与发布 token 不混用。APNs 能力需要支持该能力的开发者账号与签名配置，不能把免费 Personal Team 当作完整推送开发环境。SwiftPM 精确锁定官方 IrohLib 1.2.0。签名分发流程见 [Apple 注册设备分发说明](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices)。

首次创建 APNs key 是 Apple 账号侧操作。桌面向导在导入后自动匹配能识别的参数并测试，不会生成或分发第三方团队私钥。

### 测试

```bash
./gradlew :core:protocol:testAndroidHostTest :core:sync:testAndroidHostTest
./gradlew :core:transport:connectedAndroidDeviceTest
```

第一组是协议和状态合并测试；第二组需要模拟器或设备，使用真实 Android JNI/QUIC 而非假传输。iOS Kotlin 编译与最终 Xcode 链接是不同验证步骤，结果记录在 [验证说明](docs/VALIDATION.md)。

## 移动界面

以 DeepSeek 移动端的对话界面为参考：侧边抽屉管理历史会话，底部统一输入框。消息正文使用原生 GFM 渲染，支持列表、引用、链接、表格、代码复制；思考与工具使用中性色折叠行，流式思考和正文分别合并。

输入框下方的模型与**思考强度**采用单行水平布局，分别点击各自控件选择。档位来自桌面模型目录；当前选择来自 DSH 的 `modelSelection` 投影，桌面修改后手机同步更新。没有思考档位的模型不会出现虚构选项。

iOS 26 及以上使用系统原生 Liquid Glass 导航和发送按钮；低版本或开启“降低透明度”时使用系统实色控件。设计规范见 [界面规范](design-system/dsh-mobile/MASTER.md)。

免费 Personal Team 测试可使用：

```bash
DSH_DEVELOPMENT_TEAM=YOURTEAMID scripts/build-ios-device.sh --personal-team --device YOUR_DEVICE_ID --install
```

此构建保留 P2P 和聊天，明确关闭免费团队不支持的 APNs 权限。`DSH_IOS_DERIVED_DATA` 可指向已有的构建缓存目录。

## 目录

- `androidApp`：Android 壳、扫码/文件选择、Keystore 初始化、FCM 与通知。
- `iosApp`：Swift 壳、iroh 适配、平台交互和 APNs。
- `composeApp`：共享主题、导航、设备/会话/聊天/工作区/设置页面。
- `core/model`、`core/protocol`：领域模型与版本化帧协议。
- `core/transport`：统一 P2P 接口及实际平台绑定。
- `core/sync`：RPC、分域快照与增量合并、重连和操作确认。
- `core/database`：SQLDelight 缓存与迁移。
- `core/platform`：安全存储、文件、通知和生命周期接口。
- `third_party/iroh-ffi`：固定官方来源；生成库不提交 Git。

无独立服务端目录。与桌面的协议定义由插件仓库 `protocol/PROTOCOL.md` 维护。所有密钥、用户数据和签名文件保存在仓库外，构建与测试不读取用户模型密钥。

### 附件与图库

输入框左下方加号提供“照片图库”和“选择文件”。iOS 使用系统 PHPicker，Android 使用系统 Photo Picker（不可用时由 AndroidX 回退到图片文件选择）；仅接收用户选定的图片。图库一次最多选择 10 张，iOS 的 HEIC/HEIF 在需要时转为 JPEG 后上传。附件通过现有加密分块上传及 receipt 流程发送，取消选择不会创建新会话。

侧边栏点击会话后立即关闭，再加载桌面会话。工作区采用目录列表，文件页提供面包屑、刷新、预览和下载。发送/停止按钮可见圆形为 36 点，附件加号 16 点；点击区域保持 48 点。

会话顶部显示所属工作区；侧栏收起使用平滑过渡后再加载会话。点击输入框以外的区域可收起键盘。
