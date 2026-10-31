# Harness Link

**在手机上继续 DeepSeek Harness 会话。**

[![Android and iOS](https://img.shields.io/badge/Android%20%2B%20iOS-Kotlin%20Multiplatform-595959.svg)](#从源码构建) [![License: MIT](https://img.shields.io/badge/License-MIT-595959.svg)](LICENSE)

**[桌面插件](https://github.com/GongShichen/dsh-mobile-plugin) · [开始使用](#开始使用) · [从源码构建](#从源码构建)**

<img src="assets/HLBanner.webp" alt="Harness Link — Your desktop agent. On your phone." width="100%">

Agent 在电脑上执行，手机也能接着聊、处理审批、查看文件改动。Harness Link 用 Kotlin Multiplatform 和 Compose Multiplatform 提供 Android / iOS 原生界面，连接你正在运行的 DeepSeek Harness。

## 开始使用

你需要一台运行 **DeepSeek Harness `0.2.2-alpha.2`** 的 Apple Silicon Mac，以及 Android 8.0+ 或 iOS 17.5+ 手机。

1. **电脑安装插件。** 按照 [Harness Link Plugin 的安装说明](https://github.com/GongShichen/dsh-mobile-plugin#安装)配置实际使用的 DSH profile。
2. **安装手机 App。** Android 可[从源码构建](#android)，或在[成功的 CI 构建](https://github.com/GongShichen/dsh-mobile/actions/workflows/build.yml)中下载 `harness-link-android-debug` 产物；iOS 使用自己的开发者团队[签名安装](#ios)。
3. **扫码并确认。** 打开电脑上的“移动访问”，在手机扫描二维码，或粘贴配对链接，然后在电脑确认设备授权。
4. **继续会话。** 选择电脑与会话；双方保留设备身份，之后无需重复扫码。

电脑需保持唤醒，DSH 需持续运行。你无需部署独立服务器：iroh 优先直连，直连不可达时使用公共的端到端加密中继。

## 从对话到执行结果

| 你想做的事 | 手机上可以完成 |
| --- | --- |
| 继续一段对话 | 搜索、新建、切换、改名、归档、置顶和分支会话；选择模型与思考强度；发送消息或停止生成。 |
| 跟进 Agent 执行 | 流式阅读正文，展开或收起思考和工具过程；查看独立对话轨迹、任务状态；回答审批、计划审阅和用户询问。 |
| 查看文件与改动 | 从图库或文件选择器添加附件；浏览工作区、预览和下载文件；按轮次查看改动文件、增删行数及带行号的文本差异。 |

iOS 26+ 的导航与发送控件使用系统 Liquid Glass；较低系统版本或降低透明度设置下使用实色控件。

## 同一份桌面状态

**桌面 DSH 是唯一的业务数据源。** 手机按需获取历史、文件和状态；会话、消息、轨迹、草稿与文件改动快照仅保留在进程内存。配对电脑和必要设置会保存，设备身份由平台安全存储保护。

文件改动来自 Web 使用的同一份官方快照，各轮独立统计。桌面重启或释放会话后，旧快照会显示不可用；更早的轮次可继续分页加载。非 Git 工作区仅记录官方文件工具捕获到的改动。

工作区文件展示连接电脑上的内容。文件修改通过桌面 Agent 执行；桌面未发送草稿、滚动位置和第三方插件的 React 页面不纳入原生同步。

## 后台提醒

手机进入后台后，系统可能暂停 P2P；回到前台会重新连接并同步。可在[桌面插件的通知设置](https://github.com/GongShichen/dsh-mobile-plugin#手机通知)中导入自己的 APNs / Firebase 凭据，由 Mac 直接发送提醒。通知只提示进展，打开后再读取实际状态。

通知为可选配置。无 GMS 的 Android 可正常连接和聊天，但无法使用 FCM 后台提醒。

## 从源码构建

```sh
git clone --recurse-submodules https://github.com/GongShichen/dsh-mobile.git
cd dsh-mobile
scripts/HLDoctor.sh
```

| 构建部分 | 环境 |
| --- | --- |
| 共享工程 | JDK 21；仓库提供 Gradle 9.5.0 wrapper，固定 Kotlin 2.4.21 / Compose Multiplatform 1.12.2。 |
| Android | Android SDK 37、Build Tools 37.0.0、完整 NDK r27+、Rust / rustup；默认 NDK 为 `27.1.12297006`。 |
| iOS | macOS、Xcode、XcodeGen；CI 使用 Xcode 26.5，本地也已验证 Xcode 27.2。 |

Android 的 `minSdk` 为 26、`targetSdk` 为 36、`compileSdk` 为 37。iroh 官方绑定固定为 `1.2.0`，核心为 `1.4.1`；Swift 包要求 iOS 17.5+。默认 Android application ID 与 iOS Bundle ID 均为 `org.harnesslink.app`。

### Android

```sh
scripts/HLBuildAndroid.sh
```

首次构建会编译 arm64 iroh 原生库，产物为 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。原生库使用 16 KiB 页对齐；额外 ABI、JNI 与传输测试见 [Native iroh transport](core/transport/README.md)。

如 NDK 位于其他位置，先指定完整路径：

```sh
ANDROID_NDK_HOME=/path/to/android-ndk scripts/HLBuildIrohAndroid.sh
```

### iOS

先在 Xcode 完成首次启动配置；安装 XcodeGen 后构建 arm64 模拟器版本：

```sh
brew install xcodegen
scripts/HLBuildIOS.sh
```

产物为 `.local/DerivedData/Build/Products/Debug-iphonesimulator/HLApp.app`。脚本保留模拟器临时签名，使 Keychain 中的设备身份可正常保存。

<details>
<summary>自己的 iPhone：签名、安装与推送</summary>

先在 Xcode 配置自己的开发者团队、设备和 provisioning profile；手机需完成配对、信任与 Developer Mode 设置。脚本使用已有签名配置，不代为创建账号、证书或自动更新 provisioning。

```sh
# 免费 Personal Team：保留 P2P 和聊天，关闭 APNs 权限。
DSH_DEVELOPMENT_TEAM=YOURTEAMID scripts/HLBuildIOSDevice.sh \
  --personal-team --device YOUR_DEVICE_ID --install

# 已配置 APNs 能力的开发者团队。
DSH_DEVELOPMENT_TEAM=YOURTEAMID scripts/HLBuildIOSDevice.sh \
  --device YOUR_DEVICE_ID --install
```

`xcrun devicectl list devices` 可查看设备标识。省略 `--install` 时只构建；`--export-ipa` 可额外导出开发签名 IPA。默认输出在 `.local/ios-device/`，Personal Team 构建输出在 `.local/ios-device-personal/`。

`DSH_IOS_BUNDLE_ID` 可指定自己团队可用的 Bundle ID，`DSH_IOS_DERIVED_DATA` 可复用构建缓存。更换标识会成为新安装，需重新配对。

APNs 需要支持推送能力的开发者团队，并使用匹配的 Team、Bundle ID、密钥与环境。免费 Personal Team 构建不提供 APNs。

</details>

### 验证与贡献

```sh
./gradlew :core:database:testAndroidHostTest :core:protocol:testAndroidHostTest :core:sync:testAndroidHostTest
DSH_IOS_SIMULATOR_ID=YOUR_SIMULATOR_UUID scripts/HLTestIOS.sh
```

日常回归优先使用模拟器，通过配对链接连接真实 Mac 插件。审批、问答和文件改动的完整链路由插件仓库的 [QA 脚本](https://github.com/GongShichen/dsh-mobile-plugin/tree/main/scripts)驱动；测试使用隔离 profile。相机、推送、后台和蜂窝网络另做真机验收。

在日常使用的 Android 真机上，使用 `assembleDebugAndroidTest`、`adb install -r -t` 和 `adb shell am instrument` 保留安装数据；避免会自动卸载 App 的 Gradle connected 任务。日志标签 `HarnessLink` / `HarnessLink-P2P` 不记录邀请密钥、令牌或对话正文。

共享界面位于 [composeApp](composeApp)，协议、同步与平台适配位于 [core](core)，平台入口位于 [androidApp](androidApp) 和 [iosApp](iosApp)。提交问题时请附系统版本、连接路径和脱敏日志；贡献代码前先运行对应检查。

## 许可证

[MIT](LICENSE)。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Harness Link 是独立社区项目，与 DeepSeek 无隶属关系。[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的名称及相关权利属于其各自权利人。
